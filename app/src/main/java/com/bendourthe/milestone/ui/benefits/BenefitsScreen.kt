package com.bendourthe.milestone.ui.benefits

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.ui.MileStoneMark
import com.bendourthe.milestone.ui.theme.HeaderTeal

/**
 * The header band the dashboard uses, with this screen's title. The centered mark shows only on the tab itself:
 * a card page's title (card name and digits) is long enough to run under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BenefitsHeader(title: String, showMark: Boolean = true, navigationIcon: @Composable () -> Unit = {}) {
    Box {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = navigationIcon,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = HeaderTeal),
        )
        if (showMark) {
            Box(Modifier.matchParentSize().windowInsetsPadding(TopAppBarDefaults.windowInsets), contentAlignment = Alignment.Center) {
                MileStoneMark(44.dp, Modifier.offset(y = (-3).dp))
            }
        }
    }
}

@Composable
fun BenefitsScreen(
    viewModel: BenefitsViewModel,
    onOpenCard: (CardKey) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val remindersOn by viewModel.remindersEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(notificationsAllowed(context)) }
    LifecycleResumeEffect(Unit) {
        allowed = notificationsAllowed(context)
        onPauseOrDispose {}
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    Scaffold(topBar = { BenefitsHeader("Benefits") }, snackbarHost = { SnackbarHost(snackbarHostState) }) { outer ->
        val showBanner = remindersOn && !allowed && state is BenefitsUiState.Cards
        Column(Modifier.fillMaxSize().padding(top = outer.calculateTopPadding())) {
            if (showBanner) PermissionBanner(onAllow = { request.launch(Manifest.permission.POST_NOTIFICATIONS) })
            BenefitsBody(state, onOpenCard, PaddingValues(bottom = outer.calculateBottomPadding()))
        }
    }
}

private fun notificationsAllowed(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** Shown while reminders are on but the phone does not let MileStone post them; no error otherwise. */
@Composable
private fun PermissionBanner(onAllow: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag("reminderPermissionBanner")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Allow notifications to get a reminder before a card credit expires.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAllow) { Text("Allow") }
        }
    }
}

@Composable
private fun BenefitsBody(state: BenefitsUiState, onOpenCard: (CardKey) -> Unit, padding: PaddingValues) {
        when (val s = state) {
            BenefitsUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding))
            is BenefitsUiState.NoProgram -> Message(
                "No card benefits yet",
                "Add ${s.programNames.joinToString(" or ")} on the Rewards tab to see its cards and their benefits here.",
                Modifier.padding(padding),
            )
            is BenefitsUiState.NotReadYet -> Message(
                "Benefits appear after the next ${s.programName} refresh",
                "MileStone reads your cards and their benefits after it updates the ${s.programName} balance.",
                Modifier.padding(padding),
            )
            is BenefitsUiState.Cards -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp + padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize().testTag("benefitsList"),
            ) {
                items(s.tiles, key = { "${it.key.programId}/${it.key.lastDigits}" }) { tile -> CardTile(tile) { onOpenCard(tile.key) } }
            }
        }
}

@Composable
private fun Message(title: String, body: String, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** One TalkBack stop per card carrying the whole tile and its open action; at least 48 dp tall. */
@Composable
private fun CardTile(tile: CardTileUi, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clearAndSetSemantics {
                contentDescription = tile.description
                role = Role.Button
                onClick(label = "Open ${tile.name} benefits") { onClick(); true }
            },
    ) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(" ${tile.digitsLabel}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(tile.nextCredit, style = MaterialTheme.typography.bodyMedium)
                tile.completedLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (tile.staleNote != null) {
                    Text(tile.staleNote, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(tile.lastReadLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
