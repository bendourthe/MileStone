package com.bendourthe.milestone.ui.benefits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bendourthe.milestone.data.session.SessionStore
import com.bendourthe.milestone.domain.CardSegment
import com.bendourthe.milestone.domain.Connection
import com.bendourthe.milestone.domain.ConnectionRepository
import com.bendourthe.milestone.sync.webview.ConnectionSessions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** One issuer sign-in as the Benefits page lists it (ADR-0009). */
data class ConnectionUi(
    val connectionId: String,
    val label: String,
    val segment: CardSegment,
    val primary: Boolean,
    val signedIn: Boolean,
)

/**
 * The issuer sign-ins of a program: their Personal or Business declaration, adding another isolated sign-in, and
 * signing one out. The program's own (first) sign-in is signed out from its dashboard tile as before.
 */
class ConnectionsViewModel(
    private val connections: ConnectionRepository,
    private val sessions: ConnectionSessions,
    private val sessionStore: SessionStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val messageChannel = Channel<String>(Channel.BUFFERED)

    /** Short results to show once, such as a refused additional sign-in. */
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    fun observe(programId: String): Flow<List<ConnectionUi>> = connections.observeConnections(programId)
        .onStart { sessions.legacy(programId, CardSegment.PERSONAL, clock()) }
        .map { list ->
            list.mapIndexed { index, connection ->
                ConnectionUi(
                    connectionId = connection.connectionId,
                    label = "Sign-in ${index + 1}",
                    segment = connection.segment,
                    primary = ConnectionSessions.isLegacy(connection),
                    signedIn = sessionStore.get(connection.connectionId) != null,
                )
            }
        }

    fun setSegment(connectionId: String, segment: CardSegment) {
        viewModelScope.launch { connections.setConnectionSegment(connectionId, segment) }
    }

    /** Adds a sign-in and hands its id to [openSite], which shows the site in that sign-in's own profile. */
    fun add(programId: String, segment: CardSegment, openSite: (connectionId: String) -> Unit) {
        viewModelScope.launch {
            when (val result = sessions.add(programId, segment, clock())) {
                is ConnectionSessions.AddResult.Added -> openSite(result.connection.connectionId)
                ConnectionSessions.AddResult.ProfilesUnsupported ->
                    messageChannel.send("This phone can't keep a second sign-in separate. Update Android System WebView, then try again.")
                ConnectionSessions.AddResult.LimitReached ->
                    messageChannel.send("You can add up to ${ConnectionSessions.MAX_CONNECTIONS} sign-ins.")
                ConnectionSessions.AddResult.NotTracked -> messageChannel.send("Add this program first.")
            }
        }
    }

    /** Signs out one additional sign-in; cards only it reached are removed, everything else stays. */
    fun signOut(programId: String, connectionId: String) {
        viewModelScope.launch {
            val connection: Connection = connections.connections(programId).firstOrNull { it.connectionId == connectionId } ?: return@launch
            if (ConnectionSessions.isLegacy(connection)) return@launch
            sessions.signOutAdditional(connection)
        }
    }
}
