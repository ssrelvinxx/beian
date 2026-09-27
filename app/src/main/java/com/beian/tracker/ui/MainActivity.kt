package com.beian.tracker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.beian.tracker.R

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
}