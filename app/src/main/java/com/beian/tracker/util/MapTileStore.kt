package com.beian.tracker.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import org.osmdroid.config.Configuration

/**
 * 地图瓦片的离线存储管理。
 *
 * 关键点：瓦片缓存必须放**持久目录**（filesDir），不能放 cacheDir ——
 * 系统清理缓存会把离线地图一起删掉。
 *
 * 目录结构：
 *   filesDir/osmdroid/tiles/<tilesource>/<z>/<x>/<y>.png
 */
object MapTileStore {

    /** 瓦片根目录（持久）。 */
    fun baseDir(context: Context): File =
        File(context.filesDir, "osmdroid").apply { mkdirs() }

    /** 瓦片缓存目录（持久）。 */
    fun tileCacheDir(context: Context): File =
        File(baseDir(context), "tiles").apply { mkdirs() }

    /** 把 osmdroid 全局配置指向持久目录。App 启动与地图显示前都要调用（幂等）。 */
    fun configure(context: Context) {
        Configuration.getInstance().apply {
            // 这三个属性在 osmdroid 6.1.x 的 Configuration 上确认存在。
            // 关键是把 basePath / tileCache 指向应用私有目录（filesDir），
            // 而不是默认的 cacheDir —— 后者会被系统清理，离线瓦片会一起丢掉。
            userAgentValue = context.packageName
            osmdroidBasePath = baseDir(context)
            osmdroidTileCache = tileCacheDir(context)
        }
    }

    /** 当前是否有网络。 */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** 已缓存瓦片文件的总数量。 */
    fun cachedTileCount(context: Context): Int {
        val root = tileCacheDir(context)
        if (!root.exists()) return 0
        var count = 0
        root.walkTopDown().forEach { if (it.isFile && it.name.endsWith(".png")) count++ }
        return count
    }

    /** 已缓存瓦片占用的空间（字节）。 */
    fun cachedSizeBytes(context: Context): Long {
        val root = tileCacheDir(context)
        if (!root.exists()) return 0L
        var size = 0L
        root.walkTopDown().forEach { if (it.isFile) size += it.length() }
        return size
    }

    /** 清空全部离线瓦片。 */
    fun clear(context: Context): Boolean {
        val root = tileCacheDir(context)
        val ok = if (root.exists()) root.deleteRecursively() else true
        tileCacheDir(context) // 重建空目录
        return ok
    }
}
