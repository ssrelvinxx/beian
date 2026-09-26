package com.beian.tracker.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.beian.tracker.data.DeviceSnapshot
import com.beian.tracker.data.TrackPoint
import java.io.File

/** 把当日数据导出成文本，供手动分享给对方。 */
object ReportExporter {

    fun export(
        context: Context,
        day: String,
        points: List<TrackPoint>,
        snapshots: List<DeviceSnapshot>,
        distanceMeters: Double,
    ): File {
        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        val file = File(dir, "beian_$day.txt")

        val latest = snapshots.firstOrNull()
        val sb = StringBuilder()
        sb.appendLine("报备 $day")
        sb.appendLine("──────────────")
        sb.appendLine("移动距离：${TimeUtil.formatDistance(distanceMeters)}")
        sb.appendLine("定位点数：${points.size}")
        latest?.let {
            sb.appendLine("电量：${it.batteryLevel}%" + if (it.batteryCharging) "（充电中）" else "")
            sb.appendLine("屏幕使用：${TimeUtil.formatDuration(it.screenTimeMs)}")
            sb.appendLine("解锁次数：${it.unlockCount}")
            sb.appendLine("网络：${it.networkType}")
        }
        sb.appendLine()
        sb.appendLine("轨迹：")
        if (points.isEmpty()) {
            sb.appendLine("（无记录）")
        } else {
            points.forEach { p ->
                sb.appendLine(
                    "${TimeUtil.time(p.timestamp)}  " +
                        "${"%.5f".format(p.latitude)}, ${"%.5f".format(p.longitude)}",
                )
            }
        }
        file.writeText(sb.toString())
        return file
    }

    /** 弹出系统分享面板。 */
    fun share(context: Context, file: File, title: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, file.readText())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }
}