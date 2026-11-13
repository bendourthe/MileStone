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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bendourthe.milestone.domain.CardKey

/**
 * One card's benefits, grouped like the issuer's page: spend progress, credits, completed, other, then the full
 * benefits list. [onGone] runs once if the card is no longer stored while the page is open.
 */
@Composable
fun CardBenefitsScreen(viewModel: BenefitsViewModel, key: CardKey, onBack: () -> Unit, onGone: () -> Unit) {
    val flow = remember(key) { viewModel.card(key) }
    val page by flow.collectAsStateWithLifecycle(initialValue = LOADING)
    LaunchedEffect(page) {
        // A card no longer stored was removed from the account, or the program was signed out.
        if (page == null) onGone()
    }
    Scaffold(
        topBar = {
            BenefitsHeader(page?.takeIf { it !== LOADING }?.let { "${it.name} ${it.digitsLabel}" } ?: "Card benefits", showMark = false) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Benefits") }
            }
        },
    ) { padding ->
        val current = page
        if (current == null || current === LOADING) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp + padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().testTag("cardBenefitsList"),
        ) {
            current.staleNote?.let { note ->
                item("stale") { Text(note, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error) }
            }
            if (current.sections.isEmpty()) {
                item("empty") { Text("Amex shows no benefits for this card.", style = MaterialTheme.typography.bodyMedium) }
            }
            current.sections.forEach { section ->
                item(section.category.name) {
                    Text(
                        section.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                    )
                }
                items(section.items, key = { it.key }) { BenefitRow(it) }
            }
        }
    }
}

@Composable
private fun BenefitRow(item: BenefitUi) {
    ElevatedCard(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
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
