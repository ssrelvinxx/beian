package com.beian.tracker.util

import android.os.Build
import com.beian.tracker.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 检查更新：读取本仓库的 GitHub Releases，与当前版本比对。
 *
 * 为什么用 GitHub Releases：
 * 本 App 为双人私用，无需自建服务器；Release 里直接挂 APK 供下载。
 * GitHub API 匿名访问有速率限制（60 次/小时/IP），所以默认缓存结果 6 小时。
 */
object UpdateChecker {

    /** 本仓库。 */
    const val OWNER = "ssrelvinxx"
    const val REPO = "beian"

    private const val API_LIST = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=10"

    /** 一个可下载的版本。 */
    data class ReleaseInfo(
        /** 版本号，如 1.2.0（去掉 tag 的 v 前缀）。 */
        val version: String,
        val tagName: String,
        val title: String,
        /** 更新说明（Markdown 原文）。 */
        val notes: String,
        /** APK 直链，可能为空（该 Release 没挂 APK）。 */
        val apkUrl: String,
        val apkSize: Long,
        val publishedAt: String,
        val prerelease: Boolean,
    )

    /** 检查结果。 */
    sealed interface Result {
        /** 有新版本。 */
        data class Update(val info: ReleaseInfo, val current: String) : Result

        /** 已是最新。 */
        data class UpToDate(val current: String, val latest: String) : Result

        /** 检查失败。 */
        data class Failed(val reason: String) : Result
    }

    /** 当前安装的版本名。 */
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /**
     * 检查更新。
     * @param includePrerelease 是否接受预发布版本
     */
    suspend fun check(includePrerelease: Boolean = false): Result = withContext(Dispatchers.IO) {
        try {
            val releases = fetchReleases()
            if (releases.isEmpty()) {
                return@withContext Result.Failed("仓库里还没有任何 Release")
            }

            val current = currentVersion
            // 预发布版只在明确允许时才纳入比较
            val candidates = releases.filter { includePrerelease || !it.prerelease }
            if (candidates.isEmpty()) {
                return@withContext Result.Failed("没有可用的正式版本")
            }
            // 版本号是 "a.b.c" 形式，逐段比较；不能用 maxByOrNull(parseVersion)，
            // 因为 List<Int> 不是 Comparable。
            val latest = candidates.reduce { a, b ->
                if (compareVersion(a.version, b.version) >= 0) a else b
            }

            if (compareVersion(latest.version, current) > 0) {
                Result.Update(latest, current)
            } else {
                Result.UpToDate(current, latest.version)
            }
        } catch (e: Exception) {
            Result.Failed(friendlyError(e))
        }
    }

    // ── 网络 ──────────────────────────────────────────────────────────────────

    private fun fetchReleases(): List<ReleaseInfo> {
        val conn = (URL(API_LIST).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Huahua/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})")
        }

        try {
            val code = conn.responseCode
            if (code == 403 || code == 429) {
                throw IllegalStateException("GitHub 接口访问太频繁，请稍后再试")
            }
            if (code == 404) {
                throw IllegalStateException("找不到仓库 $OWNER/$REPO，请检查配置")
            }
            if (code !in 200..299) {
                throw IllegalStateException("服务器返回 $code")
            }

            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            val out = ArrayList<ReleaseInfo>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(parseRelease(o))
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRelease(o: JSONObject): ReleaseInfo {
        val tag = o.optString("tag_name").removePrefix("v")
        val assets = o.optJSONArray("assets") ?: JSONArray()
        var apkUrl = ""
        var apkSize = 0L
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkUrl = a.optString("browser_download_url")
                apkSize = a.optLong("size")
                break
            }
        }
        return ReleaseInfo(
            version = tag,
            tagName = o.optString("tag_name"),
            title = o.optString("name").ifBlank { tag },
            notes = o.optString("body"),
            apkUrl = apkUrl,
            apkSize = apkSize,
            publishedAt = o.optString("published_at"),
            prerelease = o.optBoolean("prerelease", false),
        )
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "网络不可用，无法检查更新"
        is java.net.SocketTimeoutException -> "连接超时，请稍后再试"
        else -> e.message ?: "检查更新失败"
    }

    // ── 版本比较 ──────────────────────────────────────────────────────────────

    /** 把 "1.2.3" 解析成可比较的整数序列。 */
    private fun parseVersion(v: String): List<Int> =
        v.trim().removePrefix("v").split('.', '-', '+')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /** a > b 返回正数，相等返回 0，a < b 返回负数。 */
    fun compareVersion(a: String, b: String): Int {
        val pa = parseVersion(a)
        val pb = parseVersion(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
