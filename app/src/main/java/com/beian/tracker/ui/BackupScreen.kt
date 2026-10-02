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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beian.tracker.R
import androidx.compose.material3.OutlinedButton
import com.beian.tracker.util.BackupCipher
import com.beian.tracker.util.BackupCodec
import com.beian.tracker.util.BackupSharer
import com.beian.tracker.util.TimeUtil
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 数据互通页：
 * - 导出：把本机数据加密后写成 .hh 文件，通过任意方式发给对方
 * - 导入：两条路 —— 在聊天里直接点开 .hh（推荐），或从 App 里选文件；
 *   两条路都汇到同一个「填昵称 → 确认」弹窗
 */
@Composable
fun BackupScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sources by vm.importedSources.collectAsStateWithLifecycle()
    val myNickname by vm.myNickname.collectAsStateWithLifecycle()
    val backupPassword by vm.backupPassword.collectAsStateWithLifecycle()

    var nicknameInput by remember { mutableStateOf(myNickname) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var nicknameForImport by remember { mutableStateOf("") }
    /**
     * 已经读进内存的包内容。
     *
     * 外部（微信）点进来的 content:// Uri 读权限是系统临时授予的，
     * 拖到用户点「导入」时再去读可能已经失效，所以一拿到就缓存下来。
     */
    var externalText by remember { mutableStateOf<String?>(null) }

    /** 导入弹窗里填的密码。 */
    var importPassword by remember { mutableStateOf("") }

    /**
     * 当前待导入的包是否需要密码。
     *
     * 明文旧包不需要，就不该显示密码框 —— 否则用户对着一串乱码
     * 猜「是不是要输密码」。
     */
    var importNeedsPassword by remember { mutableStateOf(false) }

    var confirmClearLocal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 导出（备选路径）：用户自己挑位置存盘
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                busy = true
                // ⚠️ 取数和写盘都要在 IO 上。
                //
                // buildBackupJson 会遍历全部轨迹点/事件并编码成 JSON，
                // openOutputStream().write() 又是同步阻塞写 —— 备份包几 MB 时
                // 这两步叠在一起能把主线程占住好几秒（ANR 的典型成因）。
                // 只有 Toast 回主线程。
                val json = withContext(Dispatchers.IO) {
                    vm.buildBackupJson(days = 0)
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray())
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "已导出", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "导出失败：${e.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
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
        importPassword = ""
        // ⚠️ 这里**不再**预读昵称。
        //
        // 加密后昵称在密文里，要读出它得先有密码 —— 而这一步
        // 恰恰是「等用户填密码」。所以顺序反过来了：
        // 先弹框让用户输密码，输对了再解密。
        //
        // 只判断「要不要密码」，决定弹框里显不显示密码框。
        //
        // ⚠️ 必须切到 IO：读文件是同步阻塞操作，包几 MB 时
        //    留在主线程会卡住界面。
        scope.launch(Dispatchers.IO) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalArgumentException("无法读取文件")
                text
            }.onSuccess { text ->
                withContext(Dispatchers.Main) {
                    externalText = text
                    importNeedsPassword = vm.needsPassword(text)
                    // 明文旧包没有密码可输，直接把它的昵称解析出来预填
                    if (!importNeedsPassword) {
                        runCatching { vm.peekImportNickname(text, "") }
                            .onSuccess { name ->
                                if (nicknameForImport.isBlank()) nicknameForImport = name
                            }
                    }
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    pendingImportUri = null
                    externalText = null
                    Toast.makeText(
                        context,
                        e.message ?: "无法读取该文件",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    // ── 外部点开 .hh 文件送进来的 Uri ──────────────────────────────────────────
    //
    // 用户在微信里点开对方发的 .hh、选「用其他应用打开 → 花花动态」时，
    // MainActivity 会把 Uri 放进 ViewModel，这里接过来走同一套弹窗流程。
    // 两条入口（App 内选文件 / 外部点文件）最终汇到 pendingImportUri，
    // 后续的填昵称、确认导入只有一份实现。
    val externalUri by vm.externalImport.collectAsStateWithLifecycle()
    LaunchedEffect(externalUri) {
        val uri = externalUri ?: return@LaunchedEffect
        // ⚠️ 先清再处理：不清的话配置变化（旋转屏幕）会重复触发一次导入。
        vm.consumeExternalImport()
        pendingImportUri = uri
        nicknameForImport = ""
        importPassword = ""
        // ⚠️ content:// 的读权限是系统临时授予的，只在本次 intent 的范围内有效 ——
        //    不能拖到用户点「导入」时才读，那时权限可能已失效（SecurityException）。
        //    所以一拿到就立刻读进内存。这里只读文件、不解密（解密要等用户填密码）。
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalArgumentException("无法读取文件")
            }.onSuccess { text ->
                withContext(Dispatchers.Main) {
                    externalText = text
                    importNeedsPassword = vm.needsPassword(text)
                    if (!importNeedsPassword) {
                        // 明文旧包：可直接读昵称预填
                        runCatching { vm.peekImportNickname(text, "") }
                            .onSuccess { name ->
                                if (nicknameForImport.isBlank()) nicknameForImport = name
                            }
                    }
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    pendingImportUri = null
                    externalText = null
                    Toast.makeText(
                        context,
                        e.message ?: "无法读取该文件",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
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

                // 没设密码时给一句明确提示：包仍然会加密，但用的是内置口令，
                // 强度只够「防随手打开看一眼」。用户有权知道这一点，
                // 而不是以为默认就是安全的。
                if (backupPassword.isBlank()) {
                    Text(
                        text = stringResource(R.string.backup_export_no_password_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.backup_export_password_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // 主路径：生成后直接弹分享面板。
                //
                // 导出这个动作的目的就是「发给对方」，所以默认就该一步到
                // 分享面板 —— 原来是先弹系统存储选择器、存完还要用户自己
                // 去文件管理器找文件再分享，多绕两步还容易找不到。
                Text(
                    text = stringResource(R.string.backup_export_share_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        // 取数 + 写盘都在 IO 上；只有弹分享面板回主线程
                        // （startActivity 必须在主线程调）。
                        scope.launch {
                            try {
                                busy = true
                                // 取数在 IO 上：要遍历全部轨迹点/事件并编码
                                val json = withContext(Dispatchers.IO) {
                                    vm.buildBackupJson(days = 0)
                                }
                                val name = backupFileName()

                                // ⚠️ 写盘和分享要分开调度，不能一起丢进 IO。
                                //
                                // 写文件是阻塞 IO，必须在 IO 上；
                                // 但分享内部要调 startActivity，而 Android
                                // 要求它**必须在主线程**调用 ——
                                // 一起塞进 withContext(IO) 会在部分 ROM 上抛
                                // CalledFromWrongThreadException，或者静默不动。
                                val file = withContext(Dispatchers.IO) {
                                    BackupSharer.writeOnly(context, json, name)
                                }
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.backup_export_done),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                BackupSharer.shareFile(context, file)
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    "导出失败：${e.message}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.backup_export_share))
                }

                // 备选路径：用户自己挑位置存盘，之后再手动发送。
                // 保留它是因为「发给谁还没定，先存下来」也是常见需求。
                Text(
                    text = stringResource(R.string.backup_export_save_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        exportLauncher.launch(backupFileName())
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.backup_export_save))
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
                // 主路径：让用户直接在微信/QQ 里点开文件。
                // 这是最省事的做法 —— 文件在哪、叫什么都不用管，
                // 也不必进 App 里一级级翻系统文件选择器。
                Text(
                    text = stringResource(R.string.backup_import_way_chat),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.backup_import_way_chat_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 备选路径：文件已经存到手机里了，用系统的文件选择器挑。
                Button(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.backup_import_action))
                }
            }
        }

        // ── 清空本机数据 ──────────────────────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.backup_clear_local),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.backup_clear_local_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { confirmClearLocal = true },
                    enabled = !busy && !confirmClearLocal,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(stringResource(R.string.backup_clear_local_action))
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

    // ── 清空本机数据：二次确认 ─────────────────────────────────────────────────
    if (confirmClearLocal) {
        AlertDialog(
            onDismissRequest = { confirmClearLocal = false },
            title = { Text(stringResource(R.string.backup_clear_local_title)) },
            text = { Text(stringResource(R.string.backup_clear_local_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearLocal = false
                        scope.launch {
                            vm.clearLocalData()
                            Toast.makeText(
                                context,
                                context.getString(R.string.backup_clear_local_done),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.backup_clear_local_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearLocal = false }) {
                    Text(stringResource(R.string.backup_cancel))
                }
            },
        )
    }

    // ── 导入弹窗：输密码 + 昵称 ───────────────────────────────────────────────
    //
    // ⚠️ 顺序上密码在前：没有正确密码，包里的昵称根本读不出来
    //    （它在密文里），所以不能再像以前那样自动预填。
    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text(stringResource(R.string.backup_import_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backup_import_dialog_hint))

                    // 需要密码的包才显示密码框：旧版明文包不该让用户
                    // 对着一串乱码猜「是不是要密码」。
                    if (importNeedsPassword) {
                        OutlinedTextField(
                            value = importPassword,
                            onValueChange = { importPassword = it },
                            singleLine = true,
                            // 导入时默认**明文显示**密码：
                            // 这是个「一次性的、会失败的」输入 ——
                            // 打错了没有第二次机会（包已经存下来了），
                            // 打码只会让人看不清自己输错在哪一位。
                            label = { Text(stringResource(R.string.backup_import_password)) },
                            placeholder = { Text(stringResource(R.string.backup_import_password_hint)) },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    OutlinedTextField(
                        value = nicknameForImport,
                        onValueChange = { nicknameForImport = it.take(24) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.backup_import_nickname_optional)) },
                        placeholder = { Text(stringResource(R.string.backup_import_nickname_example)) },
                        supportingText = {
                            Text(stringResource(R.string.backup_import_nickname_hint))
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = uri
                        val cached = externalText
                        // 昵称留空时用「对方」兜底 —— 导入后还能在来源列表里改。
                        val name = nicknameForImport.trim().ifBlank { "对方" }
                        val pwd = importPassword
                        pendingImportUri = null
                        externalText = null
                        importPassword = ""
                        // 同上：文件读 + 解密 + JSON 解析都是同步重活，必须切 IO。
                        // 导入时的包更大（含全部轨迹点），留在主线程必然 ANR。
                        scope.launch(Dispatchers.IO) {
                            try {
                                // 优先用已缓存的内容（外部点进来的场景），
                                // 没有再按 Uri 读（App 内选文件的场景，
                                // 那个 Uri 的权限是持久有效的）。
                                val text = cached
                                    ?: context.contentResolver.openInputStream(target)
                                        ?.bufferedReader()
                                        ?.use { it.readText() }
                                    ?: throw IllegalArgumentException("无法读取文件")
                                val src = vm.importBackupJson(text, name, pwd)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        "已导入「${src.nickname}」",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        "导入失败：${e.message}",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
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

/**
 * 导出文件名：hh_<日期>.hh
 *
 * 用日期而不是时间戳：对方收到时一眼能看出是哪天的数据，
 * 同名再次导出时覆写即可（旧文件本来也没用）。
 */
private fun backupFileName(): String =
    "hh_${TimeUtil.dayKey().replace("-", "")}${BackupCodec.EXT}"
