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

/**
 * A card on an issuer account, keyed by its local card id (legacy cards keep their last digits as the id).
 * [lastDigits] is display-only. [segment] is derived from connections; [segmentOverride] is the owner's choice.
 * Never holds a full card number or account key.
 */
@Entity(
    tableName = "cards",
    primaryKeys = ["programId", "cardId"],
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
    val cardId: String,
    val lastDigits: String?,
    val name: String,
    val sortOrder: Int,
    val cardListReadEpochMs: Long?,
    val benefitsReadEpochMs: Long?,
    @ColumnInfo(defaultValue = "0") val benefitsReadGeneration: Long = 0,
    val segment: String? = null,
    val segmentOverride: String? = null,
)

/** One issuer sign-in. Deleting the program removes its connections. */
@Entity(
    tableName = "issuer_connections",
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
data class ConnectionEntity(
    @PrimaryKey val connectionId: String,
    val programId: String,
    val segment: String,
    val sortOrder: Int,
    val addedEpochMs: Long,
    val inventoryReadEpochMs: Long?,
    @ColumnInfo(defaultValue = "0") val inventoryComplete: Boolean,
)

/**
 * Which connection reaches which card, through which keyed binding hash. A card with no coverage row is either a
 * legacy last-digits card or is removed when its last coverage goes.
 */
@Entity(
    tableName = "card_coverage",
    primaryKeys = ["connectionId", "programId", "cardId"],
    foreignKeys = [
        ForeignKey(
            entity = ConnectionEntity::class,
            parentColumns = ["connectionId"],
            childColumns = ["connectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CardEntity::class,
            parentColumns = ["programId", "cardId"],
            childColumns = ["programId", "cardId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["connectionId", "bindingHash"], unique = true),
        Index("programId", "bindingHash"),
        Index("programId", "cardId"),
    ],
)
data class CardCoverageEntity(
    val connectionId: String,
    val programId: String,
    val cardId: String,
    val bindingHash: String,
    val lastSeenEpochMs: Long,
)

@Entity(
    tableName = "benefits",
    foreignKeys = [
        ForeignKey(
            entity = CardEntity::class,
            parentColumns = ["programId", "cardId"],
            childColumns = ["programId", "cardId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("programId", "cardId")],
)
data class BenefitEntity(
    @PrimaryKey val stableKey: String,
    val programId: String,
    val cardId: String,
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
            parentColumns = ["programId", "cardId"],
            childColumns = ["programId", "cardId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("programId", "cardId")],
)
data class ReminderRecordEntity(
    val benefitKey: String,
    val periodEndEpochDay: Long,
    val programId: String,
    val cardId: String,
    val sentEpochMs: Long,
)
