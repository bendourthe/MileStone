package com.bendourthe.milestone.ui.benefits

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bendourthe.milestone.domain.CardSegment

/**
 * The issuer's sign-ins on a Personal or Business page (ADR-0009): each one's Personal or Business declaration, a
 * sign-out for each additional sign-in, and adding another sign-in for this page's segment.
 */
@Composable
fun ConnectionsSection(connections: ConnectionsViewModel, program: BenefitsProgramUi, onAddSignIn: (String, String) -> Unit) {
    val issuer = program.issuerProgramId
    val list by remember(issuer) { connections.observe(issuer) }.collectAsState(initial = emptyList())
    val segment = program.segment ?: CardSegment.PERSONAL
    val issuerName = program.name.substringBeforeLast(' ')
    ElevatedCard(Modifier.fillMaxWidth().testTag("connectionsSection")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$issuerName sign-ins", style = MaterialTheme.typography.titleMedium)
            Text(
                "Mark each sign-in Personal or Business. Cards a Personal sign-in can see show under Personal; the others under Business.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            list.forEach { connection ->
                Column(Modifier.testTag("connection:${connection.connectionId}")) {
                    Text(
                        "${connection.label}${if (connection.primary) " (balance)" else ""}: ${if (connection.signedIn) "signed in" else "not signed in"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CardSegment.entries.forEach { option ->
                            FilterChip(
                                selected = connection.segment == option,
                                onClick = { connections.setSegment(connection.connectionId, option) },
                                label = { Text(if (option == CardSegment.PERSONAL) "Personal" else "Business") },
                            )
                        }
                        if (!connection.primary) TextButton(onClick = { connections.signOut(issuer, connection.connectionId) }) { Text("Sign out") }
                    }
                }
            }
            OutlinedButton(onClick = { connections.add(issuer, segment) { id -> onAddSignIn(issuer, id) } }) {
                Text("Add a ${if (segment == CardSegment.PERSONAL) "personal" else "business"} sign-in")
            }
        }
    }
}
