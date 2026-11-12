package com.bendourthe.milestone.ui.benefits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bendourthe.milestone.domain.AccountRepository
import com.bendourthe.milestone.domain.Benefit
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.Card
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.domain.CardRepository
import com.bendourthe.milestone.domain.CatalogRepository
import com.bendourthe.milestone.domain.Enrollment
import com.bendourthe.milestone.ui.dashboard.DashboardViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the Benefits tab shows. */
sealed interface BenefitsUiState {
    data object Loading : BenefitsUiState

    /** No program with a benefits page is added; [programNames] say which ones could be. */
    data class NoProgram(val programNames: List<String>) : BenefitsUiState

    /** A program with a benefits page is added but none of its cards has been read yet. */
    data class NotReadYet(val programName: String) : BenefitsUiState

    data class Cards(val tiles: List<CardTileUi>) : BenefitsUiState
}

data class CardTileUi(
    val key: CardKey,
    val name: String,
    val digitsLabel: String,
    val nextCredit: String,
    val completedLabel: String?,
    val lastReadLabel: String,
    val staleNote: String?,
) {
    /** The whole tile as TalkBack reads it, in one stop. */
    val description: String =
        listOfNotNull("$name, card ending ${key.lastDigits}", nextCredit, completedLabel, staleNote ?: lastReadLabel).joinToString(". ")
}

data class CardPageUi(
    val name: String,
    val digitsLabel: String,
    val staleNote: String?,
    val sections: List<SectionUi>,
)

data class SectionUi(val section: BenefitSection, val title: String, val items: List<BenefitUi>)

data class BenefitUi(
    val key: String,
    val title: String,
    /** Earned over target, 0 to 1, when both amounts are known. */
    val progress: Float?,
    val amounts: String?,
    val period: String?,
    val completed: Boolean,
    val noReminder: Boolean,
    val enrollment: String?,
    val description: String?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class BenefitsViewModel(
    private val cards: CardRepository,
    private val catalog: CatalogRepository,
    accounts: AccountRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale.getDefault(),
) : ViewModel() {
    private val programs = catalog.programs.filter { it.benefits != null }

    val uiState: StateFlow<BenefitsUiState> = accounts.observeAccounts()
        .map { list -> list.map { it.programId }.filter { id -> programs.any { it.programId == id } } }
        .flatMapLatest { added ->
            if (added.isEmpty()) return@flatMapLatest flowOf(BenefitsUiState.NoProgram(programs.map { it.displayName }))
            combine(added.map { id -> cardsWithBenefits(id) }) { perProgram ->
                val all = perProgram.flatMap { it }
                if (all.isEmpty()) {
                    BenefitsUiState.NotReadYet(catalog.program(added.first())?.displayName ?: added.first())
                } else {
                    BenefitsUiState.Cards(all.map { (card, benefits) -> tile(card, benefits, clock(), zone, locale) })
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BenefitsUiState.Loading)

    /** One card's page, or null once the card is no longer stored (removed from the account or signed out). */
    fun card(key: CardKey): Flow<CardPageUi?> =
        combine(cards.observeCards(key.programId), cards.observeBenefits(key)) { list, benefits ->
            list.firstOrNull { it.key == key }?.let { page(it, benefits, clock(), zone, locale) }
        }

    private fun cardsWithBenefits(programId: String): Flow<List<Pair<Card, List<Benefit>>>> =
        cards.observeCards(programId).flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptyList())
            else combine(list.map { card -> cards.observeBenefits(card.key).map { card to it } }) { it.toList() }
        }

    companion object {
        /** A card read longer ago than this shows a stale note and gets no reminder (plan 4.1 and 5.2). */
        const val STALE_AFTER_MS = 8L * 24 * 60 * 60 * 1000

        fun digitsLabel(lastDigits: String) = "…$lastDigits"

        fun money(cents: Long, locale: Locale): String {
            val format = NumberFormat.getCurrencyInstance(Locale.US)
            if (cents % 100 == 0L) format.maximumFractionDigits = 0
            return format.format(cents / 100.0)
        }

        /** "Not updated since Nov 4" when the card's benefits are old or its last read failed; null otherwise. */
        fun staleNote(card: Card, nowMs: Long, zone: ZoneId, locale: Locale): String? {
            val read = card.benefitsReadEpochMs ?: return "Benefits not read yet"
            val failedSince = card.cardListReadEpochMs?.let { it > read } == true
            if (!failedSince && nowMs - read <= STALE_AFTER_MS) return null
            val date = Instant.ofEpochMilli(read).atZone(zone).toLocalDate()
            return "Not updated since ${date.format(DateTimeFormatter.ofPattern("MMM d", locale))}"
        }

        private fun openCredits(benefits: List<Benefit>) = benefits.filter {
            it.section == BenefitSection.CREDIT_EARNED && !it.completed && (it.remainingCents ?: 0) > 0
        }

        fun nextCredit(benefits: List<Benefit>, zone: ZoneId, locale: Locale): String {
            val open = openCredits(benefits)
            if (open.isEmpty()) return "No open credits"
            val next = open.sortedWith(compareBy<Benefit, LocalDate?>(nullsLast()) { it.periodEnd }).first()
            val ends = next.periodEnd?.let { ", ends ${it.format(DateTimeFormatter.ofPattern("MMM d", locale))}" }
                ?: next.periodLabel?.let { " ($it)" }.orEmpty()
            return "${next.title}: ${money(next.remainingCents!!, locale)} left$ends"
        }

        fun tile(card: Card, benefits: List<Benefit>, nowMs: Long, zone: ZoneId, locale: Locale): CardTileUi {
            val completed = benefits.count { it.section != BenefitSection.LISTED && it.completed }
            return CardTileUi(
                key = card.key,
                name = card.name,
                digitsLabel = digitsLabel(card.lastDigits),
                nextCredit = nextCredit(benefits, zone, locale),
                completedLabel = if (completed == 0) null else "$completed completed",
                lastReadLabel = DashboardViewModel.lastUpdatedLabel(card.benefitsReadEpochMs, nowMs),
                staleNote = staleNote(card, nowMs, zone, locale),
            )
        }

        private val SECTION_TITLES = mapOf(
            BenefitSection.SPEND_PROGRESS to "Spend progress",
            BenefitSection.CREDIT_EARNED to "Credits",
            BenefitSection.COMPLETED to "Completed",
            BenefitSection.OTHER to "Other",
            BenefitSection.LISTED to "All benefits",
        )

        fun page(card: Card, benefits: List<Benefit>, nowMs: Long, zone: ZoneId, locale: Locale) = CardPageUi(
            name = card.name,
            digitsLabel = digitsLabel(card.lastDigits),
            staleNote = staleNote(card, nowMs, zone, locale),
            sections = BenefitSection.entries.mapNotNull { section ->
                benefits.filter { it.section == section }.takeIf { it.isNotEmpty() }
                    ?.let { list -> SectionUi(section, SECTION_TITLES.getValue(section), list.map { item(it, locale) }) }
            },
        )

        fun item(benefit: Benefit, locale: Locale): BenefitUi {
            val earned = benefit.earnedCents
            val left = benefit.remainingCents
            val target = benefit.targetCents
            val amounts = listOfNotNull(earned?.let { "${money(it, locale)} earned" }, left?.let { "${money(it, locale)} left" })
                .joinToString(" · ").ifEmpty { null }
            return BenefitUi(
                key = benefit.stableKey,
                title = benefit.title,
                progress = if (earned != null && target != null && target > 0) (earned.toFloat() / target).coerceIn(0f, 1f) else null,
                amounts = amounts,
                period = benefit.periodLabel,
                completed = benefit.completed,
                noReminder = benefit.section == BenefitSection.CREDIT_EARNED && !benefit.completed && benefit.periodEnd == null,
                enrollment = when (benefit.enrollment) {
                    Enrollment.ENROLLED -> "Enrolled"
                    Enrollment.ENROLLMENT_REQUIRED -> "Enrollment required"
                    null -> null
                },
                description = benefit.description,
            )
        }
    }
}
