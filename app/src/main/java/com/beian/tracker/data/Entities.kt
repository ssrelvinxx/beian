package com.beian.tracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 本机数据的来源标识。 */
const val LOCAL_SOURCE = "LOCAL"

/**
 * 导出到 .beian 文件时写进文件里的 sourceId。
 *
 * 必须和 [LOCAL_SOURCE] 不同 —— 导入端拿文件里的这个值当「对方」的标识，
 * 两边要是撞了，对方数据会把本机数据覆盖掉。
 */
const val LOCAL_EXPORT_ID = "PEER"

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
    /** 数据来源：本机 = LOCAL，导入的对方数据 = 对应 sourceId。 */
    val sourceId: String = LOCAL_SOURCE,
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
    indices = [Index("timestamp"), Index("dayKey"), Index("sourceId")],
)
data class DeviceSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val dayKey: String,
    /**
     * 数据来源：本机 = LOCAL，导入的对方数据 = 对应 sourceId。
     *
     * 之前这张表没有来源字段，导入时对方的快照根本没法落库，
     * 报备页切到对方后顶部看不到电量 / 网络，看着像「切换没生效」。
     */
    val sourceId: String = LOCAL_SOURCE,
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

/**
 * 按天汇总。
 *
 * 主键是 (sourceId, dayKey)：本机和每个导入来源各自一天一行。
 * 之前只有 dayKey，导入对方数据后对方的汇总会覆盖本机的 ——
 * 历史页显示陌生里程就是这么来的。
 */
@Entity(
    tableName = "daily_summary",
    primaryKeys = ["sourceId", "dayKey"],
    indices = [Index("sourceId")],
)
data class DailySummary(
    /** 数据来源：本机 = LOCAL，导入的对方数据 = 对应 sourceId。 */
    val sourceId: String = LOCAL_SOURCE,
    val dayKey: String,
    val totalDistanceMeters: Double,
    val pointCount: Int,
    val unlockCount: Int,
    val screenTimeMs: Long,
    val firstSeen: Long,
    val lastSeen: Long,
)

/**
 * 某个 App 在某天的使用时长。
 *
 * 主键含 [sourceId]：导入的对方数据包也带 App 排行，
 * 不带来源就会被本机同一天的记录覆盖（或反过来）。
 */
@Entity(
    tableName = "app_usage",
    primaryKeys = ["sourceId", "dayKey", "packageName"],
    indices = [Index("dayKey"), Index("sourceId")],
)
data class AppUsage(
    /** 数据来源：本机 = LOCAL，导入的对方数据 = 对应 sourceId。 */
    val sourceId: String = LOCAL_SOURCE,
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

/**
 * 一次 App 打开记录（前台时间段）。
 * 时间线由这些片段按时间顺序拼出。
 */
@Entity(
    tableName = "app_session",
    primaryKeys = ["id"],
    indices = [Index("dayKey"), Index("startAt"), Index("sourceId")],
)
data class AppSession(
    /**
     * 稳定 id，避免重复插入：`"${sourceId}:${startAt}:${packageName}"`。
     *
     * 必须带 [sourceId] —— 导入的对方数据里同一个时刻开着同一个 App 是很可能的，
     * 不带来源两边会撞成同一条，后写的把先写的顶掉。
     */
    val id: String,
    /** 数据来源：本机 = LOCAL，导入的对方数据 = 对应 sourceId。 */
    val sourceId: String = LOCAL_SOURCE,
    val dayKey: String,
    val packageName: String,
    val appLabel: String,
    /** 进入前台时间。 */
    val startAt: Long,
    /** 离开前台时间；仍在使用中时为 0。 */
    val endAt: Long,
    /** 持续时长（毫秒）。 */
    val durationMs: Long,
)

/**
 * 报备事件流。所有需要展示给对方看的事件都进这张表。
 * 由采集时的状态变化推导生成，保证不重复。
 */
@Entity(
    tableName = "event_log",
    primaryKeys = ["id"],
    indices = [Index("dayKey"), Index("timestamp"), Index("sourceId")],
)
data class EventLog(
    /** "${sourceId}:${type}:${timestamp}" 稳定 id，重复导入不会产生副本。 */
    val id: String,
    /** 数据来源：本机 = LOCAL，导入的对方数据 = 导入时生成的 id。 */
    val sourceId: String,
    val dayKey: String,
    /** 见 EventType。 */
    val type: String,
    val timestamp: Long,
    /** 主标题，如「TA的手机开始充电」。 */
    val title: String,
    /** 附加说明，可空。 */
    val detail: String = "",
    /** 事件相关数值（如电量、通话秒数），-1 表示无。 */
    val value: Long = -1L,
)

/** 事件类型常量。 */
object EventType {
    const val SCREEN_ON = "SCREEN_ON"
    const val SCREEN_OFF = "SCREEN_OFF"
    const val UNLOCK = "UNLOCK"
    const val FIRST_OPEN_TODAY = "FIRST_OPEN_TODAY"
    const val BATTERY_LOW = "BATTERY_LOW"
    const val BATTERY_FULL = "BATTERY_FULL"
    const val CHARGING_START = "CHARGING_START"
    const val CHARGING_STOP = "CHARGING_STOP"
    const val NET_WIFI = "NET_WIFI"
    const val NET_CELLULAR = "NET_CELLULAR"
    const val NET_NONE = "NET_NONE"
    const val CALL_OUT = "CALL_OUT"
    const val CALL_IN = "CALL_IN"
    const val CALL_END = "CALL_END"
    const val APP_OPEN = "APP_OPEN"
    const val STAY = "STAY"
    const val LEAVE = "LEAVE"
    const val SERVICE_START = "SERVICE_START"
}

/**
 * 一个已导入的对方数据包（守护方视角）。
 */
@Entity(tableName = "imported_source")
data class ImportedSource(
    @PrimaryKey val sourceId: String,
    /** 用户导入时填写的昵称，如「宝贝」。 */
    val nickname: String,
    val importedAt: Long,
    /** 数据覆盖的最早 / 最晚时间。 */
    val firstDay: String,
    val lastDay: String,
    val pointCount: Int,
    val eventCount: Int,
)
