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

    /**
     * 短于这个时长的片段不记 —— 多是切换路过、误触。
     *
     * 5 秒太短了：从桌面点进一个 App、退出来，往往就有 5~8 秒。
     * 15 秒才算「真的用了一下」。
     */
    const val MIN_DURATION_MS = 15_000L

    /**
     * 系统级 / 非用户主动打开的包名，记进来没有意义。
     *
     * ⚠️ 不要只列固定包名 —— 国产 ROM 的自有组件层出不穷
     * （com.oplus.appdetail 这种，用户根本没「打开」它）。
     * 所以下面用「前缀」匹配来覆盖各家 ROM 的系统命名空间。
     */
    private val IGNORED_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher2",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
    )

    /**
     * 前缀匹配：命中即视为系统组件。
     *
     * 这些命名空间下几乎全是 ROM 自带的东西 ——
     * 桌面、权限页、应用详情、设置向导、后台管理…
     * 它们会频繁进入前台，但都不是用户「打开了一个 App」。
     */
    private val IGNORED_PREFIXES = listOf(
        // AOSP / Google
        "com.android.systemui",
        "com.android.settings",
        "com.android.providers.",
        "com.android.server.",
        "com.android.internal.",
        "com.google.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        // 小米
        "com.miui.",
        "com.xiaomi.",
        "com.android.thememanager",
        // OPPO / 一加 / realme
        "com.oplus.",
        "com.coloros.",
        "com.oppo.",
        "com.oneplus.",
        "com.realme.",
        // vivo / iQOO
        "com.vivo.",
        "com.iqoo.",
        "com.bbk.",
        // 华为 / 荣耀
        "com.huawei.",
        "com.hihonor.",
        "com.honor.",
        // 三星
        "com.samsung.android.",
        "com.sec.android.",
        // 其他常见
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf",
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
