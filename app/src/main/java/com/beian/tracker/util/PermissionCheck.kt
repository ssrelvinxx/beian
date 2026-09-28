package com.beian.tracker.util

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 采集所需权限的统一检查。
 *
 * 「开始记录」之前必须逐项确认，缺哪一项都提示用户去授权 ——
 * 只检查定位权限是不够的：
 *
 *  - 前台服务在 Android 13+ 需要 POST_NOTIFICATIONS，否则服务起来就没有
 *    常驻通知，系统很快会回收它，表现就是「点了开始但没数据」。
 *  - Android 10+ 想要息屏后继续定位，必须有 ACCESS_BACKGROUND_LOCATION，
 *    否则息屏即断点。
 *
 * 这个类不直接申请权限（申请要 Activity），只负责「查」和「算该申请哪些」。
 */
object PermissionCheck {

    /**
     * 采集能正常跑起来的最小权限集合。
     *
     * ⚠️ 定位只要求 COARSE，不把 FINE 列为必需。
     *
     * Android 12+ 允许用户只授予「大致位置」。若把 FINE 列为必需，
     * 这类用户的 [missing] 永远非空、[allGranted] 永远为 false ——
     * 轨迹页会一直显示「请授权」并把地图整块盖住，
     * 而采集其实完全可以靠 COARSE 跑起来。
     *
     * FINE 属于「更好，但不是必需」：有就用，没有也不该判定为失败。
     */
    fun required(): List<String> = buildList {
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** 是否拿到了精确定位（非必需，仅用于提示质量）。 */
    fun hasFineLocation(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /** 是否有任意一种定位权限。 */
    fun hasAnyLocation(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * 息屏后仍要记录所需的后台定位权限。
     *
     * Android 11+ 不能和前台定位一起申请，必须单独二次申请，
     * 所以这里单独区分，界面上也单独提示。
     */
    fun background(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        } else {
            null
        }

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** 必需权限里还缺哪些。空 = 齐全。 */
    fun missing(context: Context): List<String> = required().filterNot { granted(context, it) }

    fun allGranted(context: Context): Boolean = missing(context).isEmpty()

    /** 是否有后台定位（息屏不断点）。 */
    fun hasBackgroundLocation(context: Context): Boolean =
        background()?.let { granted(context, it) } ?: true

    // ── 「使用情况访问」（PACKAGE_USAGE_STATS）────────────────────────────────
    //
    // ⚠️ 这项**不在** Manifest 里声明，也不能用 requestPermissions 申请。
    // 它是「特殊权限」，只能由用户在系统设置里手动打开：
    //   Settings.ACTION_USAGE_ACCESS_SETTINGS
    //
    // 没有它，UsageStatsManager 会静默返回空列表 —— 不报错、不抛异常，
    // 只是查不到任何 App 记录。表现就是「点了开始记录，只有屏幕和网络事件，
    // 没有一条 App 打开记录」，而且用户完全不知道原因。
    //
    // 所以「开始记录」之前必须把这项一起检查进去。

    /** 是否已授予「使用情况访问」。 */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return try {
            @Suppress("DEPRECATION")
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            // 部分定制系统上该方法可能抛异常，保守地当作「未授权」处理，
            // 让界面给出引导而不是静默丢失数据。
            false
        }
    }

    /**
     * 跳转到「使用情况访问」设置页。
     *
     * 部分 ROM 没有这个 Activity，直接 startActivity 会抛 ActivityNotFoundException，
     * 所以先探测再跳，不行就退回到应用详情页。
     */
    fun openUsageAccessSettings(context: Context) {
        val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching {
            context.startActivity(direct)
        }.isSuccess
        if (!ok) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
