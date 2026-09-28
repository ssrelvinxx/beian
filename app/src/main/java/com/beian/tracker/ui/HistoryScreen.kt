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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beian.tracker.R
import com.beian.tracker.util.AppEventDeriver
import com.beian.tracker.util.TimeUtil

@Composable
fun HistoryScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val days by vm.allDays.collectAsStateWithLifecycle()
    val summaries by vm.allSummaries.collectAsStateWithLifecycle()
    val selected by vm.selectedDay.collectAsStateWithLifecycle()
    val points by vm.todayPoints.collectAsStateWithLifecycle()
    val hourlySessions by vm.hourlySessions.collectAsStateWithLifecycle()
    val selfPkg = vm.selfPackageName

    // 展开某天的时间线
    var expandedDay by remember { mutableStateOf<String?>(null) }
    var sessions by remember { mutableStateOf(emptyList<com.beian.tracker.data.AppSession>()) }
    LaunchedEffect(expandedDay) {
        sessions = expandedDay?.let { vm.appSessionsOfDayOnce(it) } ?: emptyList()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall)

        TrackMapView(
            points = points,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
        )

        Text(
            text = "${stringResource(R.string.history_day_detail)}: $selected",
            style = MaterialTheme.typography.titleSmall,
        )

        // ── 各时段使用强度 ────────────────────────────────────────────────────
        // 跟着上面选中的日期走（selectedDay 变化会重算，见 vm.hourlySessions）
        HourlyUsageChart(
            sessions = hourlySessions,
            filter = { pkg -> AppEventDeriver.isReportable(pkg, selfPkg) },
        )

        if (days.isEmpty()) {
            Text(
                text = stringResource(R.string.history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(days) { day ->
                    val summary = summaries.firstOrNull { it.dayKey == day }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.selectDay(day)
                                expandedDay = if (expandedDay == day) null else day
                            }
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
                        if (expandedDay == day) {
                            Text(
                                text = stringResource(R.string.history_app_timeline),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            AppTimelineList(
                                sessions = sessions,
                                maxItems = 50,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}