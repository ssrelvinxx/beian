package com.beian.tracker.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主线程卡顿监控（看门狗）。
 *
 * 为什么需要它：
 *   系统那侧留下的 ANR 记录只会说「等了 5 秒没人响应」，
 *   **不会说主线程当时卡在哪一行**。所以排查只能靠猜 ——
 *   而猜已经错过一次了（先怀疑地图资源被释放，改了还是卡）。
 *
 *   这个看门狗补上那个缺口：后台线程定期往主线程丢一个空任务，
 *   若长时间没被执行，就认为主线程被堵住，当场 dump 它此刻的堆栈。
 *
 * 实现要点（每一条都是为了避开一个坑）：
 *
 *   · 用**独立的后台线程**，不用协程。协程调度本身也可能被主线程的
 *     阻塞连累，看门狗必须能独立于它运行。
 *
 *   · 判「卡住」要求**连续两次**探测都超时。GC 或系统抖动造成的
 *     单次延迟不是我们的问题，记下来只会污染日志。
 *
 *   · 记录后进入冷却期。一直卡着时若不冷却，会每轮写一个文件。
 *
 *   · 只 dump **主线程**的堆栈。全线程 dump 又大又难读，
 *     而 ANR 的成因几乎都在主线程。
 *
 * 输出目录与 [CrashLog] 相同（filesDir/crash/），文件名 anr-*.txt，
 * 在设置页的「崩溃日志」里能一起看到并分享。
 */
object AnrWatchdog {

    private const val TAG = "AnrWatchdog"

    /** 超过这个时长没响应就算卡顿。 */
    private const val THRESHOLD_MS = 1500L

    /** 探测间隔。 */
    private const val POLL_INTERVAL_MS = 300L

    /** 记录一次之后的冷却时间，避免写爆存储。 */
    private const val COOLDOWN_MS = 30_000L

    /** 最多保留多少个卡顿记录。 */
    private const val MAX_FILES = 20

    @Volatile
    private var running = false

    private var worker: Thread? = null

    /**
     * 启动看门狗。由 Application.onCreate 调用（必须在主线程）。
     */
    fun start(context: Context) {
        if (running) return
        running = true

        val dir = File(context.filesDir, "crash").apply { mkdirs() }
        val mainHandler = Handler(Looper.getMainLooper())
        val mainThread = Looper.getMainLooper().thread

        worker = Thread({
            var strikes = 0
            var lastRecordedAt = 0L

            while (running) {
                val startedAt = System.currentTimeMillis()

                // 往主线程丢一个空任务，它被执行时记下时刻。
                // 用数组而不是 @Volatile 字段：局部变量的可见性没法
                // 跨线程保证，而这个写-读恰好发生在两个线程之间。
                val acked = longArrayOf(0L)
                if (!mainHandler.post { acked[0] = System.currentTimeMillis() }) break

                // 等它执行完，最多等 THRESHOLD_MS
                while (running && acked[0] == 0L &&
                    System.currentTimeMillis() - startedAt < THRESHOLD_MS
                ) {
                    try {
                        Thread.sleep(POLL_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }

                val blocked = acked[0] == 0L
                if (blocked) strikes++ else strikes = 0

                // 连续两次超时才认定，避免把偶发停顿记成卡顿
                if (strikes >= 2) {
                    val now = System.currentTimeMillis()
                    if (now - lastRecordedAt > COOLDOWN_MS) {
                        lastRecordedAt = now
                        runCatching { dump(dir, mainThread, now - startedAt, strikes) }
                            .onFailure { Log.w(TAG, "dump failed", it) }
                    }
                }

                val spent = System.currentTimeMillis() - startedAt
                val rest = POLL_INTERVAL_MS - spent
                if (rest > 0) {
                    try {
                        Thread.sleep(rest)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }
        }, "anr-watchdog").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }

        Log.i(TAG, "started, threshold=${THRESHOLD_MS}ms")
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    private fun dump(dir: File, mainThread: Thread, elapsedMs: Long, strikes: Int) {
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.appendLine("=== 主线程卡顿 ===")
        sb.appendLine("时间: ${TimeUtil.dateTime(now)}")
        sb.appendLine("表现: 主线程至少 ${elapsedMs}ms 没响应（连续 $strikes 次探测超时）")
        sb.appendLine()
        sb.appendLine("--- 主线程此刻的堆栈 ---")
        mainThread.stackTrace.forEach { e -> sb.appendLine("    at $e") }
        sb.appendLine()
        sb.appendLine("读法：从下往上，第一层属于 com.beian.tracker 的，")
        sb.appendLine("就是卡住的位置。上面那些 android./java. 是调度框架。")

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        File(dir, "anr-$stamp.txt").writeText(sb.toString())
        Log.e(TAG, "main thread blocked >=${elapsedMs}ms, dumped anr-$stamp.txt")

        // 只留最近 MAX_FILES 个
        dir.listFiles { f -> f.isFile && f.name.startsWith("anr-") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_FILES)
            ?.forEach { runCatching { it.delete() } }
    }
}
