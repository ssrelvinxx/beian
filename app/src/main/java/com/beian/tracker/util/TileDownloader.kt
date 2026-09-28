package com.beian.tracker.util

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 瓦片预下载：把一个经纬度范围、若干缩放级别的 OSM 瓦片抓进本地缓存。
 *
 * 采用标准的 Slippy Map 编号（z/x/y），与 osmdroid 的 [AmapTileSource] 一致 ——
 * 预下载的瓦片能直接被地图组件命中，不用重新联网。
 *
 * 缓存落盘位置：<tileCache>/Amap/<z>/<x>/<y>.png
 *
 * ⚠️ 瓦片来自高德公开栅格服务。它没有面向第三方的开放条款，
 * 这里限速（[THROTTLE_MS]）并限制单次瓦片数（[MAX_TILES]），
 * 仅服务个人自用的小范围轨迹回看。若要上架或商用，需改用官方 SDK 授权。
 */
object TileDownloader {

    /**
     * 高德会拦截默认 UA，用浏览器标识。
     */
    private const val USER_AGENT = "Mozilla/5.0 (Android) HuahuaOfflineMap/1.0"

    /** 单次最多下载的瓦片数。 */
    const val MAX_TILES = 600

    /** 每个瓦片之间的间隔（毫秒），避免给 OSM 造成压力。 */
    private const val THROTTLE_MS = 120L

    /** 进度回调。 */
    data class Progress(
        val done: Int,
        val total: Int,
        val failed: Int,
    ) {
        val percent: Int get() = if (total <= 0) 0 else done * 100 / total
        val finished: Boolean get() = done >= total
    }

    /** 计算某个经纬度范围在给定级别下的瓦片清单。 */
    fun tilesFor(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        zoom: Int,
    ): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        // 钳制级别：超出高德可用范围只会在本地堆一堆空瓦片
        val top = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        for (z in MIN_ZOOM..top) {
            val n = 1 shl z
            val x0 = lonToTileX(minLon, z).coerceIn(0, n - 1)
            val x1 = lonToTileX(maxLon, z).coerceIn(0, n - 1)
            val y0 = latToTileY(maxLat, z).coerceIn(0, n - 1)  // 纬度越大 y 越小
            val y1 = latToTileY(minLat, z).coerceIn(0, n - 1)
            for (x in minOf(x0, x1)..maxOf(x0, x1)) {
                for (y in minOf(y0, y1)..maxOf(y0, y1)) {
                    out.add(Triple(z, x, y))
                }
            }
        }
        return out
    }

    /** 预览：给范围与级别，估算瓦片数（用于提示用户）。 */
    fun estimateCount(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        zoom: Int,
    ): Int = tilesFor(minLat, maxLat, minLon, maxLon, zoom).size

    /**
     * 开始下载。返回进度 Flow（用 onProgress 回调更简单）。
     * @param onProgress 在主线程回调
     * @return Job，可取消
     */
    fun download(
        scope: CoroutineScope,
        cacheDir: File,
        tiles: List<Triple<Int, Int, Int>>,
        onProgress: (Progress) -> Unit,
    ): Job = scope.launch {
        var done = 0
        var failed = 0
        onProgress(Progress(0, tiles.size, 0))

        for ((z, x, y) in tiles) {
            if (!isActive) break
            val dest = tileFile(cacheDir, z, x, y)
            if (dest.exists() && dest.length() > 0) {
                // 已有缓存，跳过
                done++
                onProgress(Progress(done, tiles.size, failed))
                continue
            }
            val ok = fetch(cacheDir, z, x, y)
            done++
            if (!ok) failed++
            onProgress(Progress(done, tiles.size, failed))
            delay(THROTTLE_MS)
        }
    }

    /** 直接下载单个瓦片（供 osmdroid 缓存未命中时补抓）。 */
    suspend fun fetchOne(cacheDir: File, z: Int, x: Int, y: Int): Boolean =
        withContext(Dispatchers.IO) { fetch(cacheDir, z, x, y) }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    private fun tileFile(cacheDir: File, z: Int, x: Int, y: Int): File {
        // ⚠️ 目录第一级必须等于瓦片源的 name()，即 AmapTileSource.NAME。
        // osmdroid 按 <tileCache>/<name()>/z/x/y.png 找瓦片，
        // 写错目录就变成「下载成功但地图还是空白」。
        val dir = File(cacheDir, "${AmapTileSource.NAME}/$z/$x").apply { mkdirs() }
        return File(dir, "$y.png")
    }

    private fun fetch(cacheDir: File, z: Int, x: Int, y: Int): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            // 复用 AmapTileSource 的 URL 构造：预下载写盘的瓦片必须和
            // 地图组件请求的是同一张图，逻辑分两份写迟早会走偏。
            val url = URL(amapTileUrl(z, x, y))
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (conn.responseCode != 200) return false

            val dest = tileFile(cacheDir, z, x, y)
            conn.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.length() > 0
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    // ── 坐标换算 ─────────────────────────────────────────────────────────────

    private const val MIN_ZOOM = 10

    /**
     * 能下载的最高级别。
     *
     * 直接引用 [AmapTileSource] 的上限，避免两处各写一个 18 ——
     * 将来高德放开或被限制，只改那一处即可，不会出现
     * 「地图能显示但下载被截断」这种不一致。
     */
    private val MAX_ZOOM = AmapTileSource.MAX_ZOOM

    /** 经度 → 瓦片 X。 */
    fun lonToTileX(lon: Double, z: Int): Int {
        val n = 1 shl z
        return ((lon + 180.0) / 360.0 * n).toInt()
    }

    /** 纬度 → 瓦片 Y。 */
    fun latToTileY(lat: Double, z: Int): Int {
        val n = 1 shl z
        val latRad = lat * PI / 180.0
        return ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt()
    }

    /** 瓦片 X → 经度（左上角）。 */
    fun tileXToLon(x: Int, z: Int): Double = x.toDouble() / (1 shl z) * 360.0 - 180.0

    /** 瓦片 Y → 纬度（左上角）。 */
    fun tileYToLat(y: Int, z: Int): Double {
        val n = PI - 2.0 * PI * y / (1 shl z)
        return 180.0 / PI * atan(sinh(n))
    }
}
