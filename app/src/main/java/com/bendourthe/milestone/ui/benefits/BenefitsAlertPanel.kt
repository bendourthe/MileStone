package com.bendourthe.milestone.ui.benefits

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val WarningYellow = Color(0xFFFFD54F)
private val WarningInk = Color(0xFF3B2A00)

@Composable
internal fun BenefitsAlertCount(count: Int) {
    Surface(color = WarningYellow, contentColor = WarningInk, shape = MaterialTheme.shapes.small) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Text("$count ${if (count == 1) "alert" else "alerts"}", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun BenefitsAlertPanel(alerts: List<BenefitAlertUi>, onAcknowledge: (BenefitAlertUi) -> Unit) {
    Surface(color = WarningYellow, contentColor = WarningInk, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("benefitsAlerts")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Text("Alerts (${alerts.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            }
            alerts.forEach { alert ->
                Column {
                    Text(alert.message, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onAcknowledge(alert) }, colors = ButtonDefaults.textButtonColors(contentColor = WarningInk), modifier = Modifier.testTag("ack:${alert.id}").semantics { contentDescription = "Acknowledge: ${alert.message}" }) { Text("Acknowledge") }
                }
            }
        }
    }
}
