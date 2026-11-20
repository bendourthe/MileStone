package com.bendourthe.milestone.data.db

import android.database.SQLException
import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.bendourthe.milestone.DiagnosticLog
import com.bendourthe.milestone.domain.Benefit
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.Card
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.domain.CardList
import com.bendourthe.milestone.domain.CardRepository
import com.bendourthe.milestone.domain.CardSegment
import com.bendourthe.milestone.domain.Connection
import com.bendourthe.milestone.domain.ConnectionInventory
import com.bendourthe.milestone.domain.ConnectionRepository
import com.bendourthe.milestone.domain.Enrollment
import com.bendourthe.milestone.domain.InventoryOutcome
import com.bendourthe.milestone.domain.PeriodLength
import com.bendourthe.milestone.domain.ReminderRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * Stores cards, issuer connections and their coverage, benefits, and sent-reminder records. Each apply runs in
 * one transaction; the caller (SyncManager) serializes applies per program. Failures log the exception class and
 * counts only, never a card name, last digits, binding hash, or benefit title.
 */
class RoomCardRepository(private val db: MileStoneDatabase) : CardRepository, ConnectionRepository {
    private val dao = db.cardDao()

    override fun observeCards(programId: String): Flow<List<Card>> =
        dao.observeCards(programId).map { rows -> rows.map { it.toDomain() } }

    override fun observeBenefits(cardKey: CardKey): Flow<List<Benefit>> =
        dao.observeBenefits(cardKey.programId, cardKey.cardId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun applyCardList(programId: String, cards: CardList, readEpochMs: Long) = logged("card list", cards.cards.size) {
        db.withTransaction {
            if (dao.accountCount(programId) == 0) return@withTransaction
            cards.cards.forEachIndexed { index, draft ->
                val existing = dao.card(programId, draft.lastDigits)
                dao.upsertCard(
                    CardEntity(
                        programId = programId,
                        cardId = draft.lastDigits,
                        lastDigits = draft.lastDigits,
                        name = draft.name,
                        sortOrder = index,
                        cardListReadEpochMs = readEpochMs,
                        benefitsReadEpochMs = existing?.benefitsReadEpochMs,
                        benefitsReadGeneration = existing?.benefitsReadGeneration ?: 0,
                        segment = existing?.segment,
                        segmentOverride = existing?.segmentOverride,
                    ),
                )
            }
            if (cards.complete && cards.cards.isNotEmpty()) {
                // Only legacy last-digits cards are pruned here; a card a connection reaches is never removed by this path.
                val listed = cards.cards.map { it.lastDigits }.toSet()
                val missing = dao.uncoveredCardIds(programId).filterNot { it in listed }
                if (missing.isNotEmpty()) dao.deleteCards(programId, missing)
            }
        }
    }

    override suspend fun applyConnectionInventory(inventory: ConnectionInventory, readEpochMs: Long): InventoryOutcome {
        var outcome = InventoryOutcome.APPLIED
        logged("connection inventory", inventory.cards.size) {
            outcome = db.withTransaction { applyInventory(inventory, readEpochMs) }
        }
        return outcome
    }

    private suspend fun applyInventory(inventory: ConnectionInventory, readEpochMs: Long): InventoryOutcome {
        val programId = inventory.programId
        val connection = dao.connection(inventory.connectionId)
        if (connection == null || connection.programId != programId || dao.accountCount(programId) == 0) {
            return InventoryOutcome.UNKNOWN_CONNECTION
        }
        if (inventory.cards.map { it.bindingHash }.toSet().size != inventory.cards.size) return InventoryOutcome.REJECTED_DUPLICATE
        val previous = connection.inventoryReadEpochMs
        if (previous != null && readEpochMs <= previous) return InventoryOutcome.STALE
        val own = dao.coverageOf(connection.connectionId).associateBy { it.bindingHash }
        // Replacing every card at once would delete them with their benefits, preferences and reminder history.
        if (inventory.complete && own.isNotEmpty() && inventory.cards.isNotEmpty() && inventory.cards.none { it.bindingHash in own }) {
            return InventoryOutcome.UNMATCHED
        }
        var nextSort = dao.maxSortOrder(programId) + 1
        val listed = LinkedHashSet<String>()
        for (source in inventory.cards) {
            val cardId = own[source.bindingHash]?.cardId ?: boundElsewhere(programId, source.bindingHash) ?: newCardId(programId)
            val existing = dao.card(programId, cardId)
            dao.upsertCard(
                CardEntity(
                    programId = programId,
                    cardId = cardId,
                    lastDigits = source.lastDigits ?: existing?.lastDigits,
                    name = source.name,
                    sortOrder = existing?.sortOrder ?: nextSort++,
                    cardListReadEpochMs = readEpochMs,
                    benefitsReadEpochMs = existing?.benefitsReadEpochMs,
                    benefitsReadGeneration = existing?.benefitsReadGeneration ?: 0,
                    segment = existing?.segment,
                    segmentOverride = existing?.segmentOverride,
                ),
            )
            dao.upsertCoverage(CardCoverageEntity(connection.connectionId, programId, cardId, source.bindingHash, readEpochMs))
            listed += cardId
        }
        val touched = LinkedHashSet(listed)
        if (inventory.complete && inventory.cards.isNotEmpty()) {
            val unlisted = own.values.map { it.cardId }.filterNot { it in listed }
            if (unlisted.isNotEmpty()) {
                dao.deleteCoverage(connection.connectionId, unlisted)
                val orphans = unlisted.filter { dao.coverageOfCard(programId, it).isEmpty() }
                if (orphans.isNotEmpty()) dao.deleteCards(programId, orphans)
                touched += unlisted - orphans.toSet()
            }
        }
        touched.forEach { deriveSegment(programId, it) }
        dao.setInventoryRead(connection.connectionId, readEpochMs, inventory.complete)
        return InventoryOutcome.APPLIED
    }

    /** The one card another connection binds to [bindingHash]; null when none, or when the binding is ambiguous. */
    private suspend fun boundElsewhere(programId: String, bindingHash: String): String? =
        dao.coverageByBinding(programId, bindingHash).map { it.cardId }.distinct().singleOrNull()

    private suspend fun newCardId(programId: String): String {
        repeat(8) {
            val id = CardKey.newLocalId()
            if (dao.card(programId, id) == null) return id
        }
        throw SQLiteConstraintException("card id collision")
    }

    /** Personal when any Personal connection reaches the card, Business when only Business ones do. */
    private suspend fun deriveSegment(programId: String, cardId: String) {
        val segments = dao.reachingSegments(programId, cardId)
        val derived = when {
            CardSegment.PERSONAL.name in segments -> CardSegment.PERSONAL.name
            CardSegment.BUSINESS.name in segments -> CardSegment.BUSINESS.name
            else -> null
        }
        dao.setSegment(programId, cardId, derived)
    }

    override suspend fun cardKeyForBinding(programId: String, bindingHash: String): CardKey? =
        boundElsewhere(programId, bindingHash)?.let { CardKey(programId, it) }

    override suspend fun setCardSegmentOverride(cardKey: CardKey, segment: CardSegment?) =
        dao.setSegmentOverride(cardKey.programId, cardKey.cardId, segment?.name)

    override suspend fun applyCardBenefits(cardKey: CardKey, benefits: List<Benefit>, readEpochMs: Long, sections: Set<BenefitSection>) {
        if (sections.isEmpty()) return
        logged("card benefits", benefits.size) {
            db.withTransaction {
                if (dao.card(cardKey.programId, cardKey.cardId) == null) return@withTransaction
                dao.deleteBenefitSections(cardKey.programId, cardKey.cardId, sections.map { it.name })
                dao.insertBenefits(benefits.filter { it.cardKey == cardKey && it.section in sections }.map { it.toEntity() })
                dao.setBenefitsRead(cardKey.programId, cardKey.cardId, readEpochMs)
            }
        }
    }

    override suspend fun deleteProgramCards(programId: String) = dao.deleteProgramCards(programId)

    override suspend fun allCardsWithBenefits(): Map<Card, List<Benefit>> = db.withTransaction {
        val benefits = dao.allBenefits().map { it.toDomain() }.groupBy { it.cardKey }
        dao.allCards().associate { row -> row.toDomain().let { it to benefits[it.key].orEmpty() } }
    }

    override suspend fun reminderSent(benefitKey: String, periodEnd: LocalDate): Boolean =
        dao.reminderCount(benefitKey, periodEnd.toEpochDay()) > 0

    override suspend fun recordReminder(record: ReminderRecord): Boolean =
        dao.insertReminder(
            ReminderRecordEntity(
                benefitKey = record.benefitKey,
                periodEndEpochDay = record.periodEnd.toEpochDay(),
                programId = record.cardKey.programId,
                cardId = record.cardKey.cardId,
                sentEpochMs = record.sentEpochMs,
            ),
        ) != -1L

    override suspend fun pruneReminders(today: LocalDate) = dao.pruneReminders(today.toEpochDay())

    // ConnectionRepository

    override fun observeConnections(programId: String): Flow<List<Connection>> =
        dao.observeConnections(programId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun connections(programId: String): List<Connection> = dao.connections(programId).map { it.toDomain() }

    override suspend fun connection(connectionId: String): Connection? = dao.connection(connectionId)?.toDomain()

    override suspend fun addConnection(connection: Connection): Boolean = db.withTransaction {
        if (dao.accountCount(connection.programId) == 0 || dao.connection(connection.connectionId) != null) return@withTransaction false
        dao.insertConnection(
            ConnectionEntity(
                connectionId = connection.connectionId,
                programId = connection.programId,
                segment = connection.segment.name,
                sortOrder = connection.sortOrder,
                addedEpochMs = connection.addedEpochMs,
                inventoryReadEpochMs = null,
                inventoryComplete = false,
            ),
        )
        true
    }

    override suspend fun removeConnection(connectionId: String) = logged("connection removal", 1) {
        db.withTransaction {
            val connection = dao.connection(connectionId) ?: return@withTransaction
            val covered = dao.coverageOf(connectionId).map { it.cardId }
            dao.deleteConnection(connectionId)
            val orphans = covered.filter { dao.coverageOfCard(connection.programId, it).isEmpty() }
            if (orphans.isNotEmpty()) dao.deleteCards(connection.programId, orphans)
            (covered - orphans.toSet()).forEach { deriveSegment(connection.programId, it) }
        }
    }

    override suspend fun setConnectionSegment(connectionId: String, segment: CardSegment) {
        db.withTransaction {
            val connection = dao.connection(connectionId) ?: return@withTransaction
            dao.setConnectionSegment(connectionId, segment.name)
            dao.coverageOf(connectionId).forEach { deriveSegment(connection.programId, it.cardId) }
        }
    }

    private suspend fun logged(what: String, count: Int, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: SQLException) {
            DiagnosticLog.w(TAG, "Applying $what failed: ${e.javaClass.simpleName}, $count items")
            throw e
        }
    }

    private companion object {
        const val TAG = "CardRepository"
    }
}

private fun segmentOf(value: String?): CardSegment? = value?.let { runCatching { CardSegment.valueOf(it) }.getOrNull() }

private fun CardEntity.toDomain() = Card(
    programId = programId,
    lastDigits = lastDigits,
    name = name,
    sortOrder = sortOrder,
    cardListReadEpochMs = cardListReadEpochMs,
    benefitsReadEpochMs = benefitsReadEpochMs,
    benefitsReadGeneration = benefitsReadGeneration,
    cardId = cardId,
    segment = segmentOf(segmentOverride) ?: segmentOf(segment),
)

private fun ConnectionEntity.toDomain() = Connection(
    connectionId = connectionId,
    programId = programId,
    segment = segmentOf(segment) ?: CardSegment.PERSONAL,
    sortOrder = sortOrder,
    addedEpochMs = addedEpochMs,
    inventoryReadEpochMs = inventoryReadEpochMs,
    inventoryComplete = inventoryComplete,
)

private fun BenefitEntity.toDomain() = Benefit(
    cardKey = CardKey(programId, cardId),
    stableKey = stableKey,
    section = runCatching { BenefitSection.valueOf(section) }.getOrDefault(BenefitSection.OTHER),
    position = position,
    title = title,
    earnedCents = earnedCents,
    remainingCents = remainingCents,
    targetCents = targetCents,
    periodLabel = periodLabel,
    periodEnd = periodEndEpochDay?.let(LocalDate::ofEpochDay),
    periodLength = periodLength?.let { runCatching { PeriodLength.valueOf(it) }.getOrNull() },
    completed = completed,
    description = description,
    enrollment = enrollment?.let { runCatching { Enrollment.valueOf(it) }.getOrNull() },
)

private fun Benefit.toEntity() = BenefitEntity(
    stableKey = stableKey,
    programId = cardKey.programId,
    cardId = cardKey.cardId,
    section = section.name,
    position = position,
    title = title,
    earnedCents = earnedCents,
    remainingCents = remainingCents,
    targetCents = targetCents,
    periodLabel = periodLabel,
    periodEndEpochDay = periodEnd?.toEpochDay(),
    periodLength = periodLength?.name,
    completed = completed,
    description = description,
    enrollment = enrollment?.name,
)
