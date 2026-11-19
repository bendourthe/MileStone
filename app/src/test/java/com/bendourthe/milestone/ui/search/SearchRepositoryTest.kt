package com.bendourthe.milestone.ui.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.bendourthe.milestone.data.settings.DataStoreBenefitsDisplaySettings
import java.io.IOException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bendourthe.milestone.data.RoomAccountRepository
import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.data.db.MileStoneDatabase
import com.bendourthe.milestone.data.db.RoomCardRepository
import com.bendourthe.milestone.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SearchRepositoryTest {
    private lateinit var db: MileStoneDatabase
    private lateinit var accounts: RoomAccountRepository
    private lateinit var cards: RoomCardRepository
    @get:Rule val tmp = TemporaryFolder()
    private val preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = ViewModelStore()
    private val modelJobs = mutableListOf<Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val key = CardKey("amex", "1008")

    @Before fun setup() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MileStoneDatabase::class.java)
            .allowMainThreadQueries().build()
        accounts = RoomAccountRepository(db); cards = RoomCardRepository(db)
        accounts.addAccount("amex", AccountStatus.CONNECTED, 1)
        cards.applyCardList("amex", CardList.from(listOf(CardDraft("Delta Gold", "1008")), true), 10)
        replace("Airport lounge")
    }
    @After fun close() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        store.clear()
        modelJobs.joinAll()
        preferenceScope.coroutineContext[Job]!!.cancelAndJoin()
        if (::db.isInitialized) db.close()
        Dispatchers.resetMain()
    }
    private suspend fun replace(description: String) = cards.applyCardBenefits(key,
        assignBenefitKeys(key, listOf(BenefitDraft(BenefitSection.CREDIT_EARNED, "Lounge Credit", 0, 5000, 5000,
            "January", false, description = description))), 11)
    private fun model(repository: CardRepository = cards, preferences: BenefitsDisplaySettings? = null): SearchViewModel {
        val model = SearchViewModel(accounts, repository, StaticCatalogRepository(), displaySettings = preferences, clock = { 100 }, zone = ZoneId.of("UTC"), locale = Locale.US)
        modelJobs += model.viewModelScope.coroutineContext[Job]!!
        store.put("search", model); scope.launch { model.state.collect() }; return model
    }
    private suspend fun result(vm: SearchViewModel, predicate: (SearchUiState.Results) -> Boolean = { true }) =
        withTimeout(10_000) { vm.state.filterIsInstance<SearchUiState.Results>().first(predicate) }

    private fun proof(stage: String, target: SearchTarget?, count: Int) {
        val key = (target as? SearchTarget.BenefitTarget)?.benefitKey.orEmpty()
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        println("SearchFixture stage=$stage program=amex count=$count canonical_sha256=$hash")
    }

    @Test fun successiveEmptyMatchesPublishTheirOwnRequestVersion(): Unit = runBlocking {
        val vm = model()
        listOf("zzznomatchone", "zzznomatchtwo").forEach { query ->
            vm.setQuery(query)
            val version = vm.queryVersion
            withTimeout(10_000) { vm.publishedQueryVersion.first { it == version } }
            withTimeout(10_000) { vm.state.first { it == SearchUiState.Empty } }
            assertEquals(version, vm.publishedQueryVersion.value)
        }
    }

    @Test fun successiveQueriesKeepResultsBoundToTheirOwnRequestVersion(): Unit = runBlocking {
        val vm = model()
        vm.setQuery("lounge")
        val firstVersion = vm.queryVersion
        val first = result(vm) { it.requestVersion == firstVersion }
        assertEquals(1, first.results.count)
        vm.rememberPosition(8, 20, SearchTab.BENEFITS)
        vm.setQuery("American Express")
        val nextVersion = vm.queryVersion
        assertTrue(nextVersion > firstVersion)
        val next = result(vm) { it.requestVersion == nextVersion }
        assertTrue(next.results.count > first.results.count)
        assertEquals(firstVersion, first.requestVersion)
        assertEquals(0 to 0, vm.position(SearchTab.BENEFITS))
        vm.setQuery("American Express")
        assertEquals(nextVersion, vm.queryVersion)
    }

    @Test fun realRoomProjectionAndMatcherObserveChangeAndRemovalWithoutMutation(): Unit = runBlocking {
        val vm = model(); vm.setQuery("Delta lounge")
        val first = result(vm); assertEquals(1, first.results.count)
        val target = first.results.groups.single().hits.single().entry.target
        assertEquals(SearchValidation.CURRENT, vm.validate(target))
        proof("read", target, first.results.count)
        val before = cards.observeBenefits(key).first()
        vm.setQuery("1008"); withTimeout(10_000) { vm.state.first { it == SearchUiState.Empty } }
        assertEquals(before, cards.observeBenefits(key).first())
        replace("Terminal lounge")
        vm.setQuery("terminal")
        val updated = result(vm) { it.results.groups.single().hits.single().descriptionContext?.contains("Terminal") == true }
        assertEquals(1, updated.results.count)
        proof("updated", updated.results.groups.single().hits.single().entry.target, updated.results.count)
        accounts.markNeedsReconnect("amex")
        vm.setQuery("American Express"); val scoped = result(vm) { it.results.count(SearchTab.REWARDS) == 1 }
        assertEquals(1, scoped.results.count(SearchTab.REWARDS))
        assertTrue(scoped.results.cards("amex").single().benefits.isNotEmpty())
        accounts.removeAccount("amex")
        withTimeout(10_000) { vm.state.first { it == SearchUiState.Empty } }
        assertEquals(SearchValidation.UNAVAILABLE, vm.validate(target))
        assertTrue(cards.allCardsWithBenefits().isEmpty())
        proof("removed", null, 0)
    }

    @Test fun earlierDuplicateRemovalRejectsOldKeyEvenWhenMembershipStillExists(): Unit = runBlocking {
        fun draft(description: String) = BenefitDraft(BenefitSection.CREDIT_EARNED, "Lounge Credit", 0, 5000, 5000,
            "January", false, description = description)
        cards.applyCardBenefits(key, assignBenefitKeys(key, listOf(draft("Earlier"), draft("Later"))), 20)
        val vm = model(); vm.setQuery("Earlier")
        val old = result(vm).results.groups.single().hits.single().entry.target as SearchTarget.BenefitTarget
        cards.applyCardBenefits(key, assignBenefitKeys(key, listOf(draft("Later"))), 21)
        vm.setQuery("Later")
        // Card and benefit signals may briefly re-enter Loading after publishing this revision.
        // A real selection fails closed during that interval; await an accepted fresh result.
        suspend fun acceptedTarget(): SearchTarget.BenefitTarget = withTimeout(10_000) {
            vm.state.filterIsInstance<SearchUiState.Results>().map { state ->
                state.results.groups.single().hits.single().entry.target as SearchTarget.BenefitTarget
            }.filter { target -> target.revision !== old.revision && vm.validate(target) == SearchValidation.CURRENT }.first()
        }
        val current = acceptedTarget()
        assertEquals(old.benefitKey, current.benefitKey)
        proof("reused-key", current, 1)
        assertEquals(SearchValidation.CHANGED, vm.validate(old))
        val reselected = acceptedTarget()
        assertSame(current.revision, reselected.revision)
    }

    @Test fun cardClearRetainsTheAddedProgramAndDoesNotExposeUnaddedCatalogPrograms(): Unit = runBlocking {
        val vm = model(); vm.setQuery("Delta")
        val target = result(vm) { it.results.groups.single().hits.single().entry.target is SearchTarget.CardTarget }
            .results.groups.single().hits.single().entry.target
        cards.deleteProgramCards("amex")
        withTimeout(10_000) { vm.state.first { it == SearchUiState.Empty } }
        assertEquals(SearchValidation.UNAVAILABLE, vm.validate(target))
        vm.setQuery("American Express")
        assertEquals(1, result(vm) { it.results.groups.single().hits.single().entry.target is SearchTarget.ProgramTarget }.results.count)
        assertEquals(1, accounts.observeAccounts().first().size)
    }

    @Test fun realWelcomePreferencesUpdateSearchWithoutAddingIssuerRows(): Unit = runBlocking {
        val data = PreferenceDataStoreFactory.create(scope = preferenceScope) { tmp.root.resolve("search.preferences_pb") }
        val preferences = DataStoreBenefitsDisplaySettings(data)
        val vm = model(preferences = preferences); vm.setQuery("points")
        withTimeout(10_000) { vm.state.first { it == SearchUiState.Empty } }
        val before = cards.observeBenefits(key).first()
        preferences.setWelcomeBonus(key, WelcomeBonusSetup(0, 5000, null, "80000 points"))
        val target = result(vm).results.groups.single().hits.single().entry.target as SearchTarget.BenefitTarget
        assertEquals("local:welcome", target.benefitKey)
        assertEquals(before, cards.observeBenefits(key).first())
        preferences.setWelcomeBonus(key, null)
        withTimeout(10_000) { vm.state.first { it == SearchUiState.Empty } }
        assertEquals(before, cards.observeBenefits(key).first())
    }

    @Test fun preferenceIoFailureIsAReadErrorWhileExistingBenefitsFallbackIsPreserved(): Unit = runBlocking {
        val data = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("Synthetic read failure") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("Search must not write preferences")
        }
        val preferences = DataStoreBenefitsDisplaySettings(data)
        assertEquals(BenefitsDisplayPreferences(), preferences.observe(key).first())
        val vm = model(preferences = preferences); vm.setQuery("points")
        withTimeout(10_000) { vm.state.first { it == SearchUiState.ReadError } }
    }

    @Test fun readFailureIsDistinctFromEmptyAndLocalRetryRecovers(): Unit = runBlocking {
        val fail = AtomicBoolean(true)
        val repository = object : CardRepository by cards {
            override suspend fun allCardsWithBenefits(): Map<Card, List<Benefit>> {
                if (fail.get()) error("Synthetic local read failure")
                return cards.allCardsWithBenefits()
            }
        }
        val vm = model(repository); vm.setQuery("lounge")
        withTimeout(10_000) { vm.state.first { it == SearchUiState.ReadError } }
        fail.set(false); vm.retry()
        assertEquals(1, result(vm).results.count)
    }

    @Test fun slowLocalReadShowsLoadingAndOnlyTheLatestQueryPublishes(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val repository = object : CardRepository by cards {
            override suspend fun allCardsWithBenefits(): Map<Card, List<Benefit>> {
                gate.await(); return cards.allCardsWithBenefits()
            }
        }
        val vm = model(repository); vm.setQuery("lounge"); vm.setQuery("missing")
        delay(50); assertEquals(SearchUiState.Loading, vm.state.value)
        gate.complete(Unit)
        withTimeout(10_000) { vm.state.first { it == SearchUiState.Empty } }
        vm.setQuery("lounge"); assertEquals(1, result(vm).results.count)
    }
}
