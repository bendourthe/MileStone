package com.bendourthe.milestone.domain

import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Identifies one card on the phone: its program and the last 4 or 5 digits the issuer shows.
 *
 * A card is never identified by a full card number, an issuer account key, or a membership
 * number (such as a Priority Pass number); none of those is stored, logged, or kept here.
 */
data class CardKey(val programId: String, val lastDigits: String) {
    init {
        require(isValidLastDigits(lastDigits)) { "$programId: last digits must be 4 or 5 digits" }
    }

    companion object {
        private val LAST_DIGITS = Regex("^[0-9]{4,5}$")

        fun isValidLastDigits(value: String): Boolean = LAST_DIGITS.matches(value)
    }
}

/**
 * One card on an issuer account. [name] is a mutable attribute: a renamed or upgraded card keeps its
 * [key], so its benefits and reminder records stay with it.
 *
 * Holds no full card number, account key, or membership number, by design.
 */
data class Card(
    val programId: String,
    val lastDigits: String,
    val name: String,
    val sortOrder: Int,
    val cardListReadEpochMs: Long?,
    val benefitsReadEpochMs: Long?,
) {
    val key: CardKey get() = CardKey(programId, lastDigits)
}

/**
 * The groups the issuer's benefits page uses, in display order. LISTED holds the issuer's full benefits list
 * (every benefit with its description and enrollment status), shown after the tracked items.
 */
enum class BenefitSection { SPEND_PROGRESS, CREDIT_EARNED, COMPLETED, OTHER, LISTED }

/** Whether the issuer shows a benefit as enrolled or as needing enrollment; null when it says neither. */
enum class Enrollment { ENROLLED, ENROLLMENT_REQUIRED }

/** How long a credit's period lasts, which sets how early a reminder is sent. */
enum class PeriodLength { MONTHLY, QUARTERLY, HALF_YEAR, YEARLY }

/**
 * One benefit of one card as the issuer's page reports it. Amounts are whole cents and are null when
 * the page text did not parse; the benefit still shows its [title].
 *
 * [stableKey] is the card key plus the normalized title plus [periodLabel], with [position] used only
 * to tell apart identical titles. [section] is not part of it, because a used credit moves to COMPLETED.
 */
data class Benefit(
    val cardKey: CardKey,
    val stableKey: String,
    val section: BenefitSection,
    val position: Int,
    val title: String,
    val earnedCents: Long?,
    val remainingCents: Long?,
    val targetCents: Long?,
    val periodLabel: String?,
    val periodEnd: LocalDate?,
    val periodLength: PeriodLength?,
    val completed: Boolean,
    val description: String? = null,
    val enrollment: Enrollment? = null,
)

/** One reminder already posted for a credit's period, so the same period never reminds twice. */
data class ReminderRecord(
    val cardKey: CardKey,
    val benefitKey: String,
    val periodEnd: LocalDate,
    val sentEpochMs: Long,
)

/** A card as read from the issuer's card picker, before validation. */
data class CardDraft(val name: String, val lastDigits: String)

/** A benefit as read from the issuer's page, before it gets a stable key. */
data class BenefitDraft(
    val section: BenefitSection,
    val title: String,
    val earnedCents: Long?,
    val remainingCents: Long?,
    val targetCents: Long?,
    val periodLabel: String?,
    val completed: Boolean,
    val periodEnd: LocalDate? = null,
    val periodLength: PeriodLength? = null,
    val description: String? = null,
    val enrollment: Enrollment? = null,
    /** The issuer's own name for a listed benefit (Amex: the slug of its detail link); never an account key. */
    val issuerKey: String? = null,
)

/**
 * A validated card list. [complete] is true only when the read saw the whole list and every entry
 * was valid; only a complete, non-empty list may remove cards that are missing from it.
 */
data class CardList(val cards: List<CardDraft>, val complete: Boolean) {
    companion object {
        /**
         * Keeps entries with 4 or 5 last digits, normalizes names, and drops every entry whose last
         * digits appear more than once (never merged). Any dropped entry marks the list incomplete.
         */
        fun from(drafts: List<CardDraft>, readComplete: Boolean): CardList {
            val valid = drafts
                .filter { CardKey.isValidLastDigits(it.lastDigits) }
                .map { it.copy(name = normalizeCardName(it.name)) }
            val counts = valid.groupingBy { it.lastDigits }.eachCount()
            val unique = valid.filter { counts.getValue(it.lastDigits) == 1 }
            return CardList(unique, readComplete && unique.size == drafts.size)
        }
    }
}

private val TRADEMARKS = Regex("[™®℠©]")
private val SPACES = Regex("\\s+")

/** Trims, removes trademark signs, and collapses runs of whitespace. */
fun normalizeCardName(name: String): String = name.replace(TRADEMARKS, "").replace(SPACES, " ").trim()

/** The title form used in a benefit's stable key: normalized like a card name, then lowercased. */
fun normalizeBenefitTitle(title: String): String = normalizeCardName(title).lowercase()

/**
 * Gives each benefit of one card read its stable key. The n-th repeat of the same normalized title and
 * period label gets position n, so two identical titles stay apart while a reordered page keeps keys. A listed
 * benefit with an issuer key is keyed by it instead, so it never collides with the tracker of the same name.
 */
fun assignBenefitKeys(cardKey: CardKey, drafts: List<BenefitDraft>): List<Benefit> {
    val seen = HashMap<Pair<String, String>, Int>()
    return drafts.map { draft ->
        val title = normalizeBenefitTitle(draft.title)
        val period = draft.periodLabel?.let { normalizeCardName(it) }.orEmpty()
        val identity = when {
            draft.section != BenefitSection.LISTED -> title
            draft.issuerKey.isNullOrBlank() -> "list:$title"
            else -> "list:" + draft.issuerKey.lowercase()
        }
        val position = seen.merge(identity to period, 1, Int::plus)!! - 1
        Benefit(
            cardKey = cardKey,
            stableKey = listOf(cardKey.programId, cardKey.lastDigits, identity, period, position.toString()).joinToString("\u001F"),
            section = draft.section,
            position = position,
            title = normalizeCardName(draft.title),
            earnedCents = draft.earnedCents,
            remainingCents = draft.remainingCents,
            targetCents = draft.targetCents,
            periodLabel = draft.periodLabel?.let { normalizeCardName(it) },
            periodEnd = draft.periodEnd,
            periodLength = draft.periodLength,
            completed = draft.completed,
            description = draft.description?.let { normalizeCardName(it) }?.takeIf { it.isNotEmpty() },
            enrollment = draft.enrollment,
        )
    }
}

/** The "Benefit reminders" setting (on by default). */
interface ReminderSettings {
    val enabled: Flow<Boolean>
    suspend fun setEnabled(enabled: Boolean)
}

interface CardRepository {
    /** The program's cards in the issuer's order. */
    fun observeCards(programId: String): Flow<List<Card>>

    /** One card's benefits, by section and then page order. */
    fun observeBenefits(cardKey: CardKey): Flow<List<Benefit>>

    /**
     * Upserts every listed card (a renamed card keeps its benefits) and, only when [cards] is complete
     * and non-empty, deletes the program's cards missing from it with their benefits and reminder
     * records. Does nothing when the program is not tracked.
     */
    suspend fun applyCardList(programId: String, cards: CardList, readEpochMs: Long)

    /**
     * Replaces one card's benefits in [sections] only, in one transaction, and stamps its benefits read time; rows in
     * other sections stay, and [benefits] outside [sections] are ignored. Does nothing for an unknown card or an
     * empty [sections].
     */
    suspend fun applyCardBenefits(cardKey: CardKey, benefits: List<Benefit>, readEpochMs: Long, sections: Set<BenefitSection> = BenefitSection.entries.toSet())

    /** Deletes the program's cards, benefits, and reminder records (Sign out keeps the account row). */
    suspend fun deleteProgramCards(programId: String)

    /** Every stored benefit with its card, for the daily reminder check. */
    suspend fun allCardsWithBenefits(): Map<Card, List<Benefit>>

    suspend fun reminderSent(benefitKey: String, periodEnd: LocalDate): Boolean

    /** Returns false when a record for the same benefit and period already exists. */
    suspend fun recordReminder(record: ReminderRecord): Boolean

    /** Deletes reminder records whose period ended before [today]. */
    suspend fun pruneReminders(today: LocalDate)
}

/** Display groups are independent of issuer sections, which remain the storage/replacement boundary. */
enum class BenefitsCategory(val title: String) {
    WELCOME_BONUS("Welcome bonus"), CREDITS("Credits"), SPEND_TRACKERS("Spend trackers"), OTHER("Other")
}

data class WelcomeBonusSetup(
    val spentCents: Long,
    val targetCents: Long,
    val deadline: LocalDate?,
    val rewardLabel: String = "",
) {
    init {
        require(spentCents >= 0 && targetCents > 0)
        require(rewardLabel.length <= 120 && rewardLabel.none { it.isISOControl() })
    }
}

data class BenefitsDisplayPreferences(
    val completedLast: Boolean = false,
    val collapsed: Set<BenefitsCategory> = setOf(BenefitsCategory.OTHER),
    val acknowledged: Map<String, Long> = emptyMap(),
    val welcomeBonus: WelcomeBonusSetup? = null,
) {
    fun isAcknowledged(alertId: String, successfulReadEpochMs: Long): Boolean =
        acknowledged[alertId] == successfulReadEpochMs
}

/** On-device display preferences; acknowledgement never edits issuer benefits or reminder records. */
interface BenefitsDisplaySettings {
    fun observe(card: CardKey): Flow<BenefitsDisplayPreferences>
    suspend fun setCompletedLast(card: CardKey, completedLast: Boolean)
    suspend fun setCollapsed(card: CardKey, category: BenefitsCategory, collapsed: Boolean)
    suspend fun acknowledge(card: CardKey, alertId: String, successfulReadEpochMs: Long)
    suspend fun setWelcomeBonus(card: CardKey, setup: WelcomeBonusSetup?)
}
