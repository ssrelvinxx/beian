package com.beian.tracker.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    // ⚠️ 必须监听生命周期。
    //
    // 原来 permissionRevision 只在**卡片的按钮**被点击时才自增。
    // 而权限有两种授予路径，只有一种会经过这里：
    //   ① 点卡片里的按钮 → 走 requestPermissions / 跳设置 → 计数会加；
    //   ② 用户自己去「系统设置 → 应用 → 权限」手动打开 → 不经过本组件，
    //      计数永远不加。
    //
    // 于是②的情况下，回来时 remember 缓存的仍是旧值，
    // 卡片继续显示「开始前需要先授权 / 去设置」——
    // 用户明明已经给了权限，界面却说没给。
    //
    // 授权一定发生在离开本 App 之后，回来必然有 ON_RESUME，
    // 所以在这里无条件重查一次就能覆盖。ON_START 一并加上，
    // 成本可忽略，且能覆盖部分 ROM 不派发 resume 的情况。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) {
                permissionRevision++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ⚠️ 申请用 requestable()，判定用 missing() —— 两者刻意分开。
    //
    // missing() 基于 required()（只要 COARSE），语义是「能不能跑起来」；
    // 申请时必须把 FINE 也带上，否则 Android 12+ 的弹窗不会出现
    // 「精确位置」选项，用户被默认成「大致位置」，精度只有 1~3 公里。
    val missingPermissions = remember(permissionRevision) {
        PermissionCheck.missing(context)
    }
    // 实际拉起系统弹窗时要申请的清单（含 FINE）
    val requestList = remember(permissionRevision) {
        PermissionCheck.requestable().filterNot { PermissionCheck.granted(context, it) }
    }
    val allGranted = missingPermissions.isEmpty()
    val hasFine = remember(permissionRevision) {
        PermissionCheck.hasFineLocation(context)
    }
    val hasBackground = remember(permissionRevision) {
        PermissionCheck.hasBackgroundLocation(context)
    }
    // 「使用情况访问」是特殊权限，不能用 requestPermissions 申请，只能引导去设置页。
    // 少了它 App 记录会整段消失（UsageStats 静默返回空），所以单独判断。
    val hasUsage = remember(permissionRevision) {
        PermissionCheck.hasUsageAccess(context)
    }

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
                            // 申请「精确 + 大致 + 通知」全套（见 requestable() 注释）。
                            // 缺哪项就补哪项，已授权的不用重复打扰。
                            val toRequest = requestList
                            if (toRequest.isNotEmpty()) {
                                permissionLauncher.launch(toRequest.toTypedArray())
                            }
                            permissionRevision++
                        },
                    ) {
                        Text(stringResource(R.string.report_perm_go_settings))
                    }
                }

                // 普通权限齐了，但「使用情况访问」没开。
                //
                // 不在这里直接开记录：没有它 App 记录一条都不会有，
                // 用户会以为「开始记录没用」。先把权限补上再放行。
                !hasUsage -> {
                    Text(
                        text = stringResource(R.string.report_perm_usage_needed),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.report_perm_usage_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            PermissionCheck.openUsageAccessSettings(context)
                            permissionRevision++
                        },
                    ) {
                        Text(stringResource(R.string.report_perm_go_settings))
                    }
                    // 允许用户跳过：不给这项权限仍能记录轨迹/屏幕/网络事件，
                    // 只是没有 App 使用记录。不该强行卡死。
                    TextButton(onClick = { startTracking() }) {
                        Text(stringResource(R.string.report_perm_usage_skip))
                    }
                }

                // 权限齐全，可以开始
                else -> {
                    Button(onClick = { startTracking() }) {
                        Text(stringResource(R.string.report_start_recording))
                    }
                    // 只拿到「大致位置」时提醒一句：精度只有 1~3 公里，
                    // 轨迹会明显偏差。这是可选引导，不拦着用户开始记录。
                    if (!hasFine) {
                        Text(
                            text = stringResource(R.string.report_perm_coarse_only),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable {
                                // 定位权限在应用详情页里改（Android 12+ 的
                                // 「精确/大致」开关就在那儿），不能再用弹窗申请
                                // （系统已把该权限标记为「用户手动选择」）。
                                openAppDetails(context)
                            },
                        )
                    }
                    // 缺后台定位时**只提示、不自动跳转**。
                    //
                    // 之前这里是「点开始记录就自动拉起系统设置页」，
                    // 而且因为 askedBackground 是 remember（Composable 重建即复位），
                    // 每次冷启动后第一次点都会跳 —— 用户反馈的
                    // 「不管给不给使用情况权限，点开始记录都跳系统设置页」就是这个。
                    //
                    // 自动跳转本身也很唐突：用户只是想开始记录，却被弹到系统页。
                    // 改为常驻提示 + 手动入口：想补就点「去设置」，不想补也能正常用。
                    if (!hasBackground) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.report_perm_background_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { openAppDetails(context) }) {
                                Text(stringResource(R.string.report_perm_go_settings))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 跳到本应用的「应用详情」系统页。
 *
 * 用它的场景：引导用户去开**后台定位**（Android 11+ 不能和前台定位一起申请，
 * 只能让用户到应用详情页 → 权限 → 位置 → 选「始终允许」）。
 *
 * 跳转失败（个别 ROM 没有这个 Activity）就静默忽略 ——
 * 这只是个可选引导，跳不过去不该影响主流程。
 */
private fun openAppDetails(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
