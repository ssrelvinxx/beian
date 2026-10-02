package com.beian.tracker.util

import android.content.Context
import java.io.File
import org.osmdroid.config.Configuration

/**
 * 地图瓦片的磁盘缓存位置管理。
 *
 * 关键点：缓存必须放**持久目录**（filesDir），不能放 cacheDir ——
 * 系统清理缓存会把它一起删掉。影响的是「翻过的地图能不能离线再看」：
 * osmdroid 会把联网取到的瓦片顺手存到本地，之后同一片区域
 * 即使没网也能显示。放在 cacheDir 就等于随时会被清空。
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
            // 而不是默认的 cacheDir —— 后者会被系统清理，缓存会一起丢掉。
            userAgentValue = context.packageName
            osmdroidBasePath = baseDir(context)
            osmdroidTileCache = tileCacheDir(context)
        }
    }
}
