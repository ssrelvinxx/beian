package com.beian.tracker.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志。
 *
 * 为什么需要它：
 *   用户报「会崩」，但崩溃发生在用户手机上，开发者拿不到堆栈 ——
 *   只能靠猜，而猜已经错过一次了（把 onDetach 当成根因，改完还是崩）。
 *   把现场留下来，才能停止猜测。
 *
 * 记两类信息，缺一不可：
 *
 *   ① Java/Kotlin 层未捕获异常（[install]）
 *      用 Thread.setDefaultUncaughtExceptionHandler 接管，写入完整堆栈。
 *      这类日志有文件名和行号，能直接定位代码。
 *
 *   ② 进程退出原因（[recordProcessExitReasons]）
 *      用 ActivityManager.getHistoricalProcessExitReasons（Android 11+）。
 *      它能区分「真的崩溃了」和「被系统低内存杀了」——
 *      这两件事的修法完全不同，混在一起排查会南辕北辙。
 *
 * ⚠️ 抓不到原生崩溃的堆栈（SIGSEGV 等）。
 *    地图（osmdroid）崩溃经常是原生层的，那种情况 ① 是空的、
 *    只有 ② 会留下「进程异常退出」的记录。看到这种现象基本可以
 *    判定是原生层问题，要去查 JNI / 图形资源释放。
 */
object CrashLog {

    private const val TAG = "CrashLog"
    private const val DIR_NAME = "crash"

    /** 日志文件里最多保留多少条崩溃记录（防止文件无限增长）。 */
    private const val MAX_ENTRIES = 50

    /** 崩语文件名字：crash-<时间>.txt */
    private fun fileFor(context: Context, now: Long): File {
        val dir = File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        return File(dir, "crash-$stamp.txt")
    }

    /**
     * 安装未捕获异常处理器。
     *
     * 必须在 Application.onCreate 里尽早调用，晚了就漏掉启动阶段的崩溃。
     *
     * 处理流程刻意保持简单：**只写文件，然后交还给系统默认处理器**。
     * 不要在这里做弹窗、上报、重启之类的事 —— 进程正在死，
     * 任何多余操作都可能让日志写不完，反而丢掉现场。
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(appContext, thread, throwable)
            } catch (e: Exception) {
                // 记日志本身失败就算了，绝不能因此再抛一次
                Log.w(TAG, "failed to write crash log", e)
            }
            // 交回系统默认处理：保留系统原本的崩溃上报/重启行为
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.appendLine("=== 崩溃 ===")
        sb.appendLine("时间: ${TimeUtil.dateTime(now)}")
        sb.appendLine("线程: ${thread.name}")
        sb.appendLine("版本: ${versionName(context)} (${versionCode(context)})")
        sb.appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("ABI : ${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine()
        sb.appendLine("--- 堆栈 ---")
        sb.appendLine(Log.getStackTraceString(throwable))

        val file = fileFor(context, now)
        file.writeText(sb.toString())
        Log.e(TAG, "crash written to ${file.absolutePath}")
        trimOldFiles(context)
    }

    /**
     * 记录进程退出原因。
     *
     * 为什么要单独做这一层：
     * 如果崩溃出在原生层，[install] 那个处理器根本不会被触发 ——
     * 用户看到「闪退」，但日志文件里什么都没有，会让人误判成
     * 「日志功能坏了」。这里从系统侧问「进程上次是怎么没的」，
     * 就能把这个缺口补上。
     *
     * Android 11（API 30）才有 getHistoricalProcessExitReasons。
     *
     * 返回新写入的描述（没新东西时返回 null），供上层决定是否落盘。
     */
    fun recordProcessExitReasons(context: Context): String? {
        // API 30 以下没有这个接口。
        // 具体实现拆到带 @RequiresApi 的私有函数里 —— 直接把
        // ApplicationExitInfo 的调用写在这个分支里，minSdk 26 编译时
        // 会触发 NewApi lint 报错。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return recordExitReasonsApi30(context.applicationContext)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun recordExitReasonsApi30(context: Context): String? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null

        val reasons = try {
            am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
        } catch (e: Exception) {
            Log.w(TAG, "getHistoricalProcessExitReasons failed", e)
            return null
        }
        if (reasons.isEmpty()) return null

        val lastSeen = lastSeenExitTimestamp(context)
        val fresh = reasons.filter { it.timestamp > lastSeen }
        if (fresh.isEmpty()) return null

        val sb = StringBuilder()
        fresh.sortedBy { it.timestamp }.forEach { info ->
            sb.appendLine("=== 进程退出 ===")
            sb.appendLine("时间: ${TimeUtil.dateTime(info.timestamp)}")
            sb.appendLine("原因: ${reasonText(info.reason)}")
            info.description?.takeIf { it.isNotBlank() }?.let {
                sb.appendLine("说明: $it")
            }
            if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                sb.appendLine("⚠️ 原生层崩溃：Java 异常处理器抓不到，" +
                    "通常是 JNI 或图形资源释放问题。")
            }
            sb.appendLine()
        }

        val newest = fresh.maxOf { it.timestamp }
        saveLastSeenExitTimestamp(context, newest)

        val file = fileFor(context, System.currentTimeMillis())
        file.writeText(sb.toString())
        trimOldFiles(context)
        return sb.toString()
    }

    /** 读全部日志（崩溃 + 主线程卡顿，最新的在前），供设置页展示。 */
    fun readAll(context: Context): List<File> {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f ->
            // 两类都收：crash-*.txt（异常/进程退出）、anr-*.txt（主线程卡顿）
            f.isFile && f.name.endsWith(".txt")
        }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** 全部日志拼成一段文本，用于分享。 */
    fun readAllAsText(context: Context): String {
        val files = readAll(context)
        if (files.isEmpty()) return "（没有崩溃记录）"
        return files.joinToString("\n\n") { f ->
            "───── ${f.name} ─────\n" + runCatching { f.readText() }.getOrDefault("(读取失败)")
        }
    }

    /** 清空全部崩溃日志。 */
    fun clear(context: Context) {
        readAll(context).forEach { runCatching { it.delete() } }
    }

    private fun trimOldFiles(context: Context) {
        val files = readAll(context)
        if (files.size > MAX_ENTRIES) {
            files.drop(MAX_ENTRIES).forEach { runCatching { it.delete() } }
        }
    }

    // ── 已读到的退出记录时间戳（避免每次启动重复落盘） ──────────────

    private const val PREF = "crash_log"
    private const val KEY_LAST_EXIT = "last_exit_ts"

    private fun lastSeenExitTimestamp(context: Context): Long =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_EXIT, 0L)

    private fun saveLastSeenExitTimestamp(context: Context, ts: Long) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_EXIT, ts).apply()
    }

    // ── 退出原因翻译成中文 ────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonText(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "正常退出（EXIT_SELF）"
        ApplicationExitInfo.REASON_SIGNALED -> "被信号杀死（SIGNALED）"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "低内存被杀（LOW_MEMORY）"
        ApplicationExitInfo.REASON_CRASH -> "崩溃（CRASH）"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生崩溃（CRASH_NATIVE）"
        ApplicationExitInfo.REASON_ANR -> "无响应（ANR）"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "初始化失败"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限变更"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源占用过高被杀"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "用户主动结束"
        ApplicationExitInfo.REASON_OTHER -> "其他"
        else -> "未知（$reason）"
    }

    private fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private fun versionCode(context: Context): Long = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (_: Exception) {
        0L
    }
}
