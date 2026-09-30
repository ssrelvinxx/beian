package com.beian.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beian.tracker.R

/**
 * 可选的保留天数档位。0 表示不自动清理。
 *
 * 档位刻意给得粗（30 / 90 / 180 / 365），不给「45 天」这种 —
 * 「保留多久」是个模糊偏好，精确到一个具体天数没有意义，
 * 反而让用户纠结。给几个能一眼比较的量级就够。
 */
private val RETENTION_OPTIONS = listOf(0, 30, 90, 180, 365)

/**
 * 数据保留设置。
 *
 * 背景：轨迹点是**只增不减**的 —— 采集间隔 2 分钟时一天 720 个点，
 * 一年约 26 万个，导出包也跟着一起变大。代码里早就有
 * [com.beian.tracker.data.TrackRepository.purgeOlderThan]，
 * 但**一直没有任何调用方**，等于库只进不出。
 *
 * ⚠️ 默认档是「不自动清理」而不是某个天数。
 *    自动删数据不可逆，默认必须是「什么都不删」，
 *    由用户自己在这里明确选择 —— 否则用户装完新版，
 *    一觉醒来发现几个月前的轨迹没了。
 *
 * ⚠️ 这里只影响**本机数据**。导入的对方数据包不受影响，
 *    由用户在「数据」页的来源列表里手动删。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RetentionSection(vm: MainViewModel, modifier: Modifier = Modifier) {
    val current by vm.retentionDays.collectAsStateWithLifecycle()

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_retention),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_retention_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // FlowRow 自动换行 —— 和采集间隔那条同理，
            // 窄屏上 5 个 chip 用 Row 会被挤出屏幕。
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RETENTION_OPTIONS.forEach { days ->
                    FilterChip(
                        selected = current == days,
                        onClick = { vm.setRetentionDays(days) },
                        label = { Text(retentionLabel(days)) },
                    )
                }
            }

            Text(
                // 选中「不自动清理」时给一句明确的话，而不是显示「保留 0 天」。
                text = if (current == 0) {
                    stringResource(R.string.settings_retention_off_hint)
                } else {
                    stringResource(R.string.settings_retention_current, current)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 天数 → 展示文案（0 → 「不清理」，30 → 「30 天」，365 → 「1 年」）。 */
@Composable
private fun retentionLabel(days: Int): String = when {
    days <= 0 -> stringResource(R.string.settings_retention_never)
    days % 365 == 0 -> stringResource(R.string.settings_retention_years, days / 365)
    else -> stringResource(R.string.settings_retention_days, days)
}
