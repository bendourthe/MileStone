package com.bendourthe.milestone.ui.auth

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.bendourthe.milestone.data.credentials.AutoSignInStatus
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.sync.background.KeepAliveScheduler

/**
 * Saves the sign-in MileStone uses when a program's session ends. The fields accept password-manager autofill;
 * the values go only to the encrypted on-phone vault.
 */
@Composable
fun AutoSignInDialog(
    program: Program,
    status: AutoSignInStatus?,
    onSave: (username: String, password: String, readTexts: Boolean, lastName: String) -> Unit,
    onDismiss: () -> Unit,
    onSignInManually: () -> Unit,
) {
    val context = LocalContext.current
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var lastName by remember { mutableStateOf("") }
    var readTexts by remember { mutableStateOf(status?.readTexts ?: program.textKeywords.isNotEmpty()) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val texts = readTexts && granted[Manifest.permission.RECEIVE_SMS] != false && hasPermission(context, Manifest.permission.RECEIVE_SMS)
        onSave(username, password, texts, lastName)
        requestUnrestrictedBattery(context)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Automatic sign-in") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Save your ${program.displayName} sign-in once. MileStone signs in with it now, and again when you open MileStone after the session ends.")
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                            )
                        }
                    },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                )
                if (program.asksLastName) {
                    OutlinedTextField(
                        value = lastName,
                        onValueChange = { lastName = it },
                        label = { Text("Last name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.PersonLastName },
                    )
                }
                if (program.textKeywords.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = readTexts, onCheckedChange = { readTexts = it })
                        Text("Enter codes texted by ${program.displayName}")
                    }
                }
                Text(
                    "Encrypted with a key that never leaves this phone. Never backed up or sent anywhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty(), onClick = {
                val needed = buildList {
                    if (readTexts && !hasPermission(context, Manifest.permission.RECEIVE_SMS)) add(Manifest.permission.RECEIVE_SMS)
                    if (Build.VERSION.SDK_INT >= 33 && !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                if (needed.isEmpty()) {
                    onSave(username, password, readTexts, lastName)
                    requestUnrestrictedBattery(context)
                } else {
                    permissions.launch(needed.toTypedArray())
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onSignInManually) { Text("Sign in myself") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun hasPermission(context: Context, permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/** Background refresh runs only when MileStone is exempt from battery optimization; Android asks the user once. */
@SuppressLint("BatteryLife")
private fun requestUnrestrictedBattery(context: Context) {
    if (KeepAliveScheduler.isUnrestricted(context)) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
        )
    }
}
