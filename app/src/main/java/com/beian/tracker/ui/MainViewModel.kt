package com.beian.tracker.ui

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.beian.tracker.data.AppSession
import com.beian.tracker.data.AppUsage
import com.beian.tracker.data.DailySummary
import com.beian.tracker.data.DeviceSnapshot
import com.beian.tracker.data.EventLog
import com.beian.tracker.data.ImportedSource
import com.beian.tracker.data.LOCAL_SOURCE
import com.beian.tracker.data.TrackPoint
import com.beian.tracker.data.TrackRepository
import com.beian.tracker.service.TrackService
import com.beian.tracker.util.AppEventDeriver
import com.beian.tracker.util.BackupCodec
import com.beian.tracker.util.EventDedup
import com.beian.tracker.util.SettingsStore
import com.beian.tracker.util.TimeUtil
import com.beian.tracker.util.UpdateChecker
import com.beian.tracker.util.UsageStatsReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 检查更新的界面状态。 */
sealed interface UpdateUiState {
    /** 空闲（未检查 / 静默检查无结果 / 用户已关闭弹窗）。 */
    data object Idle : UpdateUiState

    data object Checking : UpdateUiState

    /** 发现新版本。 */
    data class Available(val info: UpdateChecker.ReleaseInfo, val current: String) : UpdateUiState

    /** 已是最新。 */
    data class Latest(val current: String) : UpdateUiState

    /** 检查失败。 */
    data class Error(val reason: String) : UpdateUiState
}

/**
 * 只读探测用的临时 sourceId —— 不落库，仅为了让 decode 产出合法结构。
 *
 * 必须是顶层常量：const val 不允许写在普通类体内。
 */
private const val PROBE_SOURCE_ID = "PROBE"

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = TrackRepository(app)
    private val settings = SettingsStore(app)

    init {
        // 打开 App 就把系统里现有的最近 7 天 App 使用数据回填进库。
        //
        // 放在 ViewModel 而不是 TrackService：后者只在「采集开关打开」
        // 且拿到定位权限时才启动，而回填只需要「使用情况访问」权限。
        // 放这里能保证只要打开过 App 就会执行一次。
        //
        // 内部有幂等标记，同一进程只会真正跑一次；
        // 采集服务里也调了一次，两处互为兜底。
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.backfillDailyUsage() }
        }

        // ── 自动清理过期数据 ─────────────────────────────────────────────────
        //
        // 轨迹点是**只增不减**的：2 分钟一个点，一天 720 个，
        // 一年约 26 万个，导出包也跟着一起变大。
        // [TrackRepository.purgeOlderThan] 早就写好了，但一直没有任何调用方，
        // 等于库只进不出。
        //
        // ⚠️ 默认**不清理**（retentionDays = 0）。
        //    自动删数据不可逆，不能替用户做主 —— 用户到设置里
        //    明确设了天数才会真的开始删。见 SettingsStore 里那段说明。
        //
        // 放 IO 且在 App 启动时跑一次就够：清理是幂等的，
        // 多跑几次只是多几个空查询。
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val days = settings.retentionDays.first()
                if (days > 0) repository.purgeOlderThan(days)
            }
        }
    }

    /** 当前查看的日期（默认今天）。 */
    private val _selectedDay = MutableStateFlow(TimeUtil.dayKey())
    val selectedDay: StateFlow<String> = _selectedDay.asStateFlow()

    /**
     * 当前查看的来源：LOCAL 是本机，其余是导入的对方数据。
     * 顶部可切换。
     */
    private val _sourceId = MutableStateFlow(LOCAL_SOURCE)
    val sourceId: StateFlow<String> = _sourceId.asStateFlow()

    /** 已导入的对方数据包。 */
    val importedSources: StateFlow<List<ImportedSource>> = repository.importedSources()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── 报备事件流 ────────────────────────────────────────────────────────────

    /** 当前来源、当天的报备事件（时间倒序）。 */
    val events: StateFlow<List<EventLog>> = combine(_sourceId, _selectedDay) { s, d -> s to d }
        .flatMapLatest { (s, d) -> repository.eventsOfDay(s, d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 当前来源最近的事件（不限当天，用于报备页连续滚动）。
     *
     * 经过 [EventDedup.collapse] 折叠：数据库里如实记录了每次亮屏/熄屏，
     * 但直接铺在聊天流里会刷屏（一晚上瞄几次时间就是十几条）。
     * 折叠只影响显示，不动数据库。
     */
    val recentEvents: StateFlow<List<EventLog>> = _sourceId
        .flatMapLatest { repository.recentEvents(it, 300) }
        .map { EventDedup.collapse(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── 轨迹 ──────────────────────────────────────────────────────────────────

    val todayPoints: StateFlow<List<TrackPoint>> = combine(_sourceId, _selectedDay) { s, d -> s to d }
        .flatMapLatest { (s, d) -> repository.pointsOfDay(s, d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val todaySummary: StateFlow<DailySummary?> =
        combine(_sourceId, _selectedDay) { s, d -> s to d }
            .flatMapLatest { (s, d) -> repository.summaryOfDay(s, d) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 当前来源有数据的日期列表。 */
    val allDays: StateFlow<List<String>> = _sourceId
        .flatMapLatest { repository.observedDays(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allSummaries: StateFlow<List<DailySummary>> = _sourceId
        .flatMapLatest { repository.allSummaries(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── App 使用 ──────────────────────────────────────────────────────────────

    /**
     * 本应用包名，用于在 App 排行 / 事件里排除自己。
     *
     * 直接用构造参数 app（而不是 getApplication()）：类属性按声明顺序初始化，
     * 这里定义在 reportableAppUsage 之前，用构造参数能确保一定已就绪。
     */
    /**
     * 本应用包名。
     *
     * 界面层筛掉自身和桌面时要用：调用 [AppEventDeriver.isReportable]
     * 需要它，否则用户会在排行里看到「桌面」占据第一。
     */
    val selfPackageName: String = app.packageName

    val appUsage: StateFlow<List<AppUsage>> = combine(_sourceId, _selectedDay) { s, d -> s to d }
        .flatMapLatest { (s, d) -> repository.appUsageOfDay(s, d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 报备页要展示的 App 使用排行。
     *
     * 原始 app_usage 里含桌面、系统 UI（它们也能进入前台），
     * 展示时必须滤掉，否则排行第一永远是「桌面」。
     * 用 [AppEventDeriver.isReportable] 与事件流保持同一套规则。
     */
    val reportableAppUsage: StateFlow<List<AppUsage>> = combine(_sourceId, _selectedDay) { s, d -> s to d }
        .flatMapLatest { (s, d) -> repository.appUsageOfDay(s, d) }
        .map { list ->
            list.filter { AppEventDeriver.isReportable(it.packageName, selfPackageName) }
                .filter { it.usageMs > 0 }
                .sortedByDescending { it.usageMs }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())


    val appSessions: StateFlow<List<AppSession>> = combine(_sourceId, _selectedDay) { s, d -> s to d }
        .flatMapLatest { (s, d) -> repository.appSessionsOfDay(s, d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 当前来源、当前日期的 App 片段，供历史页的时段柱状图使用。
     *
     * v6 起 app_session 带 sourceId，导出包也含这一项，
     * 所以看对方数据时柱状图同样有内容。
     */
    val hourlySessions: StateFlow<List<AppSession>> = combine(_sourceId, _selectedDay) { s, d -> s to d }
        .flatMapLatest { (s, d) -> repository.appSessionsOfDay(s, d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 是否已授予「使用情况访问」。
     *
     * UI 需要它来区分「没权限」和「今天确实没用过 App」——
     * 前者必须给出引导，否则用户装了新版却看不到排行，也不知道为什么。
     *
     * 授权在系统设置里完成，回来时 ViewModel 不会重建，
     * 所以光靠初始化读一次是不够的 —— 界面回到前台时要调 [refreshUsageAccess]。
     */
    private val _hasUsageAccess = MutableStateFlow(UsageStatsReader.hasPermission(app))
    val hasUsageAccess: StateFlow<Boolean> = _hasUsageAccess.asStateFlow()

    /** 重新读取「使用情况访问」权限状态。界面 ON_RESUME 时调用。 */
    fun refreshUsageAccess() {
        _hasUsageAccess.value = UsageStatsReader.hasPermission(getApplication())
    }

    // ── 本机状态 ──────────────────────────────────────────────────────────────

    /**
     * 当前来源最近的一条快照。
     *
     * 必须跟着 [_sourceId] 走：之前固定查本机，
     * 切到对方后顶部还在显示本机的电量和网络。
     */
    val latestSnapshot: StateFlow<DeviceSnapshot?> = _sourceId
        .flatMapLatest { repository.latestSnapshot(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val trackingEnabled: StateFlow<Boolean> = settings.trackingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val intervalSec: StateFlow<Int> = settings.intervalSec
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_INTERVAL)

    val myNickname: StateFlow<String> = settings.myNickname
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /**
     * 本机数据保留天数。
     *
     * 0 = 不自动清理（默认）。见 [com.beian.tracker.util.SettingsStore.retentionDays]。
     */
    val retentionDays: StateFlow<Int> = settings.retentionDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_RETENTION_DAYS)

    // ── 操作 ──────────────────────────────────────────────────────────────────

    fun selectDay(day: String) {
        _selectedDay.value = day
    }

    /** 切换到某个来源查看。 */
    fun selectSource(id: String) {
        _sourceId.value = id
    }

    fun setTracking(enabled: Boolean) {
        viewModelScope.launch { settings.setTrackingEnabled(enabled) }
    }

    fun setInterval(seconds: Int) {
        viewModelScope.launch {
            settings.setIntervalSec(seconds)
            // 服务只在启动时读一次间隔，改完必须通知它重新注册，
            // 否则用户看到的是「设置改了但没生效」。
            TrackService.reload(getApplication())
        }
    }

    fun setMyNickname(name: String) {
        viewModelScope.launch { settings.setMyNickname(name) }
    }

    /**
     * 设置保留天数，并**立即**执行一次清理。
     *
     * 只存设置不清理的话，用户选了「30 天」会以为马上就瘦身了，
     * 实际要等下次冷启动 —— 中间这段时间看着像没生效。
     *
     * ⚠️ 清理在 IO 上跑且可能删掉大量行，不要放主线程。
     *    days = 0（不清理）时直接跳过，不白跑一次全表扫描。
     */
    fun setRetentionDays(days: Int) {
        viewModelScope.launch {
            settings.setRetentionDays(days)
            if (days > 0) {
                withContext(Dispatchers.IO) {
                    runCatching { repository.purgeOlderThan(days) }
                }
            }
        }
    }

    // ── 导入 / 导出 ───────────────────────────────────────────────────────────

    /** 打包本机数据为 JSON 文本。days = 0 表示全部。 */
    suspend fun buildBackupJson(days: Int): String {
        val nickname = myNickname.value
        return BackupCodec.encode(repository.buildLocalBundle(days = days, nickname = nickname))
    }

    /**
     * 导入对方数据。
     * @return 导入结果描述；失败抛出带中文原因的异常。
     */
    /**
     * 先解析文件、读出里面的昵称，供导入弹窗做默认值 ——
     * 对方导出时已经填过昵称了，不该再让人重填一遍。
     *
     * @return 文件里的昵称（可能为空），解析失败时抛异常
     */
    fun peekImportNickname(text: String): String =
        BackupCodec.decode(text, sourceIdOverride = PROBE_SOURCE_ID).nickname

    suspend fun importBackupJson(text: String, nickname: String): ImportedSource {
        // ⚠️ 必须覆盖 sourceId。
        // 老版本导出包里的 sourceId 是 "LOCAL"，直接沿用会和本机数据撞 id，
        // 导致报备页出现两张卡、切不过去、本机数据被覆盖。
        // 这里统一换成一次性生成的来源 id，每个导入包各自独立。
        val newSourceId = "PEER-" + System.currentTimeMillis().toString(36)
        val bundle = BackupCodec.decode(text, sourceIdOverride = newSourceId)
        return repository.importBundle(bundle, nickname)
    }

    /** 清空本机采集数据（不影响导入的对方数据）。 */
    suspend fun clearLocalData() {
        repository.clearLocalData()
        post("本机数据已清空")
    }

    suspend fun deleteSource(sourceId: String) {
        repository.deleteImportedSource(sourceId)
        if (_sourceId.value == sourceId) _sourceId.value = LOCAL_SOURCE
    }

    // ── 检查更新 ──────────────────────────────────────────────────────────────

    /** 更新检查状态。 */
    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    /** 当前安装版本名。 */
    val currentVersion: String get() = UpdateChecker.currentVersion

    /** 是否允许预发布版本。 */
    val allowPrerelease: StateFlow<Boolean> = settings.allowPrerelease
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 上次检查时间（0 表示从未检查）。 */
    val lastUpdateCheckAt: StateFlow<Long> = settings.lastUpdateCheckAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /**
     * 检查更新。
     * @param silent 静默检查：只有发现新版本才改变状态，否则保持 Idle（不打扰用户）
     */
    fun checkUpdate(silent: Boolean = false) {
        if (_updateState.value is UpdateUiState.Checking) return
        viewModelScope.launch {
            _updateState.value = UpdateUiState.Checking
            val result = UpdateChecker.check(includePrerelease = allowPrerelease.value)
            settings.setLastUpdateCheckAt(System.currentTimeMillis())

            _updateState.value = when (result) {
                is UpdateChecker.Result.Update ->
                    UpdateUiState.Available(result.info, result.current)
                is UpdateChecker.Result.UpToDate ->
                    if (silent) UpdateUiState.Idle
                    else UpdateUiState.Latest(result.current)
                is UpdateChecker.Result.Failed ->
                    if (silent) UpdateUiState.Idle
                    else UpdateUiState.Error(result.reason)
            }
        }
    }

    /** 用户手动关闭更新弹窗。 */
    fun dismissUpdate() {
        if (_updateState.value is UpdateUiState.Available) {
            _updateState.value = UpdateUiState.Idle
        }
    }

    fun setAllowPrerelease(value: Boolean) {
        viewModelScope.launch { settings.setAllowPrerelease(value) }
    }

    fun setAutoCheckUpdate(value: Boolean) {
        viewModelScope.launch { settings.setAutoCheckUpdate(value) }
    }

    val autoCheckUpdate: StateFlow<Boolean> = settings.autoCheckUpdate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 一次性提示消息（UI 消费后调用 [consumeMessage] 清空）。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private fun post(msg: String) {
        _message.value = msg
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** 当前来源的昵称，用于标题展示。 */
    suspend fun nicknameOf(sourceId: String): String =
        if (sourceId == LOCAL_SOURCE) {
            myNickname.value.ifBlank { "我" }
        } else {
            repository.importedSource(sourceId)?.nickname ?: "对方"
        }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    fun totalDistance(points: List<TrackPoint>): Double {
        var distance = 0.0
        var prev: TrackPoint? = null
        for (p in points) {
            prev?.let {
                val out = FloatArray(1)
                Location.distanceBetween(it.latitude, it.longitude, p.latitude, p.longitude, out)
                if (out[0] < 2000) distance += out[0]
            }
            prev = p
        }
        return distance
    }

    suspend fun snapshotsOfDayOnce(sourceId: String, day: String): List<DeviceSnapshot> =
        repository.snapshotsOfDay(sourceId, day).first()

    suspend fun appUsageOfDayOnce(sourceId: String, day: String): List<AppUsage> =
        repository.appUsageOfDayOnce(sourceId, day)

    /**
     * 某来源某天的 App 片段（历史页展开某天时调用）。
     *
     * 默认取当前查看的来源：调用点从界面来，传 [sourceId] 更明确，
     * 但漏传时跟着当前选择走也比固定本机合理。
     */
    suspend fun appSessionsOfDayOnce(
        day: String,
        sourceId: String = _sourceId.value,
    ): List<AppSession> = repository.appSessionsOfDayOnce(sourceId, day)

    suspend fun eventsOfDayOnce(sourceId: String, day: String): List<EventLog> =
        repository.eventsOfDayOnce(sourceId, day)

    suspend fun staysOfDay(sourceId: String, day: String) = repository.staysOfDay(sourceId, day)
}
