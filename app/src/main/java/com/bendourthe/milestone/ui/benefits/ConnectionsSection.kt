package com.bendourthe.milestone.ui.benefits

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bendourthe.milestone.domain.CardSegment

/**
 * The top-bar button of a segmented program's page (ADR-0009) that opens its sign-ins: each Personal or Business
 * sign-in with its status, a sign-out for each added one, and adding a Personal or a Business sign-in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsAction(connections: ConnectionsViewModel, program: BenefitsProgramUi, onAddSignIn: (String, String) -> Unit) {
    val issuer = program.programId
    var open by rememberSaveable(issuer) { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.testTag("connectionsButton")) {
        Icon(Icons.Filled.PersonAdd, contentDescription = "${program.name} sign-ins")
    }
    if (!open) return
    ModalBottomSheet(onDismissRequest = { open = false }) {
        ConnectionsSheet(connections, program) { segment ->
            connections.add(issuer, segment) { id -> open = false; onAddSignIn(issuer, id) }
        }
    }
}

/** The sheet's content: one row per sign-in, then the two ways to add one. */
@Composable
internal fun ConnectionsSheet(connections: ConnectionsViewModel, program: BenefitsProgramUi, onAdd: (CardSegment) -> Unit) {
    val issuer = program.programId
    val list by remember(issuer) { connections.observe(issuer) }.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp).testTag("connectionsSheet"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${program.name} sign-ins", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(
            "Cards a sign-in can see are listed under its kind. Add a Business sign-in when your business cards use a different Chase username.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        list.forEach { connection ->
            val sameKind = list.filter { it.segment == connection.segment }
            val kind = if (connection.segment == CardSegment.PERSONAL) "Personal" else "Business"
            val number = if (sameKind.size > 1) " ${sameKind.indexOf(connection) + 1}" else ""
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("connection:${connection.connectionId}"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("$kind sign-in$number", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(if (connection.signedIn) "Signed in" else "Not signed in", if (connection.primary) "used for the balance" else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (connection.signedIn) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                if (!connection.primary) TextButton(onClick = { connections.signOut(issuer, connection.connectionId) }) { Text("Sign out") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onAdd(CardSegment.PERSONAL) }, modifier = Modifier.weight(1f)) { Text("Add personal") }
            Button(onClick = { onAdd(CardSegment.BUSINESS) }, modifier = Modifier.weight(1f)) { Text("Add business") }
        }
    }
}
