package com.beian.tracker.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/** 当日屏幕使用统计。 */
data class ScreenUsage(
    val screenTimeMs: Long,
    val unlockCount: Int,
    val screenOnCount: Int,
)

object UsageStatsReader {

    /** 是否已授予「使用情况访问」权限。 */
    fun hasPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 读取今日屏幕使用时长 / 解锁次数 / 亮屏次数。
     * 无权限时返回全 0，不抛异常。
     */
    fun today(context: Context): ScreenUsage {
        if (!hasPermission(context)) return ScreenUsage(0, 0, 0)

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = TimeUtil.startOfToday()
        val end = System.currentTimeMillis()

        var screenTimeMs = 0L
        var unlockCount = 0
        var screenOnCount = 0

        val events = usm.queryEvents(start, end) ?: return ScreenUsage(0, 0, 0)
        val event = UsageEvents.Event()

        // 用「亮屏→熄屏」配对累计使用时长
        var screenOnAt = -1L
        // 用「应用在前台」的时间段累计，作为屏幕时长兜底
        var lastResume = -1L
        var lastResumedPackage: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    screenOnCount++
                    if (screenOnAt < 0) screenOnAt = event.timeStamp
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    if (screenOnAt > 0) {
                        screenTimeMs += event.timeStamp - screenOnAt
                        screenOnAt = -1L
                    }
                }
                UsageEvents.Event.KEYGUARD_HIDDEN -> unlockCount++
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    lastResume = event.timeStamp
                    lastResumedPackage = event.packageName
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (lastResume > 0 && event.packageName == lastResumedPackage) {
                        lastResume = -1L
                        lastResumedPackage = null
                    }
                }
            }
        }

        // 仍在亮屏：算到当前时刻
        if (screenOnAt > 0) screenTimeMs += end - screenOnAt

        return ScreenUsage(
            screenTimeMs = screenTimeMs.coerceAtLeast(0),
            unlockCount = unlockCount,
            screenOnCount = screenOnCount,
        )
    }
}