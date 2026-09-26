package com.beian.tracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
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
fun HistoryScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val days by vm.allDays.collectAsStateWithLifecycle()
    val summaries by vm.allSummaries.collectAsStateWithLifecycle()
    val selected by vm.selectedDay.collectAsStateWithLifecycle()
    val points by vm.todayPoints.collectAsStateWithLifecycle()
    val amapKey by vm.amapKey.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall)

        TrackMapView(
            points = points,
            amapKey = amapKey,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
        )

        Text(
            text = "${stringResource(R.string.history_day_detail)}: $selected",
            style = MaterialTheme.typography.titleSmall,
        )

        if (days.isEmpty()) {
            Text(
                text = stringResource(R.string.history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(days) { day ->
                    val summary = summaries.firstOrNull { it.dayKey == day }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.selectDay(day) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(text = day, style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = summary?.let { TimeUtil.formatDistance(it.totalDistanceMeters) } ?: "—",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = summary?.let { stringResource(R.string.history_points, it.pointCount) } ?: "—",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = summary?.let { TimeUtil.formatDuration(it.screenTimeMs) } ?: "—",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}