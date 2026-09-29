package com.beian.tracker.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "beian_settings")

/** 应用设置（纯本地 DataStore）。 */
class SettingsStore(private val context: Context) {

    private val keyInterval = intPreferencesKey("interval_sec")
    private val keyEnabled = booleanPreferencesKey("tracking_enabled")
    /** 本机昵称，导出数据包时带上，对方导入后用这个显示。 */
    private val keyNickname = stringPreferencesKey("my_nickname")

    // ── 更新相关 ──────────────────────────────────────────────────────────────
    private val keyAutoCheckUpdate = booleanPreferencesKey("auto_check_update")
    private val keyAllowPrerelease = booleanPreferencesKey("allow_prerelease")
    private val keyLastUpdateCheckAt = longPreferencesKey("last_update_check_at")

    // ── 离线地图 ──────────────────────────────────────────────────────────────
    private val keyOfflineMapOnly = booleanPreferencesKey("offline_map_only")
    private val keyMapZoom = intPreferencesKey("offline_map_zoom")

    val intervalSec: Flow<Int> = context.dataStore.data.map { it[keyInterval] ?: DEFAULT_INTERVAL }

    val trackingEnabled: Flow<Boolean> = context.dataStore.data.map { it[keyEnabled] ?: false }

    val myNickname: Flow<String> = context.dataStore.data.map { it[keyNickname] ?: "" }

    suspend fun setMyNickname(value: String) {
        context.dataStore.edit { it[keyNickname] = value.take(24) }
    }

    /** 启动时自动检查更新。 */
    val autoCheckUpdate: Flow<Boolean> = context.dataStore.data.map { it[keyAutoCheckUpdate] ?: true }

    suspend fun setAutoCheckUpdate(value: Boolean) {
        context.dataStore.edit { it[keyAutoCheckUpdate] = value }
    }

    /** 是否接受预发布版本。 */
    val allowPrerelease: Flow<Boolean> = context.dataStore.data.map { it[keyAllowPrerelease] ?: false }

    suspend fun setAllowPrerelease(value: Boolean) {
        context.dataStore.edit { it[keyAllowPrerelease] = value }
    }

    /** 强制离线模式：只用本地瓦片，不发网络请求。 */
    val offlineMapOnly: Flow<Boolean> = context.dataStore.data.map { it[keyOfflineMapOnly] ?: false }

    suspend fun setOfflineMapOnly(value: Boolean) {
        context.dataStore.edit { it[keyOfflineMapOnly] = value }
    }

    /** 预下载的最大缩放级别。 */
    val mapZoom: Flow<Int> = context.dataStore.data.map { it[keyMapZoom] ?: DEFAULT_MAP_ZOOM }

    suspend fun setMapZoom(value: Int) {
        context.dataStore.edit { it[keyMapZoom] = value.coerceIn(12, 18) }
    }

    /** 上次检查更新的时间戳。 */
    val lastUpdateCheckAt: Flow<Long> = context.dataStore.data.map { it[keyLastUpdateCheckAt] ?: 0L }

    suspend fun setLastUpdateCheckAt(value: Long) {
        context.dataStore.edit { it[keyLastUpdateCheckAt] = value }
    }

    suspend fun setIntervalSec(value: Int) {
        context.dataStore.edit {
            it[keyInterval] = value.coerceIn(MIN_INTERVAL, MAX_INTERVAL)
        }
    }

    suspend fun setTrackingEnabled(value: Boolean) {
        context.dataStore.edit { it[keyEnabled] = value }
    }

    companion object {
        /**
         * 默认采集间隔（秒）。
         *
         * ⚠️ 从 60 调到 120 的原因：60 秒档下定位请求过于活跃，
         * 系统会持续显示定位使用状态，也明显更耗电。2 分钟是
         * 「轨迹可用」与「系统安静」之间的折中。
         *
         * 代价（实测估算）：步行仍能看出走向（2 分钟约 167m），
         * 但骑行/驾车时相邻两点跨度 500m~2.7km，路径会被拉直、
         * 里程偏低。用户可在设置里自行改回更密的档位。
         */
        const val DEFAULT_INTERVAL = 120

        /** 采集间隔可调范围（秒）。 */
        const val MIN_INTERVAL = 30
        const val MAX_INTERVAL = 600

        /** 预下载默认到 z16（街道级）。级别越高瓦片数增长越快。 */
        const val DEFAULT_MAP_ZOOM = 16
    }
}