package com.beian.tracker.data

import android.content.Context
import android.location.Location
import android.util.Log
import com.beian.tracker.util.AppEventDeriver
import com.beian.tracker.util.BackupCodec
import com.beian.tracker.util.DeviceInfo
import com.beian.tracker.util.EventDeriver
import com.beian.tracker.util.TimeUtil
import com.beian.tracker.util.UsageStatsReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

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

    /**
     * 上一轮写入的 App 片段指纹（条数 to 最后一条开始时间）。
     *
     * 用来跳过「数据没变」时的 replaceDay 重写 —— 那个操作是删全表再插，
     * 每轮都跑一次纯属浪费，还会占住写锁拖慢界面。
     *
     * 只在采集线程（TrackService 的 ticker 协程）读写，单线程访问，
     * 用 @Volatile 保证可见性即可，不需要加锁。
     *
     * 跨天时由 [fingerprintDay] 判断并重置，否则「昨天末条 == 今天首条」
     * 这类巧合会让新一天的数据被误判为「没变」而跳过写入。
     */
    @Volatile
    private var lastSessionFingerprint: Triple<Int, Long, Long>? = null

    /**
     * 上一轮写入的 App 使用排行指纹（条数 to usageMs 总和）。
     *
     * 不能只用「条数 + 最后开始时间」：AppUsage 里没有开始时间，
     * 而且「一直用同一个 App」时条数不变、只有 usageMs 在涨。
     */
    @Volatile
    private var lastUsageFingerprint: Pair<Int, Long>? = null

    @Volatile
    private var fingerprintDay: String? = null

    /**
     * 跨天时清空指纹。两段采集（使用排行 / 前台片段）共用同一个 day 判断，
     * 所以只在方法开头调一次 —— 分别重置会让后一处把前一处刚设好的值又清掉。
     */
    private fun resetFingerprintIfNewDay(day: String) {
        if (fingerprintDay != day) {
            fingerprintDay = day
            lastSessionFingerprint = null
            lastUsageFingerprint = null
        }
    }

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
        // ⚠️ 这里**不再**调 refreshSummary()。
        //
        // 那是 O(当天全部点) 的全表重扫：取出当天所有点，再逐对算距离。
        // 采集间隔 120 秒 → 一天 720 次回调，第 N 次回调要遍历 N 个点，
        // 全天累计约 26 万次 distanceBetween（每次都是浮点运算 + JNI 调用）。
        // 而且越到晚上点越多、单次越慢，还全程占着 SQLite 读。
        //
        // 改成增量：新点只和它前面那一个点算一次距离，累加到已有汇总上。
        appendPointToSummary(location, day)
    }

    /**
     * 增量更新当天汇总：只动「新点接入后影响到的线段」。
     *
     * 背景：原来 [recordPoint] 每落一个点就 [refreshSummary] 全量重扫当天
     * 所有点，累计约 26 万次 distanceBetween（见 recordPoint 注释）。
     *
     * ── 为什么不是「只跟上一个点算一次」 ─────────────────────────────────
     *
     * 那个简化版有个坑：插入顺序不保证时间递增。
     * [com.beian.tracker.service.TrackService.seedLastKnownLocation]
     * 会在启动时补一个**缓存位置**，它可能比库里的点都早；
     * 网络定位与 GPS 也可能交错回调。此时「新点」插在链中间，
     * 正确的增量是：
     *
     *     去掉  prev↔next  这一段
     *     加上  prev↔new 和 new↔next 两段
     *
     * 只算 new↔prev 会把 next 那半段漏掉，里程少算。
     * 首尾两种情况（没有 prev / 没有 next）退化成只加一段。
     *
     * ── 结果为什么和全量重算一致 ─────────────────────────────────────────
     *
     * 全量 = 对各相邻点对求和。增量按同一组点对做「拆一段、补两段」，
     * 集合运算上是等价的，且每段都套同样的漂移过滤
     * （[DRIFT_THRESHOLD_M]）—— 注意过滤是非线性的，所以**必须**
     * 逐段判断，不能先求和再判断。
     */
    private suspend fun appendPointToSummary(location: Location, day: String) {
        val prev = pointDao.previousBefore(LOCAL_SOURCE, day, location.time)
        val next = pointDao.nextAfter(LOCAL_SOURCE, day, location.time)

        // 逐段算「计入里程的距离」，漂移段返回 0。
        fun seg(a: TrackPoint, b: TrackPoint): Double {
            val result = FloatArray(1)
            Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result)
            return if (result[0] < DRIFT_THRESHOLD_M) result[0].toDouble() else 0.0
        }

        val newPoint = TrackPoint(
            timestamp = location.time,
            dayKey = day,
            latitude = location.latitude,
            longitude = location.longitude,
        )

        // 去掉旧的一段（若两侧都有点），补上新的两段。
        val removed = if (prev != null && next != null) seg(prev, next) else 0.0
        val added = (if (prev != null) seg(prev, newPoint) else 0.0) +
            (if (next != null) seg(newPoint, next) else 0.0)

        val old = summaryDao.getDay(LOCAL_SOURCE, day)
        val latestSnapshot = snapshotDao.latestOf(LOCAL_SOURCE)
        val todaySnapshot = if (latestSnapshot?.dayKey == day) latestSnapshot else null

        summaryDao.upsert(
            DailySummary(
                sourceId = LOCAL_SOURCE,
                dayKey = day,
                // coerceAtLeast(0)：浮点误差 + 漂移过滤的非线性，
                // 理论上不该为负，但兜一下避免出现负数里程。
                totalDistanceMeters = ((old?.totalDistanceMeters ?: 0.0) - removed + added)
                    .coerceAtLeast(0.0),
                pointCount = (old?.pointCount ?: 0) + 1,
                unlockCount = todaySnapshot?.unlockCount ?: old?.unlockCount ?: 0,
                screenTimeMs = todaySnapshot?.screenTimeMs ?: old?.screenTimeMs ?: 0L,
                // min/max 而不是直接覆盖：补一个较早的点时，
                // firstSeen 要往前推，lastSeen 不能被它带回去。
                firstSeen = minOf(
                    (old?.firstSeen ?: 0L).takeIf { it > 0 } ?: location.time,
                    location.time,
                ),
                lastSeen = maxOf(old?.lastSeen ?: 0L, location.time),
            ),
        )
    }

    fun pointsOfDay(sourceId: String, day: String): Flow<List<TrackPoint>> =
        pointDao.observeByDay(sourceId, day)

    suspend fun pointsOfDayOnce(sourceId: String, day: String): List<TrackPoint> =
        pointDao.getByDay(sourceId, day)

    suspend fun allPoints(sourceId: String): List<TrackPoint> = pointDao.getAll(sourceId)

    /**
     * 某来源有数据的全部日期。
     *
     * ⚠️ 不能只查轨迹点：导入的数据包可能只有 App 使用记录 / 报备事件，
     * 一个定位点都没有（对方没给定位权限）。只查 track_points 的话，
     * 历史页会一天都列不出来，看着像「导入没成功」。
     *
     * 四路合并后取并集、倒序去重。
     */
    fun observedDays(sourceId: String): Flow<List<String>> = combine(
        pointDao.observeDays(sourceId),
        eventDao.observeDays(sourceId),
        appUsageDao.observeDays(sourceId),
        appSessionDao.observeDays(sourceId),
    ) { pointDays, eventDays, usageDays, sessionDays ->
        (pointDays + eventDays + usageDays + sessionDays)
            .filter { it.isNotBlank() }
            .distinct()
            .sortedDescending()
    }

    // ── 设备快照 ──────────────────────────────────────────────────────────────

    /**
     * 采集一次设备状态并落库，同时根据与上次状态的差异生成报备事件。
     */
    /**
     * 把系统里现有的最近 [DAYS] 天 App 使用数据**回填**进本地库。
     *
     * 为什么需要：
     *   采集服务每轮只写「今天」这一行。历史的天从来不写 ——
     *   于是统计页想按天看排行时，除了今天之外全是空的。
     *   而系统的 UsageStats 里其实存着最近 7~14 天（各 ROM 不同）。
     *
     * 为什么用「启动跑一次」而不是「每轮都跑」：
     *   `queryUsageStats` 覆盖 7 天区间要遍历几百个 UsageStats 桶，
     *   每轮（默认 10 分钟一次）都做一次纯属浪费。历史数据不会变，
     *   补一次就够 —— 今天的数据由原有的单天路径持续刷新。
     *
     * 为什么必须尽早做：
     *   系统只保留最近 7~14 天，**过了就被清掉，再也拿不回来**。
     *   越早回填，历史攒得越全。
     *
     * 幂等：某天已有数据时，upsert 会按期长覆盖为系统当前值，
     * 不会产生重复行（主键是 sourceId + dayKey + packageName）。
     */
    suspend fun backfillDailyUsage(days: Int = BACKFILL_DAYS) {
        // ⚠️ 锁必须是【所有实例共享】的对象。
        //
        // TrackRepository 每次都是 new 的（MainViewModel 一个、
        // TrackService 一个），所以 synchronized(this) 锁的是各自的实例，
        // 两处并发进来时都能通过检查 —— 等于没锁。
        // 用伴生对象里的专用锁对象，全进程只有一把。
        synchronized(backfillLock) {
            if (usageBackfilled) return
            usageBackfilled = true
        }
        if (!UsageStatsReader.hasPermission(context)) return

        val byDay = runCatching { UsageStatsReader.dailyPerApp(context, days) }
            .getOrElse {
                // 回填失败不影响主流程；允许下次再试
                synchronized(backfillLock) { usageBackfilled = false }
                return
            }
        // ⚠️ 这两步必须**互相独立**，不能因为前一步为空就跳过后一步。
        //
        // 它们是两张表、两条取数路径，覆盖的失败模式不同：
        //   · app_usage   走 queryUsageStats（聚合），个别 ROM 返回空
        //   · app_session 走 queryEvents（原始事件），通常是能拿到的
        // 之前把「聚合为空就 return」放在片段回填之前，结果一个 ROM
        // 差异就能让柱状图永远补不上。
        runCatching { backfillUsageTable(days) }
            .onFailure { Log.w("TrackRepository", "backfill usage table failed", it) }

        runCatching { backfillSessions(days) }
            .onFailure { Log.w("TrackRepository", "backfill sessions failed", it) }
    }

    /** 回填 app_usage（各 App 当日总时长）。 */
    private suspend fun backfillUsageTable(days: Int) {
        val byDay = UsageStatsReader.dailyPerApp(context, days)
        if (byDay.isEmpty()) return

        byDay.forEach { (day, list) ->
            appUsageDao.replaceDay(
                LOCAL_SOURCE,
                day,
                list.map {
                    AppUsage(
                        sourceId = LOCAL_SOURCE,
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
    }

    /**
     * 按天回填前台片段（app_session）。
     *
     * 与 [backfillDailyUsage] 分开成两个函数，是因为两者失败的
     * 影响面不同：时长表失败只影响排行列表，片段表失败只影响柱状图
     * 和时间线。分开写，一处出错不至于把另一处也连累掉。
     */
    private suspend fun backfillSessions(days: Int) {
        for (i in 0 until days) {
            val dayStart = TimeUtil.startOfDay(i)
            // 今天只查到当前时刻；历史的天查整天。
            val dayEnd = if (i == 0) System.currentTimeMillis() else TimeUtil.endOfDay(i)
            if (dayStart >= dayEnd) continue

            val day = TimeUtil.dayKey(dayStart)
            val sessions = runCatching {
                UsageStatsReader.sessionsBetween(context, dayStart, dayEnd)
            }.getOrNull().orEmpty()
            if (sessions.isEmpty()) continue

            // 已有片段的当天：不要覆盖。当天数据由采集路径实时维护，
            // 回填只补「历史上完全空白」的那些天。
            // 否则每次启动都会把用户当天已累积的片段重写一遍，
            // 而且回填的区间是「整天」，会把采集写入的更细粒度结果盖掉。
            val existing = appSessionDao.getByDay(LOCAL_SOURCE, day)
            if (existing.isNotEmpty()) continue

            appSessionDao.insertAll(
                sessions.map {
                    AppSession(
                        // id 含 sourceId + startAt + packageName，
                        // 天然唯一，重复插入会被 REPLACE 掉，不会翻倍。
                        id = "${LOCAL_SOURCE}:${it.startAt}:${it.packageName}",
                        sourceId = LOCAL_SOURCE,
                        dayKey = day,
                        packageName = it.packageName,
                        appLabel = it.appLabel,
                        startAt = it.startAt,
                        endAt = it.endAt,
                        durationMs = it.durationMs,
                    )
                },
            )
        }
    }


    suspend fun captureSnapshot() {
        val now = System.currentTimeMillis()
        val day = TimeUtil.dayKey(now)
        val battery = DeviceInfo.battery(context)
        val network = DeviceInfo.network(context)
        val usage = UsageStatsReader.today(context)
        val screenOn = DeviceInfo.isScreenOn(context)

        // 跨天必须重置指纹：它只比对「有没有变」，不认日期。
        // 否则新一天恰好条数相同（或两段都是空列表）时会被误判成「没变」，
        // 当天数据一条都不落地。两段共用这一次重置，见方法注释。
        resetFingerprintIfNewDay(day)

        // ── 各 App 当日使用情况 ────────────────────────────────────────────────
        // 注意：查询可能因为「没有权限」而返回空。此时**不能**照常替换，
        // 否则权限一被撤销就会把当天已采到的数据清空。
        val usageUsable = UsageStatsReader.hasPermission(context)
        if (usageUsable) {
            val perApp = UsageStatsReader.todayPerApp(context)
            // 同 AppSession：没变就别重写（replaceDay 是删表再插，占写锁）。
            // 指纹取「条数 + usageMs 总和」—— 条数反映 App 增减，
            // usageMs 总和反映已列出 App 的时长增长。只看条数会漏掉
            // 「一直在用同一个 App」的时长更新。
            val usageFingerprint = perApp.size to perApp.sumOf { it.usageMs }
            if (usageFingerprint != lastUsageFingerprint) {
                lastUsageFingerprint = usageFingerprint
                appUsageDao.replaceDay(
                    LOCAL_SOURCE,
                    day,
                    perApp.map {
                        AppUsage(
                            sourceId = LOCAL_SOURCE,
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
        }

        // ── 今日 App 前台片段（时间线 + 报备事件）──────────────────────────────
        if (usageUsable) {
            val sessions = UsageStatsReader.todaySessions(context)
            // ⚠️ 性能：replaceDay 是「DELETE 当天全部 + 重新插入」，
            // 而轮询每轮都跑一次。当天片段数上百时，这是一天几万次的无谓重写，
            // 期间还占着写锁，界面查询会被拖住（卡顿）。
            //
            // 片段集合只在「有新的 App 切换」时才变，但**正在使用中的那一条**
            // 时长每轮都在增长（尾片的 durationMs 会一直涨，endAt 为 0），
            // 所以指纹必须带上它的时长，否则「一直在用同一个 App」时
            // 界面上的时长会停住不动。
            val tail = sessions.lastOrNull()
            val fingerprint = Triple(sessions.size, tail?.startAt ?: -1L, tail?.durationMs ?: -1L)
            if (fingerprint != lastSessionFingerprint) {
                lastSessionFingerprint = fingerprint

                appSessionDao.replaceDay(
                    LOCAL_SOURCE,
                    day,
                    sessions.map {
                        AppSession(
                            id = "${LOCAL_SOURCE}:${it.startAt}:${it.packageName}",
                            sourceId = LOCAL_SOURCE,
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
                // ⚠️ 性能：UsageStats 每天会还原出上百个片段，而轮询每轮都跑一次。
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
        }

        // ── 快照 ──────────────────────────────────────────────────────────────
        snapshotDao.insert(
            DeviceSnapshot(
                timestamp = now,
                dayKey = day,
                sourceId = LOCAL_SOURCE,
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
        val prevSnapshot = snapshotDao.latestBeforeOf(LOCAL_SOURCE, now)
        // 当天是否已经记过「第 1 次点亮屏幕」。
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

        // ⚠️ 这里**不再**调 refreshSummary()。
        //
        // ticker 每 60 秒跑一轮，而 refreshSummary 要取出当天全部轨迹点
        // 逐对算距离 —— 但这一轮**根本没动过轨迹点**（点由定位回调写入，
        // 和 ticker 是两条路径），距离、点数、首末时间全都没变。
        // 每 60 秒重算一次纯属白费：一天 1440 次 × 当天点数，
        // 几十万次无意义的 distanceBetween，还占着读锁和主线程外的 CPU。
        //
        // 本轮的快照确实会更新解锁次数 / 屏幕时长，这两个字段单独写进去即可。
        refreshSummarySnapshotFields(LOCAL_SOURCE, day)
    }

    /**
     * 只把「解锁次数 / 屏幕时长」同步进当天汇总，不动距离和点数。
     *
     * 给 [captureSnapshot] 用：那一轮只采了设备状态，轨迹点没变，
     * 没必要重扫全部点算距离（见该处注释）。
     *
     * ⚠️ 汇总行不存在时回退到全量 [refreshSummary]。
     *    否则新建出来的行会带着 distance=0 / pointCount=0，
     *    而当天其实已经有轨迹点了 —— 界面上里程会突然显示成 0。
     *    正常运行时汇总行早就在（首次落点时就建好了），走不到回退分支。
     */
    private suspend fun refreshSummarySnapshotFields(sourceId: String, day: String) {
        val old = summaryDao.getDay(sourceId, day)
        if (old == null) {
            refreshSummary(sourceId, day)
            return
        }

        val latestSnapshot = snapshotDao.latestOf(sourceId)
        val todaySnapshot = if (latestSnapshot?.dayKey == day) latestSnapshot else null

        summaryDao.upsert(
            old.copy(
                unlockCount = todaySnapshot?.unlockCount ?: old.unlockCount,
                screenTimeMs = todaySnapshot?.screenTimeMs ?: old.screenTimeMs,
            ),
        )
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

    /** 某个来源最近的一条快照。报备页顶部信息栏用它按来源切换显示。 */
    fun latestSnapshot(sourceId: String = LOCAL_SOURCE): Flow<DeviceSnapshot?> =
        snapshotDao.observeLatestOf(sourceId)

    suspend fun latestSnapshotOnce(sourceId: String = LOCAL_SOURCE): DeviceSnapshot? =
        snapshotDao.latestOf(sourceId)

    fun snapshotsOfDay(sourceId: String, day: String): Flow<List<DeviceSnapshot>> =
        snapshotDao.observeByDayOf(sourceId, day)

    // ── 各 App 使用情况 ───────────────────────────────────────────────────────

    fun appUsageOfDay(sourceId: String, day: String): Flow<List<AppUsage>> =
        appUsageDao.observeByDay(sourceId, day)

    suspend fun appUsageOfDayOnce(sourceId: String, day: String): List<AppUsage> =
        appUsageDao.getByDay(sourceId, day)

    fun appSessionsOfDay(sourceId: String, day: String): Flow<List<AppSession>> =
        appSessionDao.observeByDay(sourceId, day)

    suspend fun appSessionsOfDayOnce(sourceId: String, day: String): List<AppSession> =
        appSessionDao.getByDay(sourceId, day)

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
     * 本机某天第 1 次点亮屏幕的时间，用于报备页顶部展示。
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
        snapshotDao.deleteSource(sourceId)
        // 这三张表 v6 起也有 sourceId，删来源时必须一起清，
        // 否则删掉来源后它的 App 排行 / 时间线 / 汇总会一直留在库里。
        appUsageDao.deleteSource(sourceId)
        appSessionDao.deleteSource(sourceId)
        summaryDao.deleteSource(sourceId)
        sourceDao.delete(sourceId)
    }

    /**
     * 清空本机采集到的数据。
     *
     * ⚠️ 只动 [LOCAL_SOURCE] 名下的东西，导入的对方数据不受影响。
     * 汇总表是按天不按来源的，所以清完要把涉及的每一天重算一遍，
     * 否则历史页还挂着已经不存在的里程。
     */
    suspend fun clearLocalData() {
        // 先收集涉及的日子，清完要按这些日子重算汇总。
        // 这几张表 v6 起都带 sourceId，全部按本机过滤。
        val days = (
            pointDao.daysOfSource(LOCAL_SOURCE) +
                eventDao.daysOfSource(LOCAL_SOURCE) +
                snapshotDao.daysOfSource(LOCAL_SOURCE) +
                appUsageDao.daysOfSource(LOCAL_SOURCE) +
                appSessionDao.daysOfSource(LOCAL_SOURCE)
            ).distinct().filter { it.isNotBlank() }

        eventDao.deleteSource(LOCAL_SOURCE)
        pointDao.deleteSource(LOCAL_SOURCE)
        snapshotDao.deleteSource(LOCAL_SOURCE)
        appUsageDao.deleteSource(LOCAL_SOURCE)
        appSessionDao.deleteSource(LOCAL_SOURCE)

        // 汇总表按 (sourceId, dayKey) 存，同样只删本机 ——
        // 导入来源的行必须留着，否则「清空本机数据」会把对方的数据也抹掉。
        summaryDao.deleteSource(LOCAL_SOURCE)

        // 删完按涉及的日子重算本机汇总（重算会重新 INSERT 本机行）。
        days.forEach { refreshSummary(LOCAL_SOURCE, it) }
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

        // 覆盖到的日期：轨迹点、事件、App 使用、前台片段、快照 —— 取并集。
        //
        // ⚠️ 之前只取「轨迹点」的日期，导致一个真实的数据丢失：
        // 某几天没采到轨迹（服务没跑/没定位），但那几天是**在用手机的**，
        // app_usage 里有数据。导出时这些天不在 daysCovered 里，
        // 使用数据就被整段丢掉 —— 对方导入后看不到那几天的排行。
        //
        // 注意：daysOfSource 是 suspend，不能在 sortedSetOf().apply{} 里调，
        // 必须逐个 await 完再合并。
        val daysCovered = sortedSetOf<String>()
        daysCovered.addAll(points.map { it.dayKey })
        daysCovered.addAll(events.map { it.dayKey })
        daysCovered.addAll(snapshotDao.daysOfSource(LOCAL_SOURCE))
        daysCovered.addAll(appUsageDao.daysOfSource(LOCAL_SOURCE))
        daysCovered.addAll(appSessionDao.daysOfSource(LOCAL_SOURCE))

        val appUsage = ArrayList<AppUsage>()
        val appSessions = ArrayList<AppSession>()
        val snapshots = ArrayList<DeviceSnapshot>()
        daysCovered.forEach { d ->
            appUsage.addAll(appUsageDao.getByDay(LOCAL_SOURCE, d))
            appSessions.addAll(appSessionDao.getByDay(LOCAL_SOURCE, d))
            snapshots.addAll(snapshotDao.getByDay(LOCAL_SOURCE, d))
        }

        return BackupCodec.Bundle(
            // ⚠️ 绝不能是 LOCAL_SOURCE。
            // 导入端会把这个 id 当作「对方」的标识，
            // 如果导出包里也是 LOCAL，导入时就会和本机数据撞在同一个 id 上 ——
            // 表现为：报备页出现两张卡、切不过去、本机数据被对方覆盖。
            sourceId = LOCAL_EXPORT_ID,
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

        snapshotDao.deleteSource(bundle.sourceId)
        // v6 起这三张表也带 sourceId，重复导入必须一并清掉旧的一份，
        // 否则改了 packageName / 日期范围的话会残留上一版的行。
        appUsageDao.deleteSource(bundle.sourceId)
        appSessionDao.deleteSource(bundle.sourceId)
        summaryDao.deleteSource(bundle.sourceId)

        if (bundle.points.isNotEmpty()) pointDao.insertAll(bundle.points)
        if (bundle.events.isNotEmpty()) eventDao.insertAll(bundle.events)

        // App 排行与前台片段也要落库 —— 导出包里一直带着它们，
        // 但导入端此前直接丢弃，导致切到对方后「今日 App 使用」和
        // 历史页的时段柱状图永远为空。
        //
        // decode 时已经给每条的 sourceId / id 打上了本次导入的来源标识，
        // 这里直接插入即可，不会和本机数据撞。用 REPLACE 保证重复导入幂等。
        if (bundle.appUsage.isNotEmpty()) appUsageDao.upsertAll(bundle.appUsage)
        if (bundle.appSessions.isNotEmpty()) appSessionDao.insertAll(bundle.appSessions)
        // 快照也要落库 —— 导出包里一直带着它，但导入端此前直接丢弃，
        // 导致切到对方后顶部看不到电量 / 网络。
        //
        // id 必须归零：那是采集端的自增主键，直接搬过来会撞本机的行。
        // 归零后由本库重新分配，sourceId 才是区分来源的依据。
        if (bundle.snapshots.isNotEmpty()) {
            snapshotDao.insertAll(bundle.snapshots.map { it.copy(id = 0) })
        }

        // 覆盖到的日期：四类数据全算上。
        // 只算点和事件的话，「对方没给定位、包里只有 App 使用记录」
        // 这种情况下 daysCovered 会是空的，来源卡上显示「无覆盖日期」。
        val daysCovered = (
            bundle.points.map { it.dayKey } +
                bundle.events.map { it.dayKey } +
                bundle.appSessions.map { it.dayKey } +
                bundle.appUsage.map { it.dayKey }
            ).filter { it.isNotBlank() }.distinct().sorted()

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

        // 汇总每天一份（含只有 App 数据、没有轨迹点的那些天）。
        daysCovered.forEach { refreshSummary(bundle.sourceId, it) }

        return source
    }

    // ── 汇总 ──────────────────────────────────────────────────────────────────

    fun summaryOfDay(sourceId: String, day: String): Flow<DailySummary?> =
        summaryDao.observeDay(sourceId, day)

    fun allSummaries(sourceId: String): Flow<List<DailySummary>> = summaryDao.observeAll(sourceId)

    /** 重算某来源某天的距离 / 点数 / 汇总。 */
    suspend fun refreshSummary(sourceId: String, day: String) {
        val points = pointDao.getByDay(sourceId, day)
        // ⚠️ 必须取「该来源」的最近一条快照。
        // 之前用无来源的 snapshotDao.latest()，重算对方某天汇总时会
        // 读到本机的电量 / 屏幕时长，对方的汇总显示成本机数字。
        val latestSnapshot = snapshotDao.latestOf(sourceId)

        var distance = 0.0
        var prev: TrackPoint? = null
        for (p in points) {
            prev?.let {
                val result = FloatArray(1)
                Location.distanceBetween(it.latitude, it.longitude, p.latitude, p.longitude, result)
                val d = result[0]
                // 过滤定位漂移（单点跳变 > 2km 视为异常）
                if (d < DRIFT_THRESHOLD_M) distance += d
            }
            prev = p
        }

        val todaySnapshot = if (latestSnapshot?.dayKey == day) latestSnapshot else null
        summaryDao.upsert(
            DailySummary(
                sourceId = sourceId,
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

    /**
     * 清理 N 天前的本机数据。
     *
     * ⚠️ 删完必须重算受影响日期的汇总。
     *
     * 原来只删点、不动 `daily_summary`，而那些天可能被清空了却没重算，
     * 历史页就会一直挂着已经不存在的里程和点数 —— 点进详情又是空的。
     * 汇总行是按天的，删点后要按「被清过的那些天」重算或删除。
     */
    suspend fun purgeOlderThan(days: Int) {
        // days <= 0 表示不清理（见 SettingsStore.DEFAULT_RETENTION_DAYS）。
        if (days <= 0) return

        val cutoff = System.currentTimeMillis() - days * 24L * 3600_000
        val cutoffDay = TimeUtil.dayKey(cutoff)

        // 先记下会被波及的日期 —— 删完就查不出来了。
        val affectedDays = (
            pointDao.daysOfSource(LOCAL_SOURCE) +
                snapshotDao.daysOfSource(LOCAL_SOURCE)
            ).filter { it.isNotBlank() && it < cutoffDay }

        // 只清本机：导入的对方数据由用户自己决定何时删（来源列表里删）。
        pointDao.deleteOlderThan(cutoff)
        snapshotDao.deleteOlderThan(cutoff)
        appUsageDao.deleteBeforeDay(LOCAL_SOURCE, cutoffDay)
        appSessionDao.deleteBeforeDay(LOCAL_SOURCE, cutoffDay)

        // 逐天重算：当天若还有残留点会算出正确值，
        // 全被清空时 refreshSummary 会写成 0 —— 那也不对，
        // 因为「这天没数据」应该在历史页里消失，而不是留个 0 的壳。
        // 所以按天数重算后，把已经没有任何点的那些天从汇总里删掉。
        for (day in affectedDays) {
            if (pointDao.countByDay(LOCAL_SOURCE, day) == 0) {
                summaryDao.deleteDay(LOCAL_SOURCE, day)
            } else {
                refreshSummary(LOCAL_SOURCE, day)
            }
        }
    }

    companion object {
        /**
         * 定位漂移阈值（米）。
         *
         * 单点跳变超过这个距离就当作 GPS 漂移，不计入里程 ——
         * 室内/隧道口常出现几百米到几公里的瞬时跳点，算了里程会虚高。
         *
         * 增量累加（[appendPointToSummary]）和全量重算（[refreshSummary]）
         * 两处都要用同一个值，否则两条路径算出的里程会对不上。
         */
        const val DRIFT_THRESHOLD_M = 2000f

        /** 停留段：两点间隔超过该值即断开。 */
        const val STAY_GAP_MS = 5 * 60_000L

        /** 停留段：移动超过该距离即断开。 */
        const val STAY_RADIUS_M = 200f

        /** 停留段最短时长。 */
        const val STAY_MIN_MS = 10 * 60_000L


        /**
         * 启动时回填的 App 使用天数。
         *
         * 取 7 是因为系统 UsageStats 的保留期通常就是 7~14 天 ——
         * 要得更多没有意义（系统里就没有），反而白白拉长查询区间。
         * 回填后本地库会**持续累积**，不再受系统保留期限制。
         */
        const val BACKFILL_DAYS = 7

        /**
         * 回填是否已跑过（进程内）。
         *
         * ⚠️ 必须是 static 而不是实例字段：TrackRepository 每次都是 new 的
         * （MainViewModel 一个、TrackService 一个），实例字段挡不住两处都跑。
         * 放伴生对象里，同一进程内只真正回填一次。
         */
        @Volatile
        private var usageBackfilled = false

        /**
         * 回填的互斥锁。
         *
         * 必须是**所有实例共享**的对象：TrackRepository 每次都是 new 的
         * （MainViewModel / TrackService / 两个 Receiver 各一个），
         * 用 synchronized(this) 锁的是各自实例，等于没锁。
         */
        private val backfillLock = Any()
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
