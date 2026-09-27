package com.beian.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.beian.tracker.R
import com.beian.tracker.data.AppSession
import com.beian.tracker.util.TimeUtil

/**
 * App 打开时间线：按时间倒序列出「几点打开、用了多久」。
 * 仅显示达到最小时长的记录，避免系统弹窗/瞬间切换刷屏。
 */
@Composable
fun AppTimelineList(
    sessions: List<AppSession>,
    modifier: Modifier = Modifier,
    minDurationMs: Long = MIN_SESSION_MS,
    maxItems: Int = Int.MAX_VALUE,
) {
    val shown = sessions.filter { it.durationMs >= minDurationMs }.take(maxItems)

    if (shown.isEmpty()) {
        Text(
            text = stringResource(R.string.status_app_timeline_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            shown.forEachIndexed { i, s ->
                SessionRow(s)
                if (i != shown.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun SessionRow(s: AppSession) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = TimeUtil.time(s.startAt),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(48.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = s.appLabel,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(
                text = if (s.endAt > 0) {
                    "${TimeUtil.time(s.startAt)} – ${TimeUtil.time(s.endAt)}"
                } else {
                    stringResource(R.string.status_app_timeline_running)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = TimeUtil.formatDuration(s.durationMs),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** 小于该时长（1 分钟）的片段不在时间线展示。 */
const val MIN_SESSION_MS = 60_000L
