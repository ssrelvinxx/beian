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
        /**
         * 最近一次失败的原因（HTTP 状态码 / 异常类型）。
         *
         * ⚠️ 这个字段是被逼出来的：早先 [fetch] 把**所有异常都吞掉**只返回 false，
         * 结果「9/9 全失败」时完全看不出为什么 —— 是 403 被拦？DNS 不通？
         * 超时？只能靠猜。失败必须留下可读的线索。
         */
        val lastError: String? = null,
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
    /**
     * 正在进行的连接，供取消时主动断开。
     *
     * ⚠️ 为什么需要它：协程的 `cancel()` 只是把 isActive 置为 false，
     * **无法中断已经阻塞在 read() 上的 HttpURLConnection** ——
     * 最坏情况下要等满 readTimeout（15 秒）才会检查 isActive。
     * 用户点了「取消」或切走页面，感觉就是「没反应 / 卡住」。
     *
     * 所以这里记住当前连接，取消时直接 disconnect()，
     * 让阻塞中的 read 立刻抛异常退出。
     */
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    /** 中断当前正在进行的瓦片请求（供取消使用）。 */
    fun abortCurrent() {
        runCatching { activeConnection?.disconnect() }
        activeConnection = null
    }

    /**
     * 批量下载瓦片。
     *
     * ⚠️ **必须在 IO 调度器上跑**。
     *
     * 这里的 [fetch] 是**同步阻塞**的（HttpURLConnection 请求 + 写文件），
     * 而调用方传进来的 scope 常是 `viewModelScope` —— 它的默认调度器是
     * `Dispatchers.Main.immediate`。若不显式切换，一轮循环会在**主线程**
     * 里做几百次网络请求（每次超时上限 10s/15s）和文件写入，
     * UI 被彻底堵死 —— 表现就是「一点下载瓦片就卡死」。
     *
     * （[fetchOne] 一直是正确的，它自己包了 IO 切换；这里此前漏了。）
     */
    fun download(
        scope: CoroutineScope,
        cacheDir: File,
        tiles: List<Triple<Int, Int, Int>>,
        onProgress: (Progress) -> Unit,
    ): Job = scope.launch(Dispatchers.IO) {
        var done = 0
        var failed = 0
        var lastErr: String? = null
        onProgress(Progress(0, tiles.size, 0))

        try {
            for ((z, x, y) in tiles) {
                if (!isActive) break
                val dest = tileFile(cacheDir, z, x, y)
                if (dest.exists() && dest.length() > 0) {
                    // 已有缓存，跳过
                    done++
                    onProgress(Progress(done, tiles.size, failed, lastErr))
                    continue
                }
                val err = fetch(cacheDir, z, x, y)
                done++
                if (err != null) {
                    failed++
                    lastErr = err
                }
                onProgress(Progress(done, tiles.size, failed, lastErr))
                delay(THROTTLE_MS)
            }
        } finally {
            // 无论正常结束、取消还是异常，都别把连接留着
            abortCurrent()
        }
    }

    /** 直接下载单个瓦片（供 osmdroid 缓存未命中时补抓）。 */
    suspend fun fetchOne(cacheDir: File, z: Int, x: Int, y: Int): Boolean =
        withContext(Dispatchers.IO) { fetch(cacheDir, z, x, y) == null }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    private fun tileFile(cacheDir: File, z: Int, x: Int, y: Int): File {
        // ⚠️ 目录第一级必须等于瓦片源的 name()，即 AmapTileSource.NAME。
        // osmdroid 按 <tileCache>/<name()>/z/x/y.png 找瓦片，
        // 写错目录就变成「下载成功但地图还是空白」。
        val dir = File(cacheDir, "${AmapTileSource.NAME}/$z/$x").apply { mkdirs() }
        return File(dir, "$y.png")
    }

    /**
     * 抓一个瓦片。
     *
     * @return null 表示成功；否则返回**可读的失败原因**
     *   （如 `HTTP 403` / `SocketTimeoutException` / `UnknownHostException`）。
     *
     * ⚠️ 早先这里吞掉所有异常只返回 false，「9/9 全失败」时完全无法定位。
     * 失败的**原因**和失败本身一样重要，必须带出来。
     */
    private fun fetch(cacheDir: File, z: Int, x: Int, y: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            // 复用 AmapTileSource 的 URL 构造：预下载写盘的瓦片必须和
            // 地图组件请求的是同一张图，逻辑分两份写迟早会走偏。
            val url = URL(amapTileUrl(z, x, y))
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                // 超时收紧：单张瓦片拿不到就跳过，别把整个队列拖住。
                // 之前 10s/15s，一张卡住的瓦片就能让用户以为程序死了。
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            // 登记，供 abortCurrent() 在取消时主动断开
            activeConnection = conn

            val code = conn.responseCode
            if (code != 200) return "HTTP $code"

            val dest = tileFile(cacheDir, z, x, y)
            conn.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            if (dest.length() > 0) null else "空文件"
        } catch (e: Exception) {
            e::class.java.simpleName
        } finally {
            runCatching { conn?.disconnect() }
            if (activeConnection === conn) activeConnection = null
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
