package com.bendourthe.milestone.ui.catalog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bendourthe.milestone.ui.components.ProgramBadge

/**
 * Programs as thumbnails, grouped by category and filtered to the ones not added yet unless the user picks another
 * filter; added ones carry a green outline, and tapping one goes straight to its sign-in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramCatalogScreen(
    viewModel: CatalogViewModel,
    onBack: () -> Unit,
    onConnect: (programId: String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is CatalogEvent.Connect -> onConnect(event.programId)
                is CatalogEvent.ShowMessage -> snackbar.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add a program") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { FilterMenu(state.filter, viewModel::setFilter) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        CatalogContent(
            state = state,
            query = query,
            onQueryChange = viewModel::setQuery,
            onSelect = { id ->
                focusManager.clearFocus()
                viewModel.onProgramSelected(id)
            },
            // The top bar is transparent over the aurora, so the list starts below it instead of scrolling under it.
            modifier = Modifier.padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CatalogContent(
    state: CatalogUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    LazyColumn(modifier.fillMaxSize().testTag("catalogList"), contentPadding = contentPadding) {
        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search programs") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("catalogSearch"),
            )
        }
        if (state.isEmpty) {
            item(key = "noMatch") {
                Text(
                    emptyText(state),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }
        state.groups.forEach { (category, items) ->
            if (items.isNotEmpty()) {
                stickyHeader(key = "header_${category.name}") {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) { SectionTitle(category.label) }
                }
                items.chunked(2).forEachIndexed { row, pair ->
                    item(key = "row_${category.name}_$row") {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { ProgramCard(it, Modifier.weight(1f)) { onSelect(it.program.programId) } }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private fun emptyText(state: CatalogUiState): String = when {
    state.isSearching -> "No programs match \"${state.query.trim()}\""
    state.filter == CatalogFilter.NOT_ADDED -> "Every program is added"
    else -> "No programs added yet"
}

@Composable
private fun FilterMenu(selected: CatalogFilter, onSelect: (CatalogFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.FilterList, contentDescription = "Filter: ${selected.label}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CatalogFilter.entries.forEach { filter ->
                DropdownMenuItem(
                    text = { Text(filter.label) },
                    leadingIcon = { if (filter == selected) Icon(Icons.Filled.Check, contentDescription = null) },
                    // The check mark is decorative, so the selection is announced through semantics.
                    modifier = Modifier.semantics { this.selected = filter == selected },
                    onClick = {
                        open = false
                        onSelect(filter)
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
private fun AddedMark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text("Added", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun ProgramCard(item: CatalogItem, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.elevatedCardColors(),
        elevation = CardDefaults.elevatedCardElevation(),
        border = if (item.isAdded) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ProgramBadge(item.program.monogram, item.program.logoKey, item.program.brandColorArgb, size = 56.dp)
            Text(item.program.displayName, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.isAdded) AddedMark()
        }
    }
}
