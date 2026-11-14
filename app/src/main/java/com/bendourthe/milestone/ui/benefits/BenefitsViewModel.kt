package com.bendourthe.milestone.ui.benefits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bendourthe.milestone.domain.AccountRepository
import com.bendourthe.milestone.domain.Benefit
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.BenefitsDisplaySettings
import com.bendourthe.milestone.domain.WelcomeBonusSetup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import com.bendourthe.milestone.domain.BenefitsSort
import com.bendourthe.milestone.domain.BenefitsCategory
import com.bendourthe.milestone.domain.BenefitsDisplayPreferences
import com.bendourthe.milestone.domain.Card
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.domain.CardRepository
import com.bendourthe.milestone.domain.CatalogRepository
import com.bendourthe.milestone.domain.Enrollment
import com.bendourthe.milestone.domain.ReminderSettings
import kotlinx.coroutines.launch
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

    data class Cards(val tiles: List<CardTileUi>, val programs: List<BenefitsProgramUi>) : BenefitsUiState
}

data class BenefitsProgramUi(
    val programId: String, val name: String, val monogram: String, val logoKey: String?,
    val brandColorArgb: Long, val cardCount: Int, val alertCount: Int,
)

data class CardTileUi(
    val key: CardKey,
    val name: String,
    val digitsLabel: String,
    val nextCredit: String,
    val completedLabel: String?,
    val lastReadLabel: String,
    val staleNote: String?,
    val programName: String = key.programId,
    val benefitCount: Int = 0,
    val alertCount: Int = 0,
) {
    /** The whole tile as TalkBack reads it, in one stop. */
    val description: String =
        listOfNotNull("$name, card ending ${key.lastDigits}", nextCredit, "$benefitCount benefits", "$alertCount ${if (alertCount == 1) "alert" else "alerts"}", completedLabel, staleNote ?: lastReadLabel).joinToString(". ")
}

data class CardPageUi(
    val name: String,
    val digitsLabel: String,
    val staleNote: String?,
    val sections: List<SectionUi>,
    val preferences: BenefitsDisplayPreferences = BenefitsDisplayPreferences(),
    val settingsError: String? = null,
    val alerts: List<BenefitAlertUi> = emptyList(),
    val listFreshnessNote: String? = null,
)

data class SectionUi(val category: BenefitsCategory, val title: String, val items: List<BenefitUi>)

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
    private val reminders: ReminderSettings? = null,
    private val displaySettings: BenefitsDisplaySettings? = null,
) : ViewModel() {
    private val programs = catalog.programs.filter { it.benefits != null }
    // Production supplies DataStore; previews and existing test callers can keep an in-memory state.
    private val temporaryPreferences = MutableStateFlow<Map<CardKey, BenefitsDisplayPreferences>>(emptyMap())
    private val settingsErrors = MutableStateFlow<Map<CardKey, String>>(emptyMap())

    private fun preferences(key: CardKey) = displaySettings?.observe(key)
        ?: temporaryPreferences.map { it[key] ?: BenefitsDisplayPreferences() }

    private fun saveDisplay(key: CardKey, temporary: (BenefitsDisplayPreferences) -> BenefitsDisplayPreferences,
                            persistent: suspend (BenefitsDisplaySettings) -> Unit) {
        val settings = displaySettings
        if (settings == null) {
            temporaryPreferences.update { it + (key to temporary(it[key] ?: BenefitsDisplayPreferences())) }
            return
        }
        viewModelScope.launch {
            try {
                persistent(settings)
                settingsErrors.update { it - key }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                settingsErrors.update { it + (key to "Could not save this change. Please try again.") }
            }
        }
    }

    fun setSort(key: CardKey, sort: BenefitsSort) = saveDisplay(key,
        { it.copy(sort = sort) }, { it.setSort(key, sort) })

    fun setCompletedLast(key: CardKey, enabled: Boolean) = saveDisplay(key,
        { it.copy(completedLast = enabled) }, { it.setCompletedLast(key, enabled) })

    fun setCollapsed(key: CardKey, category: BenefitsCategory, collapsed: Boolean) = saveDisplay(key,
        { it.copy(collapsed = if (collapsed) it.collapsed + category else it.collapsed - category) },
        { it.setCollapsed(key, category, collapsed) })

    fun acknowledge(key: CardKey, alertId: String, generation: Long) = saveDisplay(key,
        { it.copy(acknowledged = it.acknowledged + (alertId to generation)) },
        { it.acknowledge(key, alertId, generation) })

    fun setWelcomeBonus(key: CardKey, setup: WelcomeBonusSetup?) = saveDisplay(key,
        { it.copy(welcomeBonus = setup) }, { it.setWelcomeBonus(key, setup) })

    /** The "Benefit reminders" setting; false when this screen has no setting to show. */
    val remindersEnabled: StateFlow<Boolean> =
        (reminders?.enabled ?: flowOf(false)).stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val hasReminderSetting: Boolean get() = reminders != null

    fun setRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch { reminders?.setEnabled(enabled) }
    }

    val uiState: StateFlow<BenefitsUiState> = accounts.observeAccounts()
        .map { list -> list.map { it.programId }.filter { id -> programs.any { it.programId == id } } }
        .flatMapLatest { added ->
            if (added.isEmpty()) return@flatMapLatest flowOf<BenefitsUiState>(BenefitsUiState.NoProgram(programs.map { it.displayName }))
            combine(added.map { id -> cardsWithBenefits(id) }) { perProgram ->
                val tiles = perProgram.flatMap { it }.map { (card, benefits, prefs) ->
                    tile(card, benefits, clock(), zone, locale).copy(
                        programName = catalog.program(card.programId)?.displayName ?: card.programId,
                        benefitCount = page(card, benefits, clock(), zone, locale, prefs).sections.sumOf { it.items.size },
                        alertCount = BenefitsAlerts.active(card, benefits, prefs, clock(), zone, locale).size,
                    )
                }
                BenefitsUiState.Cards(tiles, added.map { id ->
                    val program = programs.first { it.programId == id }
                    val cards = tiles.filter { it.key.programId == id }
                    BenefitsProgramUi(id, program.displayName, program.monogram, program.logoKey, program.brandColorArgb,
                        cards.size, cards.sumOf { it.alertCount })
                })
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BenefitsUiState.Loading)

    /** One card's page, or null once the card is no longer stored (removed from the account or signed out). */
    fun card(key: CardKey): Flow<CardPageUi?> =
        combine(cards.observeCards(key.programId), cards.observeBenefits(key), preferences(key), settingsErrors) { list, benefits, prefs, errors ->
            list.firstOrNull { it.key == key }?.let { page(it, benefits, clock(), zone, locale, prefs).copy(settingsError = errors[key]) }
        }

    private fun cardsWithBenefits(programId: String): Flow<List<Triple<Card, List<Benefit>, BenefitsDisplayPreferences>>> =
        cards.observeCards(programId).flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptyList())
            else combine(list.map { card -> combine(cards.observeBenefits(card.key), preferences(card.key)) { benefits, prefs -> Triple(card, benefits, prefs) } }) { it.toList() }
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

        /** Credits with money left whose period has not ended by [today] (an unknown end counts as open). */
        private fun openCredits(benefits: List<Benefit>, today: LocalDate) = benefits.filter {
            it.section == BenefitSection.CREDIT_EARNED && !it.completed && (it.remainingCents ?: 0) > 0 &&
                it.periodEnd?.isBefore(today) != true
        }

        fun nextCredit(benefits: List<Benefit>, today: LocalDate, locale: Locale): String {
            val open = openCredits(benefits, today)
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
                nextCredit = nextCredit(benefits, Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate(), locale),
                completedLabel = if (completed == 0) null else "$completed completed",
                lastReadLabel = DashboardViewModel.lastUpdatedLabel(card.benefitsReadEpochMs, nowMs),
                staleNote = staleNote(card, nowMs, zone, locale),
            )
        }

        fun page(card: Card, benefits: List<Benefit>, nowMs: Long, zone: ZoneId, locale: Locale,
                 preferences: BenefitsDisplayPreferences = BenefitsDisplayPreferences()): CardPageUi {
            val welcome = preferences.welcomeBonus?.let { setup ->
                Benefit(card.key, "local:welcome", BenefitSection.SPEND_PROGRESS, 0, "Welcome bonus",
                    setup.spentCents, (setup.targetCents - setup.spentCents).coerceAtLeast(0), setup.targetCents,
                    setup.deadline?.toString(), setup.deadline, null, setup.spentCents >= setup.targetCents,
                    description = setup.rewardLabel.takeIf { it.isNotBlank() }?.let(::rewardDisplayText))
            }
            return CardPageUi(
            name = card.name,
            digitsLabel = digitsLabel(card.lastDigits),
            staleNote = staleNote(card, nowMs, zone, locale),
            sections = BenefitsPresentation.groups(benefits + listOfNotNull(welcome), preferences.completedLast, preferences.sort).map { (category, list) ->
                SectionUi(category, category.title, list.map { item(it, locale) })
            },
            preferences = preferences,
            alerts = BenefitsAlerts.active(card, benefits, preferences, nowMs, zone, locale),
            listFreshnessNote = BenefitsAlerts.listFreshness(card, benefits, preferences),
        )
        }

        fun item(benefit: Benefit, locale: Locale): BenefitUi {
            val earned = benefit.earnedCents
            val left = benefit.remainingCents
            val target = benefit.targetCents
            val amountLabel = if (benefit.stableKey == "local:welcome") "spent" else "earned"
            val amounts = listOfNotNull(earned?.let { "${money(it, locale)} $amountLabel" }, left?.let { "${money(it, locale)} left" })
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
