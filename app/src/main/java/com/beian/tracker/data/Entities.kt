package com.beian.tracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 一条定位轨迹点。 */
@Entity(
    tableName = "track_points",
    indices = [Index("timestamp"), Index("dayKey")],
)
data class TrackPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    /** yyyy-MM-dd，便于按天查询。 */
    val dayKey: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val speed: Float = 0f,
    val accuracy: Float = 0f,
    val provider: String = "",
)

/** 设备状态快照（电量 / 屏幕 / 解锁 / 网络）。 */
@Entity(
    tableName = "device_snapshots",
    indices = [Index("timestamp"), Index("dayKey")],
)
data class DeviceSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val dayKey: String,
    val batteryLevel: Int,
    val batteryCharging: Boolean,
    /** 当日累计屏幕使用时长（毫秒）。 */
    val screenTimeMs: Long,
    /** 当日累计解锁次数。 */
    val unlockCount: Int,
    /** 当日累计亮屏次数。 */
    val screenOnCount: Int,
    /** 网络类型：WIFI / CELLULAR / NONE。 */
    val networkType: String,
    /** 是否已连接（有网）。 */
    val networkConnected: Boolean,
    val networkName: String = "",
)

/** 按天汇总。 */
@Entity(tableName = "daily_summary")
data class DailySummary(
    @PrimaryKey val dayKey: String,
    val totalDistanceMeters: Double,
    val pointCount: Int,
    val unlockCount: Int,
    val screenTimeMs: Long,
    val firstSeen: Long,
    val lastSeen: Long,
)
/** 某个 App 在某天的使用时长。 */
@Entity(
    tableName = "app_usage",
    primaryKeys = ["dayKey", "packageName"],
    indices = [Index("dayKey")],
)
data class AppUsage(
    val dayKey: String,
    val packageName: String,
    val appLabel: String,
    /** 当日使用时长（毫秒）。 */
    val usageMs: Long,
    /** 当日进入前台的次数。 */
    val launchCount: Int,
    /** 最后使用时间。 */
    val lastUsed: Long,
)
