package com.beian.tracker.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "beian_settings")

/** 应用设置（纯本地 DataStore）。 */
class SettingsStore(private val context: Context) {

    private val keyAmap = stringPreferencesKey("amap_key")
    private val keyInterval = intPreferencesKey("interval_sec")
    private val keyEnabled = booleanPreferencesKey("tracking_enabled")

    val amapKey: Flow<String> = context.dataStore.data.map { it[keyAmap].orEmpty() }

    val intervalSec: Flow<Int> = context.dataStore.data.map { it[keyInterval] ?: DEFAULT_INTERVAL }

    val trackingEnabled: Flow<Boolean> = context.dataStore.data.map { it[keyEnabled] ?: false }

    suspend fun setAmapKey(value: String) {
        context.dataStore.edit { it[keyAmap] = value.trim() }
    }

    suspend fun setIntervalSec(value: Int) {
        context.dataStore.edit { it[keyInterval] = value.coerceIn(10, 600) }
    }

    suspend fun setTrackingEnabled(value: Boolean) {
        context.dataStore.edit { it[keyEnabled] = value }
    }

    companion object {
        const val DEFAULT_INTERVAL = 60
    }
}