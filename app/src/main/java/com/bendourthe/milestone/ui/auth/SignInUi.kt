package com.bendourthe.milestone.ui.auth

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.sync.webview.LoginBridge
import com.bendourthe.milestone.ui.dashboard.TileUiState

@Composable
fun SignInMenuItems(tile: TileUiState, signIn: SignInViewModel, closeMenu: () -> Unit) {
    // Signing in again is Refresh's job: an added program signs in with its saved sign-in, never from a menu item.
    if (!tile.canSync || !(tile.isConnected || tile.hasSavedSignIn)) return
    DropdownMenuItem(text = { Text("Sign out") }, onClick = {
        closeMenu()
        signIn.requestSignOut(tile.programId)
    })
}

/** Hosts the sign-in modal and sign-out confirmation, and routes their messages to [snackbarHostState]. */
@Composable
fun SignInHost(
    signIn: SignInViewModel,
    createBridge: (Context, Program) -> LoginBridge,
    snackbarHostState: SnackbarHostState,
    onReturnToDashboard: () -> Unit,
) {
    val active by signIn.active.collectAsStateWithLifecycle()
    val pendingSignOut by signIn.pendingSignOut.collectAsStateWithLifecycle()
    val editingAutoSignIn by signIn.editingAutoSignIn.collectAsStateWithLifecycle()
    val autoSignInStatuses by signIn.autoSignInStatuses.collectAsStateWithLifecycle()

    editingAutoSignIn?.let { program ->
        AutoSignInDialog(
            program = program,
            status = autoSignInStatuses[program.programId],
            onSave = signIn::saveAutoSignIn,
            onDismiss = signIn::cancelAutoSignIn,
            onSignInManually = {
                signIn.cancelAutoSignIn()
                signIn.open(program.programId)
            },
        )
    }

    LaunchedEffect(signIn) {
        signIn.messages.collect { message ->
            if (message.returnToDashboard) onReturnToDashboard()
            // showSnackbar waits until the message is dismissed, so awaiting it here would hold back the next return.
            message.text?.let { launch { snackbarHostState.showSnackbar(it) } }
        }
    }

    active?.let { program ->
        AuthWebViewModal(program, createBridge, onEvent = { signIn.onBridgeEvent(program, it) }, onClose = signIn::close)
    }

    pendingSignOut?.let { program ->
        AlertDialog(
            onDismissRequest = signIn::cancelSignOut,
            title = { Text(if (signIn.sharedSession) "Sign out of all programs?" else "Sign out of ${program.displayName}?") },
            text = {
                Text(
                    if (signIn.sharedSession) {
                        "This phone keeps one shared sign-in for every program, so all programs will need to reconnect. Balances stay."
                    } else {
                        "MileStone forgets this sign-in. The balance stays until you sign in again."
                    },
                )
            },
            confirmButton = { TextButton(onClick = signIn::confirmSignOut) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = signIn::cancelSignOut) { Text("Cancel") } },
        )
    }
}
