package com.beian.tracker.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beian.tracker.R
import com.beian.tracker.util.ApkInstaller
import com.beian.tracker.util.TimeUtil

/**
 * 设置页里的「检查更新」区块。
 *
 * 发现新版本时弹出对话框，展示版本号与更新说明，可一键下载并安装。
 * 注意：Android 不允许静默安装，下载完成后会拉起系统安装界面，由用户确认。
 */
@Composable
fun UpdateSection(vm: MainViewModel) {
    val context = LocalContext.current
    val state by vm.updateState.collectAsStateWithLifecycle()
    val autoCheck by vm.autoCheckUpdate.collectAsStateWithLifecycle()
    val allowPre by vm.allowPrerelease.collectAsStateWithLifecycle()
    val lastCheck by vm.lastUpdateCheckAt.collectAsStateWithLifecycle()

    val available = state as? UpdateUiState.Available

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(stringResource(R.string.update_section_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = stringResource(R.string.update_current, vm.currentVersion),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { vm.checkUpdate(silent = false) },
                    enabled = state !is UpdateUiState.Checking,
                ) {
                    Text(
                        stringResource(
                            if (state is UpdateUiState.Checking) R.string.update_checking_btn
                            else R.string.update_check_btn,
                        ),
                    )
                }
            }

            // 状态提示
            when (val s = state) {
                is UpdateUiState.Checking ->
                    Text(
                        stringResource(R.string.update_connecting),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                is UpdateUiState.Latest ->
                    Text(
                        stringResource(R.string.update_latest, s.current),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                is UpdateUiState.Error ->
                    Text(
                        s.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                is UpdateUiState.Available ->
                    Text(
                        stringResource(R.string.update_found, s.info.version),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                else -> Unit
            }

            if (lastCheck > 0) {
                Text(
                    text = stringResource(R.string.update_last_check, TimeUtil.dateTime(lastCheck)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            // 自动检查
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.update_auto_check), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.update_auto_check_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoCheck, onCheckedChange = { vm.setAutoCheckUpdate(it) })
            }

            // 预发布
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.update_prerelease), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.update_prerelease_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = allowPre, onCheckedChange = { vm.setAllowPrerelease(it) })
            }
        }
    }

    // ── 新版本弹窗 ────────────────────────────────────────────────────────────
    available?.let { s ->
        val info = s.info
        // 在 Composable 上下文先取出字符串：onClick 里不能调 stringResource
        val needPermissionText = stringResource(R.string.update_need_permission)
        AlertDialog(
            onDismissRequest = { vm.dismissUpdate() },
            title = { Text(stringResource(R.string.update_dialog_title, info.version)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.update_dialog_current, s.current),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (info.apkSize > 0) {
                        Text(
                            text = stringResource(R.string.update_dialog_size, formatSize(info.apkSize)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (info.prerelease) {
                        Text(
                            text = stringResource(R.string.update_dialog_prerelease),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (info.notes.isNotBlank()) {
                        HorizontalDivider()
                        Text(
                            text = stringResource(R.string.update_notes),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = info.notes.trim(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (info.apkUrl.isBlank()) {
                        HorizontalDivider()
                        Text(
                            text = stringResource(R.string.update_no_apk),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!ApkInstaller.canInstall(context)) {
                            Toast.makeText(
                                context,
                                needPermissionText,
                                Toast.LENGTH_LONG,
                            ).show()
                            ApkInstaller.openInstallPermissionSettings(context)
                            return@TextButton
                        }
                        ApkInstaller.downloadAndInstall(
                            context = context,
                            url = info.apkUrl,
                            version = info.version,
                        )
                        vm.dismissUpdate()
                    },
                    enabled = info.apkUrl.isNotBlank(),
                ) {
                    Text(stringResource(R.string.update_install))
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissUpdate() }) {
                    Text(stringResource(R.string.update_later))
                }
            },
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
