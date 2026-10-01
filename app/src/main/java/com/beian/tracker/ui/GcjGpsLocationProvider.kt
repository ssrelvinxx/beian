package com.beian.tracker.ui

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.util.Log
import com.beian.tracker.util.CoordTransform
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer

/**
 * 会做坐标系转换、并且**立刻给出一个位置**的定位 provider。
 *
 * ── 职责一：坐标系转换 ──────────────────────────────────────────────────
 *
 * osmdroid 原生的 [GpsMyLocationProvider] 把系统给的定位**原样**交给浮层，
 * 而系统（LocationManager）给的是 **WGS-84**；本项目底图是高德瓦片，
 * 用的是 **GCJ-02**。两者在国内相差 300~600 米 ——
 * 蓝点会落在隔壁街区，这正是「地图定位不准」的直接原因。
 *
 * ── 职责二：进页面立刻有位置，不等 GPS 冷启动 ───────────────────────────
 *
 * osmdroid 的 [org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay.enableMyLocation]
 * 在注册回调后会立刻调一次 `provider.getLastKnownLocation()` 想先把蓝点画出来，
 * 但原生实现的这个方法**只是返回自己内部的 mLocation 字段**（初始为 null），
 * 并不会去问系统要缓存位置。于是必须等第一次真实定位回调 ——
 * GPS 冷启动在室内可能几十秒甚至几分钟，表现就是「给了权限、开了定位，
 * 进轨迹页还是空地图，啥也不显示」。
 *
 * 这里覆写 [getLastKnownLocation]，改为**直接问系统要**最后一次已知位置：
 * 系统缓存的位置通常就是几秒~几分钟前的，能立刻把蓝点落上去，
 * 用户一进页面就看到「定位是通的」。
 *
 * 缓存位置可能是旧的（甚至几小时前），所以会调 setter 时记录时间，
 * 过期的仍然返回 —— 但至少地图上有位置可看，且下一次真实回调会立刻纠正。
 */
class GcjGpsLocationProvider(context: Context) : GpsMyLocationProvider(context) {

    private val appContext: Context = context.applicationContext

    /**
     * 已向系统要过的缓存位置。
     *
     * 只在 [getLastKnownLocation] 第一次被调用时查询一次（osmdroid 在
     * enableMyLocation 里调它），之后每次真实回调都会经 [onLocationChanged]
     * 覆写，所以不需要反复查系统。
     */
    private var cachedLastKnown: Location? = null

    /** 是否已经尝试过读取系统缓存（避免每次查询都打一次 Binder）。 */
    private var lastKnownFetched = false

    private val locationManager: LocationManager? =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /** 拿到最新位置时通知 osmdroid（否则蓝点不会显示）。 */
    private var consumer: IMyLocationConsumer? = null

    override fun startLocationProvider(myLocationConsumer: IMyLocationConsumer): Boolean {
        consumer = myLocationConsumer
        val ok = super.startLocationProvider(myLocationConsumer)

        // ⚠️ 注册回调之后**主动推一次**缓存位置给浮层。
        //
        // 不能只依赖 super 里的 getLastKnownLocation() 那次调用：
        // 那个调用发生在 startLocationProvider 内部、且返回的还是我下面
        // 才填充的字段，时序上拿不到。所以这里显式再推一次。
        pushSeedLocation()
        return ok
    }

    override fun stopLocationProvider() {
        consumer = null
        super.stopLocationProvider()
    }

    /**
     * 主动重新取一次位置并推给浮层。
     *
     * 使用场景：用户把系统定位关了又打开，然后回到本 App。
     * 这期间系统缓存里已经有一个更新的位置，但**没有新的定位回调**
     * 发给已经注册的监听者 —— 关开关时系统把注册关系断掉了，
     * 重新打开并不会自动补回来（osmdroid 的 startLocationProvider
     * 只在**调用那一刻**遍历当时已启用的 provider，之后不再检查）。
     * 于是要等到下一个采集间隔才可能有新点，
     * 在此之前地图上还是旧位置，看起来就像「刷新不了」。
     *
     * 所以这里做两件事：
     *   ① 重新注册监听（补上被系统断掉的那段），
     *      让后续的定位回调能恢复；
     *   ② 立刻向系统要一次当前位置推给浮层，
     *      用户点一下就能看到最新位置，不必等采集间隔。
     *
     * @return true 表示取到了位置并已推送；false 表示系统暂时给不出位置
     *         （定位开关仍关着 / 无可用 provider / 缓存里还没有点），
     *         此时界面应提示用户，而不是让人以为按钮坏了。
     */
    fun refreshNow(): Boolean {
        val lm = locationManager ?: return false

        // ① 重新注册（consumer 还在说明浮层仍挂着）。
        //    定位开关刚打开时这一步是恢复回调的关键；
        //    重复注册会被系统忽略，无副作用。
        consumer?.let { c -> runCatching { super.startLocationProvider(c) } }

        // ② 立刻要一次当前位置
        val providers = runCatching { lm.getProviders(true) }.getOrNull().orEmpty()
        if (providers.isEmpty()) {
            Log.d(TAG, "refresh: no enabled provider (system location off?)")
            return false
        }

        val fresh = providers.mapNotNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()
        }.maxByOrNull { it.time } ?: run {
            Log.d(TAG, "refresh: system has no last known location yet")
            return false
        }

        lastKnownFetched = true
        cachedLastKnown = fresh
        val converted = fresh.toGcj02OrNull() ?: return false
        runCatching { consumer?.onLocationChanged(converted, this) }
        return true
    }

    /**
     * 覆盖原生的「只返回内部字段」，改为向系统查询最后一次已知位置。
     *
     * 系统里的缓存点可能是 NETWORK 给的（精度差但秒出），
     * 也可能是 GPS 给的（准但可能几小时前）。这里取**最新**的那个 ——
     * 「新鲜」比「精确」更重要：一个 3 分钟前的基站点能让用户看到
     * 「我在这个片区」，而一个 2 小时前的 GPS 点会把人放到完全错误的地方。
     */
    override fun getLastKnownLocation(): Location? {
        super.getLastKnownLocation()?.let { return it }
        if (lastKnownFetched) return cachedLastKnown
        lastKnownFetched = true
        cachedLastKnown = querySystemLastKnown()
        return cachedLastKnown
    }

    /**
     * 向系统要一次「最后一次已知位置」。
     *
     * 遍历所有已启用的 provider（GPS / NETWORK / PASSIVE …）取时间最新的那个。
     * 单个 provider 查询失败（SecurityException 等）不影响其它 provider。
     */
    private fun querySystemLastKnown(): Location? {
        val lm = locationManager ?: return null
        val providers = runCatching { lm.getProviders(true) }.getOrNull().orEmpty()
        return providers.mapNotNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()
        }.maxByOrNull { it.time }
    }

    /** 把缓存位置推给浮层，让蓝点立刻出现。 */
    private fun pushSeedLocation() {
        val lm = locationManager ?: return
        val seed = querySystemLastKnown()
        if (seed == null) {
            Log.d(TAG, "no last known location from system")
            return
        }
        // ⚠️ 转换坐标系后再交给浮层（和 onLocationChanged 同一个口径）
        val converted = seed.toGcj02OrNull() ?: return
        runCatching { consumer?.onLocationChanged(converted, this) }
    }

    // ── 坐标系转换 ────────────────────────────────────────────────────────────

    override fun onLocationChanged(location: Location) {
        lastKnownFetched = true
        cachedLastKnown = location

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

    private companion object {
        const val TAG = "GcjGpsProvider"
    }
}
