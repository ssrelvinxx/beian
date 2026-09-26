package com.beian.tracker.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TimeUtil {
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun dayKey(timestamp: Long = System.currentTimeMillis()): String = dayFormat.format(Date(timestamp))

    fun time(timestamp: Long): String = timeFormat.format(Date(timestamp))

    fun dateTime(timestamp: Long): String = dateTimeFormat.format(Date(timestamp))

    /** 今日 00:00 的时间戳。 */
    fun startOfToday(): Long {
        val cal = Calendar.getInstance()
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

    /** 把米格式化为 "1.2km" / "350m"。 */
    fun formatDistance(meters: Double): String = when {
        meters >= 1000 -> String.format(Locale.US, "%.2f km", meters / 1000)
        else -> String.format(Locale.US, "%.0f m", meters)
    }
}