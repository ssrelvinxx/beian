package com.beian.tracker.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.movableContentOf
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

    /**
     * 外部送进来的数据包 Uri（在微信 / 文件管理器里点开 .hh）。
     *
     * ⚠️ 用 onNewIntent 而不是只在 onCreate 里读：
     *    如果 App 已经在后台（很常见 —— 用户刚在数据页导出完就切去微信），
     *    系统会复用已有实例并走 onNewIntent，onCreate 根本不会再跑一次。
     *    只处理 onCreate 的话，第二次点文件导入就毫无反应。
     *
     * ⚠️ 必须调 setIntent：ComponentActivity 的 onNewIntent 默认不会把
     *    新 intent 存进 getIntent()，不自己存一份的话，
     *    某些场景（Activity 被重建）会拿回旧 intent。
     */
    private var incomingUriState = androidx.compose.runtime.mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incomingUriState.value = extractUri(intent)
        setContent {
            BeiAnTheme {
                MainScreen(externalUri = incomingUriState.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUriState.value = extractUri(intent)
    }

    /**
     * 从 ACTION_VIEW intent 里取出文件 Uri。
     *
     * ⚠️ 只认 ACTION_VIEW。不要把所有外来 intent 都当成导入 ——
     *    App 还带 launcher / 更新安装等入口，误判会让点图标启动时
     *    弹出一个莫名其妙的导入框。真正的内容校验（是不是本 App 的包）
     *    还是在解析那一步做，见 BackupCipher.decrypt。
     */
    private fun extractUri(intent: Intent?): Uri? =
        if (intent?.action == Intent.ACTION_VIEW) intent.data else null
}

private data class TabItem(val labelRes: Int, val icon: ImageVector)

@Composable
fun MainScreen(
    vm: MainViewModel = viewModel(),
    /**
     * 从外部点开 .hh 文件带进来的 Uri，可为空。
     *
     * 非空时自动切到「数据」页并交给 BackupScreen 走导入流程。
     */
    externalUri: Uri? = null,
) {
    val tabs = listOf(
        TabItem(R.string.tab_report, Icons.Filled.Today),
        TabItem(R.string.tab_track, Icons.Filled.LocationOn),
        TabItem(R.string.tab_history, Icons.Filled.DateRange),
        TabItem(R.string.tab_backup, Icons.Filled.Sync),
        TabItem(R.string.tab_settings, Icons.Filled.Settings),
    )
    var index by remember { mutableIntStateOf(0) }

    // 外部点开文件 → 直接落到「数据」页，导入弹窗就在那里弹。
    //
    // 不切页的话，用户从微信点进来会停在报备页，看着像「点了没反应」——
    // 而导入弹窗其实长在数据页里。
    //
    // Uri 转交给 ViewModel 而不是层层传参：这样 BackupScreen 无论
    // 从哪条路进来（点文件 / 点按钮）都只认同一个来源，不必区分。
    LaunchedEffect(externalUri) {
        if (externalUri != null) {
            index = BACKUP_TAB_INDEX
            vm.requestExternalImport(externalUri)
        }
    }

    /**
     * ⚠️ 用 movableContentOf 保住各页面的组合状态，切 Tab 时**不销毁重建**。
     *
     * 原来这里是 `when (index) { 0 -> ReportScreen(...) ... }`：
     * 每次切页，旧页面**整棵子树**被移除、新页面从零重建。后果很重：
     *
     *   1. TrackScreen 的 `remember { MapView(context) }` 被丢弃并重新构造。
     *      osmdroid 的 MapView 构造极贵（tile provider、线程池、
     *      SQLite 缓存、网络模块），而 onDispose 里还会先 onDetach()。
     *      切一次 Tab = 销毁一个地图 + 新建一个地图。
     *   2. 20 个 `stateIn(WhileSubscribed(5_000))` 全部退订再重订，
     *      每次都重新查数据库。
     *   3. 各页 LaunchedEffect 重跑（staysOfDay 会重算当天全部点位）。
     *   4. 这一切还和采集线程（周期写库）叠在一起。
     *
     * 合起来就是「采集时来回切 UI 页卡顿」—— 不是某一处的锅，是结构问题。
     *
     * movableContentOf 让内容在离开组合位置时**保留状态**，切回来直接复用，
     * 地图不会被重建、Flow 不会退订。这是 Compose 官方给 tab 场景的解法。
     */
    val reportContent = remember { movableContentOf<Modifier> { ReportScreen(vm, it) } }
    val trackContent = remember { movableContentOf<Modifier> { TrackScreen(vm, it) } }
    val historyContent = remember { movableContentOf<Modifier> { HistoryScreen(vm, it) } }
    val backupContent = remember { movableContentOf<Modifier> { BackupScreen(vm, it) } }
    val settingsContent = remember { movableContentOf<Modifier> { SettingsScreen(vm, it) } }

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
        // 只组合当前选中的那一页；其余页面的内容由 movableContentOf 保存着，
        // 不会被销毁，切回来时状态（含 MapView）原样复用。
        when (index) {
            0 -> reportContent(modifier)
            1 -> trackContent(modifier)
            2 -> historyContent(modifier)
            3 -> backupContent(modifier)
            else -> settingsContent(modifier)
        }
    }

    // 新版本弹窗挂在这一层：启动时的静默检查一旦发现新版本，
    // 不管用户当前停在哪个 tab 都能看到提示。
    // 之前它长在设置页的 UpdateSection 里，等于「检查到了也不提示」。
    UpdateAvailableDialog(vm)
}

/** 「数据」页在 bottom bar 里的下标。外部导入要落到这一页。 */
private const val BACKUP_TAB_INDEX = 3
