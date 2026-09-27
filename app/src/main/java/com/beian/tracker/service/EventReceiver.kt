package com.beian.tracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.beian.tracker.data.AppDatabase
import com.beian.tracker.data.EventLog
import com.beian.tracker.data.EventType
import com.beian.tracker.data.LOCAL_SOURCE
import com.beian.tracker.util.DeviceInfo
import com.beian.tracker.util.TimeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 实时事件接收器：监听屏幕开关、充电插拔、电量、网络变化，
 * 直接写入事件表，弥补 60 秒轮询抓不到的瞬时事件。
 *
 * 在 TrackService 运行时注册（registerReceiver），随服务一起销毁。
 *
 * 去重原则：**按状态变化去重，而不是按时间窗**。
 * 例如 BATTERY_LOW 只在「从 >20% 跌破到 <=20%」时记一次，
 * 之后电量继续跌不会重复记；充电后再次跌回才会再记。
 */
class EventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val now = System.currentTimeMillis()
        val day = TimeUtil.dayKey(now)

        // 本条广播直接产出的事件（type -> 文案）
        val pending = ArrayList<Pair<String, String>>()

        when (action) {
            Intent.ACTION_SCREEN_ON -> {
                pending.add(EventType.SCREEN_ON to "TA打开了手机屏幕")
                // 当天第 1 次亮屏 —— 由广播判定，比 60 秒轮询准得多
                pending.add(EventType.FIRST_OPEN_TODAY to "TA今天第1次打开手机")
            }

            Intent.ACTION_SCREEN_OFF ->
                pending.add(EventType.SCREEN_OFF to "TA关闭了手机屏幕")

            Intent.ACTION_USER_PRESENT ->
                pending.add(EventType.UNLOCK to "TA解锁了手机")

            // 充电插拔由 StaticEventReceiver（Manifest 静态注册）负责：
            // Service 被杀掉的那段时间也能收到，这里不再重复监听。

            Intent.ACTION_BATTERY_CHANGED -> {
                val pct = batteryPctFrom(intent)
                val charging = isChargingFrom(intent)
                if (pct in 0..100) {
                    if (charging && pct >= 100) {
                        pending.add(EventType.BATTERY_FULL to "TA的手机电量已充满")
                    } else if (!charging && pct <= LOW_BATTERY) {
                        pending.add(EventType.BATTERY_LOW to "TA的手机电量仅剩$pct%")
                    }
                }
            }

            android.net.ConnectivityManager.CONNECTIVITY_ACTION -> {
                val net = DeviceInfo.network(context)
                val title = when (net.type) {
                    DeviceInfo.NET_WIFI ->
                        if (net.name.isBlank()) "TA连接了WiFi" else "TA连接了WiFi：${net.name}"
                    DeviceInfo.NET_CELLULAR -> "TA切换为移动网络"
                    else -> "TA的网络已断开"
                }
                val type = when (net.type) {
                    DeviceInfo.NET_WIFI -> EventType.NET_WIFI
                    DeviceInfo.NET_CELLULAR -> EventType.NET_CELLULAR
                    else -> EventType.NET_NONE
                }
                pending.add(type to title)
            }
        }

        if (pending.isEmpty()) return

        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.get(appContext).eventLogDao()

            // 状态去重：有些事件只在状态**真正发生变化**时记录一次。
            // SCREEN_ON/OFF、UNLOCK、CHARGING_* 本身就是瞬时动作，天然不会重复，
            // 但仍加一个极短的防重（同一秒内的重复广播）以免系统双发。
            val items = ArrayList<EventLog>()
            for ((type, title) in pending) {
                val last = dao.latestOfType(LOCAL_SOURCE, type)

                val keep = when (type) {
                    // 今天第 1 次打开手机：当天已经有就不再记
                    EventType.FIRST_OPEN_TODAY ->
                        dao.countOfTypeOnDay(LOCAL_SOURCE, day, EventType.FIRST_OPEN_TODAY) == 0

                    // 电量类：依赖状态跃迁，不做时间窗。只要上一次同类事件不是刚刚
                    // （同一秒）产生的，就放行 —— 真正的去重交给 EventDeriver 的状态比对。
                    EventType.BATTERY_LOW,
                    EventType.BATTERY_FULL,
                    -> last == null || now - last.timestamp > SAME_MOMENT_MS

                    // 网络：系统切换时会连发多次广播，给一个短窗口足够。
                    EventType.NET_WIFI,
                    EventType.NET_CELLULAR,
                    EventType.NET_NONE,
                    -> last == null || now - last.timestamp > NET_DEDUP_MS

                    // 充电/屏幕：瞬时动作，只防同一瞬间的双发。
                    else -> last == null || now - last.timestamp > SAME_MOMENT_MS
                }

                if (keep) {
                    // 「今天第 1 次打开手机」按天唯一，避免与轮询兜底重复
                    val id = if (type == EventType.FIRST_OPEN_TODAY) {
                        "$LOCAL_SOURCE:$type:$day"
                    } else {
                        "$LOCAL_SOURCE:$type:$now"
                    }
                    items.add(
                        EventLog(
                            id = id,
                            sourceId = LOCAL_SOURCE,
                            dayKey = day,
                            type = type,
                            timestamp = now,
                            title = title,
                        ),
                    )
                }
            }

            if (items.isNotEmpty()) dao.insertAll(items.distinctBy { it.id })

            // 触发一次汇总刷新，让「报备」页立刻看到新事件
            try {
                com.beian.tracker.data.TrackRepository(appContext)
                    .refreshSummary(LOCAL_SOURCE, day)
            } catch (_: Exception) {
                // 汇总失败不影响事件本身
            }
        }
    }

    /** 从 BATTERY_CHANGED 广播里取电量百分比。 */
    private fun batteryPctFrom(intent: Intent): Int {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        return if (level >= 0) level * 100 / scale else -1
    }

    /** 从 BATTERY_CHANGED 广播里判断是否在充电。 */
    private fun isChargingFrom(intent: Intent): Boolean {
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    companion object {
        /** 低于该电量算低电量。 */
        const val LOW_BATTERY = 20

        /** 同一瞬间的重复保护（防止系统双发广播）。 */
        const val SAME_MOMENT_MS = 2_000L

        /** 网络切换的合并窗口。 */
        const val NET_DEDUP_MS = 5_000L

        /**
         * 动态注册监听的动作。
         *
         * ⚠️ 充电插拔（ACTION_POWER_CONNECTED / DISCONNECTED）不在这里，
         * 它们由 [StaticEventReceiver] 在 Manifest 里静态注册 ——
         * 这样即使 Service 被杀掉，插拔充电器也照样能记录。
         *
         * 屏幕开关必须动态注册：Android 不允许静态注册 ACTION_SCREEN_ON。
         */
        fun filter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION)
        }
    }
}
