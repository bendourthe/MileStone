package com.bendourthe.milestone.ui.benefits

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import kotlinx.coroutines.launch

private val WarningYellow = Color(0xFFFFD54F)
private val WarningInk = Color(0xFF3B2A00)

/** The yellow alert count; [compact] is the small icon-and-number form that sits beside a card name. */
@Composable
internal fun BenefitsAlertCount(count: Int, compact: Boolean = false) {
    if (compact) {
        Surface(color = WarningYellow, contentColor = WarningInk, shape = MaterialTheme.shapes.extraSmall) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(14.dp))
                Text("$count", style = MaterialTheme.typography.labelMedium)
            }
        }
        return
    }
    Surface(color = WarningYellow, contentColor = WarningInk, shape = MaterialTheme.shapes.small) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Text("$count ${if (count == 1) "alert" else "alerts"}", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * The card's alerts as a short list of slim yellow rows, each with its Acknowledge button on the right. Swiping a row
 * to the right also acknowledges it; the button stays for TalkBack and for anyone who does not swipe.
 */
@Composable
internal fun BenefitsAlertPanel(alerts: List<BenefitAlertUi>, onAcknowledge: (BenefitAlertUi) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("benefitsAlerts"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Alerts (${alerts.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() })
        alerts.forEach { alert -> androidx.compose.runtime.key(alert.id) { AlertRow(alert) { onAcknowledge(alert) } } }
    }
}

@Composable
private fun AlertRow(alert: BenefitAlertUi, onAcknowledge: () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    SwipeToDismissBox(
        state = state,
        enableDismissFromEndToStart = false,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                onAcknowledge()
                // A saved acknowledgement removes the row; if saving failed the row is still listed, so it returns.
                scope.launch { state.reset() }
            }
        },
        modifier = Modifier.testTag("alert:${alert.id}"),
        backgroundContent = {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxSize()) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Acknowledged", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                }
            }
        },
    ) {
        Surface(color = WarningYellow, contentColor = WarningInk, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(alert.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                TextButton(
                    onClick = onAcknowledge,
                    colors = ButtonDefaults.textButtonColors(contentColor = WarningInk),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.testTag("ack:${alert.id}").semantics { contentDescription = "Acknowledge: ${alert.message}" },
                ) { Text("Acknowledge", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}
