package com.beian.tracker.util

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84 ↔ GCJ-02 坐标转换。
 *
 * ── 为什么必须有这个文件 ────────────────────────────────────────────────
 *
 * 本应用的底图用的是**高德瓦片**（[AmapTileSource]），而高德使用的是
 * **GCJ-02**（俗称「火星坐标系」）；系统 GPS / LocationManager 给出的是
 * **WGS-84** 原始经纬度。
 *
 * 这两套坐标在国内**不是同一个点**：直接拿 WGS-84 画到 GCJ-02 底图上，
 * 会整体偏移 **300~600 米**（随经纬度变化，不是固定值）。
 * 表现就是「定位定不准」：轨迹线飘到隔壁街区、蓝点落在马路对面。
 *
 * 这个偏移是**国家强制**的加密偏移，不是精度问题，
 * 换 GPS 芯片、等更久、开 AGPS 都不会改善 —— 只能做坐标转换。
 *
 * ── 转换方向 ─────────────────────────────────────────────────────────────
 *
 * 只实现 WGS-84 → GCJ-02（正向），因为本应用只需要「把 GPS 点画到高德底图上」。
 * 反向转换（GCJ-02 → WGS-84）需要迭代逼近，本项目没有用到，不实现。
 *
 * ── 数据存储 ─────────────────────────────────────────────────────────────
 *
 * ⚠️ **转换只在显示层做，数据库里存的永远是 WGS-84 原始值。**
 *
 * 这样做的理由：
 *  · 原始数据保真。将来换底图（比如换成 OSM 的 WGS-84 瓦片）不需要重新采数。
 *  · 导出数据给别人、或导入别人的包，都是同一套标准坐标，不会双重偏移。
 *  · 转换不可逆（有精度损失），存转换后的值等于把原始信息丢掉。
 */
object CoordTransform {

    /** 圆周率。用字面量而不是 kotlin.math.PI，避免浮点细节差异。 */
    private const val PI = 3.1415926535897932384626

    /** 克拉索夫斯基椭球长半轴（米）。GCJ-02 偏移算法规定的参数。 */
    private const val A = 6378245.0

    /** 椭球偏心率平方。 */
    private const val EE = 0.00669342162296594323

    /**
     * 是否在中国大陆范围外。
     *
     * ⚠️ 境外**不做偏移** —— 这是 GCJ-02 算法的规定：
     * 偏移只在国内生效，国外坐标 WGS-84 和 GCJ-02 是同一个点。
     * 不加这个判断的话，用户在境外（或港澳台部分区域、公海）的记录
     * 会被平白无故挪走几百米。
     *
     * 边界取的是业内通行的粗略矩形（含港澳台）。
     */
    private fun outOfChina(lat: Double, lon: Double): Boolean =
        lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y +
            0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y +
            0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    /**
     * WGS-84 → GCJ-02。
     *
     * @return 转换后的 (纬度, 经度)。境外或参数非法时原样返回。
     */
    fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        // 非法值直接返回，避免 NaN 传进三角函数后污染成一片 NaN 坐标
        if (!lat.isFinite() || !lon.isFinite()) return lat to lon
        if (outOfChina(lat, lon)) return lat to lon

        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)

        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)

        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)

        return (lat + dLat) to (lon + dLon)
    }
}
