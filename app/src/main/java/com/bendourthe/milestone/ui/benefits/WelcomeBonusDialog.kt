package com.bendourthe.milestone.ui.benefits

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bendourthe.milestone.domain.WelcomeBonusSetup
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

internal fun welcomeBonusInput(spent: String, target: String, deadline: String, reward: String): WelcomeBonusSetup? = runCatching {
    fun cents(value: String): Long = BigDecimal(value.trim()).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
    WelcomeBonusSetup(cents(spent), cents(target), deadline.trim().takeIf { it.isNotEmpty() }?.let(LocalDate::parse), reward.trim())
}.getOrNull()

@Composable
internal fun WelcomeBonusDialog(initial: WelcomeBonusSetup?, onSave: (WelcomeBonusSetup?) -> Unit, onDismiss: () -> Unit) {
    var spent by rememberSaveable { mutableStateOf(initial?.spentCents?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty()) }
    var target by rememberSaveable { mutableStateOf(initial?.targetCents?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty()) }
    var deadline by rememberSaveable { mutableStateOf(initial?.deadline?.toString().orEmpty()) }
    var reward by rememberSaveable { mutableStateOf(initial?.rewardLabel.orEmpty()) }
    var error by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Welcome bonus") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Track your welcome-bonus spending and deadline.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(spent, { spent = it; error = false }, label = { Text("Spent ($)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.testTag("welcomeSpent"))
                OutlinedTextField(target, { target = it; error = false }, label = { Text("Spend target ($)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.testTag("welcomeTarget"))
                OutlinedTextField(deadline, { deadline = it; error = false }, label = { Text("Deadline (YYYY-MM-DD, optional)") }, singleLine = true,
                    modifier = Modifier.testTag("welcomeDeadline"))
                OutlinedTextField(reward, { reward = it; error = false }, label = { Text("Reward (optional)") }, singleLine = true,
                    modifier = Modifier.testTag("welcomeReward"))
                if (error) Text("Enter valid amounts and an optional YYYY-MM-DD deadline.", color = MaterialTheme.colorScheme.error)
                if (initial != null) TextButton(onClick = { onSave(null); onDismiss() }) { Text("Remove setup") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val setup = welcomeBonusInput(spent, target, deadline, reward)
                if (setup == null) error = true else { onSave(setup); onDismiss() }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
