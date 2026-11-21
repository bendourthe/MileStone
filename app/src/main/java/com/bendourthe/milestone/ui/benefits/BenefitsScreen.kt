package com.bendourthe.milestone.ui.benefits

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.bendourthe.milestone.sync.background.AndroidReminderNotifier
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
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.saveable.rememberSaveable
import com.bendourthe.milestone.ui.components.CollapsibleSectionHeader
import com.bendourthe.milestone.domain.CardSegment

/**
 * The header band the dashboard uses, with this screen's title. The centered mark shows only on the tab itself:
 * a card page's title (card name and digits) is long enough to run under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BenefitsHeader(title: String, showMark: Boolean = true, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}, navigationIcon: @Composable () -> Unit = {}) {
    Box {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = HeaderTeal),
        )
        if (showMark && LocalDensity.current.fontScale <= 1.3f) {
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
    programId: String? = null,
    onOpenProgram: (String) -> Unit = {},
    onBackToPrograms: () -> Unit = {},
    onSearch: (() -> Unit)? = null,
    /** Issuer sign-ins (ADR-0009), opened from the top bar of a segmented program's page; null hides the button. */
    connections: ConnectionsViewModel? = null,
    /** Opens the site for a newly added sign-in of a program, in its own profile. */
    onAddSignIn: (programId: String, connectionId: String) -> Unit = { _, _ -> },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(connections) { connections?.messages?.collect { snackbarHostState.showSnackbar(it) } }
    val remindersOn by viewModel.remindersEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(notificationsAllowed(context)) }
    LifecycleResumeEffect(Unit) {
        allowed = notificationsAllowed(context)
        onPauseOrDispose {}
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    val selectedProgram = (state as? BenefitsUiState.Cards)?.programs?.firstOrNull { it.programId == programId }
    Scaffold(topBar = { BenefitsHeader(if (programId == null) "Benefits" else selectedProgram?.name ?: "Card benefits", showMark = programId == null, actions = {
        if (connections != null && selectedProgram?.signIns == true) ConnectionsAction(connections, selectedProgram, onAddSignIn)
        onSearch?.let { com.bendourthe.milestone.ui.search.SearchAction(it) }
    }) {
        if (programId != null) IconButton(onClick = onBackToPrograms) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to institutions")
        }
    } }, snackbarHost = { SnackbarHost(snackbarHostState) }) { outer ->
        val showBanner = remindersOn && !allowed && (state as? BenefitsUiState.Cards)?.tiles?.isNotEmpty() == true
        Column(Modifier.fillMaxSize().padding(top = outer.calculateTopPadding())) {
            if (showBanner) {
                PermissionBanner(onAllow = {
                    val permissionMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    if (permissionMissing) {
                        request.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        // Below Android 13, or with the permission but the app's switch off: the system settings page.
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    }
                })
            }
            BenefitsBody(state, onOpenCard, PaddingValues(bottom = outer.calculateBottomPadding()), programId, onOpenProgram)
        }
    }
}

private fun notificationsAllowed(context: Context): Boolean = AndroidReminderNotifier.notificationsAllowed(context)

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
private fun BenefitsBody(state: BenefitsUiState, onOpenCard: (CardKey) -> Unit, padding: PaddingValues,
                         programId: String?, onOpenProgram: (String) -> Unit) {
    when (val s = state) {
        BenefitsUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding))
        is BenefitsUiState.NoProgram -> Message(
            "No card benefits yet",
            "Add ${s.programNames.joinToString(" or ")} on the Rewards tab to see its cards and their benefits here.",
            Modifier.padding(padding),
        )
        is BenefitsUiState.Cards -> {
            val tiles = s.tiles.filter { it.key.programId == programId }
            val program = s.programs.firstOrNull { it.programId == programId }
            // A segmented page: Personal cards, then Business cards, each section collapsible; Chase's sign-ins open
            // from the top bar (ADR-0009). With no card yet, every program shows the same message below.
            if (program?.segmented == true && tiles.isNotEmpty()) {
                var collapsed by rememberSaveable(programId) { mutableStateOf(emptyList<String>()) }
                val firstSegment = CardSegment.entries.first { segment -> tiles.any { it.segment == segment } }
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp + padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize().testTag("benefitsList"),
                ) {
                    CardSegment.entries.forEach { segment ->
                        val members = tiles.filter { it.segment == segment }
                        if (members.isEmpty()) return@forEach
                        val hidden = segment.name in collapsed
                        item(key = "segment:${segment.name}") {
                            val title = if (segment == CardSegment.PERSONAL) "Personal" else "Business"
                            CollapsibleSectionHeader(title, members.size, "card", hidden, onToggle = {
                                collapsed = if (hidden) collapsed - segment.name else collapsed + segment.name
                            }, modifier = Modifier.padding(top = if (segment == firstSegment) 0.dp else 12.dp).testTag("segment_$title"))
                        }
                        if (!hidden) items(members, key = { "${it.key.programId}/${it.key.cardId}" }) { tile -> CardTile(tile) { onOpenCard(tile.key) } }
                    }
                }
            } else if (programId != null && tiles.isEmpty()) {
                Message(
                    if (program == null) "No card benefits yet" else "Benefits appear after the next ${program.name} refresh",
                    if (program == null) "Return to Benefits to choose an institution." else "MileStone reads your cards and their benefits after it updates the ${program.name} balance.",
                    Modifier.padding(padding),
                )
            } else LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp + padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize().testTag(if (programId == null) "benefitsInstitutions" else "benefitsList"),
            ) {
                if (programId == null) items(s.programs, key = { it.programId }) { institution ->
                    BenefitsInstitutionTile(institution) { onOpenProgram(institution.programId) }
                } else items(tiles, key = { "${it.key.programId}/${it.key.cardId}" }) { tile -> CardTile(tile) { onOpenCard(tile.key) } }
            }
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
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clearAndSetSemantics {
            contentDescription = tile.description
            role = Role.Button
            onClick(label = "Open ${tile.name} benefits") { onClick(); true }
        },
    ) {
        // Artwork on the left; to its right the name (with alerts), ending and count, then freshness.
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            CardArtwork(tile.key.programId, tile.name, Modifier.width(72.dp), tile.brandColorArgb, tile.lastDigits)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tile.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (tile.alertCount > 0) BenefitsAlertCount(tile.alertCount, compact = true)
                }
                Text("${tile.digitsLabel} · ${tile.benefitCount} benefits", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                val footer = tile.staleNote ?: listOfNotNull(tile.completedLabel, tile.updatedLabel).joinToString(" · ")
                Text(footer, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (tile.staleNote != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                tile.classificationNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
