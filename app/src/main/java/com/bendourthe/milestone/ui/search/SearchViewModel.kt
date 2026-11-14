package com.bendourthe.milestone.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bendourthe.milestone.domain.*
import com.bendourthe.milestone.ui.benefits.BenefitsPresentation
import com.bendourthe.milestone.ui.benefits.BenefitsViewModel
import com.bendourthe.milestone.ui.dashboard.DashboardViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.ZoneId
import java.util.Locale

sealed interface SearchUiState {
    data object Loading : SearchUiState
    data object Start : SearchUiState
    data class Results(val results: SearchResults) : SearchUiState
    data object Empty : SearchUiState
    data object ReadError : SearchUiState
}

enum class SearchValidation { CURRENT, CHANGED, UNAVAILABLE, READ_ERROR }

internal data class SearchProgramSource(val program: Program, val updatedAt: Long?, val status: AccountStatus)
internal data class SearchInput(val programs: List<SearchProgramSource>, val cards: Map<Card, List<Benefit>>,
                                val preferences: Map<CardKey, BenefitsDisplayPreferences>) {
    fun frozen() = copy(programs = programs.sortedBy { it.program.programId }, cards = cards.mapValues { (_, rows) ->
        rows.sortedWith(compareBy<Benefit> { it.stableKey }.thenBy { it.section.name }.thenBy { it.position })
    }, preferences = preferences.mapValues { (_, p) -> p.copy(collapsed = p.collapsed.toSet(), acknowledged = p.acknowledged.toMap(), readState = p.readState.copy(sections = p.readState.sections.toMap())) })
}

internal class SearchSnapshot(val revision: SearchRevision, val source: SearchInput, val entries: List<SearchEntry>)

/** Uses the same page and duplicate owner as the destination; no second matching/deduplication policy. */
internal class SearchSnapshotFactory(private val clock: () -> Long, private val zone: ZoneId, private val locale: Locale) {
    private var previous: SearchInput? = null
    private var revision = SearchRevision()

    fun build(input: SearchInput): SearchSnapshot {
        val frozen = input.frozen()
        if (previous != frozen) { previous = frozen; revision = SearchRevision() }
        return SearchSnapshot(revision, frozen, entries(frozen, revision))
    }

    fun contains(input: SearchInput, target: SearchTarget) = entries(input, target.revision).count { it.target == target } == 1

    private fun entries(input: SearchInput, revision: SearchRevision): List<SearchEntry> = buildList {
        input.programs.forEach { source ->
            val program = source.program
            add(SearchEntry(SearchTarget.ProgramTarget(program.programId, revision), program.displayName, program.displayName,
                aliases = listOf(program.programId, program.category.label),
                freshnessNote = DashboardViewModel.lastUpdatedLabel(source.updatedAt, clock())))
            input.cards.filterKeys { it.programId == program.programId }.forEach { (card, rows) ->
                val prefs = input.preferences[card.key] ?: BenefitsDisplayPreferences()
                val page = BenefitsViewModel.page(card, rows, clock(), zone, locale, prefs)
                val freshness = listOfNotNull(page.staleNote, page.listFreshnessNote).distinct().joinToString(". ").ifEmpty { null }
                add(SearchEntry(SearchTarget.CardTarget(card.key, revision), program.displayName, page.name,
                    freshnessNote = freshness))
                val aliases = BenefitsPresentation.presented(rows).groupBy { it.benefit.stableKey }
                page.sections.forEach { section -> section.items.forEach { item ->
                    add(SearchEntry(SearchTarget.BenefitTarget(card.key, item.key, section.category, revision),
                        program.displayName, item.title, card.name, item.description,
                        aliases[item.key].orEmpty().flatMap { it.sourceTitles }.distinct(),
                        freshness,
                        item.completed, item.period))
                } }
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val accounts: AccountRepository,
    private val cards: CardRepository,
    private val catalog: CatalogRepository,
    private val displaySettings: BenefitsDisplaySettings? = null,
    clock: () -> Long = System::currentTimeMillis,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val factory = SearchSnapshotFactory(clock, zone, locale)
    private val input = MutableStateFlow("")
    val query: StateFlow<String> = input.asStateFlow()
    private val retries = MutableStateFlow(0)
    private val sharing = SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0)
    private sealed interface SourceState {
        data object Loading : SourceState
        data object Error : SourceState
        class Ready(val snapshot: SearchSnapshot) : SourceState
    }
    private data class CardSignal(val card: Card, val rows: List<Benefit>, val preferences: BenefitsDisplayPreferences)

    private fun programs(rows: List<LoyaltyAccount>) = rows.mapNotNull { account ->
        catalog.program(account.programId)?.let { SearchProgramSource(it, account.lastUpdatedEpochMs, account.status) }
    }.sortedBy { it.program.programId }

    private fun preferences(key: CardKey) = displaySettings?.observeForSearch(key) ?: flowOf(BenefitsDisplayPreferences())
    private fun programSignal(id: String): Flow<List<CardSignal>> = cards.observeCards(id).flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyList()) else combine(list.map { card ->
            combine(cards.observeBenefits(card.key), preferences(card.key)) { rows, prefs -> CardSignal(card, rows, prefs) }
        }) { it.toList() }
    }

    private suspend fun readInput(): SearchInput {
        // This existing repository method reads cards and benefits together in one Room transaction.
        val stored = cards.allCardsWithBenefits()
        val added = programs(accounts.observeAccounts().first())
        val ids = added.map { it.program.programId }.toSet()
        val current = stored.filterKeys { it.programId in ids }
        val prefs = current.keys.associate { it.key to preferences(it.key).first() }
        return SearchInput(added, current, prefs).frozen()
    }

    private fun watchSource(): Flow<SourceState> = accounts.observeAccounts().map(::programs).distinctUntilChanged()
        .flatMapLatest { added ->
            val signal = if (added.isEmpty()) flowOf(emptyList<CardSignal>()) else
                combine(added.map { programSignal(it.program.programId) }) { it.flatMap { rows -> rows } }
            signal.distinctUntilChanged().transformLatest {
                emit(SourceState.Loading)
                emit(SourceState.Ready(factory.build(readInput())))
            }
        }.onStart { emit(SourceState.Loading) }.catch { error ->
            if (error is CancellationException) throw error
            emit(SourceState.Error)
        }

    private val source = retries.flatMapLatest { watchSource() }.flowOn(worker)
        .stateIn(viewModelScope, sharing, SourceState.Loading)

    val state: StateFlow<SearchUiState> = combine(input, source) { text, snapshot -> text to snapshot }
        .mapLatest { (text, status) ->
            when (status) {
                SourceState.Loading -> SearchUiState.Loading
                SourceState.Error -> SearchUiState.ReadError
                is SourceState.Ready -> if (GlobalSearch.isBlank(text)) SearchUiState.Start else {
                    val result = GlobalSearch.match(text, status.snapshot.entries)
                    if (result.count == 0) SearchUiState.Empty else SearchUiState.Results(result)
                }
            }
        }.flowOn(worker).stateIn(viewModelScope, sharing, SearchUiState.Loading)

    fun setQuery(value: String) { input.value = GlobalSearch.boundQuery(value) }
    fun clear() { input.value = "" }
    fun retry() { retries.update { it + 1 } }

    /** A fresh local read closes the invalidation-delivery window before navigation. Never initiates sync. */
    suspend fun validate(target: SearchTarget): SearchValidation = withContext(worker) {
        try {
            val fresh = readInput()
            val ready = source.value as? SourceState.Ready
            when {
                !factory.contains(fresh, target) -> SearchValidation.UNAVAILABLE
                ready == null -> SearchValidation.CHANGED
                fresh != ready.snapshot.source -> { retry(); SearchValidation.CHANGED }
                ready.snapshot.revision !== target.revision -> SearchValidation.CHANGED
                else -> SearchValidation.CURRENT
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            SearchValidation.READ_ERROR
        }
    }
}
