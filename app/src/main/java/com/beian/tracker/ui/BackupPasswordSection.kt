package com.beian.tracker.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beian.tracker.R
import com.beian.tracker.util.BackupCipher
import com.beian.tracker.util.SettingsStore
import kotlinx.coroutines.launch

/**
 * 数据包密码设置。
 *
 * 背景：导出的 .hh 包要经微信 / QQ 转发，明文的话聊天记录里就是完整轨迹。
 * 这里让用户设一个**双方约定**的密码，导出用它加密、对方导入时输同一个。
 *
 * ⚠️ 为什么密码必须两边一样：这是对称加密的硬约束，不是实现选择。
 *    所以文案里要写清楚「把这个密码告诉对方」—— 否则对方拿到包
 *    会卡在输密码那一步，还不知道该输什么。
 *
 * ⚠️ 忘了无法找回：我们不存第二份，也无法从密文推算。
 *    这一条必须**事前**写在界面上，不能等用户丢了数据才发现。
 *
 * 没设密码时导出仍能走通（退回内置口令加密），见 BackupCipher 的类注释。
 */
@Composable
fun BackupPasswordSection(vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val saved by vm.backupPassword.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    // 已保存的密码回填到输入框：进来就能看见当前用的是哪个，
    // 免得用户忘了自己设过没有、又重设一遍。
    //
    // ⚠️ [saved] 不再会是空串（首次启动就自动生成，见 SettingsStore），
    //    所以这里回填的一定是个真密码。
    LaunchedEffect(saved) {
        if (input.isEmpty()) input = saved
    }

    val tooShort = input.isNotEmpty() && input.length < SettingsStore.MIN_BACKUP_PASSWORD
    val changed = input != saved

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_backup_password),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_backup_password_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = input,
                onValueChange = { if (it.length <= SettingsStore.MAX_BACKUP_PASSWORD) input = it },
                singleLine = true,
                // ⚠️ 默认打码。这类 App 的使用场景本来就是「身边可能有人」。
                visualTransformation = if (showPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                // 密码用等宽字体：随机密码是大小写与数字混排，
                // 等宽下抄写不容易看串行。
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.settings_backup_password_hint)) },
                isError = tooShort,
                supportingText = if (tooShort) {
                    {
                        Text(
                            stringResource(
                                R.string.settings_backup_password_too_short,
                                SettingsStore.MIN_BACKUP_PASSWORD,
                            ),
                        )
                    }
                } else {
                    null
                },
            )

            // ── 生成 / 显示 ───────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        // 随机密码用 SecureRandom（见 BackupCipher.generatePassword）。
                        // 生成后直接明文显示 —— 用户必须能看见它才好告诉对方。
                        input = BackupCipher.generatePassword()
                        showPassword = true
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_backup_password_generate))
                }
                OutlinedButton(
                    onClick = { showPassword = !showPassword },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            if (showPassword) R.string.settings_backup_password_hide
                            else R.string.settings_backup_password_show,
                        ),
                    )
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        val ok = vm.setBackupPassword(input)
                        Toast.makeText(
                            context,
                            if (ok) {
                                context.getString(R.string.settings_backup_password_saved)
                            } else {
                                context.getString(
                                    R.string.settings_backup_password_too_short,
                                    SettingsStore.MIN_BACKUP_PASSWORD,
                                )
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                enabled = changed && !tooShort,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_backup_password_save))
            }

            // ── 当前状态 ──────────────────────────────────────────────────────
            //
            // ⚠️ 这里不再有「未设密码」分支：首次启动就自动生成了一个，
            //    留空保存也会被替换成随机值，所以密码**永远存在**。
            Text(
                text = stringResource(R.string.settings_backup_password_on),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            // 忘了无法找回 —— 事前写清楚，别让用户丢了数据才知道。
            Text(
                text = stringResource(R.string.settings_backup_password_forget),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )

            // ⚠️ 这个密码是自动生成的，用户很可能从没注意过它。
            //    必须明确告诉他「导出前要把这串告诉对方」，
            //    否则对方拿到包会卡在输密码那一步，也不知道该问谁。
            Text(
                text = stringResource(R.string.settings_backup_password_share_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 「重新生成」而不是「清除」：清除后密码为空 = 退回内置口令
            // （无效加密），这条路不再开放。要换密码只能换一个新的随机值。
            //
            // ⚠️ 换密码同样不可逆：用它导出、对方还没导入的包会永久打不开。
            //    所以仍然要二次确认。
            TextButton(onClick = { confirmClear = true }) {
                Text(stringResource(R.string.settings_backup_password_regenerate))
            }
        }
    }

    // ── 重新生成密码：二次确认 ────────────────────────────────────────────────
    //
    // ⚠️ 必须确认。换掉之后，凡是「用旧密码加密、但还没导入」的包
    //    就永久打不开了 —— 旧密码不再存在于任何地方。
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.settings_backup_password_clear_title)) },
            text = { Text(stringResource(R.string.settings_backup_password_clear_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        scope.launch {
                            // 传空串 = 生成一个新的随机密码（见 setBackupPassword）。
                            val fresh = BackupCipher.generatePassword()
                            vm.setBackupPassword(fresh)
                            input = fresh
                            showPassword = true
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_backup_password_regenerated),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.settings_backup_password_regenerate),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.backup_cancel))
                }
            },
        )
    }
}
