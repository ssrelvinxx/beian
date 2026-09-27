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
 * 实时事件接收器：监听屏幕开关、电量 / 充电、网络变化，
 * 直接写入事件表，弥补 60 秒轮询抓不到的瞬时事件。
 *
 * 在 TrackService 运行时注册（registerReceiver），随服务一起销毁。
 */
class EventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val now = System.currentTimeMillis()
        val day = TimeUtil.dayKey(now)
        val pending = ArrayList<Pair<String, String>>() // type to title

        when (action) {
            Intent.ACTION_SCREEN_ON ->
                pending.add(EventType.SCREEN_ON to "TA打开了手机屏幕")

            Intent.ACTION_SCREEN_OFF ->
                pending.add(EventType.SCREEN_OFF to "TA关闭了手机屏幕")

            Intent.ACTION_USER_PRESENT ->
                pending.add(EventType.UNLOCK to "TA解锁了手机")

            Intent.ACTION_BATTERY_CHANGED -> {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                val pct = if (level >= 0) level * 100 / scale else -1
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

                if (pct in 0..100) {
                    if (charging && pct >= 100) {
                        pending.add(EventType.BATTERY_FULL to "TA的手机电量已充满")
                    } else if (!charging && pct <= 20) {
                        pending.add(EventType.BATTERY_LOW to "TA的手机电量仅剩$pct%")
                    }
                }
                // 充电状态变化用快照比对，这里只处理"充满"和"低电量"两个明确事件
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

            // 去重：同一类型在短时间内反复触发（如电量每降 1% 都发广播）只记一次
            val filtered = pending.filter { (type, _) ->
                val last = dao.latestOfType(LOCAL_SOURCE, type) ?: return@filter true
                when (type) {
                    // 低电量 / 充满 / 网络：10 分钟内不重复
                    EventType.BATTERY_LOW, EventType.BATTERY_FULL,
                    EventType.NET_WIFI, EventType.NET_CELLULAR, EventType.NET_NONE,
                    -> now - last.timestamp > DEDUP_WINDOW_MS
                    // 屏幕开关：1 秒内不重复
                    else -> now - last.timestamp > 1000
                }
            }

            val items = filtered.map { (type, title) ->
                EventLog(
                    id = "$LOCAL_SOURCE:$type:$now",
                    sourceId = LOCAL_SOURCE,
                    dayKey = day,
                    type = type,
                    timestamp = now,
                    title = title,
                )
            }
            if (items.isNotEmpty()) dao.insertAll(items.distinctBy { it.id })
        }
    }

    companion object {
        /** 同类事件的去重窗口。 */
        const val DEDUP_WINDOW_MS = 10 * 60_000L

        /** 需要监听的动作。 */
        fun filter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION)
        }
    }
}
