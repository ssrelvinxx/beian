package com.beian.tracker.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.beian.tracker.R
import com.beian.tracker.data.TrackRepository
import com.beian.tracker.ui.MainActivity
import com.beian.tracker.util.SettingsStore
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 前台服务：持续采集定位点，并周期性采集设备状态快照。
 */
class TrackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tickerJob: Job? = null

    /** 实时事件接收器（屏幕 / 电量 / 网络）。 */
    private val eventReceiver = EventReceiver()
    private var receiverRegistered = false

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var repository: TrackRepository
    private lateinit var settings: SettingsStore

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                scope.launch { repository.recordPoint(loc) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        fused = LocationServices.getFusedLocationProviderClient(this)
        repository = TrackRepository(this)
        settings = SettingsStore(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startTracking()
        }
        return START_STICKY
    }

    /**
     * 定位回调是否已注册 —— 防止重复 requestUpdates 导致同一点被写多次。
     *
     * ⚠️ 必须在 [onDestroy] 里复位。
     *
     * 它是实例字段，而 [startTracking] 每次 onStartCommand 都会走一遍：
     * 一旦这里被置 true，进程存活期间的同一次服务生命周期内就不会再注册。
     * 而 onDestroy 里已经 fused.removeLocationUpdates(locationCallback) 把回调摘了，
     * 若此时 True 的状态跟着 Service 实例残留（例如服务被系统 stop 后
     * 又在同一进程里被重新拉起、复用了同一个 Service 实例的字段语义），
     * 就会变成「回调已移除、却永远不会再注册」——
     * 结果就是定位图标还在（请求由系统侧保留），但一个点都收不到。
     */
    private var updatesRequested = false

    private fun startTracking() {
        startForegroundCompat()
        registerEventReceiver()
        if (!hasLocationPermission()) return

        scope.launch {
            // 断连补偿：把上次被杀掉期间漏掉的屏幕开关事件用 UsageStats 补回来。
            // 放在最前面，让「报备」页立刻能看到完整的当天记录。
            runCatching { repository.backfillScreenEvents() }

            val interval = settings.intervalSec.first()
            if (!updatesRequested) {
                requestUpdates(interval)
                updatesRequested = true
            }
            startTicker(interval)
        }
    }

    private fun requestUpdates(intervalSec: Int) {
        // ⚠️ 必须用 HIGH_ACCURACY，不要改成 BALANCED_POWER_ACCURACY。
        //
        // 之前用的是 BALANCED_POWER_ACCURACY + setMinUpdateDistanceMeters(10f)，
        // 在 ColorOS / MIUI 这类激进省电的 ROM 上会被系统判定为「低优先级请求」
        // 而直接降级：请求发出去了（状态栏能看到定位图标），但回调长期不触发，
        // 轨迹点一条都落不了库 —— 而同一进程里基于 UsageStats 的 App 使用统计
        // 照常有数据，于是表现为「报备页有数据、轨迹页却一个点都没有」。
        //
        // 对照验证：高德地图能正常定位，用的正是 HIGH_ACCURACY。
        //
        // 距离阈值一并去掉：采集本来就靠 intervalSec 控制频率，
        // 再加 10 米门槛会让「静止不动」时永远收不到回调，
        // 停留点分析也失去依据。
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            intervalSec * 1000L,
        ).setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            fused.requestLocationUpdates(request, locationCallback, mainLooper)
        } catch (_: SecurityException) {
            // 权限被撤销，忽略
        }
    }

    /** 周期性抓设备状态快照。 */
    private fun startTicker(intervalSec: Int) {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                // ⚠️ 必须逐次捕获。
                //
                // captureSnapshot() 里要做 UsageStats 全量查询 + 多张表的
                // replaceDay（删除+插入）。任何一步抛异常（ROM 差异、
                // 数据库锁、权限被撤），异常都会逃出 while 循环：
                //   1. 这个协程直接结束 → 采集永久停止，界面上却还显示「正在记录」
                //   2. 异常无人处理 → 传到 CoroutineExceptionHandler（没设）→ 崩溃
                //
                // 捕获后本轮跳过，下一轮继续，采集不会因为一次失败就断掉。
                runCatching { repository.captureSnapshot() }
                    .onFailure { Log.w(TAG, "captureSnapshot failed, skip this round", it) }
                delay(intervalSec * 1000L)
            }
        }
    }

    /** 注册实时事件监听。 */
    private fun registerEventReceiver() {
        if (receiverRegistered) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(eventReceiver, EventReceiver.filter(), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(eventReceiver, EventReceiver.filter())
            }
            receiverRegistered = true
        } catch (_: Exception) {
            // 注册失败不影响主流程
        }
    }

    private fun unregisterEventReceiver() {
        if (!receiverRegistered) return
        try {
            unregisterReceiver(eventReceiver)
        } catch (_: Exception) {
            // 忽略
        }
        receiverRegistered = false
    }

    /**
     * 是否具备定位权限。
     *
     * ⚠️ FINE 和 COARSE 任一即可，不能只认 FINE。
     *
     * Android 12+ 的定位授权弹窗允许用户只选「大致位置」，
     * 此时只有 COARSE 被授予。若这里只检查 FINE，这类用户点「开始记录」后
     * 会在 [startTracking] 的第一步直接 return —— 整个采集（含 App 使用统计）
     * 都不启动，而界面上还显示「正在记录」，看起来就是「明明有定位权限却没数据」。
     *
     * 代价是精度略低，但「有大致位置」远好过「完全没有数据」。
     */
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun startForegroundCompat() {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_running))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        try {
            fused.removeLocationUpdates(locationCallback)
        } catch (_: Exception) {
            // 忽略
        }
        // 回调已移除，标志必须一起复位，否则下次 startTracking 会因为
        // updatesRequested == true 而跳过注册 —— 定位请求还在、点却永远收不到。
        updatesRequested = false
        unregisterEventReceiver()
        tickerJob?.cancel()
        scope.cancel()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.beian.tracker.START"
        const val ACTION_STOP = "com.beian.tracker.STOP"
        private const val TAG = "TrackService"
        private const val CHANNEL_ID = "beian_tracking"
        private const val NOTIF_ID = 1001

        /**
         * 服务是否正在运行。
         *
         * 进程内静态标记：Service 被系统杀掉时进程通常还活着（或者一起死，
         * 那时标记也跟着没了），两种情况都不会失真。
         *
         * 它解决的是「用户设置里开着采集，但 Service 其实没跑」的静默失效 ——
         * 之前只有 [BootReceiver]（开机）和轨迹页会拉起服务，
         * App 冷启动后停在报备页就一直没人采集。
         */
        @Volatile
        private var running = false

        fun isRunning(): Boolean = running

        fun start(context: Context) {
            val intent = Intent(context, TrackService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TrackService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        /**
         * 确保服务在运行；已经在跑就什么都不做。
         *
         * 幂等很重要：[startTracking] 里的 [requestUpdates] 每调一次就多注册
         * 一份定位回调，重复调用会让同一个位置被重复写库。
         *
         * @return true 表示这次真的发起了启动
         */
        fun ensureRunning(context: Context): Boolean {
            if (running) return false
            start(context)
            return true
        }
    }
}