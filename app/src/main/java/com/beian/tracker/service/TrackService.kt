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
import com.beian.tracker.util.DeviceInfo
import com.beian.tracker.util.SettingsStore
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.HandlerThread
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

    private lateinit var locationManager: LocationManager
    private lateinit var repository: TrackRepository
    private lateinit var settings: SettingsStore

    /**
     * 系统定位回调。
     *
     * ⚠️ 这里用的是 android.location.LocationManager，不是 GMS 的
     * FusedLocationProviderClient —— 详见 [requestUpdates] 的说明。
     *
     * 收到的 Location 直接就是 android.location.Location，
     * 和 [TrackRepository.recordPoint] 的参数类型完全一致，不需要转换。
     */
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            scope.launch { repository.recordPoint(location) }
        }

        // Android 11+ 要求实现，不关心 provider 启停。
        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        repository = TrackRepository(this)
        settings = SettingsStore(this)
        locationHandler = HandlerThread("track-location").apply { start() }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                // ⚠️ 采集间隔改了要重新注册定位请求，否则新值要等下次
                // 服务重启才生效 —— 用户改完设置会发现「没反应」。
                // startTracking() 里对 updatesRequested 有防护，
                // 这里要先复位，才会用新间隔重新 requestUpdates。
                //
                // 只做「重注册定位」这一件事：断连补偿、回填都只在
                // 服务真正启动时该跑一次，改个间隔不必重做。
                // ticker 不受影响 —— 它的周期是固定的，与轨迹间隔无关。
                updatesRequested = false
                reloadLocationUpdates()
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
     * 一旦这里被置 true，同一次服务生命周期内就不会再注册。
     * 而 onDestroy 里已经 removeUpdates 把回调摘了，
     * 标志不复位就会变成「回调已移除、却永远不会再注册」——
     * 结果是一个点都收不到。
     */
    private var updatesRequested = false

    /**
     * 承载定位回调的后台线程。
     *
     * 不要用主线程 Looper：UI 忙时回调会排队延迟，也加重主线程负担。
     * onCreate 里创建，onDestroy 里必须 quitSafely()，否则线程泄漏。
     */
    private lateinit var locationHandler: HandlerThread

    private fun startTracking() {
        startForegroundCompat()
        registerEventReceiver()

        // ⚠️ App 使用数据回填必须在「定位权限」守卫**之前**。
        //
        // 它只依赖「使用情况访问」权限，和定位无关。放在守卫后面的话，
        // 用户没给定位权限时整个 startTracking 会提前 return，
        // 回填就永远不会执行 —— 统计页除了今天之外全是空的。
        scope.launch {
            runCatching { repository.backfillDailyUsage() }
        }

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
            // ⚠️ 快照周期不再跟「轨迹间隔」绑定，用独立的固定值，见 startTicker 注释。
            startTicker()
        }
    }

    /** 已注册的 provider，onDestroy 时按此摘除。 */
    private val registeredProviders = mutableListOf<String>()

    /**
     * 按当前设置重新注册定位请求（不重启 ticker、不重跑补偿逻辑）。
     *
     * 用户改了「移动轨迹采集间隔」后调用。已经注册过的话先摘掉旧的，
     * 否则会同时存在两个不同间隔的请求，系统按更密的那个回调。
     */
    private fun reloadLocationUpdates() {
        scope.launch {
            if (!hasLocationPermission()) return@launch
            val interval = runCatching { settings.intervalSec.first() }.getOrDefault(120)
            // 用一个 listener 实例注册到多个 provider，
            // 所以 removeUpdates 一次就能把所有 provider 的回调摘干净。
            runCatching { locationManager.removeUpdates(locationListener) }
            registeredProviders.clear()
            requestUpdates(interval)
        }
    }

    /**
     * 注册系统定位回调。
     *
     * ⚠️ 这里刻意使用 android.location.LocationManager，而不是 GMS 的
     * FusedLocationProviderClient。
     *
     * 原因：国行 ROM（ColorOS / HarmonyOS 等）普遍没有完整的 Google 服务框架，
     * 或者 GMS 被深度冻结。此时 LocationServices.getFusedLocationProviderClient()
     * 不会抛异常（所以原来的 try/catch SecurityException 完全抓不到），
     * requestLocationUpdates() 也「调用成功」，但回调永远不会触发 ——
     * 一个轨迹点都收不到，而且没有任何错误可供排查。
     *
     * 同一台机器上高德地图、系统相机的地理标记都正常，因为它们走的正是
     * 系统原生 LocationManager，不依赖 GMS。改用系统 API 后行为与它们一致。
     *
     * 另外注册多个 provider 并取「最近已知位置」做种子：
     * GPS 在室内可能长时间定不到位，此时 NETWORK_PROVIDER 仍能给出
     * 基站/WiFi 级定位，避免整块地图空着。
     */
    private fun requestUpdates(intervalSec: Int) {
        val minTimeMs = intervalSec * 1000L
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )

        registeredProviders.clear()
        for (provider in providers) {
            try {
                // provider 被用户在系统里关掉时直接跳过，不要注册
                // （注册了也不会回调，只会让人误以为「已经在采集」）。
                if (!locationManager.isProviderEnabled(provider)) {
                    Log.w(TAG, "provider disabled, skip: $provider")
                    continue
                }
                locationManager.requestLocationUpdates(
                    provider,
                    minTimeMs,
                    // ⚠️ 位移门槛不能再是 0。
                    //
                    // 之前写 0f，注释是「靠 minTime 控频，静止时也要有点」——
                    // 但 effect 是反的：minDistance=0 意味着**只要 provider
                    // 有任何风吹草动就回调**（GPS 抖动、基站/WiFi 切换），
                    // 系统看到的是一个持续活跃的定位请求，耗电且显眼。
                    //
                    // 给一个门槛后，静止时不再持续回调；移动时按 minTime
                    // 节流仍能落点。取值见 MIN_DISTANCE_M 的注释。
                    MIN_DISTANCE_M,
                    locationListener,
                    // ⚠️ 用后台线程的 Looper，不要用主线程。
                    //
                    // 主线程正在忙 UI（滚动、地图重绘、重组）时，定位回调
                    // 会在同一队列里排队：既让回调延迟，也加重主线程负担。
                    // 定位回调本身只是落库（内部已切到 IO 作用域），
                    // 放后台线程完全够用。线程在 onDestroy 里退出。
                    locationHandler.looper,
                )
                registeredProviders.add(provider)
            } catch (e: SecurityException) {
                // 权限被撤销
                Log.w(TAG, "no location permission for $provider", e)
            } catch (e: IllegalArgumentException) {
                // provider 不存在（部分 ROM 没有 NETWORK_PROVIDER）
                Log.w(TAG, "provider unavailable: $provider", e)
            } catch (e: Exception) {
                Log.w(TAG, "requestLocationUpdates failed: $provider", e)
            }
        }

        if (registeredProviders.isEmpty()) {
            Log.w(TAG, "没有可用的定位 provider，轨迹将无法采集")
        }

        seedLastKnownLocation(providers)
    }

    /**
     * 用「最近已知位置」补一个点。
     *
     * 注册回调后要等系统派点（GPS 冷启动可能要几十秒），这期间轨迹页
     * 一直是空的，看着就像「采集没生效」。系统缓存的最后一次位置
     * 通常就是几秒前，直接落库能让界面立刻有反馈。
     *
     * 只接受足够新鲜的位置：过期的缓存点会在地图上把当前人拉到一个
     * 完全错误的地方，比空着更糟。
     */
    private fun seedLastKnownLocation(providers: List<String>) {
        val now = System.currentTimeMillis()
        val freshest = providers.mapNotNull { provider ->
            try {
                locationManager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            }
        }.maxByOrNull { it.time } ?: return

        if (now - freshest.time > LAST_KNOWN_MAX_AGE_MS) return

        scope.launch {
            runCatching { repository.recordPoint(freshest) }
                .onFailure { Log.w(TAG, "seed last known location failed", it) }
        }
    }

    /**
     * 周期性抓设备状态快照（电量/网络/App 使用/前台片段）。
     *
     * ⚠️ 周期**不跟**「轨迹采集间隔」绑定 —— 那是两件不同的事：
     *
     *   轨迹间隔 → 用户按出行方式选（步行 2 分、骑行 1 分…），
     *              控制的是定位回调频率 `requestUpdates()`，
     *              只影响轨迹点的疏密。
     *
     *   快照周期 → 控制的是「App 使用排行、电量曲线、前台时间线」的
     *              刷新快慢。跟轨迹疏密毫无关系。
     *
     * 之前两者共用同一个值，用户把轨迹设成「10 分」省电时，
     * App 使用排行也跟着 10 分钟才更新一次，看着像卡住了。
     *
     * 取 [SNAPSHOT_INTERVAL_MS]（60 秒）的理由：
     *   · 与系统「设置 → 应用 → 使用时长」的刷新粒度相当，
     *     排行看起来是跟手的
     *   · 单轮成本主要是两次全天 queryEvents（随一天推进变长），
     *     60 秒一次在白天几十毫秒、最多上百毫秒，负担可接受
     *   · 再快（10~30 秒）收益很小，代价是成倍的全天事件扫描
     *
     * 熄屏时降到 [SNAPSHOT_INTERVAL_SCREEN_OFF_MS]：
     *   熄屏期间 App 不切换、电量网络几乎不变，高频采集纯浪费。
     */
    private fun startTicker() {
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

                // 熄屏时降频。屏幕状态用很便宜的 API 现查，
                // 不要复用 captureSnapshot 里的值（那是上一轮的结果）。
                val screenOn = runCatching { DeviceInfo.isScreenOn(this@TrackService) }
                    .getOrDefault(true)
                delay(
                    if (screenOn) SNAPSHOT_INTERVAL_MS
                    else SNAPSHOT_INTERVAL_SCREEN_OFF_MS,
                )
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
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {
            // 忽略
        }
        registeredProviders.clear()
        // 回调已移除，标志必须一起复位，否则下次 startTracking 会因为
        // updatesRequested == true 而跳过注册 —— 定位请求还在、点却永远收不到。
        updatesRequested = false
        unregisterEventReceiver()
        tickerJob?.cancel()
        scope.cancel()
        // 定位回调所在的后台线程必须退出，否则每次服务重建都漏一个线程
        runCatching { locationHandler.quitSafely() }
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.beian.tracker.START"
        const val ACTION_STOP = "com.beian.tracker.STOP"

        /** 让服务按最新设置重新注册定位请求（间隔变更后调用）。 */
        const val ACTION_RELOAD = "com.beian.tracker.RELOAD"
        private const val TAG = "TrackService"
        private const val CHANNEL_ID = "beian_tracking"
        private const val NOTIF_ID = 1001

        /** 「最近已知位置」的最大可接受年龄：超过就当过期，不用它补点。 */
        private const val LAST_KNOWN_MAX_AGE_MS = 2 * 60 * 1000L

        /**
         * 设备状态快照的周期（亮屏时）。
         *
         * ⚠️ 刻意与「轨迹采集间隔」分开。用户设的截隔是给定位用的
         * （步行 2 分 / 骑行 1 分…），跟 App 使用排行的刷新快慢无关。
         * 两者绑在一起时，把轨迹调成 10 分会导致排行 10 分钟才更新，
         * 看起来像卡住。
         *
         * 60 秒：与系统「使用时长」的刷新粒度相当，且单轮成本可接受。
         */
        private const val SNAPSHOT_INTERVAL_MS = 60_000L

        /**
         * 熄屏时的快照周期。
         *
         * 熄屏期间 App 不切换、电量网络几乎不变，采那么勤没有意义。
         * 拉长到 5 分钟：既不会漏掉明显变化（熄屏期间一般也没变化），
         * 又能把一晚上的全天事件扫描次数从 ~480 次降到 ~96 次。
         */
        private const val SNAPSHOT_INTERVAL_SCREEN_OFF_MS = 5 * 60_000L

        /**
         * 定位更新的最小位移门槛（米）。
         *
         * 取 10m 的理由：
         *   · 常见 GPS 的静态抖动在 5~10m 量级，设为 0 会把抖动全收下来 ——
         *     这正是「系统一直在用定位」的观感来源之一。
         *   · 门槛太大（如 50m）会让慢速步行（约 1.4m/s）连续几分钟不落点，
         *     轨迹出现空档。
         * 10m 能滤掉绝大多数抖动，又不至于卡住步行。
         */
        private const val MIN_DISTANCE_M = 10f

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
         * 让正在运行的服务按最新设置重新注册定位请求。
         *
         * 服务只在启动时读一次间隔（`intervalSec.first()`），之后不监听设置变化。
         * 改了间隔不通知它，新值就得等服务重启才生效。
         * 没在跑时不必发（下次启动自然会读最新值）。
         */
        fun reload(context: Context) {
            if (!running) return
            val intent = Intent(context, TrackService::class.java).setAction(ACTION_RELOAD)
            runCatching { context.startService(intent) }
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