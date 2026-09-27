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
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * OpenStreetMap 地图，绘制轨迹线。
 * 无需 API Key，首次加载瓦片需联网。
 */
@Composable
fun TrackMapView(
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // osmdroid 需要缓存目录
    remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
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
            controller.setZoom(16.0)
        }
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
            drawTrack(view, points, startLabel, endLabel)
        },
    )
}

private fun onResume(view: MapView) {
    try {
        view.onResume()
    } catch (_: Exception) {
        // 忽略
    }
}

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
            outlinePaint.color = Color.parseColor("#FF4A6FA5")
            outlinePaint.strokeWidth = 12f
        }
        view.overlays.add(line)
    }

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

    view.controller.setCenter(geoPoints.last())
    view.invalidate()
}