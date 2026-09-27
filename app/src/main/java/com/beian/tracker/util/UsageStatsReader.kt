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

/** 单个 App 的当日使用情况。 */
data class AppUsageStat(
    val packageName: String,
    val appLabel: String,
    val usageMs: Long,
    val launchCount: Int,
    val lastUsed: Long,
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
     * 读取今日各 App 的使用时长，按使用时长降序。
     * 无权限时返回空列表。
     */
    fun todayPerApp(context: Context): List<AppUsageStat> {
        if (!hasPermission(context)) return emptyList()

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = TimeUtil.startOfToday()
        val end = System.currentTimeMillis()

        val events = usm.queryEvents(start, end) ?: return emptyList()
        val event = UsageEvents.Event()

        val usageMs = HashMap<String, Long>()
        val launches = HashMap<String, Int>()
        val lastSeen = HashMap<String, Long>()
        val resumedAt = HashMap<String, Long>()

        // 记录事件发生时的屏幕状态，熄屏期间的“前台”不计入使用
        var screenOn = false

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> screenOn = true
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    screenOn = false
                    // 熄屏：把仍在“前台”的 App 结算掉
                    resumedAt.keys.toList().forEach { pkg ->
                        val at = resumedAt.remove(pkg) ?: return@forEach
                        if (at > 0) {
                            usageMs[pkg] = (usageMs[pkg] ?: 0L) + (event.timeStamp - at)
                        }
                    }
                }
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    val pkg = event.packageName ?: continue
                    // 切换到新 App：先结算上一个
                    resumedAt.keys.toList().forEach { prev ->
                        if (prev != pkg) {
                            val at = resumedAt.remove(prev) ?: return@forEach
                            if (at > 0) usageMs[prev] = (usageMs[prev] ?: 0L) + (event.timeStamp - at)
                        }
                    }
                    resumedAt[pkg] = event.timeStamp
                    launches[pkg] = (launches[pkg] ?: 0) + 1
                    lastSeen[pkg] = event.timeStamp
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val pkg = event.packageName ?: continue
                    val at = resumedAt.remove(pkg) ?: 0L
                    if (at > 0) usageMs[pkg] = (usageMs[pkg] ?: 0L) + (event.timeStamp - at)
                    lastSeen[pkg] = event.timeStamp
                }
            }
        }

        // 收尾：仍在使用中的 App 算到当前
        resumedAt.forEach { (pkg, at) ->
            if (at > 0) usageMs[pkg] = (usageMs[pkg] ?: 0L) + (end - at)
        }

        return usageMs.entries
            .filter { it.value > 0 }
            .map { (pkg, ms) ->
                AppUsageStat(
                    packageName = pkg,
                    appLabel = labelOf(context, pkg),
                    usageMs = ms,
                    launchCount = launches[pkg] ?: 0,
                    lastUsed = lastSeen[pkg] ?: 0L,
                )
            }
            .sortedByDescending { it.usageMs }
    }

    /** 包名 → 应用显示名。 */
    private fun labelOf(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
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