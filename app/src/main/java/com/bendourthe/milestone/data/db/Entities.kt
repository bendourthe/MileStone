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

/** A card on an issuer account, keyed by its last digits. Never holds a full card number or account key. */
@Entity(
    tableName = "cards",
    primaryKeys = ["programId", "lastDigits"],
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
data class CardEntity(
    val programId: String,
    val lastDigits: String,
    val name: String,
    val sortOrder: Int,
    val cardListReadEpochMs: Long?,
    val benefitsReadEpochMs: Long?,
)

@Entity(
    tableName = "benefits",
    foreignKeys = [
        ForeignKey(
            entity = CardEntity::class,
            parentColumns = ["programId", "lastDigits"],
            childColumns = ["programId", "lastDigits"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("programId", "lastDigits")],
)
data class BenefitEntity(
    @PrimaryKey val stableKey: String,
    val programId: String,
    val lastDigits: String,
    val section: String,
    val position: Int,
    val title: String,
    val earnedCents: Long?,
    val remainingCents: Long?,
    val targetCents: Long?,
    val periodLabel: String?,
    val periodEndEpochDay: Long?,
    val periodLength: String?,
    val completed: Boolean,
    val description: String?,
    val enrollment: String?,
)

/** One posted reminder per benefit and period; tied to the card so a removed card takes its records. */
@Entity(
    tableName = "benefit_reminders",
    primaryKeys = ["benefitKey", "periodEndEpochDay"],
    foreignKeys = [
        ForeignKey(
            entity = CardEntity::class,
            parentColumns = ["programId", "lastDigits"],
            childColumns = ["programId", "lastDigits"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("programId", "lastDigits")],
)
data class ReminderRecordEntity(
    val benefitKey: String,
    val periodEndEpochDay: Long,
    val programId: String,
    val lastDigits: String,
    val sentEpochMs: Long,
)
