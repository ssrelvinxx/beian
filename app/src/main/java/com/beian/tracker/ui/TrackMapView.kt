package com.beian.tracker.ui

import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.beian.tracker.data.TrackPoint
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * 基于 osmdroid 的地图，绘制轨迹线。无需 API Key，瓦片按需在线加载，
 * 数据本身始终保存在本机。
 */
@Composable
fun TrackMapView(
    points: List<TrackPoint>,
    startLabel: String,
    endLabel: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext

    val mapView = remember {
        Configuration.getInstance().userAgentValue = appContext.packageName
        MapView(appContext).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
        }
    }

    DisposableEffect(Unit) {
        onResume(mapView)
        onDispose { mapView.onPause() }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { mapView },
        update = { view -> drawTrack(view, points, startLabel, endLabel) },
    )
}

private fun drawTrack(
    map: MapView,
    points: List<TrackPoint>,
    startLabel: String,
    endLabel: String,
) {
    val overlays = map.overlays
    overlays.clear()

    if (points.isEmpty()) {
        map.invalidate()
        return
    }

    val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }

    if (geoPoints.size >= 2) {
        val line = Polyline().apply {
            setPoints(geoPoints)
            outlinePaint.color = Color.parseColor("#FF4A6FA5")
            outlinePaint.strokeWidth = 8f
        }
        overlays.add(line)
    }

    overlays.add(
        Marker(map).apply {
            position = geoPoints.first()
            title = startLabel
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        },
    )
    overlays.add(
        Marker(map).apply {
            position = geoPoints.last()
            title = endLabel
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        },
    )

    if (geoPoints.size >= 2) {
        val box = BoundingBox.fromGeoPoints(geoPoints)
        map.post { map.zoomToBoundingBox(box, true, 80) }
    } else {
        map.controller.setCenter(geoPoints.first())
        map.controller.setZoom(17.0)
    }

    map.invalidate()
}