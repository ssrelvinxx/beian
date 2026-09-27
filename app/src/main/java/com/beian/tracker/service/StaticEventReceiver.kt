package com.beian.tracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.beian.tracker.data.AppDatabase
import com.beian.tracker.data.EventLog
import com.beian.tracker.data.EventType
import com.beian.tracker.data.LOCAL_SOURCE
import com.beian.tracker.util.DeviceInfo
import com.beian.tracker.util.SettingsStore
import com.beian.tracker.util.TimeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 静态注册的事件接收器。
 *
 * 只负责「服务没在运行时也必须收到」的事件：**充电插拔**、**开机**。
 * 这些是瞬时动作，Service 被杀掉的那段时间错过了就永远补不回来。
 *
 * 与 [EventReceiver] 的分工：
 *  - [EventReceiver]（动态，随 Service）：屏幕开关、解锁、电量阈值、网络切换
 *  - 本类（静态，Manifest 注册）：充电插拔
 *
 * 两者**监听的 action 不重叠**，所以不会重复记录。
 *
 * ⚠️ 只在用户开启过记录（trackingEnabled = true）时才写库，
 * 避免用户停用后仍在后台采集。
 */
class StaticEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val now = System.currentTimeMillis()
        val day = TimeUtil.dayKey(now)

        // 用 Pair(type, title) 表达，when 作为表达式保证所有分支都有返回值
        val (type, title) = when (action) {
            Intent.ACTION_POWER_CONNECTED -> {
                val pct = runCatching { DeviceInfo.battery(context).level }.getOrDefault(-1)
                EventType.CHARGING_START to
                    if (pct in 0..100) "TA的手机开始充电（$pct%）" else "TA的手机开始充电"
            }

            Intent.ACTION_POWER_DISCONNECTED -> {
                val pct = runCatching { DeviceInfo.battery(context).level }.getOrDefault(-1)
                EventType.CHARGING_STOP to
                    if (pct in 0..100) "TA的手机结束充电（$pct%）" else "TA的手机结束充电"
            }

            else -> return
        }

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 用户没开启记录就不写
                if (!SettingsStore(appContext).trackingEnabled.first()) return@launch

                val dao = AppDatabase.get(appContext).eventLogDao()

                // 同一瞬间的系统双发保护
                val last = dao.latestOfType(LOCAL_SOURCE, type)
                if (last != null && now - last.timestamp < SAME_MOMENT_MS) return@launch

                dao.insertAll(
                    listOf(
                        EventLog(
                            id = "$LOCAL_SOURCE:$type:$now",
                            sourceId = LOCAL_SOURCE,
                            dayKey = day,
                            type = type,
                            timestamp = now,
                            title = title,
                        ),
                    ),
                )

                runCatching {
                    com.beian.tracker.data.TrackRepository(appContext)
                        .refreshSummary(LOCAL_SOURCE, day)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** 同一瞬间的重复保护。 */
        const val SAME_MOMENT_MS = 2_000L
    }
}
