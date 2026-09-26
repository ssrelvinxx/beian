package com.beian.tracker.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beian.tracker.R
import com.beian.tracker.service.TrackService

@Composable
fun TrackScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val points by vm.todayPoints.collectAsStateWithLifecycle()
    val amapKey by vm.amapKey.collectAsStateWithLifecycle()
    val tracking by vm.trackingEnabled.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) {
            vm.setTracking(true)
            TrackService.start(context)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.track_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(if (tracking) R.string.track_active else R.string.track_inactive),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = {
                    if (tracking) {
                        vm.setTracking(false)
                        TrackService.stop(context)
                    } else {
                        val fine = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (fine) {
                            vm.setTracking(true)
                            TrackService.start(context)
                        } else {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(if (tracking) R.string.track_stop else R.string.track_start),
                )
            }
        }

        TrackMapView(
            points = points,
            amapKey = amapKey,
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp),
        )
    }
}