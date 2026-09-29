package com.beian.tracker.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.beian.tracker.R
import com.beian.tracker.util.CrashLog

/**
 * 「崩溃日志」设置卡片。
 *
 * 存在的意义：用户报「会崩」时，开发者拿不到现场只能猜 ——
 * 而猜已经错过一次。把堆栈留在用户手机上、并能一键分享出来，
 * 才是真的能定位问题。
 *
 * 记录内容见 [CrashLog]：既抓 Java/Kotlin 异常堆栈，
 * 也从系统侧取进程退出原因（能区分「崩溃」和「被低内存杀」）。
 */
@Composable
fun CrashLogSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // 每次进页面重新统计（用户可能刚清空或刚崩过）
    var files by remember { mutableStateOf(CrashLog.readAll(context)) }
    var showContent by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.crash_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.crash_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (files.isEmpty()) stringResource(R.string.crash_none)
                else stringResource(R.string.crash_count, files.size),
                style = MaterialTheme.typography.bodyMedium,
                color = if (files.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showContent = true },
                    enabled = files.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.crash_view))
                }
                OutlinedButton(
                    onClick = {
                        // 直接分享纯文本，不依赖任何文件权限：
                        // 内容由 CrashLog 读出来拼成字符串，
                        // 避免 FileProvider 在不同 ROM 上的授权差异。
                        val text = CrashLog.readAllAsText(context)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "花花动态 崩溃日志")
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(
                            Intent.createChooser(intent, null)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    enabled = files.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.crash_share))
                }
                OutlinedButton(
                    onClick = { confirmClear = true },
                    enabled = files.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.crash_clear))
                }
            }
        }
    }

    // ── 内容查看弹窗 ────────────────────────────────────────────────
    if (showContent) {
        val text = remember { CrashLog.readAllAsText(context) }
        AlertDialog(
            onDismissRequest = { showContent = false },
            title = { Text(stringResource(R.string.crash_title)) },
            text = {
                // ⚠️ 必须限高 + 可滚动。
                // 崩溃堆栈动辄上百行，不限高会把弹窗撑出屏幕，
                // 按钮被挤到看不见的地方 —— 那就关不掉了。
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showContent = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    // ── 清空确认 ────────────────────────────────────────────────────
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.crash_clear)) },
            text = { Text(stringResource(R.string.crash_confirm_clear)) },
            confirmButton = {
                TextButton(onClick = {
                    CrashLog.clear(context)
                    files = CrashLog.readAll(context)
                    confirmClear = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.crash_cleared),
                        Toast.LENGTH_SHORT,
                    ).show()
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
