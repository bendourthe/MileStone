package com.bendourthe.milestone.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bendourthe.milestone.domain.RefreshInterval
import com.bendourthe.milestone.domain.SortMode

private val licensedLibraries = listOf(
    "AndroidX (Activity, Compose, Core, DataStore, Lifecycle, Navigation, Room, WebKit)",
    "Kotlin standard library, kotlinx.coroutines, kotlinx.serialization",
    "Tink",
    "Reorderable (sh.calvin.reorderable)",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, versionName: String, onBack: () -> Unit = {}, signOutSlot: @Composable () -> Unit = {}) {
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
    val refreshInterval by viewModel.refreshInterval.collectAsStateWithLifecycle()
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            GroupTitle("Default sort")
            SortMode.entries.forEach { mode ->
                ListItem(
                    headlineContent = { Text(mode.label) },
                    leadingContent = { RadioButton(selected = sortMode == mode, onClick = null) },
                    modifier = Modifier.selectable(selected = sortMode == mode, role = Role.RadioButton) { viewModel.setSortMode(mode) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            GroupTitle("Automatic refresh")
            Text(
                "When every program refreshes by itself. If the phone missed that time, opening MileStone catches up. A program that failed tries again each time you open MileStone. The refresh button and a program's menu refresh any time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            RefreshInterval.entries.forEach { interval ->
                ListItem(
                    headlineContent = { Text(interval.label) },
                    leadingContent = { RadioButton(selected = refreshInterval == interval, onClick = null) },
                    modifier = Modifier.selectable(selected = refreshInterval == interval, role = Role.RadioButton) { viewModel.setRefreshInterval(interval) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            GroupTitle("Your data")
            ListItem(
                headlineContent = { Text("Stays on this phone") },
                supportingContent = { Text("Balances and sign-in sessions are stored only on this device and are excluded from backups.") },
                leadingContent = { Icon(Icons.Outlined.Lock, contentDescription = null) },
            )
            signOutSlot()
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            GroupTitle("About")
            ListItem(
                headlineContent = { Text("MileStone") },
                supportingContent = { Text("Version $versionName") },
                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
            )
            ListItem(
                headlineContent = { Text("Open-source licenses") },
                leadingContent = { Icon(Icons.Outlined.Description, contentDescription = null) },
                modifier = Modifier.clickable(role = Role.Button) { showLicenses = true },
            )
        }
    }
    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("Open-source licenses") },
            text = { Text(licensedLibraries.joinToString("\n\n") + "\n\nAll are distributed under the Apache License 2.0.") },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}
