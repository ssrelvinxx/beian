package com.beian.tracker.ui

import android.content.Context
import android.location.Location
import com.beian.tracker.util.CoordTransform
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider

/**
 * 会做坐标系转换的定位 provider。
 *
 * ── 为什么需要它 ────────────────────────────────────────────────────────
 *
 * osmdroid 原生的 [GpsMyLocationProvider] 把系统给的定位**原样**交给浮层，
 * 而系统（LocationManager）给的是 **WGS-84**；本项目底图是高德瓦片，
 * 用的是 **GCJ-02**。两者在国内相差 300~600 米 ——
 * 蓝点会落在隔壁街区，这正是「地图定位不准」的直接原因。
 *
 * 这里继承原生实现，只在「定位回调进来」这一个点上把坐标换过去，
 * 其余（provider 选择、生命周期、回调分发）全部沿用 osmdroid 的实现 ——
 * 不重复造轮子，也就不会漏掉它内部那些细节。
 *
 * ── 为什么覆写 onLocationChanged 就够了 ──────────────────────────────────
 *
 * 原生实现的 onLocationChanged 做两件事：
 *   ① 存到内部的 mLocation 字段（getLastKnownLocation() 返回它）
 *   ② 通过 mMyLocationConsumer 分发给浮层
 *
 * 我先把 Location 的经纬度改成 GCJ-02 再调 super，等于①②拿到的
 * 都已经是转换后的值 —— 蓝点位置和「当前位置」都一致，不会一个新一个旧。
 *
 * ⚠️ 转换是**就地改对象**（Location 是可变对象）。
 *    系统内部可能缓存了同一个 Location 实例，就地改有污染风险，
 *    所以这里先 copy 一份再改。
 */
class GcjGpsLocationProvider(context: Context) : GpsMyLocationProvider(context) {

    override fun onLocationChanged(location: Location) {
        val converted = location.toGcj02OrNull() ?: run {
            // 转换失败（坐标为 NaN 等异常值）：原样交给父类，
            // 至少不会因为一个坏点让整个定位链路停掉。
            super.onLocationChanged(location)
            return
        }
        super.onLocationChanged(converted)
    }

    private fun Location.toGcj02OrNull(): Location? {
        val (lat, lon) = CoordTransform.wgs84ToGcj02(latitude, longitude)
        // 转换前后完全一致 = 境外坐标（算法不做偏移），
        // 那就没必要 copy 一个新对象出来，直接用原来的。
        if (lat == latitude && lon == longitude) return this
        if (!lat.isFinite() || !lon.isFinite()) return null

        return Location(this).apply {
            this.latitude = lat
            this.longitude = lon
        }
    }
}
