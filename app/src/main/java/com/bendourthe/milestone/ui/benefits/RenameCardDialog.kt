package com.bendourthe.milestone.ui.benefits

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.bendourthe.milestone.domain.BenefitsDisplayPreferences

/**
 * Names a card on this phone. [current] is the name shown now and [issuerName] the issuer's, offered as the way back.
 * [onSave] gets the new name, or null to show the issuer's name again.
 */
@Composable
internal fun RenameCardDialog(current: String, issuerName: String, renamed: Boolean, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    val clean = BenefitsDisplayPreferences.cleanCustomName(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename card") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= BenefitsDisplayPreferences.MAX_CUSTOM_NAME) text = it },
                label = { Text("Card name") },
                supportingText = { Text("Shown on this phone only. The issuer's name is $issuerName.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                modifier = Modifier.testTag("renameCardField"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(clean); onDismiss() }, enabled = clean != null, modifier = Modifier.testTag("renameCardSave")) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (renamed) {
                    TextButton(onClick = { onSave(null); onDismiss() }, modifier = Modifier.testTag("renameCardReset")) { Text("Use issuer name") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
