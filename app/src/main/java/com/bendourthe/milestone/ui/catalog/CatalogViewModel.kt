package com.bendourthe.milestone.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bendourthe.milestone.domain.AccountRepository
import com.bendourthe.milestone.domain.CatalogRepository
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.domain.ProgramCategory
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

data class CatalogItem(val program: Program, val isAdded: Boolean) {
    val canSync: Boolean get() = program.canSync
}

enum class CatalogFilter(val label: String) {
    NOT_ADDED("Not added"),
    ADDED("Added"),
    ALL("All programs"),
    ;

    fun keeps(item: CatalogItem): Boolean = when (this) {
        NOT_ADDED -> !item.isAdded
        ADDED -> item.isAdded
        ALL -> true
    }
}

data class CatalogUiState(
    val query: String = "",
    val filter: CatalogFilter = CatalogFilter.NOT_ADDED,
    val groups: Map<ProgramCategory, List<CatalogItem>> = emptyMap(),
) {
    val isSearching: Boolean get() = query.isNotBlank()
    val isEmpty: Boolean get() = groups.values.all { it.isEmpty() }
}

sealed interface CatalogEvent {
    data class Connect(val programId: String) : CatalogEvent
    data class ShowMessage(val text: String) : CatalogEvent
}

@OptIn(FlowPreview::class)
class CatalogViewModel(
    private val catalog: CatalogRepository,
    accounts: AccountRepository,
    private val debounceMs: Long = QUERY_DEBOUNCE_MS,
) : ViewModel() {
    private val queryState = MutableStateFlow("")
    val query: StateFlow<String> = queryState.asStateFlow()

    private val eventChannel = Channel<CatalogEvent>(Channel.BUFFERED)
    val events: Flow<CatalogEvent> = eventChannel.receiveAsFlow()

    private val filterState = MutableStateFlow(CatalogFilter.NOT_ADDED)

    val uiState: StateFlow<CatalogUiState> = combine(
        queryState.debounce { if (it.isBlank()) 0L else debounceMs },
        filterState,
        accounts.observeAccounts().map { list -> list.map { it.programId }.toSet() },
    ) { q, filter, added ->
        val matches = catalog.search(q)
        val results = (if (q.isBlank()) matches.sortedByDescending { it.isPopular } else matches)
            .map { CatalogItem(it, it.programId in added) }
            .filter { filter.keeps(it) }
        CatalogUiState(
            query = q,
            filter = filter,
            groups = ProgramCategory.entries.associateWith { cat -> results.filter { it.program.category == cat } },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogUiState())

    fun setQuery(text: String) {
        queryState.value = text
    }

    fun setFilter(filter: CatalogFilter) {
        filterState.value = filter
    }

    fun onProgramSelected(programId: String) {
        val program = catalog.program(programId)
        val event = when {
            program == null -> CatalogEvent.ShowMessage("Unknown program")
            program.canSync -> CatalogEvent.Connect(programId)
            else -> CatalogEvent.ShowMessage("${program.displayName} can't sign in from MileStone yet")
        }
        eventChannel.trySend(event)
    }

    companion object {
        const val QUERY_DEBOUNCE_MS = 150L
    }
}
