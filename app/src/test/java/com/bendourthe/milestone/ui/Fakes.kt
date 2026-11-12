package com.bendourthe.milestone.ui

import com.bendourthe.milestone.data.session.SessionRecord
import com.bendourthe.milestone.data.session.SessionStore
import com.bendourthe.milestone.domain.AccountRepository
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.BalanceOrigin
import com.bendourthe.milestone.domain.BalanceUnit
import com.bendourthe.milestone.domain.BalanceSnapshot
import com.bendourthe.milestone.domain.Benefit
import com.bendourthe.milestone.domain.Card
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.domain.CardList
import com.bendourthe.milestone.domain.CardRepository
import com.bendourthe.milestone.domain.ReminderRecord
import com.bendourthe.milestone.domain.ExtractionSpec
import com.bendourthe.milestone.domain.FakeCatalog
import com.bendourthe.milestone.domain.LoyaltyAccount
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.domain.ProgramCategory
import com.bendourthe.milestone.domain.SelectorStrategy
import com.bendourthe.milestone.domain.SettingsRepository
import com.bendourthe.milestone.domain.SortMode
import com.bendourthe.milestone.domain.SyncCapability
import com.bendourthe.milestone.domain.SyncResult
import com.bendourthe.milestone.domain.testProgram
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

val testCatalog = FakeCatalog(
    listOf(
        testProgram("amex", "American Express Membership Rewards", ProgramCategory.CREDIT_CARD).copy(isPopular = true),
        testProgram("chase", "Chase Ultimate Rewards", ProgramCategory.CREDIT_CARD).copy(isPopular = true),
        testProgram("delta", "Delta SkyMiles", ProgramCategory.AIRLINE).copy(isPopular = true),
        testProgram("marriott", "Marriott Bonvoy", ProgramCategory.HOTEL).copy(isPopular = true),
        testProgram("hyatt", "World of Hyatt", ProgramCategory.HOTEL),
        testProgram("hilton", "Hilton Honors", ProgramCategory.HOTEL),
    ).map(::syncable),
)

/** Only programs that can sync are added from the catalog, so UI fixtures default to a syncable capability. */
private fun syncable(program: Program) = program.copy(
    syncCapability = SyncCapability.Automatic(
        ExtractionSpec.Selector(SelectorStrategy.CSS, "#balance", Regex("/account"), Regex("/login")),
    ),
)

val uiTestCatalog = FakeCatalog(
    listOf(
        testProgram("amex", "American Express Membership Rewards", ProgramCategory.CREDIT_CARD).copy(isPopular = true),
        testProgram("chase", "Chase Ultimate Rewards", ProgramCategory.CREDIT_CARD).copy(isPopular = true),
        testProgram("capitalone", "Capital One Miles", ProgramCategory.CREDIT_CARD).copy(isPopular = true, balanceUnit = BalanceUnit.CASH_CENTS),
        testProgram("citi", "Citi ThankYou Points", ProgramCategory.CREDIT_CARD),
        testProgram("delta", "Delta SkyMiles", ProgramCategory.AIRLINE).copy(isPopular = true),
        testProgram("united", "United MileagePlus", ProgramCategory.AIRLINE).copy(isPopular = true),
        testProgram("alaska", "Alaska Mileage Plan", ProgramCategory.AIRLINE),
        testProgram("marriott", "Marriott Bonvoy", ProgramCategory.HOTEL).copy(isPopular = true),
        testProgram("hyatt", "World of Hyatt", ProgramCategory.HOTEL),
        testProgram("hilton", "Hilton Honors", ProgramCategory.HOTEL),
    ).map { if (it.programId == "citi") it else syncable(it) },
)

class FakeAccounts : AccountRepository {
    val accounts = MutableStateFlow<List<LoyaltyAccount>>(emptyList())
    var failReorder = false
    val reorderCalls = mutableListOf<List<String>>()

    override fun observeAccounts(): Flow<List<LoyaltyAccount>> = accounts
    override fun observeHistory(programId: String): Flow<List<BalanceSnapshot>> = flowOf(emptyList())

    override suspend fun addAccount(programId: String, status: AccountStatus, nowEpochMs: Long): Boolean {
        if (accounts.value.any { it.programId == programId }) return false
        accounts.update { it + LoyaltyAccount(programId, null, null, null, null, 0, it.size, status, null) }
        return true
    }

    override suspend fun removeAccount(programId: String) = accounts.update { list -> list.filterNot { it.programId == programId } }

    val syncWrites = mutableListOf<Pair<String, SyncResult>>()

    override suspend fun applySyncResult(programId: String, result: SyncResult, completedEpochMs: Long, createIfMissing: Boolean) {
        if (createIfMissing) addAccount(programId, AccountStatus.CONNECTED, completedEpochMs)
        if (accounts.value.none { it.programId == programId }) return
        syncWrites += programId to result
        accounts.update { list ->
            list.map { a ->
                if (a.programId != programId) return@map a
                when (result) {
                    is SyncResult.Success -> a.copy(
                        balance = result.balance, balanceOrigin = BalanceOrigin.SCRAPED, lastUpdatedEpochMs = completedEpochMs,
                        status = AccountStatus.CONNECTED, lastError = null, consecutiveFailures = 0, lastAttemptEpochMs = completedEpochMs,
                    )
                    SyncResult.NeedsReconnect -> a.copy(status = AccountStatus.NEEDS_RECONNECT, lastError = null, lastAttemptEpochMs = completedEpochMs)
                    is SyncResult.Failure -> a.copy(status = AccountStatus.ERROR, lastError = result.reason, consecutiveFailures = a.consecutiveFailures + 1, lastAttemptEpochMs = completedEpochMs)
                    SyncResult.Unsupported -> a.copy(status = AccountStatus.UNSUPPORTED, lastError = null)
                }
            }
        }
    }

    override suspend fun markNeedsReconnect(programId: String) = accounts.update { list ->
        list.map { if (it.programId == programId) it.copy(status = AccountStatus.NEEDS_RECONNECT) else it }
    }

    override suspend fun reorder(orderedProgramIds: List<String>) {
        reorderCalls += orderedProgramIds
        if (failReorder) error("disk full")
        accounts.update { list -> orderedProgramIds.mapIndexed { i, id -> list.single { it.programId == id }.copy(sortOrder = i) } }
    }
}

class FakeSessions : SessionStore {
    val records = mutableMapOf<String, SessionRecord>()
    override suspend fun get(programId: String) = records[programId]
    override suspend fun all(): Map<String, SessionRecord> = records.toMap()
    override suspend fun markConnected(programId: String, nowEpochMs: Long) {
        records[programId] = SessionRecord(nowEpochMs, nowEpochMs)
    }
    override suspend fun markVerified(programId: String, nowEpochMs: Long) {
        records[programId]?.let { records[programId] = it.copy(lastVerifiedEpochMs = nowEpochMs) }
    }
    override suspend fun clear(programId: String) {
        records.remove(programId)
    }
    override suspend fun clearAll() = records.clear()
}

class FakeSettings(initial: SortMode = SortMode.ALPHABETICAL) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val sortMode: Flow<SortMode> = state
    override suspend fun setSortMode(mode: SortMode) {
        state.value = mode
    }
    private val interval = MutableStateFlow(com.bendourthe.milestone.domain.RefreshInterval.WEEKLY)
    override val refreshInterval: Flow<com.bendourthe.milestone.domain.RefreshInterval> = interval
    override suspend fun setRefreshInterval(interval: com.bendourthe.milestone.domain.RefreshInterval) {
        this.interval.value = interval
    }
}

/** In-memory [CardRepository] with the Room implementation's rules: per-card replacement and removal only on a complete list. */
class FakeCards : CardRepository {
    val cards = MutableStateFlow<List<Card>>(emptyList())
    val benefits = MutableStateFlow<Map<CardKey, List<Benefit>>>(emptyMap())
    val reminders = mutableListOf<ReminderRecord>()

    override fun observeCards(programId: String): Flow<List<Card>> =
        cards.map { list -> list.filter { it.programId == programId }.sortedBy { it.sortOrder } }

    override fun observeBenefits(cardKey: CardKey): Flow<List<Benefit>> =
        benefits.map { map -> map[cardKey].orEmpty().sortedBy { it.section.ordinal } }

    override suspend fun applyCardList(programId: String, cards: CardList, readEpochMs: Long) {
        val current = this.cards.value
        val updated = cards.cards.mapIndexed { index, draft ->
            val old = current.firstOrNull { it.programId == programId && it.lastDigits == draft.lastDigits }
            Card(programId, draft.lastDigits, draft.name, index, readEpochMs, old?.benefitsReadEpochMs)
        }
        val listed = updated.map { it.key }.toSet()
        val kept = current.filter { card ->
            card.key !in listed && (card.programId != programId || !cards.complete || cards.cards.isEmpty())
        }
        this.cards.value = kept + updated
        dropOrphans()
    }

    override suspend fun applyCardBenefits(cardKey: CardKey, benefits: List<Benefit>, readEpochMs: Long) {
        if (cards.value.none { it.key == cardKey }) return
        this.benefits.update { it + (cardKey to benefits) }
        cards.update { list -> list.map { if (it.key == cardKey) it.copy(benefitsReadEpochMs = readEpochMs) else it } }
    }

    override suspend fun deleteProgramCards(programId: String) {
        cards.update { list -> list.filterNot { it.programId == programId } }
        dropOrphans()
    }

    override suspend fun allCardsWithBenefits(): Map<Card, List<Benefit>> =
        cards.value.associateWith { benefits.value[it.key].orEmpty() }

    override suspend fun reminderSent(benefitKey: String, periodEnd: java.time.LocalDate): Boolean =
        reminders.any { it.benefitKey == benefitKey && it.periodEnd == periodEnd }

    override suspend fun recordReminder(record: ReminderRecord): Boolean {
        if (reminderSent(record.benefitKey, record.periodEnd)) return false
        reminders += record
        return true
    }

    override suspend fun pruneReminders(today: java.time.LocalDate) {
        reminders.removeAll { it.periodEnd < today }
    }

    private fun dropOrphans() {
        val keys = cards.value.map { it.key }.toSet()
        benefits.update { map -> map.filterKeys { it in keys } }
        reminders.removeAll { it.cardKey !in keys }
    }
}
