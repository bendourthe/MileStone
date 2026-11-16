package com.bendourthe.milestone.ui.search

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bendourthe.milestone.data.RoomAccountRepository
import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.data.db.MileStoneDatabase
import com.bendourthe.milestone.data.db.RoomCardRepository
import com.bendourthe.milestone.data.settings.DataStoreBenefitsDisplaySettings
import com.bendourthe.milestone.domain.*
import com.bendourthe.milestone.ui.theme.AuroraBackground
import com.bendourthe.milestone.ui.theme.MileStoneTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

/** Wall-clock query-to-draw measurement of the production result content, excluding IME and initial loading. */
@RunWith(AndroidJUnit4::class)
class SearchLatencyTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: MileStoneDatabase
    private lateinit var cards: RoomCardRepository
    private lateinit var accounts: RoomAccountRepository
    private lateinit var prefs: DataStoreBenefitsDisplaySettings
    private val preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var preferenceFile: File
    private val models = ViewModelStore()
    private val programs = (0 until 25).map { index ->
        val letters = ('A'.code + index).toChar().toString().repeat(3)
        StaticCatalogRepository().program("amex")!!.copy(programId = "fixture%02d".format(index), displayName = "Fixture Institution$letters")
    }
    private val catalog = object : CatalogRepository {
        override val programs get() = this@SearchLatencyTest.programs
        override fun program(programId: String) = programs.firstOrNull { it.programId == programId }
        override fun search(query: String) = emptyList<Program>()
    }
    private data class Case(val family: String, val query: String, val count: Int, val firstTitle: String?)
    private class Pending(val case: Case, val started: Long, val gcBefore: Long?, val latch: CountDownLatch = CountDownLatch(1)) {
        var elapsedMs: Double = 0.0
        val readyAt = AtomicLong()
    }
    private val pending = AtomicReference<Pending?>()
    private fun matches(state: SearchUiState, case: Case): Boolean = when (state) {
        SearchUiState.Empty -> case.count == 0
        is SearchUiState.Results -> state.results.count == case.count && state.results.groups.firstOrNull()?.hits?.firstOrNull()?.entry?.title == case.firstTitle
        else -> false
    }

    @Before fun setup(): Unit = runBlocking {
        assertTrue("This destructive fixture is emulator-only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        db = Room.inMemoryDatabaseBuilder(context, MileStoneDatabase::class.java).build()
        cards = RoomCardRepository(db); accounts = RoomAccountRepository(db)
        preferenceFile = File(context.cacheDir, "search-latency-${java.util.UUID.randomUUID()}.preferences_pb")
        prefs = DataStoreBenefitsDisplaySettings(PreferenceDataStoreFactory.create(scope = preferenceScope) { preferenceFile })
        programs.forEachIndexed { programIndex, program ->
            accounts.addAccount(program.programId, if (programIndex % 5 == 0) AccountStatus.NEEDS_RECONNECT else AccountStatus.CONNECTED, 1)
            val drafts = (0 until 4).map { cardIndex ->
                val index = programIndex * 4 + cardIndex
                CardDraft("Fixture Card $index", (1000 + index).toString())
            }
            cards.applyCardList(program.programId, CardList.from(drafts, true), 1)
            drafts.forEachIndexed { cardIndex, draft ->
                val key = CardKey(program.programId, draft.lastDigits)
                val rows = (0 until 20).map { row ->
                    val number = (programIndex * 4 + cardIndex) * 20 + row
                    BenefitDraft(BenefitSection.LISTED, "Fixture benefit %04d".format(number), null, null, null, null, number % 7 == 0,
                        description = if (number % 2 == 0) "Terminal lounge access" else "City dining access")
                }
                cards.applyCardBenefits(key, assignBenefitKeys(key, rows), 1)
            }
        }
        prefs.setCompletedLast(CardKey(programs.first().programId, "1000"), false)
    }

    @After fun close(): Unit = runBlocking {
        rule.runOnUiThread { models.clear() }
        preferenceScope.coroutineContext[Job]!!.cancelAndJoin()
        db.close(); preferenceFile.delete()
    }

    @Test fun thirtySettledQueriesRenderWithinTwoHundredMillisecondsAtP95() {
        val before = runBlocking { cards.allCardsWithBenefits() }
        assertEquals(100, before.size)
        assertEquals(2000, before.values.sumOf { it.size })
        val preferenceBefore = preferenceFile.readBytes()
        val vm = SearchViewModel(accounts, cards, catalog, displaySettings = prefs)
        models.put("latency", vm)
        // Observational only: state delivery is not a replacement for the existing visible-draw endpoint.
        preferenceScope.launch(Dispatchers.Unconfined) {
            vm.state.collect { state ->
                val request = pending.get()
                if (request != null && vm.query.value == request.case.query && matches(state, request.case)) {
                    request.readyAt.compareAndSet(0, SystemClock.elapsedRealtimeNanos())
                }
            }
        }
        val phases = JSONArray()
        rule.setContent {
            val query by vm.query.collectAsStateWithLifecycle()
            val state by vm.state.collectAsStateWithLifecycle()
            val selectedTab by vm.selectedTab.collectAsStateWithLifecycle()
            val activeTab = selectedTab ?: (state as? SearchUiState.Results)?.results?.initialTab ?: SearchTab.REWARDS
            val list = remember { LazyListState() }
            val focus = remember { FocusRequester() }
            // Use the production next-layout reset; the expected visible-result draw still ends each sample.
            LaunchedEffect(query) { if (vm.consumeScrollReset()) list.requestScrollToItem(0) }
            MileStoneTheme {
                AuroraBackground(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().navigationBarsPadding().drawWithContent {
                        drawContent()
                        val renderedQuery = query
                        val renderedState = state
                        val request = pending.get()
                        val first = (renderedState as? SearchUiState.Results)?.results?.groups(activeTab)?.firstOrNull()?.hits?.firstOrNull()?.entry
                        val visible = if (request?.case?.count == 0) renderedState == SearchUiState.Empty else
                            first?.title == request?.case?.firstTitle && list.layoutInfo.visibleItemsInfo.any {
                                it.key == first?.id && it.offset < list.layoutInfo.viewportEndOffset && it.offset + it.size > list.layoutInfo.viewportStartOffset
                            }
                        if (request != null && renderedQuery == request.case.query && matches(renderedState, request.case) && visible && pending.compareAndSet(request, null)) {
                            request.elapsedMs = (SystemClock.elapsedRealtimeNanos() - request.started) / 1_000_000.0
                            request.latch.countDown()
                        }
                    }) {
                        SearchContent(query, state, list, focus, vm::setQuery, vm::clear, vm::retry, {}, {},
                            selectedTab = activeTab, onTab = vm::selectTab)
                    }
                }
            }
        }
        rule.waitUntil(30_000) { vm.state.value == SearchUiState.Start }
        fun measure(case: Case): Double {
            rule.waitForIdle()
            lateinit var request: Pending
            rule.runOnUiThread {
                val gcBefore = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
                request = Pending(case, SystemClock.elapsedRealtimeNanos(), gcBefore)
                assertTrue(pending.compareAndSet(null, request))
                vm.setQuery(case.query)
            }
            rule.waitUntil(3_000) { request.latch.count == 0L }
            val gcAfter = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
            val readyAt = request.readyAt.get()
            assertTrue("Expected result-state delivery was observed", readyAt >= request.started)
            val readyMs = (readyAt - request.started) / 1_000_000.0
            assertTrue("State delivery precedes visible draw", readyMs <= request.elapsedMs)
            val gcDelta = request.gcBefore?.let { before -> gcAfter?.minus(before) }
            phases.put(JSONObject().put("family", case.family).put("count", case.count)
                .put("results_ready_ms", readyMs).put("draw_after_results_ms", request.elapsedMs - readyMs)
                .put("gc_delta", gcDelta ?: JSONObject.NULL))
            case.firstTitle?.let { rule.onNode(hasText(it) and hasClickAction()).assertIsDisplayed() }
            assertTrue(request.elapsedMs >= 0)
            return request.elapsedMs
        }
        val broad = Case("all_entities", "fixture", 2125, "Fixture InstitutionAAA")
        val contextual = Case("contextual", "InstitutionAAA terminal", 40, "Fixture benefit 0000")
        val description = Case("description_only", "terminal", 1000, "Fixture benefit 0000")
        listOf(broad, Case("title", "benefit 0001", 1, "Fixture benefit 0001"), contextual).forEach(::measure)
        val observations = mutableListOf<Pair<Case, Double>>()
        repeat(5) { iteration ->
            val number = iteration + 2
            val title = "Fixture benefit %04d".format(number)
            val cases = listOf(broad, Case("title", "benefit %04d".format(number), 1, title), description, contextual,
                Case("program_only", "InstitutionAAA", 85, "Fixture InstitutionAAA"), Case("empty", "unavailablefixturetoken", 0, null))
            cases.forEach { case -> observations += case to measure(case) }
        }
        val sorted = observations.map { it.second }.sorted()
        assertEquals(30, sorted.size)
        val p95 = sorted[ceil(sorted.size * .95).toInt() - 1]
        val result = JSONObject().put("programs", 25).put("cards", 100).put("displayed_benefits", 2000)
            .put("samples", 30).put("warmups", 3).put("p95_ms", p95).put("limit_ms", 200)
            .put("minimum_ms", sorted.first()).put("maximum_ms", sorted.last())
            .put("device", Build.DEVICE).put("hardware", Build.HARDWARE).put("api", Build.VERSION.SDK_INT)
            .put("method", "Monotonic wall-clock from ViewModel query update to production SearchContent draw with the expected active-tab result inside the lazy viewport; visible title asserted after each sample; initial read, IME and initial focus excluded")
            .put("phase_diagnostics_including_warmups", phases)
            .put("observations", JSONArray().also { array -> observations.forEach { (case, elapsed) ->
                array.put(JSONObject().put("family", case.family).put("count", case.count).put("elapsed_ms", elapsed))
            } })
        val output = File(context.getExternalFilesDir(null), "search-latency.json")
        output.writeText(result.toString(2))
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cp ${output.absolutePath} /sdcard/Download/milestone-search-latency.json").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
        assertEquals(before, runBlocking { cards.allCardsWithBenefits() })
        assertArrayEquals(preferenceBefore, preferenceFile.readBytes())
        assertTrue("Query-to-visible p95 was $p95 ms; limit is 200 ms", p95 <= 200.0)
    }
}
