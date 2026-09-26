package com.beian.tracker.ui

import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.amap.api.maps2d.AMap
import com.amap.api.maps2d.CameraUpdateFactory
import com.amap.api.maps2d.MapView
import com.amap.api.maps2d.model.LatLng
import com.amap.api.maps2d.model.LatLngBounds
import com.amap.api.maps2d.model.PolylineOptions
import com.beian.tracker.data.TrackPoint

/**
 * 高德 2D 地图，绘制轨迹线。
 * 未配置 Key 时降级为提示文案（不崩溃）。
 */
@Composable
fun TrackMapView(
    points: List<TrackPoint>,
    amapKey: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    if (amapKey.isBlank()) {
        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.map_not_configured),
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
            onCreate(null)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mapView.onDestroy()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view ->
            drawTrack(view.map, points, startLabel, endLabel)
        },
    )
}

private fun drawTrack(map: AMap, points: List<TrackPoint>, startLabel: String, endLabel: String) {
    map.clear()
    if (points.isEmpty()) return

    val latLngs = points.map { LatLng(it.latitude, it.longitude) }

    if (latLngs.size >= 2) {
        map.addPolyline(
            PolylineOptions()
                .addAll(latLngs)
                .width(12f)
                .color(Color.parseColor("#FF4A6FA5")),
        )
    }

    // 起终点标记
    map.addMarker(
        com.amap.api.maps2d.model.MarkerOptions()
            .position(latLngs.first())
            .title(startLabel),
    )
    map.addMarker(
        com.amap.api.maps2d.model.MarkerOptions()
            .position(latLngs.last())
            .title(endLabel),
    )

    val bounds = LatLngBounds.Builder()
    latLngs.forEach { bounds.include(it) }
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 100))
}