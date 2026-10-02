package com.beian.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.beian.tracker.R
import com.beian.tracker.data.LOCAL_SOURCE
import com.beian.tracker.data.Stay
import com.beian.tracker.util.PermissionCheck
import com.beian.tracker.util.TimeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 轨迹页：地图回放 + 日期切换 + 停留点统计 + 时间轴。
 */
@Composable
fun TrackScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val points by vm.todayPoints.collectAsStateWithLifecycle()
    val tracking by vm.trackingEnabled.collectAsStateWithLifecycle()
    val selectedDay by vm.selectedDay.collectAsStateWithLifecycle()
    val sourceId by vm.sourceId.collectAsStateWithLifecycle()

    val isLocal = sourceId == LOCAL_SOURCE

    // 定位权限状态。用户在系统弹窗里授权后本页会重组，
    // 但重组时不会自动重查权限，所以挂个生命周期监听，
    // 回到前台就重新确认一次 —— 否则授权了地图上也不出现蓝点。
    //
    // ⚠️ 这里判断的是「有没有任意一种定位权限」，不是 allGranted()。
    // allGranted() 还包含通知权限，两者语义不同：通知没给时采集确实会残缺，
    // 但地图照样该能画 —— 用 allGranted() 会让地图被「请授权」整块盖住，
    // 看着就像「有定位权限却什么都不显示」。
    var locationGranted by remember { mutableStateOf(PermissionCheck.hasAnyLocation(context)) }

    /**
     * 系统定位开关。
     *
     * ⚠️ 必须与「有没有权限」分开判断。权限给了但快捷开关关了，
     * 地图组件挂上定位浮层后会取不到 provider，**整个地图被拖成空白**
     * （只剩背景色网格，瓦片一张不下）—— 这正是之前那个
     * 「不开定位进 App 地图就全空」的根因。
     */
    var systemLocationOn by remember { mutableStateOf(PermissionCheck.isSystemLocationOn(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current

    // 每次回到前台立刻重查一次。
    //
    // ON_START 和 ON_RESUME 都挂上：授权一定发生在离开本 App 之后，
    // 回来时两者至少会有一个被派发（个别 ROM 只派发其中一个）。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) {
                locationGranted = PermissionCheck.hasAnyLocation(context)
                systemLocationOn = PermissionCheck.isSystemLocationOn(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ⚠️ 只有 ON_RESUME 会漏掉一条路径：**下拉快捷开关里开关定位**。
    //
    // 那种操作不会让 Activity 走 pause/resume，生命周期回调根本不触发 ——
    // 用户在下拉栏把定位打开，回到 App 那条「系统定位已关闭，点此开启」
    // 仍挂在屏幕上，看着像没生效。
    //
    // 所以页面可见期间做一个轻量轮询兜住它。
    // 只在 RESUMED 状态跑（页面被切走 / App 退到后台会自动挂起），
    // 且只在值真的变了才赋值，避免无谓重组。
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val g = PermissionCheck.hasAnyLocation(context)
                val s = PermissionCheck.isSystemLocationOn(context)
                if (g != locationGranted) locationGranted = g
                if (s != systemLocationOn) systemLocationOn = s
                delay(2000)
            }
        }
    }

    // 两者都满足才挂定位浮层
    val canUseLocation = locationGranted && systemLocationOn

    var stays by remember { mutableStateOf(emptyList<Stay>()) }
    LaunchedEffect(sourceId, selectedDay) {
        // ⚠️ 必须在 IO 上算，不能留在 Main。
        //
        // LaunchedEffect 默认跑在主线程；staysOfDay 里 Room 的 suspend
        // 查询内部会切 IO，但**查询返回之后**那段「遍历全部点位、
        // 逐点调 Location.distanceBetween」的判断跑在调用者线程上 ——
        // 也就是主线程。一天上千个点时，这段循环能占住主线程几十到
        // 几百毫秒；它和采集写库、地图重绘叠在一起时，
        // 就是「点一下底栏卡住不动」的来源（ANR 日志里那条
        // Input dispatching timed out 等 5 秒）。
        stays = withContext(Dispatchers.IO) {
            vm.staysOfDay(sourceId, selectedDay)
        }
    }

    // 距离同理：原先直接写在组合体里，每次重组都要把所有点位重算一遍。
    // 用 derivedStateOf + IO 计算，只在 points 真的变化时才重算。
    var distance by remember { mutableStateOf(0.0) }
    LaunchedEffect(points) {
        distance = withContext(Dispatchers.Default) {
            vm.totalDistance(points)
        }
    }
    val stayDuration = remember(stays) { stays.sumOf { it.durationMs } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // ── 标题 + 采集开关 ───────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.track_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(if (tracking) R.string.track_active else R.string.track_inactive),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        SourceSelector(vm)

        // ── 地图 ──────────────────────────────────────────────────────────────
        // 我的位置蓝点：本机和看对方时都显示。
        // 看对方时显示是为了直观对比「我在哪、对方在哪、差多远」——
        // 起终点现在是「起 / 终」气泡，不会再看错成自己的轨迹。
        var peerDistance by remember { mutableStateOf<Double?>(null) }

        TrackMapView(
            points = points,
            showMyLocation = true,
            locationGranted = canUseLocation,
            onDistanceToPeer = { peerDistance = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .padding(horizontal = 16.dp),
        )

        // 看对方轨迹时，把「我在哪 vs 对方最后在哪」的距离写出来。
        // 本机轨迹不需要这行（自己到自己的轨迹没有「距离」的意义）。
        val d = peerDistance
        if (!isLocal && d != null) {
            Text(
                text = stringResource(
                    R.string.track_peer_distance,
                    TimeUtil.formatDistance(d),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        // ── 起终点时间 ────────────────────────────────────────────────────────
        //
        // 紧贴地图下方常显，不用点气泡 —— 回看轨迹时最想先知道的就是
        // 「几点从哪出发、几点到哪」，扫一眼就要看到。
        //
        // ⚠️ 刻意不用 Marker 的气泡来承担这个信息：
        //    气泡要点击才出现，等于藏起来了；而且 osmdroid 的
        //    文字标签扩展 API 各版本成员名不一致，写错编译不过。
        //    放在这里排版完全由我们掌握，也顺带能显示「共几个点」。
        if (points.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 起点：左对齐，占一半
                EndpointTime(
                    label = stringResource(R.string.map_start),
                    timestamp = points.first().timestamp,
                    modifier = Modifier.weight(1f),
                )
                // 终点：右对齐，占一半
                EndpointTime(
                    label = stringResource(R.string.map_end),
                    timestamp = points.last().timestamp,
                    alignEnd = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 系统定位关着 → 明确告诉用户，并给一键去开启的入口。
        // 不说的话，地图就是一片空白，用户只会以为「坏了」。
        if (isLocal && locationGranted && !systemLocationOn) {
            Text(
                text = stringResource(R.string.track_location_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clickable { PermissionCheck.openLocationSettings(context) },
            )
        }

        Spacer(Modifier.size(12.dp))

        // ── 日期切换 ──────────────────────────────────────────────────────────
        DaySelector(
            selected = selectedDay,
            onSelect = { vm.selectDay(it) },
        )

        Spacer(Modifier.size(8.dp))

        // ── 统计卡 ────────────────────────────────────────────────────────────
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatCell(value = "${stays.size}个", label = stringResource(R.string.track_stat_stays))
                StatCell(value = TimeUtil.formatDuration(stayDuration), label = stringResource(R.string.track_stat_stay_duration))
                StatCell(value = TimeUtil.formatDistance(distance), label = stringResource(R.string.track_stat_distance))
            }
        }


        Spacer(Modifier.size(16.dp))

        // ── 停留时间轴 ────────────────────────────────────────────────────────
        Text(
            text = stringResource(R.string.track_timeline),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.size(8.dp))

        if (stays.isEmpty()) {
            Text(
                text = stringResource(R.string.track_timeline_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                stays.sortedByDescending { it.startAt }.take(30).forEach { s ->
                    StayRow(s)
                }
            }
        }

        Spacer(Modifier.size(24.dp))
    }
}

/** 日期选择：最近 7 天 + 今天/昨天标签。 */
@Composable
private fun DaySelector(
    selected: String,
    onSelect: (String) -> Unit,
) {
    val today = TimeUtil.dayKey()
    val yesterday = TimeUtil.dayKey(System.currentTimeMillis() - 24L * 3600_000)
    val days = remember(today) {
        (0..6).map { offset ->
            TimeUtil.dayKey(System.currentTimeMillis() - offset * 24L * 3600_000)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        days.forEach { day ->
            val isSelected = day == selected
            val label = when (day) {
                today -> stringResource(R.string.track_day_today)
                yesterday -> stringResource(R.string.track_day_yesterday)
                else -> day.substring(5) // MM-dd
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onSelect(day) },
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.size(4.dp))
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else androidx.compose.ui.graphics.Color.Transparent,
                        ),
                )
            }
        }
    }
}

@Composable
private fun StatCell(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.size(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StayRow(stay: Stay) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 时间轴节点
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(36.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${TimeUtil.time(stay.endAt)}  ${stringResource(R.string.track_stay_leave)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.track_stay_duration, TimeUtil.formatDuration(stay.durationMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 起终点时间（地图下方常显）。
 *
 * 显示成两行：上行小字「起点/终点」，下行时间大字。
 * 时间用 [TimeUtil.time] 的 HH:mm:ss —— 轨迹点的秒级差异有意义
 * （比如刚好卡在某个整点前后），所以不截到分钟。
 *
 * [alignEnd] = true 时右对齐，让起终两端分别贴住左右，
 * 中间自然留白，比两个都左对齐更容易看出是「一头一尾」。
 */
@Composable
private fun EndpointTime(
    label: String,
    timestamp: Long,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = TimeUtil.time(timestamp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
