package com.bendourthe.milestone.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bendourthe.milestone.domain.RefreshInterval
import com.bendourthe.milestone.domain.SettingsRepository
import com.bendourthe.milestone.domain.SortMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val settings: SettingsRepository) : ViewModel() {
    val sortMode: StateFlow<SortMode> = settings.sortMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SortMode.BY_CATEGORY)
    val refreshInterval: StateFlow<RefreshInterval> =
        settings.refreshInterval.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RefreshInterval.WEEKLY)

    fun setSortMode(mode: SortMode) {
        viewModelScope.launch { settings.setSortMode(mode) }
    }

    fun setRefreshInterval(interval: RefreshInterval) {
        viewModelScope.launch { settings.setRefreshInterval(interval) }
    }
}
