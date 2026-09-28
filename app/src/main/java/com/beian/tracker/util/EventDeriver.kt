package com.beian.tracker.util

import com.beian.tracker.data.EventLog
import com.beian.tracker.data.EventType
import com.beian.tracker.data.LOCAL_SOURCE

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
            // 「今天第 1 次点亮屏幕」按天唯一：广播和轮询都可能产生，
            // 用固定 id + REPLACE 保证当天只有一条。
            val id = if (type == EventType.FIRST_OPEN_TODAY) {
                "$LOCAL_SOURCE:$type:$day"
            } else {
                "$LOCAL_SOURCE:$type:$now"
            }
            out.add(
                EventLog(
                    id = id,
                    sourceId = LOCAL_SOURCE,
                    dayKey = day,
                    type = type,
                    timestamp = now,
                    title = title,
                    detail = detail,
                    value = value,
                ),
            )
        }

        // ── 说明 ────────────────────────────────────────────────────────────
        // 充电插拔、电量阈值（充满 / 低电量）、网络切换 这几类事件，
        // 全部由系统广播实时记录：
        //   - CHARGING_START / CHARGING_STOP → StaticEventReceiver（静态注册）
        //   - BATTERY_FULL / BATTERY_LOW     → EventReceiver（动态注册）
        //   - NET_WIFI / NET_CELLULAR / NET_NONE → EventReceiver（动态注册）
        //
        // 这里**故意不再重复推导**：
        // 轮询只是 60 秒采一次，用快照前后比对推导这些事件，
        // 会因为「上一次快照」跨越了很长一段断连时间而误判 ——
        // 比如服务被杀 30 分钟期间用户插了充电器（广播已记录），
        // 服务重启后第一次轮询比对旧快照，会再补一条「开始充电」，造成重复。
        //
        // 宁可漏（极端情况下广播被系统丢掉），也不要重复报同样的内容。

        // ── 屏幕 ────────────────────────────────────────────────────────────
        // 屏幕开关由 EventReceiver 实时监听写入。
        // 「今天第 1 次点亮屏幕」正常情况下也由广播在亮屏瞬间写入；
        // 这里只在「当前屏幕亮着 + 当天还没有这条记录」时兜底，
        // 覆盖服务当天启动较晚、漏掉那次亮屏广播的情况。
        // 该事件用「按天固定 id」，所以广播与轮询不会产生两条。
        if (screenOn && !prev.firstOpenToday) {
            add(EventType.FIRST_OPEN_TODAY, "TA今天第1次点亮了屏幕")
        }

        return out
    }
}
