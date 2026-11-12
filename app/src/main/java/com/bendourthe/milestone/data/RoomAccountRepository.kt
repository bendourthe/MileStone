package com.bendourthe.milestone.data

import androidx.room.withTransaction
import com.bendourthe.milestone.data.db.BalanceSnapshotEntity
import com.bendourthe.milestone.data.db.LoyaltyAccountEntity
import com.bendourthe.milestone.data.db.MileStoneDatabase
import com.bendourthe.milestone.domain.AccountRepository
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.BalanceOrigin
import com.bendourthe.milestone.domain.BalanceSnapshot
import com.bendourthe.milestone.domain.FailureReason
import com.bendourthe.milestone.domain.LoyaltyAccount
import com.bendourthe.milestone.domain.SyncResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomAccountRepository(private val db: MileStoneDatabase) : AccountRepository {
    private val dao = db.loyaltyDao()

    override fun observeAccounts(): Flow<List<LoyaltyAccount>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeHistory(programId: String): Flow<List<BalanceSnapshot>> =
        dao.observeHistory(programId).map { rows ->
            rows.map { BalanceSnapshot(it.programId, it.balance, BalanceOrigin.valueOf(it.origin), it.epochMs) }
        }

    override suspend fun addAccount(programId: String, status: AccountStatus, nowEpochMs: Long): Boolean =
        dao.insert(newAccount(programId, status, nowEpochMs))

    override suspend fun removeAccount(programId: String) = dao.delete(programId)

    override suspend fun applySyncResult(
        programId: String,
        result: SyncResult,
        completedEpochMs: Long,
        createIfMissing: Boolean,
    ) {
        db.withTransaction {
            if (createIfMissing) dao.insert(newAccount(programId, AccountStatus.CONNECTED, completedEpochMs))
            val current = dao.get(programId) ?: return@withTransaction
            when (result) {
                is SyncResult.Success -> {
                    if (dao.applyScraped(programId, result.balance, completedEpochMs) > 0) {
                        dao.insertSnapshot(
                            BalanceSnapshotEntity(programId = programId, balance = result.balance, origin = BalanceOrigin.SCRAPED.name, epochMs = completedEpochMs),
                        )
                    }
                    dao.setStatus(programId, AccountStatus.CONNECTED.name, null, 0, completedEpochMs)
                }
                SyncResult.NeedsReconnect ->
                    dao.setStatus(programId, AccountStatus.NEEDS_RECONNECT.name, null, current.consecutiveFailures, completedEpochMs)
                is SyncResult.Failure ->
                    dao.setStatus(programId, AccountStatus.ERROR.name, result.reason.code, current.consecutiveFailures + 1, completedEpochMs)
                SyncResult.Unsupported ->
                    dao.setStatus(programId, AccountStatus.UNSUPPORTED.name, null, current.consecutiveFailures, null)
            }
        }
    }

    override suspend fun markNeedsReconnect(programId: String) {
        val current = dao.get(programId) ?: return
        dao.setStatus(programId, AccountStatus.NEEDS_RECONNECT.name, null, current.consecutiveFailures, null)
    }

    override suspend fun reorder(orderedProgramIds: List<String>) = dao.reorder(orderedProgramIds)
}

private fun newAccount(programId: String, status: AccountStatus, nowEpochMs: Long) = LoyaltyAccountEntity(
    programId = programId,
    balance = null,
    balanceOrigin = null,
    lastUpdatedEpochMs = null,
    lastAttemptEpochMs = null,
    consecutiveFailures = 0,
    sortOrder = 0,
    status = status.name,
    lastErrorCode = null,
    addedEpochMs = nowEpochMs,
)

private fun LoyaltyAccountEntity.toDomain() = LoyaltyAccount(
    programId = programId,
    balance = balance,
    balanceOrigin = balanceOrigin?.let { runCatching { BalanceOrigin.valueOf(it) }.getOrNull() },
    lastUpdatedEpochMs = lastUpdatedEpochMs,
    lastAttemptEpochMs = lastAttemptEpochMs,
    consecutiveFailures = consecutiveFailures,
    sortOrder = sortOrder,
    status = runCatching { AccountStatus.valueOf(status) }.getOrDefault(AccountStatus.ERROR),
    lastError = FailureReason.fromCode(lastErrorCode),
)
