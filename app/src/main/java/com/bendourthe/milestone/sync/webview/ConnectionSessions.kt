package com.bendourthe.milestone.sync.webview

import com.bendourthe.milestone.DiagnosticLog
import com.bendourthe.milestone.data.credentials.CredentialVault
import com.bendourthe.milestone.data.session.SessionStore
import com.bendourthe.milestone.domain.CardSegment
import com.bendourthe.milestone.domain.Connection
import com.bendourthe.milestone.domain.ConnectionRepository

/**
 * Independent issuer sign-ins (ADR-0009). The first sign-in of a program is its legacy connection, whose id is the
 * program id: it keeps the existing `program_<id>` profile, the `<id>` saved sign-in and that sign-in's encryption
 * associated data, so nothing is moved or resealed. Each additional sign-in gets `<program>_<n>`, its own isolated
 * profile and saved sign-in, and is refused when the device cannot isolate profiles (no MULTI_PROFILE), because
 * two logins must never share one cookie jar.
 */
class ConnectionSessions(
    private val backend: ProfileBackend,
    private val sessions: SessionStore,
    private val vault: CredentialVault,
    private val connections: ConnectionRepository,
) {
    sealed interface AddResult {
        data class Added(val connection: Connection) : AddResult
        data object ProfilesUnsupported : AddResult
        data object LimitReached : AddResult
        data object NotTracked : AddResult
    }

    /** The legacy connection of [programId], created on first use with the owner's declared [segment]. */
    suspend fun legacy(programId: String, segment: CardSegment, nowEpochMs: Long): Connection? {
        connections.connections(programId).firstOrNull { it.connectionId == programId }?.let { return it }
        val connection = Connection(legacyId(programId), programId, segment, 0, nowEpochMs)
        return connection.takeIf { connections.addConnection(it) }
    }

    /** Adds one more isolated sign-in for [programId]. */
    suspend fun add(programId: String, segment: CardSegment, nowEpochMs: Long): AddResult {
        if (!backend.supportsProfiles) return AddResult.ProfilesUnsupported
        val existing = connections.connections(programId)
        if (existing.size >= MAX_CONNECTIONS) return AddResult.LimitReached
        val taken = existing.map { it.connectionId }.toSet()
        val id = (2..MAX_CONNECTIONS + 1).map { "${programId}_$it" }.first { it !in taken }
        val connection = Connection(id, programId, segment, (existing.maxOfOrNull { it.sortOrder } ?: 0) + 1, nowEpochMs)
        return if (connections.addConnection(connection)) AddResult.Added(connection) else AddResult.NotTracked
    }

    /**
     * Signs out one additional sign-in: its live WebViews, cookies and site storage, its profile, its session record
     * and its saved sign-in. Other sign-ins and the cards they reach are untouched. The legacy sign-in is signed out
     * through [AppContainer.signOut] instead, which keeps the program's existing sign-out behavior.
     */
    suspend fun signOutAdditional(connection: Connection) {
        require(!isLegacy(connection)) { "the legacy sign-in is signed out with its program" }
        val name = profileName(connection.connectionId)
        backend.destroyLiveWebViews(name)
        backend.clearProfileData(name)
        try {
            backend.deleteProfile(name)
        } catch (e: IllegalStateException) {
            DiagnosticLog.i(TAG, "Profile for ${connection.programId} still in use; erased now, deleted at next start")
        } catch (e: IllegalArgumentException) {
            DiagnosticLog.i(TAG, "Profile for ${connection.programId} still in use; erased now, deleted at next start")
        }
        sessions.clear(connection.connectionId)
        vault.delete(connection.connectionId)
        connections.removeConnection(connection.connectionId)
    }

    /**
     * After the legacy sign-in of [programId] is signed out: returns true when other sign-ins remain, in which case
     * only the legacy connection (and the cards only it reached) is removed; false means the caller clears the
     * program's cards as before.
     */
    suspend fun afterLegacySignOut(programId: String): Boolean {
        val others = connections.connections(programId).filterNot { it.connectionId == legacyId(programId) }
        if (others.isEmpty()) return false
        connections.removeConnection(legacyId(programId))
        return true
    }

    /** Profile names that startup cleanup must keep for [programId]'s additional sign-ins. */
    suspend fun additionalProfiles(programId: String): Set<String> =
        connections.connections(programId).filterNot(::isLegacy).map { profileName(it.connectionId) }.toSet()

    companion object {
        private const val TAG = "MileStoneSession"
        const val MAX_CONNECTIONS = 4

        fun legacyId(programId: String) = programId

        fun isLegacy(connection: Connection) = connection.connectionId == legacyId(connection.programId)

        fun profileName(connectionId: String) = SessionProfiles.profileName(connectionId)
    }
}
