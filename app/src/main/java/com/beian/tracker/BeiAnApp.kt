package com.beian.tracker

import android.app.Application
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
    }
}
