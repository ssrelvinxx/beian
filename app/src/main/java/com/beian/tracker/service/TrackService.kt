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

    /** 静止心跳。见 [startStayHeartbeat]。 */
    private var heartbeatJob: Job? = null

    /**
     * 最近一次**真实定位回调**的时间戳。
     *
     * ⚠️ 心跳必须靠它判断「系统还在给不给点」，不能只看心跳自己发了几次。
     *
     * 心跳要解决的问题是：静止时定位回调不来（`minDistance=10m`），
     * 于是整天零点位、「停留」永远算不出来。但如果系统其实一直在正常
     * 回调（人在移动），心跳再补点就是**往轨迹里塞重复点** ——
     * 里程会略微偏高，地图上也会出现叠在一起的点。
     *
     * 所以心跳只在「距上次真实回调超过心跳周期」时才补。
     * 用 @Volatile：写在定位回调线程（locationHandler），
     * 读在 scope 的 IO 线程，不能靠 happens-before 侥幸。
     */
    @Volatile
    private var lastFixAt = 0L

    /**
     * 最近一次已知位置（真实回调或种子都算），心跳补点直接复用它。
     *
     * 心跳**不主动去请求一次定位**（不用 getCurrentLocation）：
     * 那会额外唤醒 GPS，静止时白耗电，而且国行 ROM 上这个调用
     * 经常直接回调一个缓存值 —— 花了电却拿不到新东西。
     * 静止时位置本来就没变，复用最近一次足够。
     */
    @Volatile
    private var lastKnown: Location? = null

    /**
     * [lastKnown] 这个坐标的「采集时刻」。
     *
     * ⚠️ 不能用 `lastKnown.time` 代替。
     *
     * 心跳补点时会构造一个「坐标是锚点、时间是现在」的新 Location，
     * 若把那个对象存回 lastKnown，`lastKnown.time` 就变成了心跳时刻 ——
     * 于是「锚点有多旧」永远算不出来，上面那道
     * [HEARTBEAT_COORD_MAX_AGE_MS] 判断就彻底失效，
     * 又会退化成「用几小时前的坐标一直补点」。
     *
     * 所以锚点的新鲜度必须单独记：它只反映「系统最后一次真正给出位置」
     * 是什么时候，不受心跳自己造的点影响。
     */
    @Volatile
    private var anchorAt = 0L

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
            // 真实回调：记录时间与位置，供静止心跳判断「系统还在给点吗」。
            lastFixAt = System.currentTimeMillis()
            lastKnown = location
            anchorAt = location.time
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
            // 静止心跳：没有它，静止时段一个点都没有，
            // 「停留」就永远算不出来。见 startStayHeartbeat。
            startStayHeartbeat()
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
     * 静止心跳：系统不给点时，按周期补一个「原地不动」的点。
     *
     * ── 为什么必须有它 ─────────────────────────────────────────────────
     *
     * 定位请求用了 `minDistance = 10m`（滤 GPS 抖动，见 [MIN_DISTANCE_M]）。
     * 代价是：**站着不动 → 位移不足 10m → 系统一次回调都不给**。
     *
     * 而「停留」的定义恰恰是「待着不动」——于是出现一个死结：
     *   越该判定为停留的时段，越是一个点都没有。
     *   轨迹页整天显示「暂无停留记录」，统计里停留点永远是 0。
     *
     * 心跳在每个周期检查一次：距上次**真实**回调是否已经超过一个周期。
     * 超过 → 说明人没在动（或系统不给点了），补一个当前位置的点。
     * 没超过 → 人在动，回调正常，不补（补了是重复点，会虚增里程）。
     *
     * ── 周期取值的取舍 ────────────────────────────────────────────────
     *
     * 用 [STAY_HEARTBEAT_MS] = 2 分钟，而不是直接用用户的采集间隔：
     *   · 停留判定要求「同一位置连续跨度 ≥ STAY_MIN_MS(10 分钟)」，
     *     2 分钟一个点能稳定覆盖住，且不会把一次停留切成碎段。
     *   · 静止时 GPS 不工作、只是复用上一次坐标，耗电可忽略。
     *   · 一天静止 12 小时 ≈ 360 个点，相对移动时本就有的点数不算多。
     */
    private fun startStayHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(STAY_HEARTBEAT_MS)
                runCatching { heartbeatOnce() }
                    .onFailure { Log.w(TAG, "stay heartbeat failed, skip this round", it) }
            }
        }
    }

    /** 心跳单轮：只在「真实回调已经停了」才补点。 */
    private suspend fun heartbeatOnce() {
        val now = System.currentTimeMillis()

        // 人在动：真实回调还在正常来，不要补重复点。
        //
        // 留一点余量（周期的四分之一）：真实回调本身有秒级抖动，
        // 卡得太死会在「刚好一个周期」时误判成静止，多补一个点。
        if (lastFixAt != 0L && now - lastFixAt < STAY_HEARTBEAT_MS - STAY_HEARTBEAT_MS / 4) return

        // 用系统缓存刷新锚点（有更新的就用）。
        //
        // 我们的定位请求一直是活跃的，系统缓存的最后位置由 provider
        // 持续维护，所以这里读到的是「当前位置」，且不会额外唤醒 GPS。
        // 静止时缓存通常不更新，那就继续用原锚点 —— 人没动，坐标仍然对。
        val cached = freshestCachedLocation()
        if (cached != null && cached.time > anchorAt) {
            lastKnown = cached
            anchorAt = cached.time
        }
        val anchor = lastKnown ?: return

        // ⚠️ 限制「连续靠同一个锚点补点」的最长时间。
        //
        // 我们无法区分这两种情况：
        //   a) 人还坐在原地（锚点仍然有效）
        //   b) 人走了，但定位链路整个失效了（GPS 一直定不到位、
        //      权限被撤、定位被系统冻结）
        // 两者都是「长时间没有任何新位置」。
        //
        // 上限之内的补点当作 (a)：合理，静止本就是这样。
        // 超过上限就按 (b) 处理、停止补点 —— 否则会以几小时前的坐标
        // 一直补下去，写出「在这个位置停留了一整晚」这种假记录。
        //
        // 少记一次停留是「缺」，写出一段不存在的是「假」，优先避免后者。
        if (anchorAt != 0L && now - anchorAt > HEARTBEAT_COORD_MAX_AGE_MS) return

        // 坐标取锚点，时间取「现在」。
        //
        // ⚠️ 时间必须换成 now。若沿用锚点里的旧时间戳，连续几个心跳点
        //    的时间几乎不动，停留段的跨度就永远凑不满 STAY_MIN_MS ——
        //    这正是原来「停留记录一直为空」的成因之一。
        //
        // 坐标**不**取新值：静止时位置本来就没变，没必要唤醒 GPS。
        val point = Location(anchor).apply { time = now }
        repository.recordPoint(point)
    }

    /** 取系统缓存里最新的一个位置（多个 provider 中挑最新的），可能为 null。 */
    private fun freshestCachedLocation(): Location? =
        listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        ).mapNotNull { provider ->
            try {
                locationManager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            }
        }.maxByOrNull { it.time }

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

        // ⚠️ 心跳的坐标来源在这里赋值，必须**先于**新鲜度检查。
        //
        // 冷启动（手机重启后、服务被杀后重拉）时系统缓存位置往往已经
        // 超过 LAST_KNOWN_MAX_AGE_MS —— 而静止心跳正是为了「人不动」的
        // 场景，那个坐标恰恰是几小时前的同一个地点，完全可用。
        // 若先做检查再赋值，这类启动下 lastKnown 永远是 null，
        // 心跳一次都补不出点，修复等于没做。
        //
        // 落库与否仍然严格按新鲜度判断（见下），只是心跳可用旧坐标。
        lastKnown = freshest
        anchorAt = freshest.time

        if (now - freshest.time > LAST_KNOWN_MAX_AGE_MS) return

        // ⚠️ 这里**不**更新 lastFixAt：种子来自系统缓存，不是新鲜回调。
        //    更新了会让心跳误以为「刚收到点」，把该补的时段跳过去。

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
                // TrackService 本身是 Context（Service 的子类），直接传。
                val screenOn = runCatching { DeviceInfo.isScreenOn(applicationContext) }
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
        // 心跳也要停，否则服务重建后会叠加出两条心跳，
        // 静止时段被双倍补点（里程与停留时长同时虚高）。
        heartbeatJob?.cancel()
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
         * 静止心跳的周期（毫秒）。
         *
         * 见 [startStayHeartbeat] 的完整说明。核心取舍：
         *   · 要显著小于停留段的最短时长（10 分钟），否则一次停留
         *     中间只有一个点、跨度凑不满，还是判不出来；
         *   · 又不能太密 —— 静止时每个点都要写一次库，
         *     1 分钟一签会让不必写的点数翻倍。
         * 2 分钟是「够密以支撑判定、又不至于冗余」的位置。
         */
        private const val STAY_HEARTBEAT_MS = 2 * 60_000L

        /**
         * 心跳连续复用同一个锚点坐标的上限（毫秒）。
         *
         * 超过这个时长仍没有任何新位置，就停止补点 —— 因为无法区分
         * 「人还坐在原地」和「定位链路已失效」，继续补只会写出
         * 「在几小时前那个位置停留到现在」的假记录。
         *
         * 取 1 小时：正常的一段停留（在家、上班、吃饭）都在这个量级，
         * 超出的更可能是采集本身出了问题。宁可断开这一段（少记），
         * 也不写出一段不存在的停留（记错）。
         */
        private const val HEARTBEAT_COORD_MAX_AGE_MS = 60 * 60_000L

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