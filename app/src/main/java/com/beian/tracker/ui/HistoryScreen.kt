package com.beian.tracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beian.tracker.R
import com.beian.tracker.data.LOCAL_SOURCE
import com.beian.tracker.util.AppEventDeriver
import com.beian.tracker.util.TimeUtil

@Composable
fun HistoryScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val days by vm.allDays.collectAsStateWithLifecycle()
    val summaries by vm.allSummaries.collectAsStateWithLifecycle()
    val selected by vm.selectedDay.collectAsStateWithLifecycle()
    val hourlySessions by vm.hourlySessions.collectAsStateWithLifecycle()
    val selfPkg = vm.selfPackageName
    val sourceId by vm.sourceId.collectAsStateWithLifecycle()
    val sources by vm.importedSources.collectAsStateWithLifecycle()

    // 数据来源：与报备页同一套判断（sourceId == LOCAL 即本机）。
    // 对方包的显示名取导入时填的昵称。
    val isLocal = sourceId == LOCAL_SOURCE
    val nickname = if (isLocal) {
        "本机"
    } else {
        sources.firstOrNull { it.sourceId == sourceId }?.nickname ?: "对方"
    }

    // 展开某天的时间线：必须带上当前来源，
    // 否则看对方数据时展开的是本机那天的片段。
    var expandedDay by remember { mutableStateOf<String?>(null) }
    var sessions by remember { mutableStateOf(emptyList<com.beian.tracker.data.AppSession>()) }
    LaunchedEffect(expandedDay, sourceId) {
        sessions = expandedDay?.let { vm.appSessionsOfDayOnce(it, sourceId) } ?: emptyList()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall)

        // ── 数据来源 ──────────────────────────────────────────────────────────
        //
        // 统计页原来**完全不标来源**：切到对方的包后，页面上的里程、点数、
        // App 排行和本机的长得一模一样，没有任何提示 —— 很容易把对方的数据
        // 当成自己的看，反过来也一样。报备页早就有这个标识，统计页漏了。
        //
        // 用带底色的整条，而不是像报备页那样的小灰字：统计页整屏都是数字，
        // 一行小字很容易被当成标题的一部分划过去。
        Surface(
            color = if (isLocal) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (isLocal) {
                    stringResource(R.string.stats_source_local)
                } else {
                    stringResource(R.string.stats_source_imported, nickname)
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        // 对方数据标出覆盖范围：只看某一天时，得知道包里到底有没有这天，
        // 否则「这几天没记录」和「这天对方没数据」分不清。
        if (!isLocal) {
            sources.firstOrNull { it.sourceId == sourceId }?.let {
                Text(
                    text = stringResource(R.string.stats_source_range, it.firstDay, it.lastDay),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

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
                            // ── 当天 App 使用排行 ──────────────────────────────
                            // 数据来自本地库，本机与导入的对方数据都能显示。
                            AppUsageOfDay(vm = vm, day = day)

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