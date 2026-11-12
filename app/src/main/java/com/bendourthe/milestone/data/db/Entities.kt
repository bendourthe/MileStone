package com.bendourthe.milestone.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "loyalty_accounts")
data class LoyaltyAccountEntity(
    @PrimaryKey val programId: String,
    val balance: Long?,
    val balanceOrigin: String?,
    val lastUpdatedEpochMs: Long?,
    val lastAttemptEpochMs: Long?,
    @ColumnInfo(defaultValue = "0") val consecutiveFailures: Int,
    val sortOrder: Int,
    val status: String,
    val lastErrorCode: String?,
    val addedEpochMs: Long,
)

@Entity(
    tableName = "balance_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = LoyaltyAccountEntity::class,
            parentColumns = ["programId"],
            childColumns = ["programId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("programId")],
)
data class BalanceSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val programId: String,
    val balance: Long,
    val origin: String,
    val epochMs: Long,
)
