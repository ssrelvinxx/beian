package com.beian.tracker.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.beian.tracker.R
import com.beian.tracker.util.UsageStatsReader

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val locationGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val usageGranted = UsageStatsReader.hasPermission(context)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        // 地图说明
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_map),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.settings_map_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 权限
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = stringResource(R.string.settings_permissions), style = MaterialTheme.typography.titleMedium)

                PermRow(
                    label = stringResource(R.string.settings_perm_location),
                    granted = locationGranted,
                    onGrant = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    },
                )
                PermRow(
                    label = stringResource(R.string.settings_perm_usage),
                    granted = usageGranted,
                    onGrant = {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    },
                )
            }
        }

        // ── 采集间隔 ──────────────────────────────────────────────────────────
        // 这个设置以前只存在于代码里（setIntervalSec 没有任何 UI 入口），
        // 用户永远只能用默认值，也就没法按自己的耗电/精度偏好调整。
        IntervalSection(vm)

        // ── 数据保留 ──────────────────────────────────────────────────────────
        // 轨迹点只增不减，这里给用户一个能自己控制的清理开关。
        // 和「采集间隔」并列：都属于「数据怎么攒、攒多久」的偏好。
        RetentionSection(vm)

        // ── 数据包密码 ────────────────────────────────────────────────────────
        // 导出包要经微信转发，明文等于把轨迹摊在聊天记录里。
        // 密码是「双方约定」的那一个，接收方导入时输同一个。
        BackupPasswordSection(vm)

        // ── 后台常驻 ──────────────────────────────────────────────────────────
        BackgroundSection()

        // 导出 / 导入已迁到独立的「数据」页（底部导航第 4 个），这里不再重复。

        // ── 检查更新 ──────────────────────────────────────────────────────────
        UpdateSection(vm)

        // ── 崩溃日志 ──────────────────────────────────────────────────────────
        // 放设置页最下面：平时不用看，出问题时才来找。
        CrashLogSection()

        // ── 关于 ──────────────────────────────────────────────────────────────
        Text(
            text = stringResource(R.string.settings_about, com.beian.tracker.BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.settings_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermRow(label: String, granted: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        if (granted) {
            Text(
                text = stringResource(R.string.settings_granted),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            OutlinedButton(onClick = onGrant) {
                Text(text = stringResource(R.string.settings_grant))
            }
        }
    }
}