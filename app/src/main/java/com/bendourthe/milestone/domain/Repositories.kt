package com.bendourthe.milestone.domain

import kotlinx.coroutines.flow.Flow

interface CatalogRepository {
    val programs: List<Program>
    fun program(programId: String): Program?
    fun search(query: String): List<Program>
}

interface AccountRepository {
    fun observeAccounts(): Flow<List<LoyaltyAccount>>
    fun observeHistory(programId: String): Flow<List<BalanceSnapshot>>

    /** Returns false when the program is already tracked. */
    suspend fun addAccount(programId: String, status: AccountStatus, nowEpochMs: Long): Boolean
    suspend fun removeAccount(programId: String)
    /** With [createIfMissing], a missing account is created as CONNECTED in the same transaction as the result. */
    suspend fun applySyncResult(
        programId: String,
        result: SyncResult,
        completedEpochMs: Long,
        createIfMissing: Boolean = false,
    )
    suspend fun markNeedsReconnect(programId: String)

    /** [orderedProgramIds] must contain exactly the tracked programs. */
    suspend fun reorder(orderedProgramIds: List<String>)
}

interface SettingsRepository {
    val sortMode: Flow<SortMode>
    suspend fun setSortMode(mode: SortMode)
    val refreshInterval: Flow<RefreshInterval>
    suspend fun setRefreshInterval(interval: RefreshInterval)
}
