package com.bendourthe.milestone.ui.benefits

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalView
import com.bendourthe.milestone.domain.SearchTarget
import com.bendourthe.milestone.ui.search.SearchReveal
import com.bendourthe.milestone.ui.search.SearchValidation
import com.bendourthe.milestone.ui.search.benefitRevealIndex
import com.bendourthe.milestone.ui.search.benefitRevealSlots
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.domain.BenefitsCategory
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration

/**
 * One card's benefits in the requested display categories, with independent collapse and saved sorting. [onGone] runs once if the card is no longer stored while the page is open.
 */
@Composable
fun CardBenefitsScreen(viewModel: BenefitsViewModel, key: CardKey, onBack: () -> Unit, onGone: () -> Unit, onSearch: (() -> Unit)? = null,
    reveal: SearchReveal? = null,
    validateReveal: suspend (SearchReveal, CardPageSource?) -> SearchValidation = { _, _ -> SearchValidation.UNAVAILABLE },
    onRevealInvalid: (SearchValidation) -> Unit = {}, snackbarHostState: androidx.compose.material3.SnackbarHostState? = null) {
    val flow = remember(key) { viewModel.card(key) }
    val page by flow.collectAsStateWithLifecycle(initialValue = LOADING)
    var showWelcome by rememberSaveable(key.programId, key.lastDigits) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val view = LocalView.current
    LaunchedEffect(reveal) {
        if (reveal != null) {
            delay(10_000)
            if (!reveal.consumed && !reveal.invalidated) onRevealInvalid(SearchValidation.READ_ERROR)
        }
    }
    LaunchedEffect(page) { if (page == null) onGone() }
    Scaffold(topBar = {
        BenefitsHeader("Card benefits", showMark = false, actions = {
            onSearch?.let { com.bendourthe.milestone.ui.search.SearchAction(it) }
            CardBenefitsMenus(key, page?.preferences ?: com.bendourthe.milestone.domain.BenefitsDisplayPreferences(), page != null && page !== LOADING,
                onSort = { viewModel.setSort(key, it) }, onCompletedLast = { viewModel.setCompletedLast(key, it) })
        }) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Benefits") }
        }
    }, snackbarHost = { snackbarHostState?.let { androidx.compose.material3.SnackbarHost(it) } }) { padding ->
        val current = page
        if (current == null || current === LOADING) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        LaunchedEffect(key, current.settingsError) {
            if (current.settingsError != null) listState.animateScrollToItem(0)
        }
        var observedSort by rememberSaveable(key.programId, key.lastDigits) { mutableStateOf<com.bendourthe.milestone.domain.BenefitsSort?>(null) }
        var observedCompletedLast by rememberSaveable(key.programId, key.lastDigits) { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(key, current.preferences.sort, current.preferences.completedLast) {
            if (observedSort != null && (observedSort != current.preferences.sort || observedCompletedLast != current.preferences.completedLast)) listState.scrollToItem(0)
            observedSort = current.preferences.sort
            observedCompletedLast = current.preferences.completedLast
        }
        LaunchedEffect(reveal, current.source) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                val request = reveal ?: return@withContext
                if (request.invalidated || (request.consumed && request.target !is SearchTarget.BenefitTarget)) return@withContext
                val finished = withTimeoutOrNull(10_000) {
                    val validation = validateReveal(request, current.source)
                    if (validation != SearchValidation.CURRENT) { onRevealInvalid(validation); return@withTimeoutOrNull true }
                    if (request.consumed) return@withTimeoutOrNull true
                    val target = request.target as? SearchTarget.BenefitTarget
                    if (target != null) {
                        val index = benefitRevealIndex(current, target)
                        if (index == null) { onRevealInvalid(SearchValidation.UNAVAILABLE); return@withTimeoutOrNull true }
                        request.expand()
                        val count = benefitRevealSlots(current, target.category).size
                        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it == count }
                        val finalCheck = validateReveal(request, current.source)
                        if (finalCheck != SearchValidation.CURRENT) { onRevealInvalid(finalCheck); return@withTimeoutOrNull true }
                        listState.scrollToItem(index)
                        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == target.benefitKey } }.first { it }
                        val title = current.sections.flatMap { it.items }.single { it.key == target.benefitKey }.title
                        view.announceForAccessibility("Search result: $title")
                    }
                    request.complete()
                    true
                }
                if (finished == null) onRevealInvalid(SearchValidation.READ_ERROR)
            }
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().padding(padding).testTag("cardBenefitsList"),
        ) {
            current.settingsError?.let { error ->
                item("settingsError") { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) }
            }
            if (current.alerts.isNotEmpty()) {
                item("alerts") { BenefitsAlertPanel(current.alerts) { alert -> viewModel.acknowledge(key, alert.id, alert.generation) } }
            }
            current.listFreshnessNote?.takeIf { note -> current.alerts.none { it.message == note } }?.let { note ->
                item("listFreshness") { Text(note, style = MaterialTheme.typography.bodySmall) }
            }
            item("card") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardArtwork(key.programId, current.name, Modifier.width(176.dp))
                    Text(current.name, style = MaterialTheme.typography.headlineSmall)
                    Text(current.digitsLabel, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (current.sections.all { it.items.isEmpty() }) {
                item("empty") { Text("No benefits yet", style = MaterialTheme.typography.bodyMedium) }
            }
            current.sections.forEach { section ->
                val target = reveal?.target as? SearchTarget.BenefitTarget
                val temporarilyExpanded = reveal?.expanded == true && !reveal.invalidated && target?.category == section.category
                val collapsed = section.category in current.preferences.collapsed && !temporarilyExpanded
                item("category:${section.category.name}") {
                    Surface(onClick = { viewModel.setCollapsed(key, section.category, !collapsed) }, shape = MaterialTheme.shapes.medium,
                        color = Color.Transparent, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        tonalElevation = 0.dp, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("category_${section.category.name}").semantics {
                                heading()
                                stateDescription = if (collapsed) "Collapsed" else "Expanded"
                            }) {
                        Column {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(section.title, style = MaterialTheme.typography.titleLarge)
                                    Text("${section.items.size} ${if (section.items.size == 1) "benefit" else "benefits"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(if (collapsed) "Show" else "Hide", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
                                Icon(if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, contentDescription = null)
                            }
                            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                if (!collapsed) {
                    if (section.category == BenefitsCategory.WELCOME_BONUS) {
                        item("welcomeSetup") {
                            TextButton(onClick = { showWelcome = true }, modifier = Modifier.testTag("welcomeSetup")) {
                                Text(if (current.preferences.welcomeBonus == null) "Set up welcome bonus" else "Edit welcome bonus")
                            }
                        }
                    }
                    items(section.items, key = { it.key }) { item ->
                        BenefitRow(item, highlighted = reveal?.consumed == true && !reveal.invalidated && target?.benefitKey == item.key)
                    }
                }
            }
        }
    }
    val current = page
    if (showWelcome && current != null && current !== LOADING) {
        WelcomeBonusDialog(current.preferences.welcomeBonus, onSave = { viewModel.setWelcomeBonus(key, it) }, onDismiss = { showWelcome = false })
    }
}

@Composable
private fun BenefitRow(item: BenefitUi, highlighted: Boolean = false) {
    ElevatedCard(Modifier.fillMaxWidth().then(if (highlighted) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium) else Modifier)
        .semantics(mergeDescendants = true) { if (highlighted) { stateDescription = "Search result"; liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite } }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (highlighted) Text("Search result", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                    textDecoration = if (item.completed) TextDecoration.LineThrough else null)
                if (item.completed) Icon(Icons.Filled.CheckCircle, contentDescription = "Completed", tint = MaterialTheme.colorScheme.primary)
            }
            item.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) }
            item.amounts?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            item.period?.let { Text("Period: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (item.noReminder) {
                Text("No reminder for this credit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // A status label, not a control: full-contrast text in an outline rather than a disabled chip.
            item.enrollment?.let {
                Surface(shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), color = Color.Transparent) {
                    Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            item.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** A marker for "not loaded yet", told apart from null ("card gone"). */
private val LOADING = CardPageUi("", "", null, emptyList())
