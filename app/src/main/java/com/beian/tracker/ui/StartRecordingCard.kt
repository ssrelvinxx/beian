package com.beian.tracker.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beian.tracker.R
import com.beian.tracker.service.TrackService
import com.beian.tracker.util.PermissionCheck

/**
 * 报备页顶部的「开始记录」卡片。
 *
 * 只有「开始」，没有「停止」—— 记录一旦开启就一直在后台跑，
 * 不需要用户惦记着关掉它。想真正停下来的话，去系统设置里关掉本应用。
 *
 * 点「开始记录」时先逐项检查权限：
 *  - 定位（必需）
 *  - 通知（Android 13+ 必需，前台服务的常驻通知没了会被系统很快回收）
 *  - 后台定位（建议，缺了息屏就断点）
 *
 * 缺哪项就申请哪项，全齐了才真正启动服务。这样不会出现
 * 「点了开始、界面显示在记录、其实什么都没采到」。
 *
 * @param onStarted 服务成功启动后的回调（用于让地图等组件刷新）
 */
@Composable
fun StartRecordingCard(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    onStarted: () -> Unit = {},
) {
    val context = LocalContext.current
    val tracking by vm.trackingEnabled.collectAsStateWithLifecycle()

    // 权限状态会随授权变化，用一个自增计数强制重组时重查
    var permissionRevision by remember { mutableIntStateOf(0) }
    val missingPermissions = remember(permissionRevision) {
        PermissionCheck.missing(context)
    }
    val allGranted = missingPermissions.isEmpty()
    val hasBackground = remember(permissionRevision) {
        PermissionCheck.hasBackgroundLocation(context)
    }

    // 记录用户是否已经同意继续（缺后台定位时用来决定显示哪段提示）
    var askedBackground by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // 无论结果如何都重查一次 —— 用户可能只同意了一部分
        permissionRevision++
    }

    /** 真正启动采集：权限齐全后调用。 */
    fun startTracking() {
        vm.setTracking(true)
        TrackService.start(context)
        onStarted()
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = if (tracking) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                // 已经在记录
                tracking -> {
                    Text(
                        text = stringResource(R.string.report_recording_on),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (!hasBackground) {
                        Text(
                            text = stringResource(R.string.report_perm_background_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 权限没齐：先说明缺什么，再让用户点
                !allGranted -> {
                    Text(
                        text = stringResource(R.string.report_perm_needed),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.report_perm_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            // 先申请普通权限（定位 + 通知）
                            permissionLauncher.launch(missingPermissions.toTypedArray())
                            permissionRevision++
                        },
                    ) {
                        Text(stringResource(R.string.report_perm_go_settings))
                    }
                }

                // 权限齐全，可以开始
                else -> {
                    Button(
                        onClick = {
                            if (hasBackground || askedBackground) {
                                startTracking()
                            } else {
                                // 先提示「始终允许」的重要性，再拉起系统设置。
                                // Android 11+ 后台定位不能和前台定位一起申请，
                                // 只能引导到应用详情页让用户手选。
                                askedBackground = true
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null),
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                                // 无论如何都先把采集开起来，后台定位是「更好」而非「必须」
                                startTracking()
                            }
                        },
                    ) {
                        Text(stringResource(R.string.report_start_recording))
                    }
                    if (askedBackground && !hasBackground) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.report_perm_background_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { permissionRevision++ }) {
                                Text(stringResource(R.string.report_perm_go_settings))
                            }
                        }
                    }
                }
            }
        }
    }
}
