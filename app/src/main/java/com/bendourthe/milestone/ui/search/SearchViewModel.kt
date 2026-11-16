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
    data class Results(val results: SearchResults, val requestVersion: Long = 0) : SearchUiState
    data object Empty : SearchUiState
    data object ReadError : SearchUiState
}

enum class SearchValidation { CURRENT, CHANGED, UNAVAILABLE, READ_ERROR }

internal data class SearchProgramSource(val program: Program, val updatedAt: Long?, val status: AccountStatus, val balance: Long? = null)
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
                freshnessNote = DashboardViewModel.lastUpdatedLabel(source.updatedAt, clock()),
                programDisplay = SearchProgramDisplay(program.monogram, program.logoKey, program.brandColorArgb,
                    source.balance?.let { program.formatBalance(it, locale) } ?: "-- ${program.unitLabel}")))
            input.cards.filterKeys { it.programId == program.programId }.forEach { (card, rows) ->
                val prefs = input.preferences[card.key] ?: BenefitsDisplayPreferences()
                val page = BenefitsViewModel.page(card, rows, clock(), zone, locale, prefs)
                val freshness = listOfNotNull(page.staleNote, page.listFreshnessNote).distinct().joinToString(". ").ifEmpty { null }
                add(SearchEntry(SearchTarget.CardTarget(card.key, revision), program.displayName, page.name,
                    freshnessNote = freshness))
                val aliases = BenefitsPresentation.presented(rows).groupBy { it.benefit.stableKey }
                var displayOrder = 0
                page.sections.forEach { section -> section.items.forEach { item ->
                    add(SearchEntry(SearchTarget.BenefitTarget(card.key, item.key, section.category, revision),
                        program.displayName, item.title, card.name, item.description,
                        aliases[item.key].orEmpty().flatMap { it.sourceTitles }.distinct(),
                        freshness,
                        item.completed, item.period, displayOrder++, item.enrollment))
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
    private val edits = java.util.concurrent.atomic.AtomicLong()
    private class QueryRequest(val text: String, val version: Long)
    private val requests = MutableStateFlow(QueryRequest("", 0))
    val queryVersion: Long get() = edits.get()
    val query: StateFlow<String> = input.asStateFlow()
    private val retries = MutableStateFlow(0)
    private val notification = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = notification.asStateFlow()
    var reveal: SearchReveal? = null
        private set
    private var revealObserver: Job? = null
    fun report(result: SearchValidation) { notification.value = result.message() }

    private val sharing = SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0)
    private sealed interface SourceState {
        data object Loading : SourceState
        data object Error : SourceState
        class Ready(val snapshot: SearchSnapshot) : SourceState
    }
    private data class CardSignal(val card: Card, val rows: List<Benefit>, val preferences: BenefitsDisplayPreferences)

    private fun programs(rows: List<LoyaltyAccount>) = rows.mapNotNull { account ->
        catalog.program(account.programId)?.let { SearchProgramSource(it, account.lastUpdatedEpochMs, account.status, account.balance) }
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

    // Publish on Main immediately before stateIn stores state; equal Empty values still need a new generation.
    private val publishedVersion = MutableStateFlow(0L)
    internal val publishedQueryVersion: StateFlow<Long> = publishedVersion.asStateFlow()

    val state: StateFlow<SearchUiState> = combine(requests, source) { request, snapshot -> request to snapshot }
        .mapLatest { (request, status) ->
            val text = request.text
            request.version to when (status) {
                SourceState.Loading -> SearchUiState.Loading
                SourceState.Error -> SearchUiState.ReadError
                is SourceState.Ready -> if (GlobalSearch.isBlank(text)) SearchUiState.Start else {
                    val result = GlobalSearch.match(text, status.snapshot.entries)
                    if (result.count == 0) SearchUiState.Empty else SearchUiState.Results(result, request.version)
                }
            }
        }.flowOn(worker).onEach { (version, _) -> publishedVersion.value = version }
        .map { (_, state) -> state }.stateIn(viewModelScope, sharing, SearchUiState.Loading)

    // These belong to this navigation entry only, never to saved instance state or storage.
    private val tab = MutableStateFlow<SearchTab?>(null)
    val selectedTab: StateFlow<SearchTab?> = tab.asStateFlow()
    private val positions = mutableMapOf<SearchTab, Pair<Int, Int>>()
    private val collapsedGroups = MutableStateFlow<Set<String>>(emptySet())
    val collapsed: StateFlow<Set<String>> = collapsedGroups.asStateFlow()
    fun toggleGroup(key: String) { collapsedGroups.update { if (key in it) it - key else it + key } }
    private var resetScroll = false
    private var initialFocus = true

    fun position(tab: SearchTab): Pair<Int, Int> = positions[tab] ?: (0 to 0)
    fun selectTab(value: SearchTab) { tab.value = value }
    fun rememberPosition(index: Int, offset: Int, tab: SearchTab = this.tab.value ?: SearchTab.REWARDS) {
        positions[tab] = index to offset
    }
    fun consumeInitialFocus(): Boolean = initialFocus.also { initialFocus = false }
    fun consumeScrollReset(): Boolean = resetScroll.also { resetScroll = false }
    fun setQuery(value: String) {
        val bounded = GlobalSearch.boundQuery(value)
        if (input.value != bounded) {
            val version = edits.incrementAndGet()
            notification.value = null
            positions.clear()
            collapsedGroups.value = emptySet()
            tab.value = null
            resetScroll = true
            input.value = bounded
            requests.value = QueryRequest(bounded, version)
        }
    }
    fun clear() { setQuery("") }
    fun retry() { notification.value = null; retries.update { it + 1 } }

    /** Keep the source revision observed while its result page is on top of this search entry. */
    suspend fun prepare(target: SearchTarget): SearchValidation {
        notification.value = null
        val snapshot = (source.value as? SourceState.Ready)?.snapshot
        val result = validateAgainst(target, snapshot)
        if (result == SearchValidation.CURRENT && snapshot != null) {
            if (revealObserver == null) revealObserver = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { source.collect() }
            reveal = SearchReveal(target, snapshot)
            notification.value = null
        }
        return result
    }

    /** Fresh transaction and live revision are both required; key membership alone is insufficient. */
    suspend fun validate(target: SearchTarget): SearchValidation = validateAgainst(target, (source.value as? SourceState.Ready)?.snapshot)

    suspend fun validateReveal(request: SearchReveal, page: com.bendourthe.milestone.ui.benefits.CardPageSource? = null): SearchValidation {
        val result = validateAgainst(request.target, request.snapshot)
        if (result != SearchValidation.CURRENT) return result
        if (page == null) return if (request.target is SearchTarget.ProgramTarget) result else SearchValidation.READ_ERROR
        val card = when (val target = request.target) {
            is SearchTarget.ProgramTarget -> return result
            is SearchTarget.CardTarget -> target.card
            is SearchTarget.BenefitTarget -> target.card
        }
        val expected = request.snapshot.source.cards.entries.singleOrNull { it.key.key == card } ?: return SearchValidation.UNAVAILABLE
        val rows = page.benefits.sortedWith(compareBy<Benefit> { it.stableKey }.thenBy { it.section.name }.thenBy { it.position })
        return if (page.card == expected.key && rows == expected.value &&
            page.preferences == (request.snapshot.source.preferences[card] ?: BenefitsDisplayPreferences())) result else SearchValidation.CHANGED
    }

    private suspend fun validateAgainst(target: SearchTarget, expected: SearchSnapshot?): SearchValidation = withContext(worker) {
        try {
            val fresh = readInput()
            val current = source.value as? SourceState.Ready
            when {
                !factory.contains(fresh, target) -> SearchValidation.UNAVAILABLE
                expected == null || current == null -> SearchValidation.CHANGED
                fresh != expected.source -> { retry(); SearchValidation.CHANGED }
                expected.revision !== target.revision || current.snapshot.revision !== target.revision -> SearchValidation.CHANGED
                else -> SearchValidation.CURRENT
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            SearchValidation.READ_ERROR
        }
    }
}
