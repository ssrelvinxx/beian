package com.beian.tracker.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * 用系统 DownloadManager 下载新版 APK，并在下载完成后拉起安装界面。
 *
 * 注意：Android 不允许普通 App 静默安装，必须由用户在弹出的系统界面点「安装」。
 * Android 8.0+ 还需用户为本 App 开启「安装未知应用」权限。
 */
object ApkInstaller {

    /** 是否已获得「安装未知应用」权限。 */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** 跳到系统的「安装未知应用」授权页。 */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(context, "请到系统设置里允许安装未知应用", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 下载 APK 并安装。
     *
     * @param version 用于生成文件名
     * @return DownloadManager 的 id，可用于查询进度；失败返回 -1
     */
    fun downloadAndInstall(
        context: Context,
        url: String,
        version: String,
        title: String = "花花动态",
    ): Long {
        if (url.isBlank()) {
            Toast.makeText(context, "这个版本没有提供 APK 下载", Toast.LENGTH_LONG).show()
            return -1
        }

        return try {
            val fileName = "huahua_$version.apk"
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("$title $version")
                setDescription("正在下载新版本…")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                // 存到 App 私有外部目录：无需任何存储权限，FileProvider 也能直接分享
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
                setMimeType("application/vnd.android.package-archive")
            }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(request)

            registerInstallReceiver(context, dm, id, fileName)
            id
        } catch (e: Exception) {
            Toast.makeText(context, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
            -1
        }
    }

    /** 下载完成后拉起安装界面。 */
    private fun registerInstallReceiver(
        context: Context,
        dm: DownloadManager,
        downloadId: Long,
        fileName: String,
    ) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return
                try {
                    ctx.unregisterReceiver(this)
                } catch (_: Exception) {
                    // 忽略
                }
                installDownloaded(ctx, dm, downloadId, fileName)
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun installDownloaded(
        context: Context,
        dm: DownloadManager,
        downloadId: Long,
        fileName: String,
    ) {
        // 用 FileProvider 分享已下载的文件（App 私有外部目录）
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            fileName,
        )

        if (!file.exists()) {
            Toast.makeText(context, "下载的文件找不到了", Toast.LENGTH_LONG).show()
            return
        }

        if (!canInstall(context)) {
            Toast.makeText(
                context,
                "请先允许「花花动态」安装未知应用，再点一次更新",
                Toast.LENGTH_LONG,
            ).show()
            openInstallPermissionSettings(context)
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开安装界面：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 已完成下载的 APK 文件（如果存在），用于「已下载，去安装」。 */
    fun downloadedApk(context: Context, version: String): File? {
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "huahua_$version.apk",
        )
        return file.takeIf { it.exists() }
    }

    /** 直接安装本地已有 APK。 */
    fun installLocal(context: Context, file: File) {
        if (!canInstall(context)) {
            openInstallPermissionSettings(context)
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法安装：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
