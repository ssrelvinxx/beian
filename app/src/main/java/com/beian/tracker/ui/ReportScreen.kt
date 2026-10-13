package com.beian.tracker.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beian.tracker.R
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
@OptIn(ExperimentalFoundationApi::class)
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
    // 新事件到达时滚到顶部（列表是时间倒序）。
    //
    // ⚠️ 只在**当前已经贴着顶部**时才跟随。
    //
    // 列表改成按天折叠后，上面收着的是好几天的事件，往下一划
    // 就是在翻历史。原来无条件 animateScrollToItem(0) 会把人
    // 拽回今天 —— 正在看前天的记录，一到采集周期就被弹走，
    // 根本读不完一条。
    // 贴顶时才跟随：正在看最新的，新事件来了自然要看到；
    // 一旦往下滚了，就说明用户在主动翻旧记录，不要打扰。
    LaunchedEffect(events.firstOrNull()?.id) {
        if (events.isNotEmpty() && listState.firstVisibleItemIndex <= 1) {
            listState.animateScrollToItem(0)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {

        SourceSelector(vm)

        // ── 开始记录（只在本机视图显示）──────────────────────────────────────
        // 记录只开不关，所以这里没有「停止」按钮。
        // 看对方的数据包时不该出现这个入口，否则容易误触。
        if (isLocal) {
            StartRecordingCard(vm)
        }

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
                // 电量 / 网络：本机与对方都显示。
                // 之前只在 isLocal 分支渲染，切到对方后这一行整个空掉，
                // 看起来就像「切换没生效」。
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                }
                // 对方数据额外标出覆盖的日期范围
                if (!isLocal) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

        // ── 后台常驻提醒（只在未加白名单、且是本机视图时显示）──────────────
        if (isLocal) {
            BackgroundWarning()
        }

        // ── 今日 App 使用排行：已移除 ─────────────────────────────────────────
        // 按需求从报备页去掉。App 使用统计统一放到「统计」页（原历史页）看，
        // 报备页只留事件流，页面更短、切过来也更快。

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
            // ── 按天分组（可折叠）─────────────────────────────────────────────
            //
            // 事件流原来是**一长串混在一起**的：recentEvents 取最近 300 条，
            // 中间跨了好几天也没有分界，昨天的和今天的挨在一起往下排，
            // 看不出哪里换了一天（只有每条气泡自己的时间）。
            //
            // 先按 dayKey 切段、加日期头；再把每一天做成可折叠的 ——
            // 一天的事件动辄几十条，全铺开时想找前天的记录要划过整屏，
            // 而实际上绝大多数时候只看最近这一天。
            //
            // 分组在**客户端**做而不是改成「按天查询 + 多个列表」：
            // 事件已经按时间倒序取回来了，就地切段不需要额外的数据库往返；
            // 而且列表始终是同一个 LazyColumn，滚动位置不会因为切分而丢失。
            val grouped = remember(events) {
                events.groupBy { it.dayKey }.toList().sortedByDescending { it.first }
            }

            // 展开状态：存「哪些天是展开的」而不是「当前展开的是哪一天」。
            //
            // 用 Set 是为了支持同时展开多天 —— 比对「昨天和前天」时要能
            // 一起看，只允许开一天的话得来回切，很别扭。
            //
            // ⚠️ 默认展开**最新的一天**，其余收起。
            //    用 [userToggled] 把「还没动过」和「手动全收起」区分开：
            //    两者都表现为空 Set，但前者要套用默认（展开最新一天），
            //    后者应当真的一天都不展开。
            // ⚠️ 用 sourceId 作为 rememberSaveable 的 inputs：
            //    切换数据来源（本机 ↔ 对方）时状态自动重置，转屏重建时
            //    因为 inputs 没变而正常恢复。
            //
            //    不要写成 LaunchedEffect(sourceId) { userToggled = false }：
            //    LaunchedEffect 每次进入组合都会跑（转屏也算），
            //    那样一转屏就把用户点开的那些天全收回去，
            //    而 rememberSaveable 刚存下的状态当场被自己覆盖掉。
            var expandedDays by rememberSaveable(sourceId, stateSaver = DaySetSaver) {
                mutableStateOf(emptySet<String>())
            }
            var userToggled by rememberSaveable(sourceId) { mutableStateOf(false) }

            val defaultExpanded = remember(grouped) {
                grouped.firstOrNull()?.first?.let { setOf(it) } ?: emptySet()
            }
            val effectiveExpanded = if (userToggled) expandedDays else defaultExpanded

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            ) {
                grouped.forEach { (day, dayEvents) ->
                    val expanded = day in effectiveExpanded
                    // stickyHeader：滚到某天中间时日期条仍然钉在顶部，
                    // 否则一屏里全是气泡，又回到「看不出这是哪天」。
                    stickyHeader(key = "hdr-$day") {
                        DayHeader(
                            day = day,
                            count = dayEvents.size,
                            expanded = expanded,
                            onToggle = {
                                // 第一次点击时以「默认展开的那天」为基准再改，
                                // 而不是从空集合开始 —— 否则点一下「昨天」，
                                // 今天会跟着一起收起来，像是误触。
                                val base = if (userToggled) expandedDays else defaultExpanded
                                expandedDays = if (day in base) base - day else base + day
                                userToggled = true
                            },
                        )
                    }
                    if (expanded) {
                        items(dayEvents, key = { it.id }) { e ->
                            EventBubble(event = e, fromMe = e.sourceId != LOCAL_SOURCE)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 事件流的日期分隔头。
 *
 * 带背景色 + 满宽：它是「粘」在顶部的，气泡会从它下面滚过去，
 * 没有实底会透出下面的内容，看着很乱。
 *
 * 整条可点击：折叠状态下用户会顺手去点「那天那行字」，
 * 只让右侧小箭头可点的话，点击区域比看起来小得多，很容易点空。
 *
 * @param expanded 该天是否展开（决定箭头朝向）
 * @param onToggle 点整条时切换展开状态
 */
@Composable
private fun DayHeader(
    day: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = TimeUtil.dayLabel(day),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.report_day_count, count),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.report_day_collapse else R.string.report_day_expand,
                    ),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * [rememberSaveable] 用的 Saver：把 `Set<String>` 转成 `ArrayList<String>` 存取。
 *
 * 为什么要显式写：`Set` 本身不是 Bundle 能直接保存的类型，
 * 不提供 Saver 时 `rememberSaveable` 会在运行时抛异常，
 * 而这类异常在编译期完全看不出来（只有真的转屏/重建时才崩）。
 *
 * ArrayList 是 Bundle 原生支持的，且顺序会被保留 ——
 * 与「按天展开」的语义无关，存进去只为能取回来。
 */
private val DaySetSaver = Saver<Set<String>, ArrayList<String>>(
    save = { ArrayList(it) },
    restore = { it.toSet() },
)

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
    "FIRST_OPEN_TODAY" -> "💡"
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
