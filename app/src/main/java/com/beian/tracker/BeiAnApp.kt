package com.beian.tracker

import android.app.Application
import android.util.Log
import com.beian.tracker.util.AnrWatchdog
import com.beian.tracker.util.CrashLog
import com.beian.tracker.util.MapTileStore
import com.beian.tracker.util.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

        // ── 首次启动生成数据包密码 ────────────────────────────────────────────
        //
        // ⚠️ 必须自动生成，不能留空。
        //
        // 留空时导出会退回**内置口令**加密，而那个口令就在代码里，
        // 反编译即可见 —— 等于没加密。用户看到文件是乱码会以为安全，
        // 于是最该保护的轨迹数据实际上谁都能解开。
        // 默认值必须落在安全的一侧：随机 16 位，用户什么都不做也是安全的。
        //
        // 幂等（已有密码就原样返回），且放到协程里跑：
        // DataStore 的读写要走磁盘，放主线程会给启动加一次 IO 抖动。
        // 用 applicationScope 而不是 GlobalScope —— 它的生命周期
        // 跟着进程走，不会在配置变更等场景被意外取消。
        applicationScope.launch {
            runCatching { SettingsStore(this@BeiAnApp).ensureBackupPassword() }
                .onFailure { Log.w(TAG, "ensure backup password failed", it) }
        }
    }

    private companion object {
        const val TAG = "BeiAnApp"

        /** 只在进程存活期间用得上，进程结束即释放，不需要清理。 */
        private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
