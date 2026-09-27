package com.beian.tracker.data

import android.content.Context
import android.location.Location
import com.beian.tracker.util.AppEventDeriver
import com.beian.tracker.util.BackupCodec
import com.beian.tracker.util.DeviceInfo
import com.beian.tracker.util.EventDeriver
import com.beian.tracker.util.TimeUtil
import com.beian.tracker.util.UsageStatsReader
import kotlinx.coroutines.flow.Flow

/**
 * 轨迹、设备状态、报备事件的读写入口。
 *
 * 所有查询都按 [sourceId] 隔离：LOCAL 是本机数据，其余是导入的对方数据。
 */
class TrackRepository(private val context: Context) {

    private val db = AppDatabase.get(context)

    private val pointDao = db.trackPointDao()
    private val snapshotDao = db.deviceSnapshotDao()
    private val summaryDao = db.dailySummaryDao()
    private val appUsageDao = db.appUsageDao()
    private val appSessionDao = db.appSessionDao()
    private val eventDao = db.eventLogDao()
    private val sourceDao = db.importedSourceDao()

    // ── 轨迹点 ────────────────────────────────────────────────────────────────

    suspend fun recordPoint(location: Location) {
        val day = TimeUtil.dayKey(location.time)
        pointDao.insert(
            TrackPoint(
                timestamp = location.time,
                dayKey = day,
                sourceId = LOCAL_SOURCE,
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                speed = location.speed,
                accuracy = location.accuracy,
                provider = location.provider.orEmpty(),
            ),
        )
        refreshSummary(LOCAL_SOURCE, day)
    }

    fun pointsOfDay(sourceId: String, day: String): Flow<List<TrackPoint>> =
        pointDao.observeByDay(sourceId, day)

    suspend fun pointsOfDayOnce(sourceId: String, day: String): List<TrackPoint> =
        pointDao.getByDay(sourceId, day)

    suspend fun allPoints(sourceId: String): List<TrackPoint> = pointDao.getAll(sourceId)

    fun observedDays(sourceId: String): Flow<List<String>> = pointDao.observeDays(sourceId)

    // ── 设备快照 ──────────────────────────────────────────────────────────────

    /**
     * 采集一次设备状态并落库，同时根据与上次状态的差异生成报备事件。
     */
    suspend fun captureSnapshot() {
        val now = System.currentTimeMillis()
        val day = TimeUtil.dayKey(now)
        val battery = DeviceInfo.battery(context)
        val network = DeviceInfo.network(context)
        val usage = UsageStatsReader.today(context)
        val screenOn = DeviceInfo.isScreenOn(context)

        // ── 各 App 当日使用情况 ────────────────────────────────────────────────
        // 注意：查询可能因为「没有权限」而返回空。此时**不能**照常替换，
        // 否则权限一被撤销就会把当天已采到的数据清空。
        val usageUsable = UsageStatsReader.hasPermission(context)
        if (usageUsable) {
            val perApp = UsageStatsReader.todayPerApp(context)
            appUsageDao.replaceDay(
                day,
                perApp.map {
                    AppUsage(
                        dayKey = day,
                        packageName = it.packageName,
                        appLabel = it.appLabel,
                        usageMs = it.usageMs,
                        launchCount = it.launchCount,
                        lastUsed = it.lastUsed,
                    )
                },
            )
        }

        // ── 今日 App 前台片段（时间线 + 报备事件）──────────────────────────────
        if (usageUsable) {
            val sessions = UsageStatsReader.todaySessions(context)
            appSessionDao.replaceDay(
                day,
                sessions.map {
                    AppSession(
                        id = "${it.startAt}:${it.packageName}",
                        dayKey = day,
                        packageName = it.packageName,
                        appLabel = it.appLabel,
                        startAt = it.startAt,
                        endAt = it.endAt,
                        durationMs = it.durationMs,
                    )
                },
            )

            // 同一批片段转成「TA 打开了 XX」事件，写进报备流。
            //
            // ⚠️ 性能：UsageStats 每天会还原出上百个片段，而轮询每 60 秒跑一次。
            // 如果每次都全量 insertAll，一天要写十几万次（内容还都一样）。
            // 所以先查「已记到哪个时间点」，只处理它之后的片段。
            //
            // 会话的 startAt 是事件时间戳，天然有序，取历史最大值即可。
            val lastAppOpenAt = eventDao.latestOfType(LOCAL_SOURCE, EventType.APP_OPEN)?.timestamp ?: 0L
            val fresh = sessions.filter { it.startAt > lastAppOpenAt }
            if (fresh.isNotEmpty()) {
                val appEvents = AppEventDeriver.derive(
                    sessions = fresh,
                    selfPackage = context.packageName,
                )
                if (appEvents.isNotEmpty()) eventDao.insertAll(appEvents)
            }
        }

        // ── 快照 ──────────────────────────────────────────────────────────────
        snapshotDao.insert(
            DeviceSnapshot(
                timestamp = now,
                dayKey = day,
                batteryLevel = battery.level,
                batteryCharging = battery.charging,
                screenTimeMs = usage.screenTimeMs,
                unlockCount = usage.unlockCount,
                screenOnCount = usage.screenOnCount,
                networkType = network.type,
                networkConnected = network.connected,
                networkName = network.name,
            ),
        )

        // ── 事件推导 ──────────────────────────────────────────────────────────
        val prevSnapshot = snapshotDao.latestBefore(now)
        // 当天是否已经记过「第 1 次打开手机」。
        // 广播（EventReceiver）会在亮屏时立刻写入，这里是轮询侧的兜底：
        // 如果服务是当天启动的、又漏掉了亮屏广播，轮询会补上一条。
        val firstOpenToday = (
            eventDao.countOfTypeOnDay(LOCAL_SOURCE, day, EventType.FIRST_OPEN_TODAY) > 0
            )

        // 上次屏幕状态：看最近一条屏幕事件
        val lastScreenEvent = eventDao.latestOfType(LOCAL_SOURCE, EventType.SCREEN_ON)
        val lastScreenOff = eventDao.latestOfType(LOCAL_SOURCE, EventType.SCREEN_OFF)
        val prevScreenOn = lastScreenEvent != null &&
            (lastScreenOff == null || lastScreenEvent.timestamp > lastScreenOff.timestamp)

        val prev = EventDeriver.Prev(
            batteryLevel = prevSnapshot?.batteryLevel ?: -1,
            charging = prevSnapshot?.batteryCharging ?: false,
            networkType = prevSnapshot?.networkType ?: "",
            networkName = prevSnapshot?.networkName ?: "",
            screenOn = prevScreenOn,
            firstOpenToday = firstOpenToday,
        )

        // EventDeriver 现在只产出 FIRST_OPEN_TODAY（其余类型全部交给广播实时记录，
        // 避免轮询用旧快照比对而重复上报）。
        val events = EventDeriver.derive(
            prev = prev,
            batteryLevel = battery.level,
            charging = battery.charging,
            networkType = network.type,
            networkName = network.name,
            screenOn = screenOn,
            now = now,
        )
        if (events.isNotEmpty()) eventDao.insertAll(events)

        refreshSummary(LOCAL_SOURCE, day)
    }

    /**
     * 断连补偿：Service 被杀掉的那段时间，屏幕开关事件会漏记。
     *
     * 系统 UsageStats 里有完整的屏幕交互记录，用它把缺口补上。
     * 在 Service 每次启动时调用一次即可（幂等：已存在的 id 会被 REPLACE 覆盖）。
     *
     * @return 补记的事件条数
     */
    suspend fun backfillScreenEvents(lookbackMs: Long = 6 * 60 * 60_000L): Int {
        val now = System.currentTimeMillis()

        // 上一条屏幕事件的时间，或最多回溯 6 小时
        val lastScreen = listOfNotNull(
            eventDao.latestOfType(LOCAL_SOURCE, EventType.SCREEN_ON),
            eventDao.latestOfType(LOCAL_SOURCE, EventType.SCREEN_OFF),
        ).maxByOrNull { it.timestamp }

        val since = maxOf(lastScreen?.timestamp ?: 0L, now - lookbackMs)
        if (since >= now) return 0

        // 断开很短（<2 分钟）就不用补，避免启动瞬间误补
        if (now - since < 2 * 60_000L) return 0

        val missed = UsageStatsReader.screenEventsBetween(context, since, now)
        if (missed.isEmpty()) return 0

        val items = missed.map { (ts, isOn) ->
            val type = if (isOn) EventType.SCREEN_ON else EventType.SCREEN_OFF
            EventLog(
                id = "$LOCAL_SOURCE:$type:$ts",
                sourceId = LOCAL_SOURCE,
                dayKey = TimeUtil.dayKey(ts),
                type = type,
                timestamp = ts,
                title = if (isOn) "TA打开了手机屏幕" else "TA关闭了手机屏幕",
                detail = "断连期间补记",
            )
        }

        // 只补那些确实没有的（id 唯一，REPLACE 保证不重复）
        eventDao.insertAll(items.distinctBy { it.id })

        // 补完刷新涉及的每一天的汇总
        items.map { it.dayKey }.distinct().forEach { refreshSummary(LOCAL_SOURCE, it) }
        return items.size
    }

    fun latestSnapshot(): Flow<DeviceSnapshot?> = snapshotDao.observeLatest()

    suspend fun latestSnapshotOnce(): DeviceSnapshot? = snapshotDao.latest()

    fun snapshotsOfDay(day: String): Flow<List<DeviceSnapshot>> = snapshotDao.observeByDay(day)

    // ── 各 App 使用情况 ───────────────────────────────────────────────────────

    fun appUsageOfDay(day: String): Flow<List<AppUsage>> = appUsageDao.observeByDay(day)

    suspend fun appUsageOfDayOnce(day: String): List<AppUsage> = appUsageDao.getByDay(day)

    fun appSessionsOfDay(day: String): Flow<List<AppSession>> = appSessionDao.observeByDay(day)

    suspend fun appSessionsOfDayOnce(day: String): List<AppSession> = appSessionDao.getByDay(day)

    // ── 报备事件流 ────────────────────────────────────────────────────────────

    fun eventsOfDay(sourceId: String, day: String): Flow<List<EventLog>> =
        eventDao.observeByDay(sourceId, day)

    fun recentEvents(sourceId: String, limit: Int = 200): Flow<List<EventLog>> =
        eventDao.observeRecent(sourceId, limit)

    suspend fun eventsOfDayOnce(sourceId: String, day: String): List<EventLog> =
        eventDao.getByDay(sourceId, day)

    suspend fun allEvents(sourceId: String): List<EventLog> = eventDao.getAll(sourceId)

    suspend fun latestEvent(sourceId: String): EventLog? = eventDao.latest(sourceId)

    fun eventDays(sourceId: String): Flow<List<String>> = eventDao.observeDays(sourceId)

    /**
     * 本机某天第 1 次打开手机的时间，用于报备页顶部展示。
     * 没有记录时返回 null。
     */
    suspend fun firstOpenOfDay(day: String): Long? =
        eventDao.getByDay(LOCAL_SOURCE, day)
            .firstOrNull { it.type == EventType.FIRST_OPEN_TODAY }
            ?.timestamp

    // ── 导入来源 ──────────────────────────────────────────────────────────────

    fun importedSources(): Flow<List<ImportedSource>> = sourceDao.observeAll()

    suspend fun importedSource(sourceId: String): ImportedSource? = sourceDao.get(sourceId)

    suspend fun deleteImportedSource(sourceId: String) {
        eventDao.deleteSource(sourceId)
        pointDao.deleteSource(sourceId)
        sourceDao.delete(sourceId)
    }

    // ── 导出 / 导入 ───────────────────────────────────────────────────────────

    /** 打包本机数据。days = 0 表示全部；否则只取最近 N 天。 */
    suspend fun buildLocalBundle(days: Int = 0, nickname: String = ""): BackupCodec.Bundle {
        val cutoff = if (days > 0) {
            System.currentTimeMillis() - days * 24L * 3600_000
        } else {
            0L
        }
        val points = allPoints(LOCAL_SOURCE).filter { it.timestamp >= cutoff }
        val events = allEvents(LOCAL_SOURCE).filter { it.timestamp >= cutoff }

        // 覆盖到的日期：优先用轨迹点，其次用事件
        val daysCovered = points.map { it.dayKey }.toSortedSet().ifEmpty {
            events.map { it.dayKey }.toSortedSet()
        }

        val appUsage = ArrayList<AppUsage>()
        val appSessions = ArrayList<AppSession>()
        val snapshots = ArrayList<DeviceSnapshot>()
        daysCovered.forEach { d ->
            appUsage.addAll(appUsageDao.getByDay(d))
            appSessions.addAll(appSessionDao.getByDay(d))
            snapshots.addAll(snapshotDao.getByDay(d))
        }

        return BackupCodec.Bundle(
            sourceId = LOCAL_SOURCE,
            nickname = nickname,
            exportedAt = System.currentTimeMillis(),
            points = points,
            snapshots = snapshots,
            events = events,
            appUsage = appUsage,
            appSessions = appSessions,
        )
    }

    /**
     * 导入一个数据包。
     * @return 导入后的来源信息
     */
    suspend fun importBundle(bundle: BackupCodec.Bundle, nickname: String): ImportedSource {
        // 同来源重复导入：先清空再写，保证是"最新一份"
        eventDao.deleteSource(bundle.sourceId)
        pointDao.deleteSource(bundle.sourceId)

        if (bundle.points.isNotEmpty()) pointDao.insertAll(bundle.points)
        if (bundle.events.isNotEmpty()) eventDao.insertAll(bundle.events)

        val daysCovered = (bundle.points.map { it.dayKey } + bundle.events.map { it.dayKey })
            .filter { it.isNotBlank() }
            .sorted()

        val source = ImportedSource(
            sourceId = bundle.sourceId,
            nickname = nickname.ifBlank { bundle.nickname.ifBlank { "对方" } },
            importedAt = System.currentTimeMillis(),
            firstDay = daysCovered.firstOrNull() ?: "",
            lastDay = daysCovered.lastOrNull() ?: "",
            pointCount = bundle.points.size,
            eventCount = bundle.events.size,
        )
        sourceDao.upsert(source)

        // 重算导入数据涉及的每一天的汇总
        daysCovered.distinct().forEach { refreshSummary(bundle.sourceId, it) }

        return source
    }

    // ── 汇总 ──────────────────────────────────────────────────────────────────

    fun summaryOfDay(day: String): Flow<DailySummary?> = summaryDao.observeDay(day)

    fun allSummaries(): Flow<List<DailySummary>> = summaryDao.observeAll()

    /** 重算某来源某天的距离 / 点数 / 汇总。 */
    suspend fun refreshSummary(sourceId: String, day: String) {
        val points = pointDao.getByDay(sourceId, day)
        val latestSnapshot = snapshotDao.latest()

        var distance = 0.0
        var prev: TrackPoint? = null
        for (p in points) {
            prev?.let {
                val result = FloatArray(1)
                Location.distanceBetween(it.latitude, it.longitude, p.latitude, p.longitude, result)
                val d = result[0]
                // 过滤定位漂移（单点跳变 > 2km 视为异常）
                if (d < 2000) distance += d
            }
            prev = p
        }

        val todaySnapshot = if (latestSnapshot?.dayKey == day) latestSnapshot else null
        summaryDao.upsert(
            DailySummary(
                dayKey = day,
                totalDistanceMeters = distance,
                pointCount = points.size,
                unlockCount = todaySnapshot?.unlockCount ?: 0,
                screenTimeMs = todaySnapshot?.screenTimeMs ?: 0L,
                firstSeen = points.firstOrNull()?.timestamp ?: 0L,
                lastSeen = points.lastOrNull()?.timestamp ?: 0L,
            ),
        )
    }

    /**
     * 按天统计停留点 / 停留时长，供轨迹页展示。
     * 停留判定：两点间隔 > 5 分钟且距离 < 200 米，视为同一停留段。
     */
    suspend fun staysOfDay(sourceId: String, day: String): List<Stay> {
        val points = pointDao.getByDay(sourceId, day)
        val out = ArrayList<Stay>()
        if (points.size < 2) return out

        var segStart = points.first()
        var segLast = points.first()

        fun close(at: TrackPoint) {
            val dur = at.timestamp - segStart.timestamp
            if (dur >= STAY_MIN_MS) {
                out.add(
                    Stay(
                        startAt = segStart.timestamp,
                        endAt = at.timestamp,
                        durationMs = dur,
                        latitude = segStart.latitude,
                        longitude = segStart.longitude,
                    ),
                )
            }
        }

        for (i in 1 until points.size) {
            val p = points[i]
            val result = FloatArray(1)
            Location.distanceBetween(segLast.latitude, segLast.longitude, p.latitude, p.longitude, result)
            val gap = p.timestamp - segLast.timestamp
            val far = result[0] > STAY_RADIUS_M

            if (far || gap > STAY_GAP_MS) {
                close(segLast)
                segStart = p
            }
            segLast = p
        }
        close(segLast)
        return out
    }

    /** 清理 N 天前的本机数据。 */
    suspend fun purgeOlderThan(days: Int) {
        val cutoff = System.currentTimeMillis() - days * 24L * 3600_000
        pointDao.deleteOlderThan(cutoff)
        snapshotDao.deleteOlderThan(cutoff)
        appUsageDao.deleteBeforeDay(TimeUtil.dayKey(cutoff))
        appSessionDao.deleteBeforeDay(TimeUtil.dayKey(cutoff))
    }

    companion object {
        /** 停留段：两点间隔超过该值即断开。 */
        const val STAY_GAP_MS = 5 * 60_000L

        /** 停留段：移动超过该距离即断开。 */
        const val STAY_RADIUS_M = 200f

        /** 停留段最短时长。 */
        const val STAY_MIN_MS = 10 * 60_000L

    }
}

/** 一段停留。 */
data class Stay(
    val startAt: Long,
    val endAt: Long,
    val durationMs: Long,
    val latitude: Double,
    val longitude: Double,
)
