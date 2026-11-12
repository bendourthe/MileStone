package com.bendourthe.milestone.data.settings

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bendourthe.milestone.domain.RefreshInterval
import com.bendourthe.milestone.domain.SettingsRepository
import com.bendourthe.milestone.domain.SortMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

class DataStoreSettingsRepository(private val store: DataStore<Preferences>) : SettingsRepository {
    private var loggedReadFailure = false

    private val prefs: Flow<Preferences> = store.data.catch { error ->
        if (error !is IOException) throw error
        if (!loggedReadFailure) {
            loggedReadFailure = true
            Log.w(TAG, "Settings unreadable; using defaults")
        }
        emit(emptyPreferences())
    }

    override val sortMode: Flow<SortMode> = prefs
        .map { prefs -> prefs[SORT_MODE]?.let { stored -> SortMode.entries.firstOrNull { it.name == stored } } ?: SortMode.BY_CATEGORY }
        .distinctUntilChanged()

    override suspend fun setSortMode(mode: SortMode) {
        store.edit { it[SORT_MODE] = mode.name }
    }

    override val refreshInterval: Flow<RefreshInterval> = prefs
        .map { prefs -> prefs[REFRESH_INTERVAL]?.let { stored -> RefreshInterval.entries.firstOrNull { it.name == stored } } ?: RefreshInterval.WEEKLY }
        .distinctUntilChanged()

    override suspend fun setRefreshInterval(interval: RefreshInterval) {
        store.edit { it[REFRESH_INTERVAL] = interval.name }
    }

    companion object {
        const val STORE_NAME = "settings"
        private const val TAG = "MileStoneSettings"
        /** A new key, because "sortMode" holds choices made before By category existed and became the default. */
        private val SORT_MODE = stringPreferencesKey("sortModeV2")
        private val REFRESH_INTERVAL = stringPreferencesKey("refreshInterval")
    }
}
