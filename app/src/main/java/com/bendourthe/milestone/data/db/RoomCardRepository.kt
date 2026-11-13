package com.bendourthe.milestone.data.db

import android.database.SQLException
import androidx.room.withTransaction
import com.bendourthe.milestone.DiagnosticLog
import com.bendourthe.milestone.domain.Benefit
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.Card
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.domain.CardList
import com.bendourthe.milestone.domain.CardRepository
import com.bendourthe.milestone.domain.Enrollment
import com.bendourthe.milestone.domain.PeriodLength
import com.bendourthe.milestone.domain.ReminderRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * Stores cards, their benefits, and sent-reminder records. Each apply runs in one transaction; the
 * caller (SyncManager) serializes applies per program. Failures log the exception class and counts
 * only, never a card name, last digits, or benefit title.
 */
class RoomCardRepository(private val db: MileStoneDatabase) : CardRepository {
    private val dao = db.cardDao()

    override fun observeCards(programId: String): Flow<List<Card>> =
        dao.observeCards(programId).map { rows -> rows.map { it.toDomain() } }

    override fun observeBenefits(cardKey: CardKey): Flow<List<Benefit>> =
        dao.observeBenefits(cardKey.programId, cardKey.lastDigits).map { rows -> rows.map { it.toDomain() } }

    override suspend fun applyCardList(programId: String, cards: CardList, readEpochMs: Long) = logged("card list", cards.cards.size) {
        db.withTransaction {
            if (dao.accountCount(programId) == 0) return@withTransaction
            cards.cards.forEachIndexed { index, draft ->
                val existing = dao.card(programId, draft.lastDigits)
                dao.upsertCard(
                    CardEntity(
                        programId = programId,
                        lastDigits = draft.lastDigits,
                        name = draft.name,
                        sortOrder = index,
                        cardListReadEpochMs = readEpochMs,
                        benefitsReadEpochMs = existing?.benefitsReadEpochMs,
                    ),
                )
            }
            if (cards.complete && cards.cards.isNotEmpty()) {
                val listed = cards.cards.map { it.lastDigits }.toSet()
                val missing = dao.lastDigits(programId).filterNot { it in listed }
                if (missing.isNotEmpty()) dao.deleteCards(programId, missing)
            }
        }
    }

    override suspend fun applyCardBenefits(cardKey: CardKey, benefits: List<Benefit>, readEpochMs: Long, sections: Set<BenefitSection>) {
        if (sections.isEmpty()) return
        logged("card benefits", benefits.size) {
            db.withTransaction {
                if (dao.card(cardKey.programId, cardKey.lastDigits) == null) return@withTransaction
                dao.deleteBenefitSections(cardKey.programId, cardKey.lastDigits, sections.map { it.name })
                dao.insertBenefits(benefits.filter { it.cardKey == cardKey && it.section in sections }.map { it.toEntity() })
                dao.setBenefitsRead(cardKey.programId, cardKey.lastDigits, readEpochMs)
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
                lastDigits = record.cardKey.lastDigits,
                sentEpochMs = record.sentEpochMs,
            ),
        ) != -1L

    override suspend fun pruneReminders(today: LocalDate) = dao.pruneReminders(today.toEpochDay())

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

private fun CardEntity.toDomain() = Card(programId, lastDigits, name, sortOrder, cardListReadEpochMs, benefitsReadEpochMs)

private fun BenefitEntity.toDomain() = Benefit(
    cardKey = CardKey(programId, lastDigits),
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
    lastDigits = cardKey.lastDigits,
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
