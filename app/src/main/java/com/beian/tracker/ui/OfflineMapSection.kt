package com.beian.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beian.tracker.R
import com.beian.tracker.util.TileDownloader

/**
 * 离线地图管理卡片。
 *
 * - 显示已缓存瓦片数量与占用空间
 * - 按当前轨迹范围预下载瓦片
 * - 强制离线开关（完全不发网络请求）
 * - 清空缓存
 */
@Composable
fun OfflineMapSection(vm: MainViewModel, modifier: Modifier = Modifier) {
    val stats by vm.tileStats.collectAsStateWithLifecycle()
    val progress by vm.downloadProgress.collectAsStateWithLifecycle()
    val offlineOnly by vm.offlineMapOnly.collectAsStateWithLifecycle()
    val zoom by vm.mapZoom.collectAsStateWithLifecycle()
    val points by vm.todayPoints.collectAsStateWithLifecycle()
    val sourceId by vm.sourceId.collectAsStateWithLifecycle()
    val day by vm.selectedDay.collectAsStateWithLifecycle()

    // 进入时刷新一次统计
    LaunchedEffect(sourceId, day) { vm.refreshTileStats() }

    /**
     * ⚠️ 离开这一页时必须停掉瓦片下载。
     *
     * 下载任务挂在 viewModelScope 上，而 MainViewModel 是 **Activity 作用域**
     * （`viewModel()`），Tab 切换不会销毁它 —— 切走之后下载仍在后台猛跑，
     * 每张瓦片都回写一次 _downloadProgress（StateFlow）。
     *
     * 而 MainScreen 用的是 `when (index) { ... }` 直接切换，切走时
     * TrackScreen / 本组件**整个销毁**。于是形成：
     *   旧组合正在销毁 + 新组合正在建立 + 后台每几十毫秒推一次状态
     * → 重组风暴，界面卡死。这就是「下载瓦片时切页面卡死」的成因。
     *
     * 所以本页销毁即取消下载。用户要看进度就留在这一页；
     * 真要后台下载应该走前台服务，而不是靠一个已销毁的界面。
     */
    DisposableEffect(Unit) {
        onDispose { vm.cancelTileDownload() }
    }

    val estimate = vm.estimateTiles(zoom)
    val overLimit = estimate > TileDownloader.MAX_TILES

    val zoomLabel = when {
        zoom <= 13 -> stringResource(R.string.map_offline_zoom_blocks)
        zoom <= 15 -> stringResource(R.string.map_offline_zoom_roads)
        zoom <= 17 -> stringResource(R.string.map_offline_zoom_streets)
        else -> stringResource(R.string.map_offline_zoom_max)
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.map_offline_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(
                    R.string.map_offline_stats,
                    stats.count,
                    formatBytes(stats.bytes),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            // ── 缩放级别 ──────────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.map_offline_zoom, zoom, zoomLabel),
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = zoom.toFloat(),
                onValueChange = { vm.setMapZoom(it.toInt()) },
                valueRange = 12f..18f,
                steps = 5,
                enabled = progress == null,
            )
            Text(
                text = if (points.isEmpty()) {
                    stringResource(R.string.map_offline_no_points)
                } else {
                    stringResource(R.string.map_offline_estimate, estimate) +
                        if (overLimit) {
                            stringResource(R.string.map_offline_over_limit, TileDownloader.MAX_TILES)
                        } else {
                            ""
                        }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (overLimit) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            // ── 下载 / 进度 ───────────────────────────────────────────────────
            val p = progress
            if (p == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.downloadOfflineTiles() },
                        enabled = points.isNotEmpty() && !overLimit,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.map_offline_download))
                    }
                    if (stats.count > 0) {
                        OutlinedButton(onClick = { vm.clearOfflineTiles() }) {
                            Text(stringResource(R.string.map_offline_clear))
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(
                        progress = { p.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.map_offline_progress,
                                p.done,
                                p.total,
                                p.percent,
                            ) + if (p.failed > 0) {
                                stringResource(R.string.map_offline_failed, p.failed) +
                                    // 带上失败原因（HTTP 状态码/异常名），
                                    // 否则「全失败」时只能瞎猜。
                                    (p.lastError?.let {
                                        stringResource(R.string.map_offline_err, it)
                                    } ?: "")
                            } else {
                                ""
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                        OutlinedButton(onClick = { vm.cancelTileDownload() }) {
                            Text(stringResource(R.string.map_offline_cancel))
                        }
                    }
                }
            }

            HorizontalDivider()

            // ── 强制离线 ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.map_offline_only),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.map_offline_only_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = offlineOnly, onCheckedChange = { vm.setOfflineMapOnly(it) })
            }

            Text(
                text = stringResource(R.string.map_offline_tip),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
