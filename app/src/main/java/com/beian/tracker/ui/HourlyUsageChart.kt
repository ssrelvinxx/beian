package com.beian.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.beian.tracker.R
import com.beian.tracker.data.AppSession
import com.beian.tracker.util.TimeUtil
import java.util.Calendar

/**
 * 一天 24 小时的使用强度柱状图。
 *
 * 数据来源是 [AppSession]（每条片段有 startAt / durationMs）。
 * 按片段**起点所在的小时**归桶，把时长累加进去。
 *
 * 为什么不均分到跨小时的那些片段：一次滑手机很少跨过整点，
 * 真要精确切分就得把片段按分钟劈开，收益远不抵复杂度。
 * 归到起点小时足够看出「几点在玩手机」。
 *
 * 横轴只标 0/6/12/18，24 个标签会糊成一片。
 */
@Composable
fun HourlyUsageChart(
    sessions: List<AppSession>,
    modifier: Modifier = Modifier,
    /** 这些包名的时间不计入（桌面、系统界面等）。 */
    filter: (String) -> Boolean = { true },
) {
    val perHour = remember(sessions) { hourlyBuckets(sessions, filter) }
    val peak = perHour.maxOrNull() ?: 0L

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.history_hourly_title),
                style = MaterialTheme.typography.titleSmall,
            )

            if (peak <= 0L) {
                Text(
                    text = stringResource(R.string.history_hourly_empty),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Text(
                text = stringResource(
                    R.string.history_hourly_peak,
                    TimeUtil.formatDuration(peak),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── 柱子 ──────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                perHour.forEachIndexed { hour, ms ->
                    val ratio = if (peak > 0) ms.toFloat() / peak else 0f
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        // 用固定高度容器包住柱子，让 0 值也占位，
                        // 否则整行会因为空 Column 塌掉、柱子宽度不均
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((100 * ratio).coerceAtLeast(if (ms > 0) 3f else 0f).dp)
                                .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Text(
                            text = if (hour % 6 == 0) hour.toString() else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── 图例 ──────────────────────────────────────────────────────────
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.history_hourly_axis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 把会话按起点小时累加成 24 个桶。 */
private fun hourlyBuckets(
    sessions: List<AppSession>,
    filter: (String) -> Boolean,
): LongArray {
    val buckets = LongArray(24)
    val cal = Calendar.getInstance()
    sessions.forEach { s ->
        if (!filter(s.packageName)) return@forEach
        if (s.durationMs <= 0) return@forEach
        cal.timeInMillis = s.startAt
        val h = cal.get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)
        buckets[h] += s.durationMs
    }
    return buckets
}
