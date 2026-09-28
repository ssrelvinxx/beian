package com.beian.tracker.util

import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Release 资产的下载地址解析。
 *
 * 为什么需要镜像：APK 挂在 GitHub Release 上，而 release 资产实际存放在
 * `objects.githubusercontent.com`，这个域名在国内经常连不上或极慢。
 * 结果是「检查更新成功、点下载一直转圈」——用户看到有新版本却装不上。
 *
 * 做法：把原始直链前拼上加速前缀，逐个探测，取第一个能用的。
 * 探测是**轻量**的（只请求前 1KB），不会把整个 APK 拉两遍。
 *
 * 顺序有意为之：镜像在前、官方兜底在后。镜像都是第三方服务，
 * 有失效的可能，所以官方地址永远保留为最后一项 —— 探测全部失败时
 * 仍然把它交给 DownloadManager，让系统网络环境决定成败。
 */
object DownloadMirrors {

    /**
     * 加速前缀。拼接方式就是简单的前缀 + 原始 URL。
     *
     * 只保留实测稳定、且明确支持 GitHub release 的几家。
     * 失效的直接从列表里删掉，不要留着一堆探测不过的地址
     * ——每次下载都要一个个试，等的是用户。
     */
    private val PROXIES = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://ghfast.top/",
    )

    /** 探测超时：镜像不通就得赶紧换下一个，别让用户干等。 */
    private const val PROBE_TIMEOUT_MS = 6_000

    /**
     * 给 [originalUrl] 找出一个可用地址。
     *
     * 探测本身有网络开销，所以只探测**镜像**；都不通就直接返回官方地址，
     * 不再对官方地址做额外探测（DownloadManager 会自己处理）。
     */
    fun resolve(originalUrl: String): String {
        if (originalUrl.isBlank()) return originalUrl
        for (proxy in PROXIES) {
            val candidate = proxy + originalUrl
            if (probe(candidate)) return candidate
        }
        return originalUrl
    }

    /** 所有候选地址，按优先级排列。用于需要自己重试的场景。 */
    fun candidates(originalUrl: String): List<String> =
        PROXIES.map { it + originalUrl } + originalUrl

    /**
     * 只请求前 1KB 判断这个地址通不通。
     *
     * 用 Range 头而不是 HEAD：部分镜像不实现 HEAD，但都支持 GET+Range。
     * 拿到 200/206 即视为可用。
     */
    private fun probe(url: String): Boolean {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                // 关键：只要 1 字节，避免把 12MB 的 APK 整个下下来
                setRequestProperty("Range", "bytes=0-1023")
                setRequestProperty("User-Agent", "Huahua-Updater")
                instanceFollowRedirects = true
            }
            try {
                val code = conn.responseCode
                code in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }
}
