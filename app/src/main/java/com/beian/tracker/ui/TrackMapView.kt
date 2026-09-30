package com.beian.tracker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.Location
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.beian.tracker.R
import com.beian.tracker.data.TrackPoint
import com.beian.tracker.util.MapTileStore
import com.beian.tracker.util.AmapTileSource
import com.beian.tracker.util.TimeUtil
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.tan
import org.osmdroid.api.IGeoPoint
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.infowindow.InfoWindow
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/**
 * 轨迹地图。支持离线显示：
 *
 * 1. 瓦片缓存放在**持久目录**（filesDir），系统清理缓存不会删掉离线地图。
 * 2. 无网络时 osmdroid 会优先读本地瓦片；命中的区域可正常显示。
 * 3. 完全无瓦片时，仍会绘制**轨迹线 + 起终点**，并把背景设为浅灰，避免纯空白。
 *
 * @param offlineMode 强制离线（只用本地瓦片，不发网络请求）
 */
@Composable
fun TrackMapView(
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
    offlineMode: Boolean = false,
    /**
     * 是否显示「我的位置」蓝点。
     *
     * 本机轨迹时显示；**看对方轨迹时也显示** —— 那样才能一眼看出
     * 「我在哪、对方在哪、差多远」。之前只在看本机时显示，
     * 是因为怕用户误以为对方轨迹是自己走的；
     * 现在起终点已改成明确的「起 / 终」气泡，不会再看错，
     * 距离也由 [onDistanceToPeer] 直接算出来给用户，误读的余地更小了。
     */
    showMyLocation: Boolean = false,
    /**
     * 定位权限当前是否已授予。
     *
     * 必须由调用方传入并作为 remember 的 key：
     * 用户在弹窗里授权后，本组件会重组，但 showMyLocation 没变，
     * 只用它做 key 的话蓝点永远不会创建 —— 表现就是
     * 「明明给了定位权限，地图上还是没有我」。
     */
    locationGranted: Boolean = false,
    /**
     * 我的位置到 [points] 最后一个点的直线距离（米）。
     *
     * 仅在「看对方轨迹 + 我的定位可用」时回调；
     * 其它情况回调 null，调用方据此隐藏距离那行。
     *
     * 用直线距离而不是「到对方轨迹的最近距离」：后者要遍历整条轨迹，
     * 而这些点动辄上千个，在定位回调里做太重。
     * 对「方便我看距离」这个诉求，直线距离已经够用且更直观。
     */
    onDistanceToPeer: ((Double?) -> Unit)? = null,
) {
    val context = LocalContext.current

    // 指向持久目录（幂等，多次调用无副作用）
    remember {
        MapTileStore.configure(context)
        true
    }

    val startLabel = stringResource(R.string.map_start)
    val endLabel = stringResource(R.string.map_end)
    val pinStart = stringResource(R.string.map_pin_start)
    val pinEnd = stringResource(R.string.map_pin_end)

    val mapView = remember {
        MapView(context).apply {
            // 用高德源：OSM 官方域名国内连不上（瓦片根本下不来，地图空白），
            // 且即便下到也几乎没有中文地名。高德同为 Slippy Map 编号，
            // 换源不会偏移，能显示中文路名/地名/地铁线。详见 AmapTileSource 注释。
            setTileSource(AmapTileSource())
            // 双指缩放/拖动（保留）
            setMultiTouchControls(true)
            // ⚠️ 关掉 osmdroid 内置的 [−][+] 缩放按钮。
            //
            // 它默认是开的，会在地图中央下方浮出一对白色方块按钮
            // （截图里能看到），既挡地图内容，又和双指手势抢触摸事件 ——
            // 用户双指缩放后常有「拖不动了」的感觉。
            // 关掉它，缩放完全交给双指手势。
            setBuiltInZoomControls(false)
            // 无瓦片时的背景色，避免死黑/纯白
            setBackgroundColor(Color.parseColor("#FFEFE6EA"))
            controller.setZoom(15.0)
        }
    }

    // 离线开关变化时，控制网络瓦片下载
    DisposableEffect(offlineMode) {
        applyOfflineMode(mapView, offlineMode)
        onDispose { }
    }

    // 「我的位置」蓝点。
    //
    // 本机轨迹、以及看对方轨迹时都要挂 —— 后者是为了知道「我离对方多远」。
    // 权限没给就不创建，避免 osmdroid 内部抛 SecurityException。
    val myLocation = remember(showMyLocation, locationGranted) {
        if (!showMyLocation || !locationGranted) {
            null
        } else {
            runCatching {
                MyLocationNewOverlay(GpsMyLocationProvider(context), mapView)
            }.getOrNull()
        }
    }

    // ── 我的位置 → 对方最新点的距离 ────────────────────────────────────────
    //
    // 通过 onDistanceToPeer 交给界面显示。没挂定位浮层 / 点位为空 → null。
    //
    // 用 rememberUpdatedState 取最新的 points 与回调，
    // 避免把它们写进 effect 的 key —— 那样每来一个新点都会重启
    // 定位浮层，蓝点会闪。
    val latestPoints by rememberUpdatedState(points)
    val latestOnDistance by rememberUpdatedState(onDistanceToPeer)

    // ⚠️ 用轮询读 [MyLocationNewOverlay.myLocation]，而不是 runOnFirstFix：
    //    · runOnFirstFix 只在**第一次**定位成功时触发一次，
    //      之后走动几百米它也不会再响，距离就一直停在旧值。
    //    · osmdroid 各版本给定位变化挂监听的方法名不一致，
    //      硬写容易编译不过。
    // myLocation 只是读一个已缓存的 GeoPoint，3 秒一次可以忽略不计。
    LaunchedEffect(myLocation) {
        val overlay = myLocation
        if (overlay == null) {
            latestOnDistance?.invoke(null)
            return@LaunchedEffect
        }
        while (true) {
            latestOnDistance?.invoke(distanceToLastPoint(overlay.myLocation, latestPoints))
            delay(LOCATION_DISTANCE_INTERVAL_MS)
        }
    }

    // ⚠️ 顺序至关重要：地图的 onResume 必须**先于**定位浮层注册。
    //
    // 原因：定位不可用时（系统开关关着 / provider 缺失）enableMyLocation()
    // 会抛异常。若这个 effect 排在地图 resume 之前，异常会让后面
    // 的 effect 整个不执行 —— mapView.onResume() 被跳过，
    // **osmdroid 就不会下载任何瓦片**，地图只剩背景色网格。
    //
    // 这就是那个「不开定位进 App，地图一片空白；开了定位就正常」的真根因。
    // 把地图 resume 放前面，它就不可能被定位问题拖累。
    DisposableEffect(Unit) {
        onResume(mapView)
        onDispose {
            // ⚠️ 这里**只能** onPause，绝不能 onDetach()。
            //
            // 原因：页面用 movableContentOf 保活（见 MainActivity），
            // 切走时 Compose 只是「离开组合位置」，MapView 实例**被保留复用**，
            // 切回来还是同一个对象。
            //
            // 而 osmdroid 的 onDetach() 是**销毁级**调用：它会关掉瓦片缓存 DB、
            // 拆掉网络模块、清空线程池。调过之后这个实例就废了。
            //
            // 于是必然出现：切走 → onDetach() 拆掉地图
            //              切回 → 复用这个已拆掉的实例 → 底层资源已释放
            //              → 卡死，或原生崩溃（Kotlin 抓不到的 SIGSEGV，直接闪退）
            //
            // 这正是「轨迹 → 报备 → 回轨迹必闪退」的根因。
            //
            // 地图真正该销毁的时机是 Activity 结束、页面不再复用的时候，
            // 那种情况下进程随之结束，不需要我们手动 detach。
            mapView.onPause()
        }
    }

    DisposableEffect(myLocation) {
        myLocation?.let {
            mapView.overlays.add(it)
            // onResume 之后 enableMyLocation 才会真正开始接收定位回调，
            // 少了这一步蓝点不会出现。
            runCatching { it.onResume() }
            runCatching { it.enableMyLocation() }
        }
        onDispose {
            myLocation?.let { ov ->
                runCatching { ov.disableMyLocation() }
                runCatching { ov.onPause() }
                mapView.overlays.remove(ov)
            }
        }
    }

    // ⚠️ 必须裁切。
    //
    // MapView 是**原生 View**，不受 Compose 的父容器约束：用户放大/拖动地图时，
    // 它自身的绘制范围会溢出这个 Box，直接压在下面的统计卡片、停留时间轴上
    // （表现就是「地图放大后挡住其他 UI」）。
    //
    // clipToBounds() 让子 View 的绘制被限制在 Box 边界内。
    // 顺带加圆角，和卡片风格统一。
    Box(
        modifier = modifier
            .clipToBounds()
            .clip(RoundedCornerShape(12.dp)),
    ) {
        // ⚠️ 用 points 的「指纹」而不是 points 本身做 key。
        //
        // update 块在每次重组时都会执行，而重组非常频繁（地图自身 invalidate、
        // 状态栏变化、采集每轮落一个新点…）。原来的写法无条件调用
        // drawTrack()，而它内部是 `removeAll` + 重建 Polyline + 逐个 Marker，
        // 等于每次重组都把整条轨迹重画一遍 —— 点位一多就卡死。
        //
        // lastDrawKey 记住上次画的是什么，只有点位真的变了才重画。
        val drawKey = remember { mutableStateOf<String?>(null) }

        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { 
                // ⚠️ 不能给 MapView 设 setOnTouchListener。
                //
                // osmdroid 的 MapView 在构造函数里就执行了
                // `setOnTouchListener(this)`，它自己的 onTouch() 里做
                // 全部手势识别（单指拖动、双指捏合、双击放大、长按）。
                // 我用 setOnTouchListener 覆盖它 = 把地图的手势能力整个抽掉，
                // 结果会是「拖不动、也缩放不了」。
                //
                // 正确做法：不动 MapView，包一层父容器，
                // 由这个容器阻止 Compose 的 verticalScroll 抢手势。
                ChildInterceptBlocker(context, mapView)
            },
            update = { _ ->
                // 参数是外层容器（ChildInterceptBlocker），本块用不到 ——
                // 地图操作一律用捕获的 mapView（传容器会类型不匹配）。
                applyOfflineMode(mapView, offlineMode)
                // 指纹：点数 + 首尾点（足够区分「没变」和「新增/切换了日期」）
                val key = buildString {
                    append(points.size).append('|')
                    points.firstOrNull()?.let { append(it.timestamp) }
                    append('|')
                    points.lastOrNull()?.let { append(it.timestamp) }
                    append('|').append(startLabel).append(endLabel)
                    // pin 文案跟着语言走，切语言也要重画（否则图标上还是旧文字）
                    append('|').append(pinStart).append(pinEnd)
                }
                if (drawKey.value != key) {
                    drawKey.value = key
                    drawTrack(
                        view = mapView,
                        points = points,
                        startLabel = startLabel,
                        endLabel = endLabel,
                        pinStart = pinStart,
                        pinEnd = pinEnd,
                        keepOverlay = myLocation,
                    )
                }
            },
        )

        // 还没采到点时给一句提示，而不是留一片空白地图让人以为坏了
        if (points.isEmpty()) {
            Text(
                text = stringResource(R.string.map_no_points),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** 我的位置（可为 null）到轨迹最后一点的直线距离（米）。任一侧缺失返回 null。 */
private fun distanceToLastPoint(
    mine: IGeoPoint?,
    points: List<TrackPoint>,
): Double? {
    if (mine == null) return null
    val last = points.lastOrNull() ?: return null
    val out = FloatArray(1)
    Location.distanceBetween(
        mine.latitude, mine.longitude,
        last.latitude, last.longitude,
        out,
    )
    return out[0].toDouble()
}

/** 读「我的位置」算距离的间隔。定位本身也不是高频更新，3 秒足够。 */
private const val LOCATION_DISTANCE_INTERVAL_MS = 3_000L

/**
 * 切换在线/离线。
 *
 * osmdroid 的网络下载开关：`setUseDataConnection`。
 * false 时只读本地缓存，完全不发请求 —— 真正的离线模式。
 */
private fun applyOfflineMode(view: MapView, offline: Boolean) {
    try {
        view.setUseDataConnection(!offline)
    } catch (_: Exception) {
        // 某些版本签名不同，忽略
    }
}

private fun onResume(view: MapView) {
    try {
        view.onResume()
    } catch (_: Exception) {
        // 忽略
    }
}

/** 绘制轨迹线、起终点标记，并自动缩放到轨迹范围。 */
private fun drawTrack(
    view: MapView,
    points: List<TrackPoint>,
    startLabel: String,
    endLabel: String,
    pinStart: String,
    pinEnd: String,
    keepOverlay: org.osmdroid.views.overlay.Overlay? = null,
) {
    // ⚠️ 先关掉所有气泡，再动 overlay 列表。
    //
    // 气泡（InfoWindow）**不是 overlay 的一部分** —— 它是 osmdroid 挂在
    // MapView 上的一个真实 View，生命周期不跟随 overlays 列表。
    //
    // 所以会出现：点开「终点」的气泡 → 切到一个没有轨迹点的日期 →
    // 下面的 removeAll 把 Marker 摘掉了，**气泡却留在屏幕上**。
    // 表现就是截图里那个矛盾画面：地图上挂着「终点 23:29:14」，
    // 同一屏却写着「暂无轨迹点」、统计是 0 个 / 0 m。
    //
    // 关气泡必须用 InfoWindow 自己的静态方法（closeAllInfoWindowsOn）：
    // 遍历 overlays 挨个 closeInfoWindow() 是**无效**的 ——
    // 气泡已经和 Marker 失去关联，列表里也找不到它。
    runCatching { InfoWindow.closeAllInfoWindowsOn(view) }

    // 不能直接 clear() —— 会把「我的位置」浮层一起清掉。
    // 只摘掉上一次画的轨迹线和起终点标记。
    //
    // ⚠️ 这个方法只在点位**真的变化**时才会被调用（调用方用指纹拦掉了
    // 无意义的重组重绘），所以这里的全量重建是可接受的。
    view.overlays.removeAll { it !== keepOverlay }

    val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }

    if (geoPoints.size >= 2) {
        val line = Polyline().apply {
            setPoints(geoPoints)
            outlinePaint.color = Color.parseColor("#FFFF6B9D")
            outlinePaint.strokeWidth = 12f
            // 同一条线
            outlinePaint.isAntiAlias = true
        }
        view.overlays.add(line)
    }

    if (geoPoints.isNotEmpty()) {
        // 起终点标记带上**获取时间**。
        //
        // 光有「起点/终点」两个词，回看时看不出几点到、几点离开 ——
        // 而时间和坐标一样都在数据里（TrackPoint.timestamp），
        // 顺手显示出来，不需要查任何外部服务。
        //
        // 时间写进 snippet（副标题），点开气泡就是「起点 / 08:31」两行。
        // 不用 Marker 的文字标签扩展 API —— 那套成员名在不同 osmdroid
        // 版本间有差异，写错就是编译不过，不值得为它冒险。
        // 想在图上常显时间的话，走「底部信息条」那条路（见 TrackScreen），
        // 那边的排版完全在我们自己手里。
        val startPoint = points.firstOrNull()
        val endPoint = points.lastOrNull()

        view.overlays.add(
            makeMarker(
                view = view,
                label = startLabel,
                pinText = pinStart,
                pinColor = PIN_GREEN,
                geoPoint = geoPoints.first(),
                timestamp = startPoint?.timestamp,
            ),
        )
        view.overlays.add(
            makeMarker(
                view = view,
                label = endLabel,
                pinText = pinEnd,
                pinColor = PIN_RED,
                geoPoint = geoPoints.last(),
                timestamp = endPoint?.timestamp,
            ),
        )
    }

    // ── 自动缩放到整条轨迹 ────────────────────────────────────────────────
    //
    // ⚠️ 这里**刻意不用** view.zoomToBoundingBox()。
    //
    // 实测（看门狗抓到的堆栈）：
    //   TrackMapView.kt:315  view.zoomToBoundingBox(box, false, 48)
    //     → Projection.getCloserPixel(Projection.java:492)  ← 卡在这里
    //   触发自 AndroidView 的 update 块（onAttachedToWindow 时机）
    //
    // 原因：这个方法在 MapView **还没测量出宽高**时被调用
    // （切页回来、地图刚挂到窗口上，此刻 width/height 都还是 0）。
    // 它内部用 `getWidth() - 2 * border` 算可用宽度 = −96，
    // 再拿负数尺寸去反推缩放级别 → 内部循环出不来。
    // 结果主线程被堵约 20 秒，系统判定无响应 → ANR。
    //
    // 现在改成两件事：
    //   ① 宽高没准备好就 post 到布局之后再做（有次数上限，不会无限重试）
    //   ② 缩放级别自己按 Web 墨卡托算，并夹到合法区间 ——
    //      不经过 osmdroid 那段有问题的循环
    fitToTrack(view, geoPoints, keepOverlay, retries = 0)

    view.invalidate()
}

/**
 * 造一个「起点/终点 + 时间」标记。
 *
 * 图标是照着高德那种「圆圈 + 中间一个字」的气泡做的：
 * 白底圆环 + 彩色描边 + 中间一个「起」/「终」。
 * 用代码画而不是塞 PNG —— 一张图要适配各种 DPI，
 * 而且这里只需要圆和字，画出来比切图更清楚也更小。
 *
 * 时间放在 [Marker.snippet]（副标题）里 —— 这是 osmdroid 从早期版本
 * 就有的稳定公共字段，点开气泡会显示在主标题下方两行：
 *
 *     起点
 *     08:31
 *
 * [timestamp] 为 null 时不写 snippet（调用处已判空，正常不会发生）。
 */
private fun makeMarker(
    view: MapView,
    label: String,
    pinText: String,
    pinColor: Int,
    geoPoint: GeoPoint,
    timestamp: Long?,
): Marker = Marker(view).apply {
    position = geoPoint
    title = label
    timestamp?.let { snippet = TimeUtil.time(it) }
    // 图标画不出来（极端 OOM 等）就别设，让 osmdroid 用默认图 ——
    // 总比整条轨迹因为一个图标崩掉强。
    pinIcon(view.context, pinText, pinColor)?.let { icon = it }
    // 锚点设在圆心：圆形气泡的「所指位置」就是它的中心
    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
}

/** 气泡直径（dp）。和界面其它圆角元素量级接近，不喧宾夺主。 */
private const val PIN_SIZE_DP = 34f

/** 描边宽度（dp）。 */
private const val PIN_STROKE_DP = 3f

/** 中间那个字的字号（dp）。 */
private const val PIN_TEXT_DP = 15f

/**
 * 画一个「白底 + 彩边 + 中间字」的圆形气泡图标。
 *
 * 起点用绿色、终点用红色 —— 和主流地图一致，
 * 用户不用看图例就知道哪个是头哪个是尾。
 *
 * 返回 null 表示绘制失败，调用方应退回默认图标。
 */
private fun pinIcon(context: Context, text: String, color: Int): Drawable? = try {
    val density = context.resources.displayMetrics.density
    val size = (PIN_SIZE_DP * density).toInt().coerceAtLeast(1)
    val stroke = PIN_STROKE_DP * density

    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    // 半径要扣掉半个描边，否则描边会被画布边缘切掉一半
    val radius = size / 2f - stroke / 2f
    val cx = size / 2f
    val cy = size / 2f

    // ① 内部白底
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = Color.WHITE
    }
    canvas.drawCircle(cx, cy, radius, fill)

    // ② 彩色描边
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        this.color = color
    }
    canvas.drawCircle(cx, cy, radius, ring)

    // ③ 中间的字，用同色
    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = PIN_TEXT_DP * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    // 让文字在视觉上垂直居中：baseline 要下移 (ascent+descent)/2
    val fm = tp.fontMetrics
    val baseline = cy - (fm.ascent + fm.descent) / 2f
    canvas.drawText(text, cx, baseline, tp)

    BitmapDrawable(context.resources, bmp)
} catch (_: Throwable) {
    // 用 Throwable 而不是 Exception：Bitmap 分配失败可能抛 OOM，
    // 那属于 Error 分支，不抓的话会直接崩。
    null
}

/** 把地图缩放到刚好装下 [geoPoints]。 */
private fun fitToTrack(
    view: MapView,
    geoPoints: List<GeoPoint>,
    keepOverlay: org.osmdroid.views.overlay.Overlay?,
    retries: Int,
) {
    try {
        if (geoPoints.size >= 2) {
            // ⚠️ 宽高为 0 时算出来的缩放级别必然是错的（会被夹到最小值，
            //    地图一下子缩到全世界），所以必须等测量完成。
            //    这个时机正是崩溃堆栈里 onAttachedToWindow 的那一刻。
            if (view.width <= 0 || view.height <= 0) {
                if (retries < MAX_FIT_RETRIES) {
                    view.post { fitToTrack(view, geoPoints, keepOverlay, retries + 1) }
                }
                return
            }

            val box = BoundingBox.fromGeoPoints(geoPoints)
            val zoom = fitZoom(box, view.width, view.height)
            view.controller.setZoom(zoom)
            view.controller.setCenter(
                GeoPoint(box.centerLatitude, box.centerLongitude),
            )
        } else if (geoPoints.isNotEmpty()) {
            view.controller.setZoom(16.0)
            view.controller.setCenter(geoPoints.last())
        } else {
            // 还没有任何轨迹点：如果拿得到当前位置就居中过去，
            // 让人一眼看到「定位是通的」，而不是对着空白地图猜。
            val mine = (keepOverlay as? MyLocationNewOverlay)?.myLocation
            if (mine != null) {
                view.controller.setZoom(16.0)
                view.controller.setCenter(mine)
            }
        }
    } catch (_: Exception) {
        // 缩放失败时退回到居中最后一个点
        if (geoPoints.isNotEmpty()) {
            try {
                view.controller.setCenter(geoPoints.last())
            } catch (_: Exception) { /* 忽略 */ }
        }
    }
}

/**
 * 计算能把 [box] 完整装进 [widthPx]×[heightPx] 的最大缩放级别。
 *
 * 按 Web 墨卡托自己算：经度方向线性，纬度方向取墨卡托 y。
 * 不用 osmdroid 的 zoomToBoundingBox —— 它在尺寸非法时会卡死（见上面注释）。
 *
 * 结果会向下取整并夹到 [MIN_FIT_ZOOM]..[MAX_FIT_ZOOM]。
 * 向下取整是为了**保证装得下**：宁可稍微缩一点，也不要因为浮点误差
 * 让轨迹贴边溢出。
 */
private fun fitZoom(box: BoundingBox, widthPx: Int, heightPx: Int): Double {
    // 边距按比例取，避免在小尺寸视图上把可用区域算成负数
    val border = min(FIT_BORDER_PX, min(widthPx, heightPx) / 4)
    val usableW = (widthPx - 2 * border).coerceAtLeast(1)
    val usableH = (heightPx - 2 * border).coerceAtLeast(1)

    // 退化情况（所有点重合）→ 夹到极小跨度，最终会被 MAX_FIT_ZOOM 兜住
    val lonSpan = (box.lonEast - box.lonWest).coerceAtLeast(MIN_SPAN)
    val latSpan = (latToMercatorY(box.latSouth) - latToMercatorY(box.latNorth))
        .coerceAtLeast(MIN_SPAN)

    // 世界在 z 级时有 TILE_SIZE * 2^z 像素
    val zoomLon = log2(usableW * 360.0 / (lonSpan * TILE_SIZE))
    val zoomLat = log2(usableH / (latSpan * TILE_SIZE))

    return floor(min(zoomLon, zoomLat)).coerceIn(MIN_FIT_ZOOM, MAX_FIT_ZOOM)
}

/** 纬度 → 归一化墨卡托 y（0 = 北极，1 = 南极）。 */
private fun latToMercatorY(lat: Double): Double {
    val rad = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
    return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0
}

/** 墨卡托在极区发散，纬度先夹到可表示范围。 */
private const val MAX_LAT = 85.05112878

/** 单张瓦片边长（osmdroid 与高德源都是 256）。 */
private const val TILE_SIZE = 256.0

/** 起点气泡色（绿）。 */
private const val PIN_GREEN = 0xFF12B76A.toInt()

/** 终点气泡色（红）。 */
private const val PIN_RED = 0xFFE5484D.toInt()

/** 缩放边距（像素），与 osmdroid 原来传的 48 保持一致。 */
private const val FIT_BORDER_PX = 48

/** 最小跨度，防止退化成 0 导致算出无穷大。 */
private const val MIN_SPAN = 1e-7

private const val MIN_FIT_ZOOM = 2.0

/** 与 AmapTileSource.MAX_ZOOM 一致：超过 18 级也没有瓦片了。 */
private const val MAX_FIT_ZOOM = 18.0

/** 等布局完成的重试次数上限，避免视图一直没被测量时无限 post。 */
private const val MAX_FIT_RETRIES = 3

/**
 * 包一层父容器，用来阻止【外层】容器抢走地图的手势。
 *
 * 背景：地图嵌在 Compose 的 `verticalScroll` 里。用户在图上纵向拖动时，
 * 外层会把它当成「页面滚动」拦截掉 —— 表现是「地图拖不动」，
 * 而横向还能动（页面只滚纵向），特别容易被误判成地图坏了。
 *
 * 为什么不用 `mapView.setOnTouchListener`：
 * osmdroid 的 MapView 在构造函数里就 `setOnTouchListener(this)`，
 * 它自己就是靠这个回调做全部手势识别的。覆盖它等于废掉地图的所有手势。
 *
 * 所以反过来做：**不动 MapView，只在它外面加一层**。
 * 这层容器不拦截任何事件（onInterceptTouchEvent 永远 false），
 * 只做一件事：把 MapView 发出的「别拦截我」请求继续往上传，
 * 从而让 Compose 的滚动容器在手指按下的这段时间里不抢手势。
 */
private class ChildInterceptBlocker(
    context: android.content.Context,
    child: android.view.View,
) : android.widget.FrameLayout(context) {

    init {
        isClickable = false
        isFocusable = false
        addView(
            child,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    /** 绝不拦截：事件必须原样到达 MapView。 */
    override fun onInterceptTouchEvent(ev: android.view.MotionEvent?): Boolean = false

    /** 把子 View（MapView）的诉求继续往上传。 */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
        parent?.requestDisallowInterceptTouchEvent(disallowIntercept)
    }
}
