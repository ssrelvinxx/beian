package com.beian.tracker

import android.app.Application
import com.beian.tracker.util.AnrWatchdog
import com.beian.tracker.util.CrashLog
import com.beian.tracker.util.MapTileStore

class BeiAnApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 把地图瓦片缓存指向持久目录：系统清理缓存不会删掉离线地图
        MapTileStore.configure(this)

        // ⚠️ 崩溃日志要**最早**安装，晚了会漏掉启动阶段的崩溃。
        //    放在 MapTileStore.configure 之后是因为它几乎不会抛异常，
        //    真出了事反而想留个记录。
        CrashLog.install(this)

        // 补记「进程上次是怎么没的」。
        //
        // 原生层崩溃时 CrashLog.install 那个处理器不会被触发（进程直接被
        // 信号杀掉），日志文件会是空的 —— 那看起来像「日志功能坏了」。
        // 这里从系统侧问一次，把这种情况也留下来。
        // 读过的记录会记时间戳，不会每次启动都重复写。
        runCatching { CrashLog.recordProcessExitReasons(this) }

        // 主线程卡顿监控。
        //
        // 用户报的「闪退」实际是 ANR（输入等待 5 秒超时），
        // 而系统的 ANR 记录不给主线程堆栈 —— 只能靠这个看门狗
        // 在卡住的那一刻把堆栈抓下来。
        // 阈值 1.5 秒、连续两次超时才记，避免把 GC 抖动误报成卡顿。
        AnrWatchdog.start(this)
    }
}
