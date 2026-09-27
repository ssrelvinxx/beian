package com.beian.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beian.tracker.data.EventLog
import com.beian.tracker.data.LOCAL_SOURCE
import com.beian.tracker.util.TimeUtil

/**
 * 报备页：以聊天气泡形式展示事件流。
 *
 * - 左侧白气泡 = 对方设备产生的事件
 * - 右侧绿气泡 = 我方（本机）事件
 * 顶部展示对方昵称、在线状态、最新电量。
 */
@Composable
fun ReportScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val sourceId by vm.sourceId.collectAsStateWithLifecycle()
    val events by vm.recentEvents.collectAsStateWithLifecycle()
    val snapshot by vm.latestSnapshot.collectAsStateWithLifecycle()
    val sources by vm.importedSources.collectAsStateWithLifecycle()

    val isLocal = sourceId == LOCAL_SOURCE
    val nickname = if (isLocal) {
        "本机"
    } else {
        sources.firstOrNull { it.sourceId == sourceId }?.nickname ?: "对方"
    }

    val listState = rememberLazyListState()
    // 新事件到达时滚到顶部（列表是时间倒序）
    LaunchedEffect(events.firstOrNull()?.id) {
        if (events.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Column(modifier = modifier.fillMaxSize()) {

        SourceSelector(vm)

        // ── 顶部信息栏 ────────────────────────────────────────────────────────
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = nickname,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (isLocal) "本机数据" else "导入的数据",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (isLocal) {
                        snapshot?.let {
                            Text(
                                text = "🔋 ${it.batteryLevel}%",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = when {
                                    it.batteryCharging -> "⚡ 充电中"
                                    else -> "未充电"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = "📶 ${networkLabel(it.networkType, it.networkName)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        val src = sources.firstOrNull { it.sourceId == sourceId }
                        src?.let {
                            Text(
                                text = "数据 ${it.firstDay} ~ ${it.lastDay}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        // ── 事件流 ────────────────────────────────────────────────────────────
        if (events.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isLocal) "暂无报备事件\n开启采集后会在这里显示" else "这个数据包里没有事件",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            ) {
                items(events, key = { it.id }) { e ->
                    EventBubble(event = e, fromMe = e.sourceId != LOCAL_SOURCE)
                }
            }
        }
    }
}

@Composable
private fun EventBubble(event: EventLog, fromMe: Boolean) {
    // fromMe = 对方设备的事件，显示在左侧（白气泡）；本机事件在右侧（绿气泡）
    val alignEnd = !fromMe

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = TimeUtil.time(event.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (alignEnd) 16.dp else 4.dp,
                        bottomEnd = if (alignEnd) 4.dp else 16.dp,
                    ),
                )
                .background(
                    if (alignEnd) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "${iconOf(event.type)} ${event.title}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (event.detail.isNotBlank()) {
                    Text(
                        text = event.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 事件类型 → emoji 图标。 */
private fun iconOf(type: String): String = when (type) {
    "FIRST_OPEN_TODAY" -> "🔒"
    "SCREEN_ON" -> "📱"
    "SCREEN_OFF" -> "🌙"
    "UNLOCK" -> "🔓"
    "BATTERY_LOW" -> "🪫"
    "BATTERY_FULL" -> "🔋"
    "CHARGING_START" -> "⚡"
    "CHARGING_STOP" -> "🔌"
    "NET_WIFI" -> "📶"
    "NET_CELLULAR" -> "📡"
    "NET_NONE" -> "❌"
    "CALL_OUT" -> "📞"
    "CALL_IN" -> "📲"
    "CALL_END" -> "📵"
    "APP_OPEN" -> "👀"
    "STAY" -> "📍"
    "LEAVE" -> "🚶"
    else -> "•"
}

private fun networkLabel(type: String, name: String): String = when (type) {
    "WIFI" -> if (name.isBlank()) "WiFi" else "WiFi：$name"
    "CELLULAR" -> "移动网络"
    else -> "未连接"
}
