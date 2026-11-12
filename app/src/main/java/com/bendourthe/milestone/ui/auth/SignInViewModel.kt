package com.bendourthe.milestone.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bendourthe.milestone.data.credentials.AutoSignInStatus
import com.bendourthe.milestone.data.credentials.Credentials
import com.bendourthe.milestone.domain.CatalogRepository
import com.bendourthe.milestone.domain.FailureReason
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.domain.SyncResult
import com.bendourthe.milestone.sync.extract.BridgeEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** A null [text] only returns to the dashboard, where the tile itself shows the sign-in and the balance. */
data class SignInMessage(val text: String?, val returnToDashboard: Boolean)

class SignInViewModel(
    private val catalog: CatalogRepository,
    private val applyInteractive: suspend (programId: String, result: SyncResult) -> SyncResult,
    private val signOutProgram: suspend (programId: String) -> Unit,
    /** True when programs share one cookie jar, so signing out of one signs out of all. */
    val sharedSession: Boolean,
    private val locale: Locale = Locale.getDefault(),
    val autoSignInStatuses: StateFlow<Map<String, AutoSignInStatus>> = MutableStateFlow(emptyMap()),
    private val saveSignIn: suspend (programId: String, credentials: Credentials, readTexts: Boolean) -> Unit = { _, _, _ -> },
    private val allowAutoSignIn: suspend (programId: String) -> Unit = {},
    /** Signs in out of sight with the saved sign-in and writes the result. */
    private val connectHidden: suspend (programId: String) -> SyncResult = { SyncResult.NeedsReconnect },
    /** The capture build shows the site while it signs in, so the user can capture the balance page. */
    private val showSite: Boolean = false,
) : ViewModel() {
    private val _editingAutoSignIn = MutableStateFlow<Program?>(null)

    /** The program whose sign-in the user is entering before its first automatic sign-in. */
    val editingAutoSignIn: StateFlow<Program?> = _editingAutoSignIn.asStateFlow()

    fun cancelAutoSignIn() {
        _editingAutoSignIn.value = null
    }

    fun saveAutoSignIn(username: String, password: String, readTexts: Boolean, lastName: String = "") {
        val program = _editingAutoSignIn.value ?: return
        if (password.isEmpty()) return
        _editingAutoSignIn.value = null
        viewModelScope.launch {
            saveSignIn(program.programId, Credentials(username.trim(), password, lastName.trim()), readTexts)
            signInHidden(program)
        }
    }

    /**
     * The main sign-in action for Add program and a tapped sign-in chip. Without a saved sign-in, the user first saves it.
     * MileStone then signs in out of sight; the site opens only when that sign-in could not finish by itself, so the
     * user signs in there once. Asking again from inside MileStone lifts a pause left by a stopped attempt; a request
     * from outside the app ([liftPause] false, such as a notification tap) never does, so another app cannot make
     * MileStone type a refused password again.
     */
    fun connect(programId: String, liftPause: Boolean = true) {
        if (_active.value != null) return
        val program = catalog.program(programId)?.takeIf { it.canSync } ?: return
        if (autoSignInStatuses.value[programId]?.saved != true) {
            _editingAutoSignIn.value = program
            return
        }
        viewModelScope.launch {
            if (liftPause) allowAutoSignIn(programId)
            signInHidden(program)
        }
    }

    private val _connecting = MutableStateFlow<Set<String>>(emptySet())

    /** Programs signing in out of sight. */
    val connecting: StateFlow<Set<String>> = _connecting.asStateFlow()

    private suspend fun signInHidden(program: Program) {
        if (showSite) {
            _active.value = program
            return
        }
        if (program.programId in _connecting.value) return
        _connecting.value += program.programId
        messageChannel.send(SignInMessage(null, true))
        val result = try {
            connectHidden(program.programId)
        } finally {
            _connecting.value -= program.programId
        }
        when (result) {
            is SyncResult.Success -> Unit
            // A sign-in page that comes back after submitting is not proof of a wrong password: sites also return it for
            // an extra step or a bot check, so the site opens to show what it wants instead of asking for the password again.
            SyncResult.Failure(FailureReason.PasswordRefused), SyncResult.NeedsReconnect, SyncResult.Failure(FailureReason.NeedsAttention),
            SyncResult.Failure(FailureReason.CodeMissing), SyncResult.Failure(FailureReason.SignInUnfinished) -> {
                messageChannel.send(SignInMessage("${program.displayName} needs you to sign in once", false))
                if (_active.value == null) _active.value = program
            }
            is SyncResult.Failure ->
                messageChannel.send(SignInMessage("Couldn't update ${program.displayName}: ${result.reason.explanation()}", false))
            SyncResult.Unsupported ->
                messageChannel.send(SignInMessage("This phone can't sign in to ${program.displayName}. Update Android System WebView from the Play Store, then try again.", false))
        }
    }

    private val _active = MutableStateFlow<Program?>(null)
    val active: StateFlow<Program?> = _active.asStateFlow()

    private val _pendingSignOut = MutableStateFlow<Program?>(null)
    val pendingSignOut: StateFlow<Program?> = _pendingSignOut.asStateFlow()

    private val messageChannel = Channel<SignInMessage>(Channel.BUFFERED)
    val messages: Flow<SignInMessage> = messageChannel.receiveAsFlow()

    fun open(programId: String) {
        if (_active.value != null) return
        _active.value = catalog.program(programId)?.takeIf { it.canSync }
    }

    /** Closing before a balance arrives writes nothing. */
    fun close() {
        _active.value = null
    }

    fun onBridgeEvent(program: Program, event: BridgeEvent) {
        if (_active.value?.programId != program.programId) return
        when (event) {
            BridgeEvent.LoginRequired, is BridgeEvent.AutoSignInStopped -> Unit
            is BridgeEvent.Extracted -> {
                close()
                viewModelScope.launch {
                    val message = when (val written = applyInteractive(program.programId, SyncResult.Success(event.balance))) {
                        is SyncResult.Success -> SignInMessage(null, true)
                        else -> SignInMessage("Couldn't save the ${program.displayName} balance", false)
                    }
                    messageChannel.send(message)
                }
            }
            is BridgeEvent.Failed -> {
                close()
                messageChannel.trySend(SignInMessage("Couldn't update ${program.displayName}: ${event.reason.explanation()}", false))
            }
            BridgeEvent.Unsupported -> {
                close()
                messageChannel.trySend(SignInMessage("This phone can't sign in to ${program.displayName}. Update Android System WebView from the Play Store, then try again.", false))
            }
        }
    }

    fun requestSignOut(programId: String) {
        _pendingSignOut.value = catalog.program(programId)
    }

    fun cancelSignOut() {
        _pendingSignOut.value = null
    }

    fun confirmSignOut() {
        val program = _pendingSignOut.value ?: return
        _pendingSignOut.value = null
        viewModelScope.launch {
            signOutProgram(program.programId)
            messageChannel.send(SignInMessage(if (sharedSession) "Signed out of all programs" else "Signed out of ${program.displayName}", false))
        }
    }

    private fun FailureReason.explanation(): String = when (this) {
        FailureReason.Timeout -> "the balance didn't appear in time"
        FailureReason.Tls -> "the connection wasn't secure"
        is FailureReason.Network -> "the site didn't load"
        FailureReason.Unparseable, FailureReason.SelectorAmbiguous -> "the balance couldn't be read"
        FailureReason.RendererGone -> "the page stopped responding"
        else -> "something went wrong"
    }
}
