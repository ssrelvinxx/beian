package com.beian.tracker.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TimeUtil {
    /**
     * ⚠️ SimpleDateFormat **不是线程安全的**，而这三个 formatter 是
     * 单例对象里的字段，会被多个线程共用：
     *
     *   · 采集线程（TrackService，Dispatchers.IO）
     *   · App 使用数据回填（Dispatchers.IO）
     *   · UI 线程（界面显示日期/时间）
     *
     * 并发调用 format() 会出问题：轻则拿到别的日期字符串，
     * 重则抛 ArrayIndexOutOfBoundsException（内部 Calendar 状态被踩）。
     *
     * 用 ThreadLocal 给每个线程一份自己的实例 —— 无锁、无竞争，
     * 比 synchronized 快，也比「每次 new 一个」省对象。
     */
    private val dayFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
    private val timeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss", Locale.US)
    }
    private val dateTimeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }

    fun dayKey(timestamp: Long = System.currentTimeMillis()): String =
        dayFormat.get()!!.format(Date(timestamp))

    fun time(timestamp: Long): String = timeFormat.get()!!.format(Date(timestamp))

    fun dateTime(timestamp: Long): String =
        dateTimeFormat.get()!!.format(Date(timestamp))

    /** 今日 00:00 的时间戳。 */
    fun startOfToday(): Long = startOfDay(0)

    /**
     * N 天前的 00:00 时间戳。[daysAgo] = 0 即今天。
     *
     * 用 Calendar 做「减天数」而不是直接减 24h 的毫秒数：
     * 夏令时切换那天只有 23 小时，直接减毫秒会算错一天。
     */
    fun startOfDay(daysAgo: Int): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** N 天后的 00:00，即某天的次日零点（用作区间右开边界）。 */
    fun endOfDay(daysAgo: Int): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo + 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** 把毫秒格式化为 "1小时23分" 这类易读文本。 */
    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0分"
        val totalMinutes = ms / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}小时${minutes}分"
            hours > 0 -> "${hours}小时"
            else -> "${minutes}分"
        }
    }

    /**
     * 超短时长格式化，给柱状图纵轴标签用。
     *
     * 柱子上只有几十 dp 的宽度，放不下「1小时20分」这种；
     * 这里压缩成「1h20」「45m」「0」这类形式。
     * 不满 1 分钟但确实有值时显示 "<1m"，避免看起来像没有数据。
     */
    fun formatCompact(ms: Long): String {
        if (ms <= 0) return "0"
        if (ms < 60_000) return "<1m"
        val totalMinutes = ms / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h${minutes}"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    /**
     * 短时长格式化，秒级精度 —— 用于「用了多久」这类不超过几分钟的场景。
     * 与 [formatDuration] 的区别：后者最小单位是分钟，30 秒会显示成 "0分"。
     */
    fun formatShortDuration(ms: Long): String = when {
        ms < 60_000 -> "${(ms / 1000).coerceAtLeast(1)}秒"
        ms < 60 * 60_000 -> "${ms / 60_000}分"
        else -> formatDuration(ms)
    }

    /** 把米格式化为 "1.2km" / "350m"。 */
    fun formatDistance(meters: Double): String = when {
        meters >= 1000 -> String.format(Locale.US, "%.2f km", meters / 1000)
        else -> String.format(Locale.US, "%.0f m", meters)
    }
}