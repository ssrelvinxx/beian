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

/** 一次 App 前台片段。 */
data class AppSessionStat(
    val packageName: String,
    val appLabel: String,
    val startAt: Long,
    val endAt: Long,
    val durationMs: Long,
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

    /**
     * 读取今日 App 前台片段（打开时间 + 时长），按打开时间升序。
     * 仍在使用中的片段 endAt = 0，durationMs 截止到当前。
     */
    fun todaySessions(context: Context): List<AppSessionStat> {
        if (!hasPermission(context)) return emptyList()

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = TimeUtil.startOfToday()
        val end = System.currentTimeMillis()

        val events = usm.queryEvents(start, end) ?: return emptyList()
        val event = UsageEvents.Event()

        val out = ArrayList<AppSessionStat>()
        var screenOn = false
        // 当前处于前台的 App 及其开始时间
        var curPkg: String? = null
        var curStart = -1L

        fun closeCurrent(at: Long, stillOpen: Boolean = false) {
            val pkg = curPkg ?: return
            val s0 = curStart
            if (s0 > 0 && at > s0) {
                out.add(
                    AppSessionStat(
                        packageName = pkg,
                        appLabel = labelOf(context, pkg),
                        startAt = s0,
                        endAt = if (stillOpen) 0L else at,
                        durationMs = at - s0,
                    ),
                )
            }
            if (!stillOpen) {
                curPkg = null
                curStart = -1L
            }
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> screenOn = true
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    screenOn = false
                    // 熄屏：结束当前片段
                    closeCurrent(event.timeStamp)
                }
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    val pkg = event.packageName ?: continue
                    if (pkg == curPkg) continue
                    // 切到新 App：结束上一个
                    closeCurrent(event.timeStamp)
                    curPkg = pkg
                    curStart = event.timeStamp
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val pkg = event.packageName ?: continue
                    if (pkg == curPkg) closeCurrent(event.timeStamp)
                }
            }
        }

        // 收尾：仍在使用的片段
        if (curPkg != null && curStart > 0 && screenOn) {
            closeCurrent(end, stillOpen = true)
        }

        return out.sortedBy { it.startAt }
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
    /**
     * 回溯某段时间内的屏幕开关事件（用于服务重启后的断连补偿）。
     *
     * 系统 UsageStats 会保留屏幕交互记录，即使我们的 Service 当时没在运行。
     * 用它把断开期间漏掉的「亮屏 / 熄屏」补回事件表。
     *
     * @param since 起始时间戳（一般取「上一条事件的时间」）
     * @param until 结束时间戳
     * @return 按时间升序的 (时间戳, 是否亮屏)
     */
    fun screenEventsBetween(context: Context, since: Long, until: Long): List<Pair<Long, Boolean>> {
        if (since >= until) return emptyList()
        if (!hasPermission(context)) return emptyList()

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(since, until) ?: return emptyList()
        val event = UsageEvents.Event()
        val out = ArrayList<Pair<Long, Boolean>>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE ->
                    out.add(event.timeStamp to true)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE ->
                    out.add(event.timeStamp to false)
            }
        }
        return out.sortedBy { it.first }
    }

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