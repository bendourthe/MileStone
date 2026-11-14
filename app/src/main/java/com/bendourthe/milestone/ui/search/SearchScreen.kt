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
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bendourthe.milestone.domain.SearchHit
import com.bendourthe.milestone.domain.SearchKind
import com.bendourthe.milestone.domain.SearchTarget
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
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val listState = remember(viewModel) { LazyListState(viewModel.firstVisibleItemIndex, viewModel.firstVisibleItemOffset) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(viewModel) {
        if (viewModel.consumeInitialFocus()) focus.requestFocus()
    }
    LaunchedEffect(query) { if (viewModel.consumeScrollReset()) listState.scrollToItem(0) }
    DisposableEffect(viewModel, listState) {
        onDispose { viewModel.rememberPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
    }
    SearchContent(query, state, listState, focus, viewModel::setQuery,
        onClear = { viewModel.clear(); focus.requestFocus() }, onRetry = viewModel::retry,
        onBack = { keyboard?.hide(); onBack() }, onSelect = { keyboard?.hide(); onSelect(it) }, selecting = selecting, notice = notice)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchContent(query: String, state: SearchUiState, listState: LazyListState,
    focus: FocusRequester, onQuery: (String) -> Unit, onClear: () -> Unit, onRetry: () -> Unit,
    onBack: () -> Unit, onSelect: (SearchTarget) -> Unit, selecting: Boolean = false, notice: String? = null) {
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
                SearchUiState.Start -> Text("Search programs, cards, and benefits", Modifier.padding(16.dp))
                SearchUiState.Empty -> Text("No matches in your stored items", Modifier.padding(16.dp).testTag("searchEmpty"))
                SearchUiState.ReadError -> Column(Modifier.padding(16.dp)) {
                    Text("Stored items could not be read")
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
                is SearchUiState.Results -> {
                    Text("${state.results.count} ${if (state.results.count == 1) "result" else "results"}",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("searchResultCount"))
                    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().testTag("searchResults"),
                        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.results.groups.forEach { group ->
                            item("group:${group.programId}") {
                                Text(group.programName, style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(vertical = 8.dp).semantics { heading() })
                            }
                            items(group.hits, key = { it.entry.id }) { hit -> SearchResult(hit, enabled = !selecting) { onSelect(hit.entry.target) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResult(hit: SearchHit, enabled: Boolean, onClick: () -> Unit) {
    val entry = hit.entry
    ElevatedCard(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.kind.label, style = MaterialTheme.typography.labelMedium)
            Text(entry.title, style = MaterialTheme.typography.titleMedium,
                textDecoration = if (entry.completed) TextDecoration.LineThrough else null)
            if (entry.kind == SearchKind.BENEFIT) entry.cardName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            hit.descriptionContext?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            entry.period?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (entry.completed) Text("Completed", style = MaterialTheme.typography.labelMedium)
            entry.freshnessNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
