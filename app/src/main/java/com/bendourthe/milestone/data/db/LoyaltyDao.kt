package com.bendourthe.milestone.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LoyaltyDao {
    @Query("SELECT * FROM loyalty_accounts ORDER BY sortOrder")
    abstract fun observeAll(): Flow<List<LoyaltyAccountEntity>>

    @Query("SELECT * FROM balance_snapshots WHERE programId = :programId ORDER BY epochMs, id")
    abstract fun observeHistory(programId: String): Flow<List<BalanceSnapshotEntity>>

    @Query("SELECT programId FROM loyalty_accounts")
    abstract suspend fun allIds(): List<String>

    @Query("SELECT MAX(sortOrder) FROM loyalty_accounts")
    abstract suspend fun maxSortOrder(): Int?

    @Query("SELECT * FROM loyalty_accounts WHERE programId = :programId")
    abstract suspend fun get(programId: String): LoyaltyAccountEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIgnoring(entity: LoyaltyAccountEntity): Long

    @Insert
    abstract suspend fun insertSnapshot(snapshot: BalanceSnapshotEntity)

    @Query("DELETE FROM loyalty_accounts WHERE programId = :programId")
    abstract suspend fun delete(programId: String)

    @Query("UPDATE loyalty_accounts SET sortOrder = :sortOrder WHERE programId = :programId")
    protected abstract suspend fun setSortOrder(programId: String, sortOrder: Int)

    /** Inserts a new account at the end of the custom order; returns false if it already exists. */
    @Transaction
    open suspend fun insert(entity: LoyaltyAccountEntity): Boolean {
        val next = (maxSortOrder() ?: -1) + 1
        return insertIgnoring(entity.copy(sortOrder = next)) != -1L
    }

    @Transaction
    open suspend fun reorder(orderedIds: List<String>) {
        val stored = allIds()
        require(orderedIds.size == stored.size && orderedIds.toSet() == stored.toSet()) {
            "reorder ids do not match the tracked programs"
        }
        orderedIds.forEachIndexed { index, id -> setSortOrder(id, index) }
    }

    @Query(
        """
        UPDATE loyalty_accounts
        SET balance = :balance, balanceOrigin = 'SCRAPED', lastUpdatedEpochMs = :completedEpochMs
        WHERE programId = :programId
        """,
    )
    abstract suspend fun applyScraped(programId: String, balance: Long, completedEpochMs: Long): Int

    @Query(
        """
        UPDATE loyalty_accounts
        SET status = :status, lastErrorCode = :errorCode, consecutiveFailures = :consecutiveFailures,
            lastAttemptEpochMs = COALESCE(:attemptEpochMs, lastAttemptEpochMs)
        WHERE programId = :programId
        """,
    )
    abstract suspend fun setStatus(
        programId: String,
        status: String,
        errorCode: String?,
        consecutiveFailures: Int,
        attemptEpochMs: Long?,
    ): Int
}
