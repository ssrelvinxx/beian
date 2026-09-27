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
import com.beian.tracker.util.AppEventDeriver
import com.beian.tracker.util.BackupCodec
import com.beian.tracker.util.EventDedup
import com.beian.tracker.util.MapTileStore
import com.beian.tracker.util.TileDownloader
import com.beian.tracker.util.UpdateChecker
import com.beian.tracker.util.SettingsStore
import com.beian.tracker.util.TimeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** 已缓存的离线瓦片统计。 */
data class TileStats(
    val count: Int,
    val bytes: Long,
)

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

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = TrackRepository(app)
    private val settings = SettingsStore(app)

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

    val todaySummary: StateFlow<DailySummary?> = _selectedDay
        .flatMapLatest { repository.summaryOfDay(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 当前来源有数据的日期列表。 */
    val allDays: StateFlow<List<String>> = _sourceId
        .flatMapLatest { repository.observedDays(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allSummaries: StateFlow<List<DailySummary>> = repository.allSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── App 使用 ──────────────────────────────────────────────────────────────

    /**
     * 本应用包名，用于在 App 排行 / 事件里排除自己。
     *
     * 直接用构造参数 app（而不是 getApplication()）：类属性按声明顺序初始化，
     * 这里定义在 reportableAppUsage 之前，用构造参数能确保一定已就绪。
     */
    private val selfPackageName: String = app.packageName

    val appUsage: StateFlow<List<AppUsage>> = _selectedDay
        .flatMapLatest { repository.appUsageOfDay(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 报备页要展示的 App 使用排行。
     *
     * 原始 app_usage 里含桌面、系统 UI（它们也能进入前台），
     * 展示时必须滤掉，否则排行第一永远是「桌面」。
     * 用 [AppEventDeriver.isReportable] 与事件流保持同一套规则。
     */
    val reportableAppUsage: StateFlow<List<AppUsage>> = _selectedDay
        .flatMapLatest { repository.appUsageOfDay(it) }
        .map { list ->
            list.filter { AppEventDeriver.isReportable(it.packageName, selfPackageName) }
                .filter { it.usageMs > 0 }
                .sortedByDescending { it.usageMs }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())


    val appSessions: StateFlow<List<AppSession>> = _selectedDay
        .flatMapLatest { repository.appSessionsOfDay(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── 本机状态 ──────────────────────────────────────────────────────────────

    val latestSnapshot = repository.latestSnapshot()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val trackingEnabled: StateFlow<Boolean> = settings.trackingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val intervalSec: StateFlow<Int> = settings.intervalSec
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_INTERVAL)

    val myNickname: StateFlow<String> = settings.myNickname
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

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
        viewModelScope.launch { settings.setIntervalSec(seconds) }
    }

    fun setMyNickname(name: String) {
        viewModelScope.launch { settings.setMyNickname(name) }
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
    suspend fun importBackupJson(text: String, nickname: String): ImportedSource {
        val bundle = BackupCodec.decode(text)
        return repository.importBundle(bundle, nickname)
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

    // ── 离线地图 ──────────────────────────────────────────────────────────────

    /** 强制离线模式。 */
    val offlineMapOnly: StateFlow<Boolean> = settings.offlineMapOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 预下载的最大缩放级别。 */
    val mapZoom: StateFlow<Int> = settings.mapZoom
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_MAP_ZOOM)

    /** 已缓存瓦片统计。 */
    private val _tileStats = MutableStateFlow(TileStats(0, 0L))
    val tileStats: StateFlow<TileStats> = _tileStats.asStateFlow()

    /** 下载进度；null 表示未在下载。 */
    private val _downloadProgress = MutableStateFlow<TileDownloader.Progress?>(null)
    val downloadProgress: StateFlow<TileDownloader.Progress?> = _downloadProgress.asStateFlow()

    private var downloadJob: Job? = null

    /** 刷新已缓存瓦片统计（进离线页时调用）。 */
    fun refreshTileStats() {
        viewModelScope.launch(Dispatchers.IO) {
            val count = MapTileStore.cachedTileCount(getApplication())
            val size = MapTileStore.cachedSizeBytes(getApplication())
            _tileStats.value = TileStats(count, size)
        }
    }

    /**
     * 按当前来源、当前日期的轨迹范围预下载瓦片。
     * 没有轨迹时以最后一个已知点为中心下载一小片。
     */
    fun downloadOfflineTiles() {
        val pts = todayPoints.value
        if (pts.isEmpty()) {
            _downloadProgress.value = null
            post("这一天没有轨迹点，无法确定下载范围")
            return
        }

        val app = getApplication<Application>()
        val zoom = mapZoom.value
        val minLat = pts.minOf { it.latitude }
        val maxLat = pts.maxOf { it.latitude }
        val minLon = pts.minOf { it.longitude }
        val maxLon = pts.maxOf { it.longitude }

        val tiles = TileDownloader.tilesFor(minLat, maxLat, minLon, maxLon, zoom)
        if (tiles.isEmpty()) return

        if (tiles.size > TileDownloader.MAX_TILES) {
            post(
                "范围太大（约 ${tiles.size} 个瓦片，上限 ${TileDownloader.MAX_TILES}）。" +
                    "请调低缩放级别，或换一天轨迹更集中的日期",
            )
            return
        }

        downloadJob?.cancel()
        downloadJob = TileDownloader.download(
            scope = viewModelScope,
            cacheDir = MapTileStore.tileCacheDir(app),
            tiles = tiles,
        ) { p ->
            _downloadProgress.value = p
            if (p.finished) {
                post(
                    "离线地图已下载：${p.done - p.failed}/${p.total} 个瓦片" +
                        if (p.failed > 0) "（${p.failed} 个失败）" else "",
                )
                refreshTileStats()
                // 下载完成后清掉进度条
                viewModelScope.launch {
                    delay(1500)
                    _downloadProgress.value = null
                }
            }
        }
    }

    fun cancelTileDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadProgress.value = null
    }

    /** 预估：当前轨迹范围在给定级别下需要多少瓦片。 */
    fun estimateTiles(zoom: Int): Int {
        val pts = todayPoints.value
        if (pts.isEmpty()) return 0
        return TileDownloader.estimateCount(
            minLat = pts.minOf { it.latitude },
            maxLat = pts.maxOf { it.latitude },
            minLon = pts.minOf { it.longitude },
            maxLon = pts.maxOf { it.longitude },
            zoom = zoom,
        )
    }

    fun clearOfflineTiles() {
        viewModelScope.launch(Dispatchers.IO) {
            MapTileStore.clear(getApplication())
            withContext(Dispatchers.Main) {
                post("离线地图已清空")
                refreshTileStats()
            }
        }
    }

    fun setOfflineMapOnly(value: Boolean) {
        viewModelScope.launch { settings.setOfflineMapOnly(value) }
    }

    fun setMapZoom(value: Int) {
        viewModelScope.launch { settings.setMapZoom(value) }
    }

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

    suspend fun snapshotsOfDayOnce(day: String): List<DeviceSnapshot> =
        repository.snapshotsOfDay(day).first()

    suspend fun appUsageOfDayOnce(day: String): List<AppUsage> =
        repository.appUsageOfDayOnce(day)

    suspend fun appSessionsOfDayOnce(day: String): List<AppSession> =
        repository.appSessionsOfDayOnce(day)

    suspend fun eventsOfDayOnce(sourceId: String, day: String): List<EventLog> =
        repository.eventsOfDayOnce(sourceId, day)

    suspend fun staysOfDay(sourceId: String, day: String) = repository.staysOfDay(sourceId, day)
}
