package com.bendourthe.milestone.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bendourthe.milestone.domain.AccountRepository
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.CatalogRepository
import com.bendourthe.milestone.domain.FailureReason
import com.bendourthe.milestone.domain.SettingsRepository
import com.bendourthe.milestone.domain.SortMode
import com.bendourthe.milestone.domain.usecase.DashboardEntry
import com.bendourthe.milestone.domain.usecase.ObserveDashboard
import com.bendourthe.milestone.sync.ProgramSyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

data class TileUiState(
    val programId: String,
    val displayName: String,
    val monogram: String,
    val logoKey: String?,
    val brandColorArgb: Long,
    val unitLabel: String,
    val formattedBalance: String,
    val lastUpdatedLabel: String,
    /** What a running sync is doing, such as "Signing in..."; null when idle. */
    val syncStatus: String?,
    val needsReconnect: Boolean,
    val errorReason: FailureReason?,
    val canSync: Boolean,
    val isConnected: Boolean,
    val hasSavedSignIn: Boolean = false,
    val typeLabel: String = "",
    val categoryLabel: String = "",
) {
    val isSyncing: Boolean get() = syncStatus != null
}

data class DashboardUiState(
    val isLoading: Boolean = true,
    val sortMode: SortMode = SortMode.BY_CATEGORY,
    val tiles: List<TileUiState> = emptyList(),
) {
    val isEmpty: Boolean get() = !isLoading && tiles.isEmpty()
    val canReorder: Boolean get() = sortMode == SortMode.CUSTOM

    /** The section a tile sits under in the grouped sorts, or null when the list has no sections. */
    fun sectionOf(tile: TileUiState): String? = when (sortMode) {
        SortMode.BY_CATEGORY -> tile.categoryLabel
        SortMode.BY_TYPE -> tile.typeLabel
        SortMode.ALPHABETICAL, SortMode.CUSTOM -> null
    }
    val isRefreshing: Boolean get() = tiles.any { it.isSyncing }
    val canRefresh: Boolean get() = tiles.any { it.canSync }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(
    private val catalog: CatalogRepository,
    observeDashboard: ObserveDashboard,
    private val settings: SettingsRepository,
    private val accounts: AccountRepository,
    syncState: StateFlow<Map<String, ProgramSyncState>>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val locale: Locale = Locale.getDefault(),
    private val signOut: suspend (programId: String) -> Unit = {},
    private val requestRefreshAll: () -> Unit = {},
    openRefreshes: Flow<Set<String>> = emptyFlow(),
    private val requestRefresh: (programId: String) -> Unit = {},
    /** Programs with a saved sign-in: a session that ended for one of them signs in again by itself, so it needs no prompt. */
    savedSignIns: Flow<Set<String>> = flowOf(emptySet()),
) : ViewModel() {
    fun refreshAll() = requestRefreshAll()

    fun refresh(programId: String) = requestRefresh(programId)

    private val dragOrder = MutableStateFlow<List<TileUiState>?>(null)
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    private val live: Flow<DashboardUiState> = settings.sortMode.flatMapLatest { mode ->
        combine(observeDashboard(mode), syncState, savedSignIns) { entries, sync, saved ->
            DashboardUiState(isLoading = false, sortMode = mode, tiles = entries.map { it.toTile(sync[it.program.programId], it.program.programId in saved) })
        }
    }

    init {
        viewModelScope.launch {
            openRefreshes.collect { refreshed -> openSummary(live.first().tiles, refreshed)?.let { messageChannel.send(it) } }
        }
    }

    val uiState: StateFlow<DashboardUiState> = combine(live, dragOrder) { state, held -> if (held == null) state else state.copy(tiles = held) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun setSortMode(mode: SortMode) {
        viewModelScope.launch { settings.setSortMode(mode) }
    }

    fun onMove(from: Int, to: Int) {
        val state = uiState.value
        if (!state.canReorder) return
        val current = (dragOrder.value ?: state.tiles).toMutableList()
        if (from !in current.indices || to !in current.indices || from == to) return
        current.add(to, current.removeAt(from))
        dragOrder.value = current
    }

    fun onDragEnd() {
        val held = dragOrder.value ?: return
        viewModelScope.launch {
            try {
                accounts.reorder(held.map { it.programId })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                messageChannel.send("Could not save the new order")
            } finally {
                dragOrder.value = null
            }
        }
    }

    fun remove(programId: String) {
        viewModelScope.launch {
            signOut(programId)
            accounts.removeAccount(programId)
        }
    }

    private fun DashboardEntry.toTile(sync: ProgramSyncState?, hasSavedSignIn: Boolean) = TileUiState(
        programId = program.programId,
        displayName = program.displayName,
        monogram = program.monogram,
        logoKey = program.logoKey,
        brandColorArgb = program.brandColorArgb,
        unitLabel = program.unitLabel,
        formattedBalance = account.balance?.let { program.formatBalance(it, locale) } ?: "--",
        lastUpdatedLabel = lastUpdatedLabel(account.lastUpdatedEpochMs, clock()),
        syncStatus = when (sync) {
            ProgramSyncState.Syncing -> if (account.status == AccountStatus.CONNECTED) "Syncing..." else "Connecting..."
            ProgramSyncState.SigningIn -> "Signing in..."
            ProgramSyncState.AwaitingCode -> "Waiting for the texted code..."
            ProgramSyncState.Idle, null -> null
        },
        needsReconnect = account.status == AccountStatus.NEEDS_RECONNECT && !hasSavedSignIn,
        hasSavedSignIn = hasSavedSignIn,
        errorReason = account.lastError.takeIf { account.status == AccountStatus.ERROR },
        canSync = program.canSync,
        isConnected = account.status == AccountStatus.CONNECTED,
        typeLabel = program.balanceUnit.typeLabel,
        categoryLabel = program.category.label,
    )

    companion object {
        /** After the refresh on opening MileStone: which of the [refreshed] programs are still not connected, and why. */
        fun openSummary(tiles: List<TileUiState>, refreshed: Set<String>): String? = tiles
            .filter { it.programId in refreshed && it.canSync && (it.needsReconnect || it.errorReason != null) }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "Couldn't reconnect: ") { "${it.displayName} (${it.errorReason?.shortLabel() ?: "Sign in needed"})" }

        fun lastUpdatedLabel(epochMs: Long?, nowEpochMs: Long): String {
            if (epochMs == null) return "Never"
            val minutes = (nowEpochMs - epochMs) / 60_000
            return when {
                minutes < 1 -> "Last updated: just now"
                minutes < 60 -> "Last updated: $minutes min ago"
                minutes < 24 * 60 -> "Last updated: ${minutes / 60} h ago"
                else -> "Last updated: ${minutes / (24 * 60)} d ago"
            }
        }
    }
}
