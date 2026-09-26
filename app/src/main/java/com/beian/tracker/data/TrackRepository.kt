package com.beian.tracker.data

import android.content.Context
import android.location.Location
import com.beian.tracker.util.DeviceInfo
import com.beian.tracker.util.TimeUtil
import com.beian.tracker.util.UsageStatsReader
import kotlinx.coroutines.flow.Flow

/** 轨迹与设备状态的读写入口。 */
class TrackRepository(private val context: Context) {

    private val db = AppDatabase.get(context)

    private val pointDao = db.trackPointDao()
    private val snapshotDao = db.deviceSnapshotDao()
    private val summaryDao = db.dailySummaryDao()

    // ── 轨迹点 ────────────────────────────────────────────────────────────────

    suspend fun recordPoint(location: Location) {
        val day = TimeUtil.dayKey(location.time)
        pointDao.insert(
            TrackPoint(
                timestamp = location.time,
                dayKey = day,
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                speed = location.speed,
                accuracy = location.accuracy,
                provider = location.provider.orEmpty(),
            ),
        )
        refreshSummary(day)
    }

    fun pointsOfDay(day: String): Flow<List<TrackPoint>> = pointDao.observeByDay(day)

    suspend fun pointsOfDayOnce(day: String): List<TrackPoint> = pointDao.getByDay(day)

    fun recentPoints(limit: Int = 500): Flow<List<TrackPoint>> = pointDao.observeRecent(limit)

    fun observedDays(): Flow<List<String>> = pointDao.observeDays()

    // ── 设备快照 ──────────────────────────────────────────────────────────────

    /** 采集一次设备状态并落库。 */
    suspend fun captureSnapshot() {
        val now = System.currentTimeMillis()
        val day = TimeUtil.dayKey(now)
        val battery = DeviceInfo.battery(context)
        val network = DeviceInfo.network(context)
        val usage = UsageStatsReader.today(context)

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
        refreshSummary(day)
    }

    fun latestSnapshot(): Flow<DeviceSnapshot?> = snapshotDao.observeLatest()

    suspend fun latestSnapshotOnce(): DeviceSnapshot? = snapshotDao.latest()

    fun snapshotsOfDay(day: String): Flow<List<DeviceSnapshot>> = snapshotDao.observeByDay(day)

    // ── 汇总 ──────────────────────────────────────────────────────────────────

    fun summaryOfDay(day: String): Flow<DailySummary?> = summaryDao.observeDay(day)

    fun allSummaries(): Flow<List<DailySummary>> = summaryDao.observeAll()

    /** 重算某天的距离 / 点数 / 汇总。 */
    suspend fun refreshSummary(day: String) {
        val points = pointDao.getByDay(day)
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

    /** 清理 N 天前的数据。 */
    suspend fun purgeOlderThan(days: Int) {
        val cutoff = System.currentTimeMillis() - days * 24L * 3600_000
        pointDao.deleteOlderThan(cutoff)
        snapshotDao.deleteOlderThan(cutoff)
    }
}