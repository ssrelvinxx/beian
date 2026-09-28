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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow

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
    val perApp = remember(sessions) { hourlyPerApp(sessions, filter) }
    val perHour = remember(perApp) { totalPerHour(perApp) }
    val peak = perHour.maxOrNull() ?: 0L

    // 选中的小时（点柱子查看该小时用了哪些 App）
    var selectedHour by remember(sessions) { mutableStateOf<Int?>(null) }

    val scrollState = rememberScrollState()

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

            Text(
                text = stringResource(R.string.history_hourly_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── 柱子：横向可滑动 ────────────────────────────────────────────
            //
            // 之前 24 根柱子用一个 Row + weight(1f) 平铺在固定宽度里，
            // 每根只有十几 dp，「每小时用了多久」根本看不出来。
            // 现在每根固定宽度、整行横向滚动，柱子有足够空间显示时长标签。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                perHour.forEachIndexed { hour, ms ->
                    val ratio = if (peak > 0) (ms.toFloat() / peak) else 0f
                    val isSelected = selectedHour == hour
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                selectedHour = if (isSelected) null else hour
                            }
                            .padding(vertical = 4.dp)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                                else Color.Transparent,
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        // 时长标签：哪怕柱子很矮也显示，方便一眼对比
                        Text(
                            text = if (ms > 0) TimeUtil.formatCompact(ms) else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(2.dp))
                        // 固定高度容器保证 0 值也占位，柱子宽度一致
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((90 * ratio).coerceAtLeast(if (ms > 0) 3f else 0f).dp)
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.primary,
                                ),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${hour}时",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }

            // ── 选中小时的明细 ──────────────────────────────────────────────
            selectedHour?.let { h ->
                val apps = perApp[h].orEmpty()
                    .entries
                    .filter { it.value > 0 }
                    .sortedByDescending { it.value }
                if (apps.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.history_hourly_detail, h),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    apps.forEach { (label, ms) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = TimeUtil.formatDuration(ms),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ── 图例 ──────────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.history_hourly_axis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 按「小时 → 各 App 时长」聚合。
 *
 * 归桶用片段**起点**所在的小时（沿用原有口径）：
 * 一次滑手机很少跨过整点，真要精确切分得把片段按分钟劈开，
 * 收益远不抵复杂度。
 *
 * 返回 24 个桶，每桶是「App 名 → 累计时长」。用 App 名而不是包名做键，
 * 直接拿去渲染明细就不用再查一次标签。
 */
private fun hourlyPerApp(
    sessions: List<AppSession>,
    filter: (String) -> Boolean,
): Array<LinkedHashMap<String, Long>> {
    val buckets = Array(24) { linkedMapOf<String, Long>() }
    val cal = Calendar.getInstance()
    sessions.forEach { s ->
        if (!filter(s.packageName)) return@forEach
        if (s.durationMs <= 0) return@forEach
        cal.timeInMillis = s.startAt
        val h = cal.get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)
        // 标签缺失时退回包名，至少不是空白
        val label = s.appLabel.ifBlank { s.packageName }
        buckets[h][label] = (buckets[h][label] ?: 0L) + s.durationMs
    }
    return buckets
}

/** 每小时的合计时长。 */
private fun totalPerHour(perApp: Array<LinkedHashMap<String, Long>>): LongArray =
    LongArray(24) { h -> perApp[h].values.sum() }
