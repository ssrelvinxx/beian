package com.beian.tracker.util

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台常驻保障。
 *
 *  Android 原生 + 国产 ROM 都会为了省电而限制后台应用：
 *  - 原生（Doze）：需要加入「电池优化白名单」
 *  - 小米/华为/OPPO/vivo：另有「自启动」「后台运行」开关，白名单也不一定够
 *
 *  这里提供检测 + 一键跳转，让用户手动放行。
 *
 *  ⚠️ Google Play 不允许非闹钟类应用直接弹「忽略电池优化」的系统对话框，
 *  本项目为自用侧载，不受此限制。
 */
object BackgroundGuard {

    /** 是否已加入电池优化白名单（即不会被 Doze 限制）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 弹出「忽略电池优化」系统对话框。
     * 失败（部分 ROM 不支持）时回退到电池优化列表页。
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (launch(context, direct)) return
        openBatteryOptimizationSettings(context)
    }

    /** 打开系统的「电池优化」列表页。 */
    fun openBatteryOptimizationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launch(context, intent)
    }

    /** 打开本应用的系统设置详情页。 */
    fun openAppSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launch(context, intent)
    }

    /**
     * 打开厂商的「自启动 / 后台运行」管理页。
     * 各 ROM 的组件名不同，逐个尝试，都失败则回退到应用详情页。
     */
    fun openAutoStartSettings(context: Context) {
        val candidates = when (manufacturer()) {
            "xiaomi", "redmi", "poco" -> listOf(
                // 小米：自启动管理
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            )

            "huawei", "honor" -> listOf(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity",
                ),
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
                ),
            )

            "oppo", "realme", "oneplus" -> listOf(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                ),
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity",
                ),
                ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity",
                ),
                ComponentName(
                    "com.oplus.safecenter",
                    "com.oplus.safecenter.permission.startup.StartupAppListActivity",
                ),
            )

            "vivo", "iqoo" -> listOf(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                ),
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                ),
            )

            "meizu" -> listOf(
                ComponentName(
                    "com.meizu.safe",
                    "com.meizu.safe.permission.SmartBGActivity",
                ),
            )

            "asus" -> listOf(
                ComponentName(
                    "com.asus.mobilemanager",
                    "com.asus.mobilemanager.autostart.AutoStartActivity",
                ),
            )

            else -> emptyList()
        }

        for (component in candidates) {
            val intent = Intent().apply {
                setComponent(component)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launch(context, intent)) return
        }

        // 都打不开：回退到应用详情页，让用户自己找
        openAppSettings(context)
    }

    /** 手机厂商（小写）。 */
    fun manufacturer(): String = (Build.MANUFACTURER ?: "").lowercase()

    /**
     * 是否是「需要额外引导」的国产 ROM。
     * 用来决定要不要在界面上强提示。
     */
    fun needsVendorGuidance(): Boolean = when (manufacturer()) {
        "xiaomi", "redmi", "poco",
        "huawei", "honor",
        "oppo", "realme", "oneplus",
        "vivo", "iqoo",
        "meizu", "asus",
        -> true

        else -> false
    }

    /** 厂商显示名，用于文案。 */
    fun vendorLabel(): String = when (manufacturer()) {
        "xiaomi", "redmi", "poco" -> "小米"
        "huawei" -> "华为"
        "honor" -> "荣耀"
        "oppo" -> "OPPO"
        "realme" -> "realme"
        "oneplus" -> "一加"
        "vivo" -> "vivo"
        "iqoo" -> "iQOO"
        "meizu" -> "魅族"
        "asus" -> "华硕"
        else -> Build.MANUFACTURER ?: ""
    }

    /** 尝试启动，成功返回 true。 */
    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}
