package com.beian.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

/** 可选的采集间隔档位（秒）。 */
private val INTERVAL_OPTIONS = listOf(30, 60, 120, 300, 600)

/**
 * 采集间隔设置。
 *
 * ⚠️ 这个区块是补上去的：`SettingsStore.setIntervalSec()` 一直存在，
 * 但**没有任何界面调用它**，等于用户永远只能用默认值。
 * 而已默认值又是耗电与提示最明显的档位，没有入口就完全没得选。
 *
 * 各档位的实际取舍（供选择时参考，已标注在界面文案里）：
 *   · 30s / 60s —— 轨迹细，但定位请求频繁，耗电高、系统提示明显
 *   · 120s      —— 折中：步行仍能看出走向，骑行以上会开始拉直
 *   · 300s/600s —— 只留大轮廓，适合「记录去过哪」而非「怎么走的」
 */
@Composable
fun IntervalSection(vm: MainViewModel, modifier: Modifier = Modifier) {
    val current by vm.intervalSec.collectAsStateWithLifecycle()

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_interval),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_interval_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 横向放不下 5 个，只列出常用档；当前值若不在此列仍会显示为选中态
                INTERVAL_OPTIONS.forEach { sec ->
                    FilterChip(
                        selected = current == sec,
                        onClick = { vm.setInterval(sec) },
                        label = { Text(intervalLabel(sec)) },
                    )
                }
            }

            Text(
                text = stringResource(R.string.settings_interval_current, intervalLabel(current)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 秒 → 展示文案（60 → 「1 分」，120 → 「2 分」，30 → 「30 秒」）。 */
@Composable
private fun intervalLabel(sec: Int): String =
    if (sec < 60) {
        stringResource(R.string.settings_interval_sec, sec)
    } else {
        stringResource(R.string.settings_interval_min, sec / 60)
    }
