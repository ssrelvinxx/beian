package com.beian.tracker.ui

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.beian.tracker.data.DailySummary
import com.beian.tracker.data.DeviceSnapshot
import com.beian.tracker.data.TrackPoint
import com.beian.tracker.data.TrackRepository
import com.beian.tracker.util.SettingsStore
import com.beian.tracker.util.TimeUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = TrackRepository(app)
    private val settings = SettingsStore(app)

    /** 当前查看的日期（默认今天）。 */
    private val _selectedDay = MutableStateFlow(TimeUtil.dayKey())
    val selectedDay: StateFlow<String> = _selectedDay.asStateFlow()

    val todayPoints: StateFlow<List<TrackPoint>> = _selectedDay
        .flatMapLatest { repository.pointsOfDay(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val todaySummary: StateFlow<DailySummary?> = _selectedDay
        .flatMapLatest { repository.summaryOfDay(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val latestSnapshot = repository.latestSnapshot()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val allDays: StateFlow<List<String>> = repository.observedDays()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allSummaries: StateFlow<List<DailySummary>> = repository.allSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val amapKey: StateFlow<String> = settings.amapKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val trackingEnabled: StateFlow<Boolean> = settings.trackingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val intervalSec: StateFlow<Int> = settings.intervalSec
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_INTERVAL)

    /** 从一组点算出总距离（米）。 */
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

    /** 取某天的设备快照（导出报备用）。 */
    suspend fun snapshotsOfDayOnce(day: String): List<DeviceSnapshot> =
        repository.snapshotsOfDay(day).first()

    fun selectDay(day: String) {
        _selectedDay.value = day
    }

    fun setTracking(enabled: Boolean) {
        viewModelScope.launch { settings.setTrackingEnabled(enabled) }
    }

    fun setInterval(seconds: Int) {
        viewModelScope.launch { settings.setIntervalSec(seconds) }
    }
}