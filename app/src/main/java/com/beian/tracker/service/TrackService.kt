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

    private fun startTracking() {
        startForegroundCompat()
        registerEventReceiver()
        if (!hasLocationPermission()) return

        scope.launch {
            // 断连补偿：把上次被杀掉期间漏掉的屏幕开关事件用 UsageStats 补回来。
            // 放在最前面，让「报备」页立刻能看到完整的当天记录。
            runCatching { repository.backfillScreenEvents() }

            val interval = settings.intervalSec.first()
            requestUpdates(interval)
            startTicker(interval)
        }
    }

    private fun requestUpdates(intervalSec: Int) {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            intervalSec * 1000L,
        ).setMinUpdateDistanceMeters(10f)
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
                repository.captureSnapshot()
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

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
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
        unregisterEventReceiver()
        tickerJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.beian.tracker.START"
        const val ACTION_STOP = "com.beian.tracker.STOP"
        private const val CHANNEL_ID = "beian_tracking"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, TrackService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TrackService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}