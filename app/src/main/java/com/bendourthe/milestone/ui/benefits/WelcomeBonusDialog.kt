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
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import java.time.Instant
import java.time.ZoneOffset
import androidx.compose.ui.unit.dp
import com.bendourthe.milestone.domain.WelcomeBonusSetup
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

private fun welcomeCents(value: String): Long? {
    val text = value.trim()
    if (text.length > 20 || !Regex("(?:\\d{1,17}(?:\\.\\d{0,2})?|\\.\\d{1,2})").matches(text)) return null
    return runCatching { BigDecimal(text).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact() }.getOrNull()
}

internal fun welcomeBonusErrors(spent: String, target: String, deadline: String, reward: String): Map<String, String> = buildMap {
    if (welcomeCents(spent) == null) put("spent", "Enter a valid nonnegative amount.")
    if ((welcomeCents(target) ?: 0) <= 0) put("target", "Enter a target greater than zero.")
    if (deadline.isNotBlank() && (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(deadline.trim()) || runCatching { LocalDate.parse(deadline.trim()) }.isFailure)) put("deadline", "Use a valid date in YYYY-MM-DD format.")
    val label = reward.trim()
    if (label.length > 120 || label.any { it.isISOControl() }) put("reward", "Use at most 120 characters without control characters.")
}

internal fun welcomeBonusInput(spent: String, target: String, deadline: String, reward: String): WelcomeBonusSetup? {
    if (welcomeBonusErrors(spent, target, deadline, reward).isNotEmpty()) return null
    return WelcomeBonusSetup(checkNotNull(welcomeCents(spent)), checkNotNull(welcomeCents(target)), deadline.trim().takeIf { it.isNotEmpty() }?.let(LocalDate::parse), reward.trim())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WelcomeBonusDialog(initial: WelcomeBonusSetup?, onSave: (WelcomeBonusSetup?) -> Unit, onDismiss: () -> Unit) {
    var spent by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial?.spentCents?.let(::amountInputText).orEmpty())) }
    var target by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial?.targetCents?.let(::amountInputText).orEmpty())) }
    var deadline by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(normalizeDateEdit(TextFieldValue(initial?.deadline?.toString().orEmpty()))) }
    var reward by rememberSaveable { mutableStateOf(initial?.rewardLabel.orEmpty()) }
    var error by rememberSaveable { mutableStateOf(false) }
    var showCalendar by rememberSaveable { mutableStateOf(false) }
    val isoDeadline = dateInputText(deadline.text)
    val errors = if (error) welcomeBonusErrors(spent.text, target.text, isoDeadline, reward) else emptyMap()
    val focus = remember { listOf("spent", "target", "deadline", "reward").associateWith { FocusRequester() } }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    AlertDialog(
        modifier = Modifier.testTag("welcomeBonusDialog"),
        onDismissRequest = onDismiss,
        title = { Text("Welcome bonus") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Track your welcome-bonus spending and deadline.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(spent, { spent = normalizeAmountEdit(it); error = false }, label = { Text("Spent ($)") }, singleLine = true,
                    visualTransformation = AmountInputTransformation, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = "spent" in errors, supportingText = { errors["spent"]?.let { Text(it) } }, modifier = Modifier.focusRequester(focus.getValue("spent")).testTag("welcomeSpent"))
                OutlinedTextField(target, { target = normalizeAmountEdit(it); error = false }, label = { Text("Spend target ($)") }, singleLine = true,
                    visualTransformation = AmountInputTransformation, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = "target" in errors, supportingText = { errors["target"]?.let { Text(it) } }, modifier = Modifier.focusRequester(focus.getValue("target")).testTag("welcomeTarget"))
                OutlinedTextField(deadline, { deadline = normalizeDateEdit(it); error = false }, label = { Text("Deadline") }, singleLine = true,
                    visualTransformation = DateInputTransformation, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    trailingIcon = { IconButton(onClick = { focusManager.clearFocus(); showCalendar = true }, modifier = Modifier.testTag("welcomeCalendar")) { Icon(Icons.Filled.DateRange, contentDescription = "Choose deadline date") } },
                    isError = "deadline" in errors, supportingText = { Text(errors["deadline"] ?: "Optional. YYYY-MM-DD") }, modifier = Modifier.focusRequester(focus.getValue("deadline")).testTag("welcomeDeadline"))
                OutlinedTextField(reward, { reward = it; error = false }, label = { Text("Reward (optional)") }, singleLine = true,
                    visualTransformation = RewardInputTransformation, isError = "reward" in errors, supportingText = { errors["reward"]?.let { Text(it) } }, modifier = Modifier.focusRequester(focus.getValue("reward")).testTag("welcomeReward"))
                if (initial != null) TextButton(onClick = { onSave(null); onDismiss() }) { Text("Remove setup") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val setup = welcomeBonusInput(spent.text, target.text, isoDeadline, reward)
                if (setup == null) {
                    error = true
                    welcomeBonusErrors(spent.text, target.text, isoDeadline, reward).keys.firstOrNull()?.let { focus.getValue(it).requestFocus() }
                } else { onSave(setup); onDismiss() }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (showCalendar) {
        val initialDate = runCatching { LocalDate.parse(isoDeadline) }.getOrNull()?.takeIf { it.year in DatePickerDefaults.YearRange }
        val picker = rememberDatePickerState(initialSelectedDateMillis = initialDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(modifier = Modifier.testTag("welcomeDateDialog"), onDismissRequest = { showCalendar = false },
            confirmButton = { TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                picker.selectedDateMillis?.let {
                    val selected = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString().replace("-", "")
                    deadline = TextFieldValue(selected, TextRange(selected.length)); error = false
                }
                showCalendar = false
            }) { Text("Use date") } },
            dismissButton = { TextButton(onClick = { showCalendar = false }) { Text("Cancel") } }) {
            DatePicker(state = picker)
        }
    }
}
