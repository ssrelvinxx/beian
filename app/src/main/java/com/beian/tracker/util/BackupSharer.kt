package com.beian.tracker.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把导出的备份包分享出去。
 *
 * 为什么要有这一步（而不是直接用 SAF 的 CreateDocument 存盘）：
 *   用户导出数据的目的就是**发给对方**。原来的流程是
 *   「弹系统存储选择器 → 选个目录 → 存好 → 用户自己再去文件管理器
 *     找到这个文件 → 长按 → 分享」—— 四步里有一步纯属多余，
 *   而且很多人存完根本找不到文件在哪。
 *
 *   现在改为写到自己目录、直接弹分享面板 —— 和「崩溃日志」的分享
 *   是同一个体验，选微信/QQ 发出去就行。
 *
 * 文件放在 filesDir/exports/。file_paths.xml 里已声明
 * `<files-path name="exports" path="exports/"/>`，FileProvider 能授权。
 */
object BackupSharer {

    private const val DIR_NAME = "exports"

    /** 只保留最近这么多份导出，避免反复导出把存储堆满。 */
    private const val MAX_KEEP = 3

    /** 导出目录。 */
    fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /**
     * 只把备份写入文件，不分享。
     *
     * ⚠️ 必须在 IO 线程调用（同步阻塞写，备份包可能几 MB）。
     *
     * 与 [shareFile] 分开，是因为两者要求的线程不同：
     * 写盘属于 IO，而分享内部要 startActivity、**必须在主线程**。
     * 合成一个方法的话，调用方无论放哪个线程都是错的。
     *
     * @return 写好的文件，交给 [shareFile]
     */
    fun writeOnly(context: Context, json: String, fileName: String): File {
        val file = File(dir(context), fileName)
        file.writeText(json)
        trimOld(context)
        return file
    }

    /**
     * 用 FileProvider 授权，把文件交给系统分享面板。
     *
     * ⚠️ 必须在主线程调用 —— 内部会 startActivity。
     */
    fun shareFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            // ⚠️ ClipData 与 FLAG_GRANT_READ_URI_PERMISSION 缺一不可。
            //
            // 分享目标（微信、QQ 等）在**另一个进程**里读这个 uri。
            // 只加 flag 而不放进 ClipData 时，部分 ROM 不会把读权限
            // 传递过去 —— 对方收到的会是一个打不开的空文件。
            // （与安装 APK 时遇到的是同一类问题。）
            clipData = ClipData.newRawUri("backup", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** 只保留最近 [MAX_KEEP] 份导出。 */
    private fun trimOld(context: Context) {
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(BackupCodec.EXT) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_KEEP)
            ?.forEach { runCatching { it.delete() } }
    }
}
