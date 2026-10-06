package io.github.sriram_pr.snaggle.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class UpdateFrequency(val intervalMs: Long?) {
    DAILY(24 * 60 * 60 * 1000L),
    WEEKLY(7 * 24 * 60 * 60 * 1000L),
    MANUAL(null),
}

/** What to do when a download fails in a way a newer yt-dlp might fix. */
enum class NightlyRetry { ASK, AUTO }

data class Settings(
    val updateFrequency: UpdateFrequency = UpdateFrequency.DAILY,
    val notifyEngineUpdates: Boolean = true,
    val nightlyRetry: NightlyRetry = NightlyRetry.ASK,
    val lastEngineCheck: Long = 0,
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object Keys {
        val frequency = stringPreferencesKey("update_frequency")
        val notify = booleanPreferencesKey("notify_engine_updates")
        val nightly = stringPreferencesKey("nightly_retry")
        val lastCheck = longPreferencesKey("last_engine_check")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            updateFrequency = p[Keys.frequency]?.let { runCatching { UpdateFrequency.valueOf(it) }.getOrNull() } ?: UpdateFrequency.DAILY,
            notifyEngineUpdates = p[Keys.notify] ?: true,
            nightlyRetry = p[Keys.nightly]?.let { runCatching { NightlyRetry.valueOf(it) }.getOrNull() } ?: NightlyRetry.ASK,
            lastEngineCheck = p[Keys.lastCheck] ?: 0,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setUpdateFrequency(value: UpdateFrequency) = context.dataStore.edit { it[Keys.frequency] = value.name }
    suspend fun setNotifyEngineUpdates(value: Boolean) = context.dataStore.edit { it[Keys.notify] = value }
    suspend fun setNightlyRetry(value: NightlyRetry) = context.dataStore.edit { it[Keys.nightly] = value.name }
    suspend fun setLastEngineCheck(value: Long) = context.dataStore.edit { it[Keys.lastCheck] = value }
}
