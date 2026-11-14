package com.bendourthe.milestone.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bendourthe.milestone.domain.SortMode
import com.bendourthe.milestone.ui.MileStoneMark
import com.bendourthe.milestone.ui.theme.HeaderTeal
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onAddProgram: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    tileMenuItems: @Composable (tile: TileUiState, closeMenu: () -> Unit) -> Unit = { _, _ -> },
    benefitsPrograms: Set<String> = emptySet(),
    onOpenBenefits: (programId: String) -> Unit = {},
    onSignIn: (programId: String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmPullRefresh by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Long) }
    }

    Scaffold(
        topBar = {
          Box {
            TopAppBar(
                title = { Text("MileStone") },
                // A band a little darker than the bottom bar sets the header apart from the aurora below.
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HeaderTeal),
                actions = {
                    if (state.canRefresh) {
                        Box(contentAlignment = Alignment.Center) {
                            IconButton(
                                onClick = viewModel::refreshAll,
                                enabled = !state.isRefreshing,
                                modifier = Modifier.semantics { if (state.isRefreshing) stateDescription = "Refreshing" },
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh All")
                            }
                            if (state.isRefreshing) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
                        }
                    }
                    SortMenu(state.sortMode, viewModel::setSortMode)
                    IconButton(onClick = onAddProgram) { Icon(Icons.Filled.Add, contentDescription = "Add program") }
                },
            )
            // Centered on the whole bar, not on the space between the title and the actions, which sits left of the
            // screen's center because the title is wider than the actions. The status bar inset keeps it in the 64 dp
            // row; 44 dp raised 3 dp leaves about 7 dp above the mark and 13 dp below it.
            Box(
                Modifier.matchParentSize().windowInsetsPadding(TopAppBarDefaults.windowInsets),
                contentAlignment = Alignment.Center,
            ) { MileStoneMark(44.dp, Modifier.offset(y = (-3).dp)) }
          }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding))
            state.isEmpty -> EmptyDashboard(Modifier.padding(padding))
            // The refresh button carries the only spinner, so the pull indicator never stays on screen. A pull is easy to
            // make by accident while scrolling and a full refresh can take minutes, so it asks first; the button does not.
            else -> PullToRefreshBox(
                isRefreshing = false,
                onRefresh = { if (!state.isRefreshing) confirmPullRefresh = true },
                modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            ) {
                TileList(state, viewModel, tileMenuItems, onSignIn, benefitsPrograms, onOpenBenefits, PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 16.dp))
            }
        }
    }
    if (confirmPullRefresh) {
        AlertDialog(
            onDismissRequest = { confirmPullRefresh = false },
            title = { Text("Refresh all programs?") },
            text = { Text("Every program refreshes, signing in one at a time where needed. This can take a few minutes.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmPullRefresh = false
                    viewModel.refreshAll()
                }) { Text("Refresh all") }
            },
            dismissButton = { TextButton(onClick = { confirmPullRefresh = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SortMenu(current: SortMode, onSelect: (SortMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort programs") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    trailingIcon = { if (mode == current) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                    onClick = {
                        open = false
                        onSelect(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyDashboard(modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No programs yet", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Tap + to track your points and miles in one place.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun TileList(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    tileMenuItems: @Composable (tile: TileUiState, closeMenu: () -> Unit) -> Unit,
    onSignIn: (programId: String) -> Unit,
    benefitsPrograms: Set<String>,
    onOpenBenefits: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.sortMode) { listState.scrollToItem(0) }
    val haptics = LocalHapticFeedback.current
    val reorderState = rememberReorderableLazyListState(listState) { from, to -> viewModel.onMove(from.index, to.index) }
    LazyColumn(state = listState, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize().testTag("dashboardList")) {
        state.tiles.forEachIndexed { index, tile ->
            val section = state.sectionOf(tile)
            if (section != null && state.tiles.getOrNull(index - 1)?.let(state::sectionOf) != section) {
                item(key = "section:$section") {
                    Text(
                        section,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 12.dp).semantics { heading() },
                    )
                }
            }
            item(key = tile.programId) {
                ReorderableItem(reorderState, key = tile.programId, enabled = state.canReorder) {
                    val moveActions = if (state.canReorder) {
                        listOfNotNull(
                            if (index > 0) CustomAccessibilityAction("Move up") { viewModel.onMove(index, index - 1); viewModel.onDragEnd(); true } else null,
                            if (index < state.tiles.lastIndex) CustomAccessibilityAction("Move down") { viewModel.onMove(index, index + 1); viewModel.onDragEnd(); true } else null,
                        )
                    } else {
                        emptyList()
                    }
                    ProgramTile(
                        tile = tile,
                        showDragHandle = state.canReorder,
                        onRemove = { viewModel.remove(tile.programId) },
                        onErrorClick = { onSignIn(tile.programId) },
                        onOpen = if (tile.programId in benefitsPrograms) ({ onOpenBenefits(tile.programId) }) else null,
                        extraMenuItems = { close -> tileMenuItems(tile, close) },
                        modifier = Modifier
                            .longPressDraggableHandle(
                                enabled = state.canReorder,
                                onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                                onDragStopped = { viewModel.onDragEnd() },
                            )
                            .semantics { if (moveActions.isNotEmpty()) customActions = moveActions },
                    )
                }
            }
        }
    }
}
