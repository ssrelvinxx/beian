package com.beian.tracker.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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

    /** 采集能正常跑起来的最小权限集合。 */
    fun required(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
}
