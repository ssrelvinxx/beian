package com.beian.tracker.ui

import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
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
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

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
) {
    val context = LocalContext.current

    // 指向持久目录（幂等，多次调用无副作用）
    remember {
        MapTileStore.configure(context)
        true
    }

    if (points.isEmpty()) {
        Box(
            modifier = modifier.padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.map_no_points),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val startLabel = stringResource(R.string.map_start)
    val endLabel = stringResource(R.string.map_end)

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
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

    DisposableEffect(Unit) {
        onResume(mapView)
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view ->
            applyOfflineMode(view, offlineMode)
            drawTrack(view, points, startLabel, endLabel)
        },
    )
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
) {
    view.overlays.clear()

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
