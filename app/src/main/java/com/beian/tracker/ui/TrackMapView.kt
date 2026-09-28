package com.beian.tracker.ui

import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.beian.tracker.R
import com.beian.tracker.data.TrackPoint
import com.beian.tracker.util.MapTileStore
import com.beian.tracker.util.AmapTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
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
     * 只在看本机数据时显示 —— 看对方的轨迹却把自己标上去会让人误解。
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
) {
    val context = LocalContext.current

    // 指向持久目录（幂等，多次调用无副作用）
    remember {
        MapTileStore.configure(context)
        true
    }

    val startLabel = stringResource(R.string.map_start)
    val endLabel = stringResource(R.string.map_end)

    val mapView = remember {
        MapView(context).apply {
            // 用高德源：OSM 官方域名国内连不上（瓦片根本下不来，地图空白），
            // 且即便下到也几乎没有中文地名。高德同为 Slippy Map 编号，
            // 换源不会偏移，能显示中文路名/地名/地铁线。详见 AmapTileSource 注释。
            setTileSource(AmapTileSource())
            setMultiTouchControls(true)
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
    // 只在看本机数据时挂上 —— 看对方轨迹却把自己标上去会误导。
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

    DisposableEffect(myLocation) {
        myLocation?.let {
            mapView.overlays.add(it)
            // onResume 之后 enableMyLocation 才会真正开始接收定位回调，
            // 少了这一步蓝点不会出现。
            it.onResume()
            it.enableMyLocation()
        }
        onDispose {
            myLocation?.let { ov ->
                ov.disableMyLocation()
                ov.onPause()
                mapView.overlays.remove(ov)
            }
        }
    }

    DisposableEffect(Unit) {
        onResume(mapView)
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { mapView },
            update = { view ->
                applyOfflineMode(view, offlineMode)
                drawTrack(view, points, startLabel, endLabel, myLocation)
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
    keepOverlay: org.osmdroid.views.overlay.Overlay? = null,
) {
    // 不能直接 clear() —— 会把「我的位置」浮层一起清掉。
    // 只摘掉上一次画的轨迹线和起终点标记。
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
        view.overlays.add(
            Marker(view).apply {
                position = geoPoints.first()
                title = startLabel
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            },
        )
        view.overlays.add(
            Marker(view).apply {
                position = geoPoints.last()
                title = endLabel
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            },
        )
    }

    // 自动缩放到整条轨迹（含少量边距）
    try {
        if (geoPoints.size >= 2) {
            val box = org.osmdroid.util.BoundingBox.fromGeoPoints(geoPoints)
            view.zoomToBoundingBox(box, false, 48)
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

    view.invalidate()
}
