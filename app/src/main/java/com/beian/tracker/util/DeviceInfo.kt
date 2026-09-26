package com.beian.tracker.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager

/** 电量信息。 */
data class BatteryInfo(
    val level: Int,
    val charging: Boolean,
)

/** 网络信息。 */
data class NetworkInfo(
    val type: String,
    val connected: Boolean,
    val name: String,
)

object DeviceInfo {
    const val NET_WIFI = "WIFI"
    const val NET_CELLULAR = "CELLULAR"
    const val NET_NONE = "NONE"

    fun battery(context: Context): BatteryInfo {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryInfo(level.coerceIn(0, 100), charging)
    }

    fun network(context: Context): NetworkInfo {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: return NetworkInfo(NET_NONE, false, "")
        val caps = cm.getNetworkCapabilities(active) ?: return NetworkInfo(NET_NONE, false, "")

        val connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val ssid = wifiName(context)
                NetworkInfo(NET_WIFI, connected, ssid)
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                NetworkInfo(NET_CELLULAR, connected, "")
            else -> NetworkInfo(NET_NONE, false, "")
        }
    }

    @Suppress("DEPRECATION")
    private fun wifiName(context: Context): String = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.connectionInfo?.ssid?.trim('"').orEmpty()
    } catch (_: Exception) {
        ""
    }

    /** 开机以来经过的时间（毫秒）。 */
    fun uptimeMs(): Long = android.os.SystemClock.elapsedRealtime()

    fun isScreenOn(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }
}