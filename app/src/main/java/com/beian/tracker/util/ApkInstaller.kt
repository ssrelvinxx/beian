package com.beian.tracker.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    suspend fun downloadAndInstall(
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

            // GitHub 的 release 资产在国内经常连不上，先找一个可用的加速地址。
            //
            // 这一步必须在下发到 DownloadManager **之前**做完：
            // DownloadManager 一旦 enqueue 就认准那个 URL 了，下载失败只在
            // 通知栏留个记录，没有任何回调让我们换源重试。
            // 探测是阻塞 IO（最多几家镜像 × 6 秒），所以整个方法声明成 suspend，
            // 由调用方放在协程里，绝不能占着主线程。
            val resolved = withContext(Dispatchers.IO) { DownloadMirrors.resolve(url) }

            val request = DownloadManager.Request(Uri.parse(resolved)).apply {
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
            context.startActivity(buildInstallIntent(context, uri))
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开安装界面：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 构造拉起系统安装界面的 Intent。
     *
     * ⚠️ 必须把 uri 同时放进 [ClipData]。
     *
     * 包安装器跑在**另一个进程**（com.android.packageinstaller）。
     * 从 Android 10 起，仅靠 `FLAG_GRANT_READ_URI_PERMISSION` 已经不保证
     * 把 FileProvider 的读权限传递过去 —— 实测表现就是安装界面能弹出，
     * 但立刻报「解析软件包时出现问题」，或者一闪就退，包装不上。
     *
     * 放进 ClipData 后，系统会把「这个 uri 可以读」跟随 Intent 一起
     * 授权给接收方，安装器才拿得到文件内容。
     *
     * 另外显式 setPackage 到系统安装器：不指定时某些 ROM 会弹
     * 应用选择器让用户挑「用什么打开」，那个列表里通常没有安装器，
     * 用户就以为「点了没反应」。
     */
    private fun buildInstallIntent(context: Context, uri: Uri): Intent {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            // ClipData 是权限传递的关键，见上面注释
            clipData = ClipData.newRawUri("apk", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // 优先交给系统安装器；找不到就退回原来的行为（不加 setPackage）
        val installer = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("apk", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("com.android.packageinstaller")
        }
        @Suppress("DEPRECATION")
        val hasInstaller =
            context.packageManager.resolveActivity(installer, 0) != null
        return if (hasInstaller) installer else intent
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
            context.startActivity(buildInstallIntent(context, uri))
        } catch (e: Exception) {
            Toast.makeText(context, "无法安装：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
