package com.bendourthe.milestone.ui.benefits

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.bendourthe.milestone.ui.components.ProgramBadge

@Composable
internal fun BenefitsInstitutionTile(program: BenefitsProgramUi, onClick: () -> Unit) {
    val count = "${program.cardCount} ${if (program.cardCount == 1) "card" else "cards"}"
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 80.dp)
        .testTag("benefitsProgram_${program.programId}").clearAndSetSemantics {
            contentDescription = "${program.name}, $count, ${program.alertCount} ${if (program.alertCount == 1) "alert" else "alerts"}"
            role = Role.Button
            onClick(label = "Open ${program.name} cards") { onClick(); true }
        }) {
        // Full-size like the Rewards tiles; the alert count is a small badge at the top right of the name line.
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ProgramBadge(program.monogram, program.logoKey, program.brandColorArgb, size = 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(program.name, style = MaterialTheme.typography.titleLarge, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (program.alertCount > 0) BenefitsAlertCount(program.alertCount, compact = true)
                }
                Text(count, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
