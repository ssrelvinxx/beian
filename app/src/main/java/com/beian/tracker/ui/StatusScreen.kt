package com.beian.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beian.tracker.R
import com.beian.tracker.util.TimeUtil

@Composable
fun StatusScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val snapshot by vm.latestSnapshot.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.status_title), style = MaterialTheme.typography.headlineSmall)

        StatCard(
            stringResource(R.string.status_battery),
            snapshot?.let {
                val suffix = if (it.batteryCharging) {
                    " · " + stringResource(R.string.status_charging)
                } else {
                    ""
                }
                it.batteryLevel.toString() + "%" + suffix
            } ?: "—",
        )
        StatCard(
            stringResource(R.string.status_screen_time),
            TimeUtil.formatDuration(snapshot?.screenTimeMs ?: 0L),
        )
        StatCard(
            stringResource(R.string.status_unlock_count),
            (snapshot?.unlockCount ?: 0).toString(),
        )
        StatCard(
            stringResource(R.string.status_network),
            snapshot?.let { networkLabel(it.networkType, it.networkName) } ?: "—",
        )
        StatCard(
            stringResource(R.string.status_uptime),
            TimeUtil.formatDuration(com.beian.tracker.util.DeviceInfo.uptimeMs()),
        )

        snapshot?.let {
            Text(
                text = stringResource(R.string.status_updated_at, TimeUtil.time(it.timestamp)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}