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
import com.beian.tracker.R
import com.beian.tracker.data.LOCAL_SOURCE
import com.beian.tracker.data.Stay
import com.beian.tracker.util.PermissionCheck
import com.beian.tracker.util.TimeUtil

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
    val offlineOnly by vm.offlineMapOnly.collectAsStateWithLifecycle()

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
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                locationGranted = PermissionCheck.hasAnyLocation(context)
                systemLocationOn = PermissionCheck.isSystemLocationOn(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 两者都满足才挂定位浮层
    val canUseLocation = locationGranted && systemLocationOn

    var stays by remember { mutableStateOf(emptyList<Stay>()) }
    LaunchedEffect(sourceId, selectedDay) {
        stays = vm.staysOfDay(sourceId, selectedDay)
    }

    val distance = vm.totalDistance(points)
    val stayDuration = stays.sumOf { it.durationMs }

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
        TrackMapView(
            points = points,
            offlineMode = offlineOnly,
            // 看本机数据时把自己的位置也标出来；看对方的包则不加，
            // 否则会让人以为那条轨迹是自己走的。
            showMyLocation = isLocal,
            locationGranted = canUseLocation,
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .padding(horizontal = 16.dp),
        )

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

        // ── 离线地图管理 ──────────────────────────────────────────────────────
        OfflineMapSection(
            vm = vm,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

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
