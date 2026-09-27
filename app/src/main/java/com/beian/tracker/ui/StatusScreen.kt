package com.beian.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beian.tracker.R
import com.beian.tracker.util.TimeUtil

@Composable
fun StatusScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val snapshot by vm.latestSnapshot.collectAsStateWithLifecycle()
    val appUsage by vm.appUsage.collectAsStateWithLifecycle()

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

        Text(
            text = stringResource(R.string.status_app_usage),
            style = MaterialTheme.typography.titleMedium,
        )

        if (appUsage.isEmpty()) {
            Text(
                text = stringResource(R.string.status_app_usage_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val max = appUsage.maxOf { it.usageMs }.coerceAtLeast(1L)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    appUsage.forEachIndexed { i, item ->
                        AppUsageRow(
                            label = item.appLabel,
                            usageMs = item.usageMs,
                            launchCount = item.launchCount,
                            fraction = item.usageMs.toFloat() / max,
                        )
                        if (i != appUsage.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppUsageRow(
    label: String,
    usageMs: Long,
    launchCount: Int,
    fraction: Float,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth(0.6f),
                maxLines = 1,
            )
            Text(
                text = TimeUtil.formatDuration(usageMs),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 4.dp),
        )
        Text(
            text = stringResource(R.string.status_app_launches, launchCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}