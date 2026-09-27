package com.beian.tracker.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beian.tracker.R
import com.beian.tracker.util.TimeUtil
import kotlinx.coroutines.launch

/**
 * 数据互通页：
 * - 导出：把本机数据写成 .beian 文件，通过任意方式发给对方
 * - 导入：选择对方发来的文件，填昵称，导入后即可查看
 */
@Composable
fun BackupScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sources by vm.importedSources.collectAsStateWithLifecycle()
    val myNickname by vm.myNickname.collectAsStateWithLifecycle()

    var nicknameInput by remember { mutableStateOf(myNickname) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var nicknameForImport by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 导出：先写临时文件，再走分享
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                busy = true
                val json = vm.buildBackupJson(days = 0)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray())
                }
                Toast.makeText(context, "已导出", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    // 导入：读文件内容
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingImportUri = uri
        nicknameForImport = ""
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.backup_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        // ── 我的昵称 ──────────────────────────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.backup_my_nickname),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.backup_my_nickname_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = nicknameInput,
                        onValueChange = { nicknameInput = it.take(24) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("例如：宝贝") },
                    )
                    Button(
                        onClick = {
                            vm.setMyNickname(nicknameInput.trim())
                            Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text(stringResource(R.string.backup_save))
                    }
                }
            }
        }

        // ── 导出 ──────────────────────────────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.backup_export),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.backup_export_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        val stamp = TimeUtil.dayKey().replace("-", "")
                        exportLauncher.launch("beian_${stamp}.beian")
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.backup_export_action))
                }
            }
        }

        // ── 导入 ──────────────────────────────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.backup_import),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.backup_import_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.backup_import_action))
                }
            }
        }

        // ── 已导入的对方数据 ──────────────────────────────────────────────────
        if (sources.isNotEmpty()) {
            Text(
                text = stringResource(R.string.backup_imported),
                style = MaterialTheme.typography.titleSmall,
            )
            sources.forEach { s ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = s.nickname,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "${s.firstDay} ~ ${s.lastDay}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "${s.pointCount} 个定位点 · ${s.eventCount} 条事件",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    vm.deleteSource(s.sourceId)
                                    Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                                }
                            },
                        ) {
                            Text(stringResource(R.string.backup_delete))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.size(24.dp))
    }

    // ── 导入时填昵称 ──────────────────────────────────────────────────────────
    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text(stringResource(R.string.backup_import_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backup_import_dialog_hint))
                    OutlinedTextField(
                        value = nicknameForImport,
                        onValueChange = { nicknameForImport = it.take(24) },
                        singleLine = true,
                        placeholder = { Text("例如：宝贝") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = uri
                        val name = nicknameForImport.trim().ifBlank { "对方" }
                        pendingImportUri = null
                        scope.launch {
                            try {
                                val text = context.contentResolver.openInputStream(target)
                                    ?.bufferedReader()
                                    ?.use { it.readText() }
                                    ?: throw IllegalArgumentException("无法读取文件")
                                val src = vm.importBackupJson(text, name)
                                Toast.makeText(
                                    context,
                                    "已导入「${src.nickname}」",
                                    Toast.LENGTH_LONG,
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    "导入失败：${e.message}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.backup_import_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) {
                    Text(stringResource(R.string.backup_cancel))
                }
            },
        )
    }
}
