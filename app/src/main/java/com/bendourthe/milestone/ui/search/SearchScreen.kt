package com.bendourthe.milestone.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bendourthe.milestone.domain.SearchHit
import com.bendourthe.milestone.domain.SearchResults
import com.bendourthe.milestone.domain.SearchKind
import com.bendourthe.milestone.domain.SearchTarget
import com.bendourthe.milestone.domain.SearchTab
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.ui.components.ProgramBadge
import com.bendourthe.milestone.ui.benefits.CardArtwork
import com.bendourthe.milestone.domain.SearchProgramDisplay
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import com.bendourthe.milestone.ui.theme.HeaderTeal

@Composable
fun SearchAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Filled.Search, contentDescription = "Search") }
}

/** Query and position live only in the navigation entry's view model. */
@Composable
fun SearchScreen(viewModel: SearchViewModel, onBack: () -> Unit, onSelect: (SearchTarget) -> Unit, selecting: Boolean = false) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val publishedVersion by viewModel.publishedQueryVersion.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val chosenTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val collapsed by viewModel.collapsed.collectAsStateWithLifecycle()
    val activeTab = chosenTab ?: (state as? SearchUiState.Results)?.results?.initialTab ?: SearchTab.REWARDS
    val lists = rememberSearchLists(viewModel, state)
    val listState = lists.getValue(activeTab)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(viewModel) {
        if (viewModel.consumeInitialFocus()) focus.requestFocus()
    }
    val latestLists by rememberUpdatedState(lists)
    val latestVersion by rememberUpdatedState((state as? SearchUiState.Results)?.requestVersion ?: viewModel.queryVersion)
    DisposableEffect(viewModel) {
        onDispose {
            if (latestVersion == viewModel.queryVersion) latestLists.forEach { (tab, list) ->
                viewModel.rememberPosition(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, tab)
            }
        }
    }
    SearchContent(query, state, listState, focus, viewModel::setQuery,
        onClear = { viewModel.clear(); focus.requestFocus() }, onRetry = viewModel::retry,
        onBack = { keyboard?.hide(); onBack() }, onSelect = { keyboard?.hide(); onSelect(it) }, selecting = selecting, notice = notice,
        selectedTab = activeTab, onTab = viewModel::selectTab, collapsed = collapsed, onToggleGroup = viewModel::toggleGroup,
        updating = publishedVersion != viewModel.queryVersion ||
            ((state as? SearchUiState.Results)?.requestVersion?.let { it != viewModel.queryVersion } == true))
}

/** Shares the production per-tab viewport and query reset with boundary tests. */
@Composable
internal fun rememberSearchLists(viewModel: SearchViewModel, state: SearchUiState): Map<SearchTab, LazyListState> {
    // Keep old results at their current position while matching. Reset only with the new result set.
    val version = (state as? SearchUiState.Results)?.requestVersion ?: viewModel.queryVersion
    val lists = remember(viewModel) { SearchTab.entries.associateWith { tab ->
        val (index, offset) = viewModel.position(tab)
        LazyListState(index, offset)
    } }
    LaunchedEffect(viewModel, version) {
        if (version == viewModel.queryVersion && viewModel.consumeScrollReset()) {
            lists.values.forEach { it.requestScrollToItem(0) }
        }
    }
    return lists
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchContent(query: String, state: SearchUiState, listState: LazyListState,
    focus: FocusRequester, onQuery: (String) -> Unit, onClear: () -> Unit, onRetry: () -> Unit,
    onBack: () -> Unit, onSelect: (SearchTarget) -> Unit, selecting: Boolean = false, notice: String? = null,
    selectedTab: SearchTab? = null, onTab: (SearchTab) -> Unit = {},
    collapsed: Set<String> = emptySet(), onToggleGroup: (String) -> Unit = {}, updating: Boolean = false) {
    val keyboard = LocalSoftwareKeyboardController.current
    Scaffold(topBar = {
        TopAppBar(title = { Text("Search") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = HeaderTeal))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            OutlinedTextField(value = query, onValueChange = onQuery, label = { Text("Search stored items", maxLines = 1) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focus).testTag("globalSearchQuery"),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                } })
            val activeTab = selectedTab ?: (state as? SearchUiState.Results)?.results?.initialTab ?: SearchTab.REWARDS
            TabRow(selectedTabIndex = activeTab.ordinal, containerColor = MaterialTheme.colorScheme.surface) {
                SearchTab.entries.forEach { tab ->
                    val count = (state as? SearchUiState.Results)?.results?.count(tab) ?: 0
                    Tab(selected = activeTab == tab, onClick = { onTab(tab) },
                        modifier = Modifier.testTag("searchTab${tab.name}"), text = { Text("${tab.label} ($count)") })
                }
            }
            if (selecting) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("searchSelectionLoading"))
                Text("Checking stored item", Modifier.padding(horizontal = 16.dp))
            }
            notice?.let {
                Column(Modifier.padding(horizontal = 16.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) {
                    Text(it)
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
            when (state) {
                SearchUiState.Loading -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("searchLoading"))
                    Text("Reading stored items", Modifier.padding(16.dp))
                }
                SearchUiState.Start -> Text(if (updating) "Updating results" else "Search programs, cards, and benefits", Modifier.padding(16.dp))
                SearchUiState.Empty -> Text(if (updating) "Updating results" else "No matches in your stored items",
                    Modifier.padding(16.dp).testTag(if (updating) "searchUpdating" else "searchEmpty"))
                SearchUiState.ReadError -> Column(Modifier.padding(16.dp)) {
                    Text("Stored items could not be read")
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
                is SearchUiState.Results -> SearchResultList(state.results, listState, activeTab, selecting, collapsed, onToggleGroup, onSelect, updating)
            }
        }
    }
}

@Composable
private fun ColumnScope.SearchResultList(results: SearchResults, listState: LazyListState, activeTab: SearchTab,
    selecting: Boolean, collapsed: Set<String>, onToggleGroup: (String) -> Unit, onSelect: (SearchTarget) -> Unit, updating: Boolean) {
    val groups = results.groups(activeTab)
    val count = results.count(activeTab)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(if (updating) "Updating results" else "$count ${if (count == 1) "result" else "results"}",
            Modifier.weight(1f).testTag("searchResultCount").semantics { if (updating) liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
        Text("${results.count} total", Modifier.padding(start = 8.dp).testTag("searchTotalCount"), style = MaterialTheme.typography.bodySmall)
    }
    if (groups.isEmpty()) Text("No matching ${activeTab.label.lowercase()} items", Modifier.padding(16.dp))
    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().testTag("searchResults"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { group ->
            val programKey = "${activeTab.name}:program:${group.programId}"
            if (activeTab == SearchTab.REWARDS) {
                items(group.hits, key = { it.entry.id }, contentType = { "program" }) { hit -> SearchProgramResult(hit, enabled = !selecting) { onSelect(hit.entry.target) } }
            } else {
                item("group:${group.programId}", contentType = "programHeader") {
                    SearchGroupHeader(group.programName, group.hits.size, programKey !in collapsed,
                        Modifier.testTag("searchProgramGroup"), program = true, display = group.programDisplay, onToggle = { onToggleGroup(programKey) })
                }
                if (programKey !in collapsed) {
                    results.cards(group.programId).forEach { cardGroup ->
                        val card = cardGroup.card
                        val first = cardGroup.context
                        val cardHit = cardGroup.cardHit
                        val cardKey = "card:${card.programId}:${card.cardId}"
                        item(cardKey, contentType = "cardHeader") {
                            SearchCardHeader(cardHit?.entry?.title ?: first.cardName.orEmpty(), card,
                                cardGroup.benefits.size, cardKey !in collapsed, first.freshnessNote,
                                onToggle = { onToggleGroup(cardKey) }, enabled = !selecting,
                                onClick = { onSelect(SearchTarget.CardTarget(card, first.target.revision)) })
                        }
                        if (cardKey !in collapsed) {
                            cardGroup.sections.forEach { (category, hits) ->
                                val sectionKey = "$cardKey:${category.name}"
                                item(sectionKey, contentType = "sectionHeader") {
                                    SearchGroupHeader(category.title, hits.size, sectionKey !in collapsed,
                                        Modifier.padding(start = 12.dp).testTag("searchSectionGroup"),
                                        onToggle = { onToggleGroup(sectionKey) })
                                }
                                if (sectionKey !in collapsed) {
                                    items(hits, key = { it.entry.id }, contentType = { "benefit" }) { hit ->
                                        CompactBenefitResult(hit, enabled = !selecting) { onSelect(hit.entry.target) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchGroupHeader(title: String, count: Int, expanded: Boolean, modifier: Modifier = Modifier,
    program: Boolean = false, display: SearchProgramDisplay? = null, onToggle: () -> Unit) {
    Surface(onClick = onToggle, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
        .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
        shape = MaterialTheme.shapes.small,
        color = if (program) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (program) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = if (program) 12.dp else 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (program) ProgramBadge(display?.monogram ?: title.take(2), display?.logoKey,
                display?.brandColorArgb ?: HeaderTeal.toArgb().toLong(),
                Modifier.testTag("searchBenefitsProgramImage"), size = 40.dp)
            Text("$title ($count)", style = if (program) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).semantics { heading() })
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = "${if (expanded) "Collapse" else "Expand"} $title")
        }
    }
}

@Composable
private fun SearchCardHeader(name: String, card: CardKey, count: Int, expanded: Boolean, freshness: String?,
    onToggle: () -> Unit, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("searchCardHeader")
        .semantics { freshness?.let { stateDescription = it } },
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(start = 12.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            CardArtwork(card.programId, name, Modifier.width(40.dp).testTag("searchCardThumbnail:${card.programId}:${card.cardId}"))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() })
                Text("Card ending ${card.displayDigits ?: "unknown"}", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onToggle, modifier = Modifier.testTag("searchCardToggle")
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }) {
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = "${if (expanded) "Collapse" else "Expand"} matches for $name ($count)")
            }
        }
    }
}

@Composable
private fun CompactBenefitResult(hit: SearchHit, enabled: Boolean, onClick: () -> Unit) {
    val entry = hit.entry
    val card = (entry.target as SearchTarget.BenefitTarget).card
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.padding(start = 24.dp).fillMaxWidth()
        .heightIn(min = 48.dp).testTag("searchCompactBenefit")
        .semantics {
            contentDescription = "${entry.cardName.orEmpty()}, Card ending ${card.displayDigits ?: "unknown"}"
            stateDescription = listOfNotNull(entry.period, "Completed".takeIf { entry.completed }, entry.enrollment).joinToString(", ")
        },
        shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface) {
        Text(entry.title, Modifier.padding(horizontal = 12.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            textDecoration = if (entry.completed) TextDecoration.LineThrough else null)
    }
}

@Composable
private fun SearchProgramResult(hit: SearchHit, enabled: Boolean, onClick: () -> Unit) {
    val entry = hit.entry
    val display = entry.programDisplay
    ElevatedCard(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("searchProgramTile")) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProgramBadge(display?.monogram ?: entry.title.take(2), display?.logoKey,
                display?.brandColorArgb ?: HeaderTeal.toArgb().toLong(), Modifier.testTag("searchProgramImage"), size = 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(display?.balance ?: "--", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("searchProgramBalance"))
            }
        }
    }
}
