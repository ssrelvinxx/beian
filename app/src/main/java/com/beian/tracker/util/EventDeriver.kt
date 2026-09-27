package com.beian.tracker.util

import com.beian.tracker.data.EventLog
import com.beian.tracker.data.EventType

/**
 * 由「当前状态」与「上一次已知状态」的差异推导报备事件。
 *
 * 纯函数式：输入上一次状态 + 当前状态，输出需要记录的事件。
 * 不依赖数据库，方便测试与复用（本机采集 / 导入解析都用同一套文案）。
 */
object EventDeriver {

    /** 电量低于该值时提醒充电。 */
    const val LOW_BATTERY = 20

    /** 上一次已知的状态。 */
    data class Prev(
        val batteryLevel: Int = -1,
        val charging: Boolean = false,
        val networkType: String = DeviceInfo.NET_NONE,
        val networkName: String = "",
        val screenOn: Boolean = false,
        val firstOpenToday: Boolean = false,
    )

    /**
     * 生成事件。返回的 timestamp 一律为 [now]。
     * [firstOpenToday] 由调用方查库得出（当天是否已有 FIRST_OPEN_TODAY）。
     * 文案统一用「TA」，与守护方视角一致。
     */
    fun derive(
        prev: Prev,
        batteryLevel: Int,
        charging: Boolean,
        networkType: String,
        networkName: String,
        screenOn: Boolean,
        now: Long,
    ): List<EventLog> {
        val out = ArrayList<EventLog>()
        val day = TimeUtil.dayKey(now)

        fun add(type: String, title: String, detail: String = "", value: Long = -1L) {
            out.add(
                EventLog(
                    id = "LOCAL:$type:$now",
                    sourceId = "LOCAL",
                    dayKey = day,
                    type = type,
                    timestamp = now,
                    title = title,
                    detail = detail,
                    value = value,
                ),
            )
        }

        // ── 电量 ────────────────────────────────────────────────────────────
        if (batteryLevel in 0..100) {
            // 首次采集：只记录当前充电状态，不产生"开始充电"噪音
            if (prev.batteryLevel >= 0) {
                if (charging && !prev.charging) {
                    add(EventType.CHARGING_START, "TA的手机开始充电", "当前电量$batteryLevel%", batteryLevel.toLong())
                }
                if (!charging && prev.charging) {
                    add(EventType.CHARGING_STOP, "TA的手机结束充电", "当前电量$batteryLevel%", batteryLevel.toLong())
                }
                // 充满：充电中且达到 100
                if (charging && batteryLevel >= 100 && prev.batteryLevel < 100) {
                    add(EventType.BATTERY_FULL, "TA的手机电量已充满", "当前电量100%", 100L)
                }
                // 低电量：跌破阈值才提醒一次
                if (!charging && batteryLevel <= LOW_BATTERY && prev.batteryLevel > LOW_BATTERY) {
                    add(
                        EventType.BATTERY_LOW,
                        "TA的手机电量仅剩$batteryLevel%",
                        "提醒对方充电",
                        batteryLevel.toLong(),
                    )
                }
            }
        }

        // ── 网络 ────────────────────────────────────────────────────────────
        if (prev.networkType != networkType || prev.networkName != networkName) {
            when (networkType) {
                DeviceInfo.NET_WIFI -> add(
                    EventType.NET_WIFI,
                    if (networkName.isBlank()) "TA连接了WiFi" else "TA连接了WiFi：$networkName",
                )
                DeviceInfo.NET_CELLULAR -> add(EventType.NET_CELLULAR, "TA切换为移动网络")
                DeviceInfo.NET_NONE -> add(EventType.NET_NONE, "TA的网络已断开")
            }
        }

        // ── 屏幕 ────────────────────────────────────────────────────────────
        // 屏幕开关由 EventReceiver 实时监听写入，这里只兜底「今天第 1 次打开手机」，
        // 避免轮询与广播重复产生 SCREEN_ON。
        if (screenOn && !prev.screenOn && !prev.firstOpenToday) {
            add(EventType.FIRST_OPEN_TODAY, "TA今天第1次打开手机")
        }

        return out
    }
}
