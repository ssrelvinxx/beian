package com.beian.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.beian.tracker.R
import com.beian.tracker.util.BackgroundGuard
import kotlinx.coroutines.delay

/**
 * 「后台常驻」设置卡片。
 *
 * 息屏后系统会冻结/杀掉后台应用，导致轨迹断点和事件丢失。
 * 这里提供检测 + 一键跳转，让用户手动放行。
 */
@Composable
fun BackgroundSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // 每次进入页面重新检测（用户可能刚从系统设置返回）
    var batteryOk by remember { mutableStateOf(BackgroundGuard.isIgnoringBatteryOptimizations(context)) }

    // 采集服务此刻是否真的在跑。
    // 用户最容易困惑的就是「我到底有没有在后台记录」——
    // 只看开关状态会骗人（开关开着但服务被系统杀了）。
    // 挂生命周期监听：从系统设置回来时重新确认一次。
    var serviceRunning by remember { mutableStateOf(BackgroundGuard.isTrackingServiceRunning(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // 回到前台立刻重查一次。
    //
    // ON_START 与 ON_RESUME 都挂：去系统设置改完电池白名单回来，
    // 两者至少有一个会被派发（个别 ROM 只派发其中一个）。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) {
                serviceRunning = BackgroundGuard.isTrackingServiceRunning(context)
                batteryOk = BackgroundGuard.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ⚠️ 只靠生命周期回调会漏掉一类变化：**在本页停留时状态被外部改变**。
    //
    // 例如从下拉快捷开关、其他应用的电池管理、或系统自动把服务拉起/杀掉 ——
    // 这些都不经过本 App 的 pause/resume，回调不会响，
    // 页面就一直显示「未放行 / 服务未运行」的旧结论。
    //
    // 所以可见期间做一个轻量轮询兜住它。只在 RESUMED 跑
    // （切页 / 退后台自动挂起），且仅在值真的变了才赋值，避免无谓重组。
    //
    // 双频率，理由：
    //   · 电池白名单（PowerManager，本地调用）每 2 秒查，便宜。
    //   · 服务是否在跑必须走 ActivityManager.getRunningServices，
    //     是跨进程 Binder 调用，2 秒一次不划算 —— 但它又**不能省**：
    //     进程内的 TrackService.isRunning() 标记在服务被系统杀掉时
    //     不会归 false（onDestroy 常常不执行），
    //     也就是「标记说在跑、其实已经死了」这种情况，
    //     恰恰只有系统查询能发现。所以放宽到 10 秒一次，
    //     既能察觉变化，又不至于常驻占用。
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var tick = 0
            while (true) {
                // 每轮都查电池白名单（本地调用）
                val b = BackgroundGuard.isIgnoringBatteryOptimizations(context)
                if (b != batteryOk) batteryOk = b

                // 每 5 轮（约 10 秒）做一次系统级的服务运行态核对
                if (tick % 5 == 0) {
                    val s = BackgroundGuard.isTrackingServiceRunning(context)
                    if (s != serviceRunning) serviceRunning = s
                }

                tick++
                delay(2000)
            }
        }
    }

    val vendor = BackgroundGuard.vendorLabel()
    val needsVendor = BackgroundGuard.needsVendorGuidance()

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_bg_title),
                style = MaterialTheme.typography.titleMedium,
            )
            // ── 服务真实状态 ──────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_bg_running),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        if (serviceRunning) {
                            R.string.settings_bg_running_on
                        } else {
                            R.string.settings_bg_running_off
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (serviceRunning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            Text(
                text = stringResource(R.string.settings_bg_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── 忽略电池优化 ──────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_bg_battery),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.settings_bg_battery_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (batteryOk) {
                    Text(
                        text = stringResource(R.string.settings_bg_battery_ok),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    OutlinedButton(
                        onClick = {
                            BackgroundGuard.requestIgnoreBatteryOptimizations(context)
                            // 用户可能立刻返回，下次重组时重新检测
                            batteryOk = BackgroundGuard.isIgnoringBatteryOptimizations(context)
                        },
                    ) {
                        Text(stringResource(R.string.settings_bg_battery_btn))
                    }
                }
            }

            // ── 厂商自启动（仅国产 ROM 显示）──────────────────────────────────
            if (needsVendor) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_bg_autostart),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.settings_bg_autostart_desc, vendor),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { BackgroundGuard.openAutoStartSettings(context) }) {
                        Text(stringResource(R.string.settings_bg_autostart_btn))
                    }
                }
            }

            Text(
                text = if (needsVendor) {
                    stringResource(R.string.settings_bg_vendor_note, vendor)
                } else {
                    stringResource(R.string.settings_bg_note)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 报备页顶部的后台常驻提醒条。
 *
 * 只在「还没加入电池优化白名单」时显示 —— 这是轨迹断点最常见的原因，
 * 用户不去设置页就永远发现不了。
 */
@Composable
fun BackgroundWarning(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 每次进页面重新检测
    var ok by remember { mutableStateOf(BackgroundGuard.isIgnoringBatteryOptimizations(context)) }

    if (ok) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .clickable {
                BackgroundGuard.requestIgnoreBatteryOptimizations(context)
                ok = BackgroundGuard.isIgnoringBatteryOptimizations(context)
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.bg_warn_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.bg_warn_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        Text(
            text = stringResource(R.string.bg_warn_action),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
