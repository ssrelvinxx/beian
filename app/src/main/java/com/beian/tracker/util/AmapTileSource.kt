package com.beian.tracker.util

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/**
 * 高德路网瓦片源（含中文地名注记）。
 *
 * ── 为什么不用 OSM 官方源 ──────────────────────────────────────────────
 *
 * 之前用的是 osmdroid 内置的 [org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK]，
 * 指向 `tile.openstreetmap.org`。两个问题：
 *
 *  1. **国内访问不到**。实测该域名在国内网络下连接超时，
 *     表现就是地图一片空白 —— 瓦片根本没下载下来。
 *  2. **即使下到了，中文地名也很少**。其注记以英文/当地语言混合，
 *     国内城市街道名大量缺失，看不出来「在哪个城市哪条街」。
 *
 * 高德栅格服务同为标准 Slippy Map 编号（z/x/y 与 OSM 完全一致，
 * 已用同一套换算验证：同编号精确命中天安门），换源不会产生偏移。
 * `lang=zh_cn` 返回全中文注记，含路名、地名、地铁线、POI 图标。
 *
 * ── 关于合规 ──────────────────────────────────────────────────────────
 *
 * 这是个人自用的轨迹回看功能（只看自己和家人设备的历史位置），
 * 不对外提供服务、不涉及商业使用。若将来要上架或商用，
 * 需改用高德官方 SDK 并申请对应授权 —— 直接抓栅格瓦片是不被许可的。
 *
 * ── 实现说明 ──────────────────────────────────────────────────────────
 *
 * 没有继承 [org.osmdroid.tileprovider.tilesource.XYTileSource]：它的
 * [getTileURLString] 固定拼成 `baseUrl/{z}/{x}/{y}.ext`（路径式），
 * 而高德是 query 参数式（`?x=..&y=..&z=..`），拼不出来，只能自己实现。
 *
 * URL 用的子域列表放在**顶层常量**而不是 companion object 里：
 * 超类构造在子类初始化之前执行，此时 companion 的 `val` 还是 null，
 * 写在那里会 NPE。`const val` 是编译期内联，才可以在构造参数里用。
 */
class AmapTileSource : OnlineTileSourceBase(
    // ⚠️ name() 决定 osmdroid 的磁盘缓存目录名（<tileCache>/<name>/z/x/y.png）。
    //    改了这里等于换源：旧缓存目录会被弃用（不冲突，只是白占空间）。
    NAME,
    0,          // minZoom
    MAX_ZOOM,   // maxZoom
    256,        // tileSizePx
    ".png",     // 扩展名
    // ⚠️ 不能用 *BASE_URLS 展开：OnlineTileSourceBase 这个参数是 String[]
    //    而不是 vararg，编译器会报「spread operator can only be applied
    //    in a vararg position」。直接传数组。
    BASE_URLS, // 子域轮换：单域名并发容易被限流
) {
    override fun getTileURLString(pMapTileIndex: Long): String = amapTileUrl(
        z = MapTileIndex.getZoom(pMapTileIndex),
        x = MapTileIndex.getX(pMapTileIndex),
        y = MapTileIndex.getY(pMapTileIndex),
    )

    companion object {
        /**
         * 缓存目录名 / 瓦片源标识。
         *
         * 改了这里等于换源，旧缓存目录会被弃用（不冲突，只是白占空间）。
         */
        const val NAME = "Amap"

        /**
         * 可用的最高缩放级别。
         *
         * ⚠️ 实测：z19 和 z20 返回的是 179 字节的空瓦片，高德栅格最高到 18。
         * 写大了用户放大到最大级别会看到一片空白。
         *
         * `const val` 是编译期内联，所以可以安全地用在超类构造参数里。
         */
        const val MAX_ZOOM = 18
    }
}

/**
 * 高德瓦片子域。
 *
 * 放在类**外部**（顶层）而不是 companion object 里 —— companion 的 `val`
 * 初始化晚于主构造函数，而这里要在构造超类时就用它展开，会拿到 null。
 * 顶层属性在类加载时初始化，没有这个顺序问题。
 *
 * `style=8` 是带注记的路网图，中文地名就来自这个样式。
 */
private val BASE_URLS = arrayOf(
    "https://webrd01.is.autonavi.com/appmaptile",
    "https://webrd02.is.autonavi.com/appmaptile",
    "https://webrd03.is.autonavi.com/appmaptile",
    "https://webrd04.is.autonavi.com/appmaptile",
)

/**
 * 构造某个瓦片的完整 URL。
 *
 * 只有 [AmapTileSource] 在用。
 */
internal fun amapTileUrl(z: Int, x: Int, y: Int): String {
    // 与 AmapTileSource.getTileURLString 用同一套散列，保证命中同一子域缓存
    val sub = BASE_URLS[((x + y) and 0x7FFFFFFF) % BASE_URLS.size]
    return "$sub?lang=zh_cn&size=1&scale=1&style=8&x=$x&y=$y&z=$z"
}
