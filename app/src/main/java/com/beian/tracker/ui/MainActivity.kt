package com.beian.tracker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.beian.tracker.R
import com.beian.tracker.service.TrackService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BeiAnTheme {
                MainScreen()
            }
        }
    }
}

private data class TabItem(val labelRes: Int, val icon: ImageVector)

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val tabs = listOf(
        TabItem(R.string.tab_report, Icons.Filled.Today),
        TabItem(R.string.tab_track, Icons.Filled.LocationOn),
        TabItem(R.string.tab_history, Icons.Filled.DateRange),
        TabItem(R.string.tab_backup, Icons.Filled.Sync),
        TabItem(R.string.tab_settings, Icons.Filled.Settings),
    )
    var index by remember { mutableIntStateOf(0) }

    // 启动时静默检查一次更新：只有发现新版本才会弹窗提示
    val autoCheck by vm.autoCheckUpdate.collectAsStateWithLifecycle()
    LaunchedEffect(autoCheck) {
        if (autoCheck) vm.checkUpdate(silent = true)
    }

    // 采集开关是「用户意愿」，不代表服务真在跑。
    // 进程被杀掉后重开 App，之前只有进轨迹页才会重新拉起服务 ——
    // 停在报备页就一直没人采集，界面却显示「采集中」。
    // 这里在 App 起来时确认一次，让开关和现实一致。
    val context = LocalContext.current
    val trackingEnabled by vm.trackingEnabled.collectAsStateWithLifecycle()
    LaunchedEffect(trackingEnabled) {
        if (trackingEnabled) TrackService.ensureRunning(context)
    }

    // 「使用情况访问」是在系统设置里授权的，回来时 ViewModel 不会重建，
    // 不主动重读的话，用户授权完仍然看到「未授权」提示。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshUsageAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, tab ->
                    NavigationBarItem(
                        selected = index == i,
                        onClick = { index = i },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (index) {
            0 -> ReportScreen(vm, modifier)
            1 -> TrackScreen(vm, modifier)
            2 -> HistoryScreen(vm, modifier)
            3 -> BackupScreen(vm, modifier)
            else -> SettingsScreen(vm, modifier)
        }
    }

    // 新版本弹窗挂在这一层：启动时的静默检查一旦发现新版本，
    // 不管用户当前停在哪个 tab 都能看到提示。
    // 之前它长在设置页的 UpdateSection 里，等于「检查到了也不提示」。
    UpdateAvailableDialog(vm)
}