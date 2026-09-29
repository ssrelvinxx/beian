package com.beian.tracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beian.tracker.R
import com.beian.tracker.data.AppUsage
import com.beian.tracker.data.LOCAL_SOURCE
import com.beian.tracker.util.TimeUtil

/** 折叠状态下展示几个 App。 */
private const val COLLAPSED_COUNT = 3

/**
 * 报备页的「今日 App 使用」卡片。
 *
 * 默认只显示前 2 个（外加展开按钮），点一下才铺开完整的排行 ——
 * 事件流才是这个页面的主体，排行不该把它挤下去。
 */
@Composable
fun AppUsageSection(vm: MainViewModel, modifier: Modifier = Modifier) {
    val usage by vm.reportableAppUsage.collectAsStateWithLifecycle()
    val hasAccess by vm.hasUsageAccess.collectAsStateWithLifecycle()
    val sourceId by vm.sourceId.collectAsStateWithLifecycle()
    val isLocal = sourceId == LOCAL_SOURCE

    // 没权限：只在**看本机数据**时提示授权。
    //
    // 看导入的对方数据时不需要任何权限 —— 数据就在本地库里。
    // 之前这里不看来源，一切到对方就弹「请授权使用情况访问」，
    // 把对方已经导入好的排行整块盖住，看着像「导入的数据没有排行」。
    if (isLocal && !hasAccess) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.report_app_usage),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.report_app_usage_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    // 没有数据就不占位（本机是「今天没用过 App」，对方是「这个包没带排行」）。
    // 看对方时给一行说明，否则用户会以为导入漏了数据。
    if (usage.isEmpty()) {
        if (!isLocal) {
            Card(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.report_app_usage),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.report_app_usage_peer_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        return
    }

    var expanded by rememberSaveable { mutableStateOf(false) }
    val visible = if (expanded) usage else usage.take(COLLAPSED_COUNT)
    val total = usage.sumOf { it.usageMs }
    val max = usage.maxOf { it.usageMs }.coerceAtLeast(1L)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── 标题行：名称 + 总时长 + 展开/收起 ────────────────────────────
            // 展开按钮必须放在这里（列表上方）。
            // 之前放在列表下方，展开后列表变长会把按钮推出屏幕，
            // 用户滚到卡片底部才能收起 —— 看起来就是「收不回去」。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.report_app_usage),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(
                        R.string.report_app_usage_total,
                        TimeUtil.formatDuration(total),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (usage.size > COLLAPSED_COUNT) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (expanded) {
                            stringResource(R.string.report_app_usage_less)
                        } else {
                            stringResource(R.string.report_app_usage_more, usage.size - COLLAPSED_COUNT)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { expanded = !expanded }
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                    )
                }
            }

            // ── 排行 ─────────────────────────────────────────────────────────
            visible.forEach { item ->
                AppUsageRow(item = item, maxMs = max)
            }

        }
    }
}

/**
 * 一行：应用名 + 时长 + 进度条。
 *
 * 供报备页的今日排行、以及「统计」页里按天展开的排行共用。
 */
@Composable
fun AppUsageRow(item: AppUsage, maxMs: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.appLabel,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = TimeUtil.formatDuration(item.usageMs),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        // 时长条：按最长的那一项归一化，不占满整行
        LinearProgressIndicator(
            progress = { (item.usageMs.toFloat() / maxMs).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .widthIn(min = 20.dp),
        )
    }
}

/**
 * 「某一天」的 App 使用排行。
 *
 * 与 [AppUsageSection] 的区别：那个跟着「当前选中的日期」走，
 * 这个显式传入 [day]，用在「统计」页按天展开的场景。
 *
 * 数据直接读本地库 —— 本机数据和导入的对方数据都适用，
 * 看导入数据不需要任何权限（权限只在采集本机数据时才需要）。
 */
@Composable
fun AppUsageOfDay(
    vm: MainViewModel,
    day: String,
    modifier: Modifier = Modifier,
) {
    val sourceId by vm.sourceId.collectAsStateWithLifecycle()
    var usage by remember { mutableStateOf(emptyList<AppUsage>()) }

    LaunchedEffect(sourceId, day) {
        usage = runCatching { vm.appUsageOfDayOnce(sourceId, day) }
            .getOrDefault(emptyList())
            .filter { it.usageMs > 0 }
            .sortedByDescending { it.usageMs }
    }

    // 那天没有数据就不占位，留白比显示一行「无数据」干净
    if (usage.isEmpty()) return

    val total = usage.sumOf { it.usageMs }
    val max = usage.maxOf { it.usageMs }.coerceAtLeast(1L)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.history_app_usage),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(
                    R.string.report_app_usage_total,
                    TimeUtil.formatDuration(total),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        usage.forEach { item -> AppUsageRow(item = item, maxMs = max) }
    }
}
