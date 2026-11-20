package com.bendourthe.milestone.domain

import kotlinx.coroutines.flow.Flow

/**
 * One authenticated sign-in to an issuer (ADR-0009). An issuer program can have several; each has its own
 * isolated browser profile and credentials. [segment] is the owner's declaration of what the sign-in is for,
 * which classifies the cards only it can reach.
 */
data class Connection(
    val connectionId: String,
    val programId: String,
    val segment: CardSegment,
    val sortOrder: Int,
    val addedEpochMs: Long,
    val inventoryReadEpochMs: Long? = null,
    val inventoryComplete: Boolean = false,
) {
    init {
        require(isValidConnectionId(connectionId)) { "connection id must be lowercase letters, digits, and underscores" }
    }

    companion object {
        private val ID = Regex("^[a-z][a-z0-9_]{1,47}$")

        fun isValidConnectionId(value: String): Boolean = ID.matches(value)
    }
}

/**
 * One card as one connection's card selector reports it. [bindingHash] is a keyed, per-install hash of the
 * page's control identity for that card: equal hashes from two connections are the proven binding that lets
 * them share one local card. The raw control identity, a full card number, or an issuer account key is never
 * stored. [lastDigits] is display-only and never used to match cards.
 */
data class SourceCard(val bindingHash: String, val name: String, val lastDigits: String?) {
    init {
        require(BINDING.matches(bindingHash)) { "binding hash must be 64 lowercase hex digits" }
        require(lastDigits == null || CardKey.isValidLastDigits(lastDigits)) { "last digits must be 4 or 5 digits" }
    }

    private companion object {
        val BINDING = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * The cards one connection's read saw. Only a [complete] inventory may remove this connection's coverage of
 * cards it no longer lists; a partial inventory only adds or refreshes.
 */
data class ConnectionInventory(
    val programId: String,
    val connectionId: String,
    val cards: List<SourceCard>,
    val complete: Boolean,
)

/** What applying a connection inventory did. */
enum class InventoryOutcome {
    /** Cards and coverage were updated. */
    APPLIED,

    /** An inventory read at or after this one was already applied for the connection; nothing changed. */
    STALE,

    /** The connection or its program is not tracked; nothing changed. */
    UNKNOWN_CONNECTION,

    /** The inventory repeated a binding hash; nothing changed. */
    REJECTED_DUPLICATE,

    /**
     * A complete inventory shared no card with what the connection already covers, which looks like the issuer's
     * card controls changing identity rather than every card changing at once; nothing changed.
     */
    UNMATCHED,
}

/** The issuer sign-ins the owner has added. */
interface ConnectionRepository {
    fun observeConnections(programId: String): Flow<List<Connection>>

    suspend fun connections(programId: String): List<Connection>

    suspend fun connection(connectionId: String): Connection?

    /** Adds [connection]; returns false when its id is taken or its program is not tracked. */
    suspend fun addConnection(connection: Connection): Boolean

    /**
     * Removes the connection and its coverage. Cards that no other connection reaches are removed with their
     * benefits and reminder records; cards another connection reaches are kept.
     */
    suspend fun removeConnection(connectionId: String)

    /** Changes the owner's declaration and re-derives the segment of the cards this connection reaches. */
    suspend fun setConnectionSegment(connectionId: String, segment: CardSegment)
}
