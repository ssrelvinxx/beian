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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beian.tracker.R
import com.beian.tracker.data.AppUsage
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

    if (usage.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
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
            // ── 标题行：名称 + 总时长 ─────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.report_app_usage),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(
                        R.string.report_app_usage_total,
                        TimeUtil.formatDuration(total),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── 排行 ─────────────────────────────────────────────────────────
            visible.forEach { item ->
                AppUsageRow(item = item, maxMs = max)
            }

            // ── 展开 / 收起 ───────────────────────────────────────────────────
            if (usage.size > COLLAPSED_COUNT) {
                val hidden = usage.size - COLLAPSED_COUNT
                Text(
                    text = if (expanded) {
                        stringResource(R.string.report_app_usage_less)
                    } else {
                        stringResource(R.string.report_app_usage_more, hidden)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

/** 一行：图标占位 + 应用名 + 时长 + 进度条。 */
@Composable
private fun AppUsageRow(item: AppUsage, maxMs: Long) {
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
