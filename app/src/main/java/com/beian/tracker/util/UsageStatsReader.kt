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

    /**
     * 同一 App 的相邻片段合并阈值。
     *
     * 即使闭环排除了后台时间，仍会有「切走几秒又回来」造成的碎片
     * （去了趟通知栏、被系统弹窗打断、分屏切换）。
     * 间隔小于该值的两段视为同一次使用。
     */
    private const val MERGE_GAP_MS = 2 * 60 * 1000L

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
     *
     * ⚠️ 改用 `queryUsageStats`（系统聚合），不再用 `queryEvents` 自己累加。
     *
     * 为什么换：自己按事件累加看着直接，实际很难做对 ——
     * 已确认的漏算是「同一个 App 连续两次 MOVE_TO_FOREGROUND」
     * （Activity 重建、权限弹窗返回、分屏切换都会触发）：
     * 后一次会直接覆盖前一次的起始时间，前一段时长凭空消失。
     * 另有熄屏边界、无 BACK 直接切前台等边界，各 ROM 行为还不一致。
     *
     * `queryUsageStats` 由系统自己算，好处有两层：
     *   1. 结果与「设置 → 应用 → 使用时长」一致，用户对照不会觉得数据错。
     *   2. 不必再维护那套易错的状态机。
     *
     * 代价：只有总时长，拿不到「几点到几点在用」。
     * 所以时间轴与「TA 打开了 XX」事件仍然走 [todaySessions]/[queryEvents]，
     * 那是唯一能拿到精确起止时间的接口。两者分工，不是替换关系。
     */
    fun todayPerApp(context: Context): List<AppUsageStat> {
        if (!hasPermission(context)) return emptyList()

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = TimeUtil.startOfToday()
        val end = System.currentTimeMillis()

        // INTERVAL_DAILY + 覆盖「今天」的区间：系统会给出该区间内各包的聚合。
        val stats = runCatching { usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end) }
            .getOrNull()
            .orEmpty()

        val out = stats
            // ⚠️ 必须过滤：queryUsageStats 会带回一堆 totalTimeInForeground=0
            // 的历史包（系统保留最近若干天的记录），不过滤会列出一堆没用的 App。
            .filter { it.totalTimeInForeground > 0 }
            .mapNotNull { s ->
                val pkg = s.packageName ?: return@mapNotNull null
                AppUsageStat(
                    packageName = pkg,
                    appLabel = labelOf(context, pkg),
                    usageMs = s.totalTimeInForeground,
                    // 系统聚合里 lastTimeUsed 是该包最后一次在前台的时间；
                    // launchCount 系统不提供，用 0 表示未知（界面不展示它）。
                    launchCount = 0,
                    lastUsed = s.lastTimeUsed,
                )
            }

        if (out.isEmpty()) {
            // 个别 ROM（或数据刚重置）下 queryUsageStats 会返回空，
            // 此时退回事件累加 —— 有漏算也比整块空白好。
            return todayPerAppFromEvents(context, start, end)
        }
        return out.sortedByDescending { it.usageMs }
    }

    /**
     * 事件累加版（回退路径）。
     *
     * ⚠️ 已修掉「同一 App 连续两次 MOVE_TO_FOREGROUND 丢时长」的问题：
     * 收到新的前台事件时，若该包已有未结算的起点，先把它结算掉再覆盖。
     * 这个函数只在 [todayPerApp] 拿不到系统聚合时才会用到。
     */
    private fun todayPerAppFromEvents(
        context: Context,
        start: Long,
        end: Long,
    ): List<AppUsageStat> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(start, end) ?: return emptyList()
        val event = UsageEvents.Event()

        val usageMs = HashMap<String, Long>()
        val launches = HashMap<String, Int>()
        val lastSeen = HashMap<String, Long>()
        val resumedAt = HashMap<String, Long>()

        /** 结算某个包从 [at] 到 [until] 的前台时长。 */
        fun settle(pkg: String, at: Long, until: Long) {
            if (at > 0 && until > at) usageMs[pkg] = (usageMs[pkg] ?: 0L) + (until - at)
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                // 熄屏：把仍在「前台」的 App 结算掉。
                // 这里不需要额外的 screenOn 标志 —— 熄屏本身就以
                // SCREEN_NON_INTERACTIVE 事件的形式出现，直接结算即可。
                // （此前有个只赋值不读取的 screenOn 变量，已删除。）
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    resumedAt.keys.toList().forEach { pkg ->
                        val at = resumedAt.remove(pkg) ?: return@forEach
                        settle(pkg, at, event.timeStamp)
                    }
                }
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    val pkg = event.packageName ?: continue
                    // 其他包让位，先结算
                    resumedAt.keys.toList().forEach { prev ->
                        if (prev != pkg) {
                            val at = resumedAt.remove(prev) ?: return@forEach
                            settle(prev, at, event.timeStamp)
                        }
                    }
                    // ⚠️ 同一个包重复收到前台事件时，先结算上一段再覆盖起点。
                    // 之前这里直接 resumedAt[pkg] = timestamp，
                    // 前一段时长就丢了（Activity 重建等场景很常见）。
                    resumedAt[pkg]?.let { prevAt -> settle(pkg, prevAt, event.timeStamp) }
                    resumedAt[pkg] = event.timeStamp
                    launches[pkg] = (launches[pkg] ?: 0) + 1
                    lastSeen[pkg] = event.timeStamp
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val pkg = event.packageName ?: continue
                    val at = resumedAt.remove(pkg) ?: 0L
                    settle(pkg, at, event.timeStamp)
                    lastSeen[pkg] = event.timeStamp
                }
            }
        }

        // 收尾：仍在使用中的 App 算到当前
        resumedAt.forEach { (pkg, at) -> settle(pkg, at, end) }

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
        // App 已退到后台，但屏幕仍亮着 —— 这时不算「结束」，只是「暂时不在最前面」
        var curBackgrounded = false
        // 退到后台的时刻：结算时用它当右端点，后台时长天然被排除
        var curBackgroundAt = -1L

        /**
         * 结算当前片段。
         *
         * [at] 是前台时段的右端点。注意调用方传的必须是「它真正离开前台」的时刻，
         * 而不是收到 MOVE_TO_BACKGROUND 的时刻 —— 后台时间不计入前台使用。
         */
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
                curBackgrounded = false
            }
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> screenOn = true

                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    screenOn = false
                    // 熄屏：前台时段到此为止
                    closeCurrent(event.timeStamp)
                }

                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    val pkg = event.packageName ?: continue
                    if (pkg == curPkg) {
                        // 同一个 App 又回到前台：把中间在后台的那段时间排除掉。
                        // 做法是就地结算「上一次的前台时段」，然后以此刻为新起点。
                        if (curBackgrounded) {
                            // 结算时用「退到后台的时刻」作为终点，后台时长天然被排除
                            closeCurrent(curBackgroundAt)
                            curPkg = pkg
                            curStart = event.timeStamp
                            curBackgrounded = false
                        }
                        continue
                    }
                    // 真的切到别的 App：结算上一个（若有）
                    if (curPkg != null) {
                        closeCurrent(if (curBackgrounded) curBackgroundAt else event.timeStamp)
                    }
                    curPkg = pkg
                    curStart = event.timeStamp
                    curBackgrounded = false
                }

                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val pkg = event.packageName ?: continue
                    if (pkg == curPkg) {
                        // ⚠️ 关键改动：不再立即结束。
                        // App 退到后台 ≠ 用户不用了（等消息、看通知、切走两秒再回来）。
                        // 只记下时刻，等它真的被别的 App 顶掉、或熄屏，再结算。
                        curBackgrounded = true
                        curBackgroundAt = event.timeStamp
                    }
                }
            }
        }

        // 收尾：仍在使用的片段（屏幕亮着才算）
        if (curPkg != null && curStart > 0 && screenOn) {
            closeCurrent(end, stillOpen = true)
        }

        return mergeAdjacent(out).sortedBy { it.startAt }
    }

    private fun mergeAdjacent(sessions: List<AppSessionStat>): List<AppSessionStat> {
        if (sessions.size <= 1) return sessions
        val sorted = sessions.sortedBy { it.startAt }
        val merged = ArrayList<AppSessionStat>()
        for (s in sorted) {
            val last = merged.lastOrNull()
            val samePkg = last != null && last.packageName == s.packageName
            // 用「上一段的实际结束时刻」算间隔，不是用跨度 —— 否则后台时间会混进来
            val lastEnd = if (last != null) last.startAt + last.durationMs else 0L
            val gap = if (last != null) s.startAt - lastEnd else Long.MAX_VALUE
            if (samePkg && gap in 0..MERGE_GAP_MS) {
                // ⚠️ 时长累加，不是取首尾跨度。
                // 跨度会把中间「切走的那几十秒」也算成使用时长，
                // 而我们要的是纯前台时间。
                val newDuration = last!!.durationMs + s.durationMs
                merged[merged.size - 1] = last.copy(
                    // endAt 仅用于展示/调试，取最后一段的结束
                    endAt = if (s.endAt == 0L) 0L else lastEnd + gap + s.durationMs,
                    durationMs = newDuration,
                )
            } else {
                merged.add(s)
            }
        }
        return merged
    }

    /** 包名 → 应用显示名。 */
    /**
     * 取应用显示名。
     *
     * 取不到时**不要**返回整串包名（会显示成 com.tencent.tmgp.dfm 糊在界面上），
     * 退化成包名最后一段更可读。
     *
     * 取不到通常是因为 Android 11+ 的包可见性限制 ——
     * 已在 AndroidManifest 里声明 QUERY_ALL_PACKAGES + <queries> 解决。
     */
    private fun labelOf(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(info).toString().ifBlank { shortName(packageName) }
    } catch (_: Exception) {
        shortName(packageName)
    }

    /** com.tencent.tmgp.dfm -> tmgp.dfm 之前取最后一段：dfm */
    private fun shortName(packageName: String): String =
        packageName.substringAfterLast('.').ifBlank { packageName }

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