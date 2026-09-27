package com.beian.tracker.util

import com.beian.tracker.data.EventLog
import com.beian.tracker.data.EventType
import com.beian.tracker.data.LOCAL_SOURCE

/**
 * 把「App 前台片段」转成报备事件。
 *
 * 数据来源是系统的 UsageStats（需要「使用情况访问」权限）：
 * 用户切到某个 App 时，系统会发 MOVE_TO_FOREGROUND，
 * [UsageStatsReader.todaySessions] 据此还原出每个 App 的前台时段。
 *
 * 这里把这些时段转成 `APP_OPEN` 事件，让「TA 打开了微信」这类记录
 * 出现在报备界面。
 *
 * ⚠️ 转换是**幂等**的：id 由 `startAt + 包名` 决定，
 * 每次轮询重新生成同样的 id，靠数据库 REPLACE 去重，
 * 不会因为反复调用而堆积重复记录。
 */
object AppEventDeriver {

    /** 短于这个时长的片段不记 —— 多是切换路过、误触。 */
    const val MIN_DURATION_MS = 5_000L

    /**
     * 系统级包名，记进来没有意义。
     * 前两个是桌面和系统 UI，用户在桌面上划来划去不算「打开了 App」。
     */
    private val IGNORED_PACKAGES = setOf(
        "com.android.systemui",
        "android",
        "com.android.launcher",
        "com.android.launcher2",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.bbk.launcher2",
    )

    /** 包名前缀：这些是系统应用，通常不需要报备。 */
    private val IGNORED_PREFIXES = listOf(
        "com.android.settings",
    )

    /**
     * 这个包名是否值得上报 / 展示。
     *
     * 过滤掉：桌面、系统 UI、系统设置、本应用自己。
     * App 使用排行也复用这个判断，避免出现「桌面 3小时」这种没意义的条目。
     */
    fun isReportable(packageName: String, selfPackage: String): Boolean =
        packageName != selfPackage &&
            packageName !in IGNORED_PACKAGES &&
            IGNORED_PREFIXES.none { packageName.startsWith(it) }

    /**
     * @param sessions 当天的 App 前台片段（来自 UsageStatsReader）
     * @param selfPackage 本应用包名 —— 不记录「打开自己」
     * @return 可以直接 insertAll 的事件列表
     */
    fun derive(
        sessions: List<AppSessionStat>,
        selfPackage: String,
    ): List<EventLog> = sessions.asSequence()
        .filter { it.durationMs >= MIN_DURATION_MS }
        .filter { isReportable(it.packageName, selfPackage) }
        .map { s ->
            EventLog(
                // 稳定 id：同一次会话无论推导多少次都是同一条
                id = "$LOCAL_SOURCE:${EventType.APP_OPEN}:${s.startAt}:${s.packageName}",
                sourceId = LOCAL_SOURCE,
                dayKey = TimeUtil.dayKey(s.startAt),
                type = EventType.APP_OPEN,
                timestamp = s.startAt,
                title = "TA打开了「${s.appLabel}」",
                detail = formatDetail(s.durationMs),
                value = s.durationMs,
            )
        }
        .toList()

    /** 时长文案。UsageStats 反推出的片段一定有起止，所以时长总是正的。 */
    private fun formatDetail(durationMs: Long): String =
        if (durationMs <= 0) "" else "用了 ${TimeUtil.formatShortDuration(durationMs)}"
}
