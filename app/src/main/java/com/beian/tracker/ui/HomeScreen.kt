package com.beian.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beian.tracker.R
import com.beian.tracker.util.DeviceInfo
import com.beian.tracker.util.TimeUtil

@Composable
fun HomeScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val day by vm.selectedDay.collectAsStateWithLifecycle()
    val points by vm.todayPoints.collectAsStateWithLifecycle()
    val summary by vm.todaySummary.collectAsStateWithLifecycle()
    val snapshot by vm.latestSnapshot.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(text = day, style = MaterialTheme.typography.bodyMedium)

        StatCard(stringResource(R.string.home_distance), TimeUtil.formatDistance(vm.totalDistance(points)))
        StatCard(stringResource(R.string.home_points), points.size.toString())
        StatCard(stringResource(R.string.home_unlock), (summary?.unlockCount ?: 0).toString())
        StatCard(
            stringResource(R.string.home_screen),
            TimeUtil.formatDuration(summary?.screenTimeMs ?: 0L),
        )
        StatCard(
            stringResource(R.string.home_battery),
            snapshot?.let { "${it.batteryLevel}%" + if (it.batteryCharging) " ⚡" else "" } ?: "—",
        )
        StatCard(
            stringResource(R.string.home_network),
            snapshot?.let { networkLabel(it.networkType, it.networkName) } ?: "—",
        )
    }
}

@Composable
internal fun StatCard(label: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(text = value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** 网络类型 → 展示文案。 */
@Composable
internal fun networkLabel(type: String, name: String): String = when (type) {
    DeviceInfo.NET_WIFI -> if (name.isNotBlank()) "WiFi · $name" else "WiFi"
    DeviceInfo.NET_CELLULAR -> stringResource(R.string.net_cellular)
    else -> stringResource(R.string.net_none)
}