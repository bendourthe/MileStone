package com.bendourthe.milestone.ui.search

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.view.View
import android.view.ViewGroup
import android.graphics.Rect as AndroidRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

/** Wall-clock query-to-draw measurement of the production result content, excluding IME and initial loading. */
class SearchLatencyScenario(private val activity: ComponentActivity, private val injectedDelayMs: Long) {
    private val context get() = activity.applicationContext
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
        override val programs get() = this@SearchLatencyScenario.programs
        override fun program(programId: String) = programs.firstOrNull { it.programId == programId }
        override fun search(query: String) = emptyList<Program>()
    }
    private data class Case(val family: String, val query: String, val count: Int, val firstTitle: String?)
    private data class Ready(val at: Long, val processCpuMs: Long, val threadId: Long, val threadCpuNs: Long)
    private class Pending(val case: Case, val started: Long, val gcBefore: Long?, val latch: CountDownLatch = CountDownLatch(1)) {
        var elapsedMs: Double = 0.0
        val ready = AtomicReference<Ready?>()
        val startThreadId = Thread.currentThread().id
        val startThreadCpuNs = Debug.threadCpuTimeNanos()
        val startProcessCpuMs = android.os.Process.getElapsedCpuTime()
        var drawThreadCpuNs = 0L
        var drawProcessCpuMs = 0L
    }
    private val pending = AtomicReference<Pending?>()
    private fun matches(state: SearchUiState, case: Case): Boolean = when (state) {
        SearchUiState.Empty -> case.count == 0
        is SearchUiState.Results -> state.results.count == case.count && state.results.groups.firstOrNull()?.hits?.firstOrNull()?.entry?.title == case.firstTitle
        else -> false
    }

    private fun assertDisplayedTitle(title: String) {
        fun roots(view: View): List<ViewRootForTest> = buildList {
            if (view is ViewRootForTest) add(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) addAll(roots(view.getChildAt(index)))
        }
        val deadline = SystemClock.uptimeMillis() + 3000
        var count = 0
        var displayed = false
        while (!displayed && SystemClock.uptimeMillis() < deadline) {
            onMain {
                val matches = roots(activity.window.decorView).flatMap { root ->
                    root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).map { root to it }
                }.filter { (_, node) ->
                    node.config.contains(SemanticsProperties.Text) && node.config[SemanticsProperties.Text].any { it.text == title } &&
                        node.config.contains(SemanticsActions.OnClick)
                }
                count = matches.size
                displayed = matches.singleOrNull()?.let { (root, node) ->
                    var layout: LayoutInfo? = node.layoutInfo
                    var placed = true
                    while (layout != null) { placed = placed && layout.isPlaced; layout = layout.parentInfo }
                    val location = IntArray(2)
                    root.view.getLocationInWindow(location)
                    val clipped = node.boundsInRoot.translate(Offset(location[0].toFloat(), location[1].toFloat()))
                    val visible = AndroidRect()
                    placed && root.view.isShown && node.boundsInWindow.width > 0 && node.boundsInWindow.height > 0 &&
                        root.view.getGlobalVisibleRect(visible) && clipped.width > 0 && clipped.height > 0 &&
                        clipped.intersect(Rect(visible.left.toFloat(), visible.top.toFloat(), visible.right.toFloat(), visible.bottom.toFloat())).let { it.width > 0 && it.height > 0 }
                } == true
            }
            if (!displayed) Thread.sleep(10)
        }
        assertEquals("Expected one clickable title", 1, count)
        assertTrue("Expected clickable title is displayed", displayed)
    }

    fun setup(): Unit = runBlocking {
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

    fun close(): Unit = runBlocking {
        onMain { models.clear() }
        preferenceScope.coroutineContext[Job]!!.cancelAndJoin()
        db.close(); preferenceFile.delete()
    }

    fun run(): JSONObject {
        val before = runBlocking { cards.allCardsWithBenefits() }
        assertEquals(100, before.size)
        assertEquals(2000, before.values.sumOf { it.size })
        val preferenceBefore = preferenceFile.readBytes()
        val vm = SearchViewModel(accounts, cards, catalog, displaySettings = prefs)
        models.put("latency", vm)
        // Observational only: state delivery is not a replacement for the existing visible-draw endpoint.
        preferenceScope.launch(Dispatchers.Unconfined) {
            combine(vm.state, vm.publishedQueryVersion) { state, version -> state to version }.collect { (state, version) ->
                val request = pending.get()
                if (request != null && version == vm.queryVersion && vm.query.value == request.case.query && matches(state, request.case) &&
                    (state !is SearchUiState.Results || state.requestVersion == vm.queryVersion)) {
                    request.ready.compareAndSet(null, Ready(SystemClock.elapsedRealtimeNanos(), android.os.Process.getElapsedCpuTime(), Thread.currentThread().id, Debug.threadCpuTimeNanos()))
                }
            }
        }
        val focusDeadline = SystemClock.uptimeMillis() + 5000
        var initialFocus = false
        while (!initialFocus && SystemClock.uptimeMillis() < focusDeadline) {
            onMain { initialFocus = activity.hasWindowFocus() }
            if (!initialFocus) Thread.sleep(10)
        }
        check(initialFocus)
        val phases = JSONArray()
        onMain {
            activity.setContent {
            val query by vm.query.collectAsStateWithLifecycle()
            val state by vm.state.collectAsStateWithLifecycle()
            val publishedVersion by vm.publishedQueryVersion.collectAsStateWithLifecycle()
            val selectedTab by vm.selectedTab.collectAsStateWithLifecycle()
            val activeTab = selectedTab ?: (state as? SearchUiState.Results)?.results?.initialTab ?: SearchTab.REWARDS
            val lists = rememberSearchLists(vm, state)
            val list = lists.getValue(activeTab)
            val focus = remember { FocusRequester() }
            // Use production per-tab viewports and reset; the expected visible-result draw still ends each sample.
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
                        if (request != null && renderedQuery == request.case.query && matches(renderedState, request.case) && visible && activity.hasWindowFocus() &&
                            publishedVersion == vm.queryVersion &&
                            (renderedState !is SearchUiState.Results || renderedState.requestVersion == vm.queryVersion) && pending.compareAndSet(request, null)) {
                            request.elapsedMs = (SystemClock.elapsedRealtimeNanos() - request.started) / 1_000_000.0
                            request.drawThreadCpuNs = Debug.threadCpuTimeNanos()
                            request.drawProcessCpuMs = android.os.Process.getElapsedCpuTime()
                            request.latch.countDown()
                        }
                    }) {
                        SearchContent(query, state, list, focus, vm::setQuery, vm::clear, vm::retry, {}, {},
                            selectedTab = activeTab, onTab = vm::selectTab,
                            updating = publishedVersion != vm.queryVersion ||
                                ((state as? SearchUiState.Results)?.requestVersion?.let { it != vm.queryVersion } == true))
                    }
                }
            }
        }
        }
        runBlocking { withTimeout(30_000) { vm.state.first { it == SearchUiState.Start } } }
        require(injectedDelayMs in 0L..1000L)
        fun measure(case: Case): Double {
            // The preceding sample has completed its visible draw and title assertion.
            // Drain queued UI work without waiting for the continuously animated background to become idle.
            var focused = false
            onMain { focused = activity.hasWindowFocus() }
            assertTrue("Synthetic Search window must have foreground focus", focused)
            lateinit var request: Pending
            onMain {
                val gcBefore = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
                request = Pending(case, SystemClock.elapsedRealtimeNanos(), gcBefore)
                assertTrue(pending.compareAndSet(null, request))
                if (injectedDelayMs > 0) Thread.sleep(injectedDelayMs)
                vm.setQuery(case.query)
            }
            assertTrue("Expected matching result was visibly drawn", request.latch.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val gcAfter = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
            val ready = requireNotNull(request.ready.get())
            val readyAt = ready.at
            assertTrue("Expected result-state delivery was observed", readyAt >= request.started)
            val readyMs = (readyAt - request.started) / 1_000_000.0
            assertTrue("State delivery precedes visible draw", readyMs <= request.elapsedMs)
            val gcDelta = request.gcBefore?.let { before -> gcAfter?.minus(before) }
            phases.put(JSONObject().put("family", case.family).put("count", case.count)
                .put("results_ready_ms", readyMs).put("draw_after_results_ms", request.elapsedMs - readyMs)
                .put("gc_delta", gcDelta ?: JSONObject.NULL)
                .put("main_cpu_ms", (request.drawThreadCpuNs - request.startThreadCpuNs) / 1_000_000.0)
                .put("process_ready_cpu_ms", ready.processCpuMs - request.startProcessCpuMs)
                .put("process_after_ready_cpu_ms", request.drawProcessCpuMs - ready.processCpuMs)
                .put("ready_observer_is_main", ready.threadId == request.startThreadId)
                .put("main_after_ready_cpu_ms", if (ready.threadId == request.startThreadId) (request.drawThreadCpuNs - ready.threadCpuNs) / 1_000_000.0 else JSONObject.NULL))
            case.firstTitle?.let(::assertDisplayedTitle)
            assertTrue(request.elapsedMs >= 0)
            return request.elapsedMs
        }
        val broad = Case("all_entities", "fixture", 2125, "Fixture InstitutionAAA")
        val contextual = Case("contextual", "InstitutionAAA terminal", 40, "Fixture benefit 0000")
        val description = Case("description_only", "terminal", 1000, "Fixture benefit 0000")
        listOf(broad, Case("title", "benefit 0001", 1, "Fixture benefit 0001"), contextual).forEach(::measure)
        android.util.Log.i("MileStoneSearchFixture", "timing phase=measured samples=30")
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
            .put("samples", 30).put("warmups", 3).put("frame_clock", "Android Choreographer").put("injected_ui_delay_ms", injectedDelayMs).put("p95_ms", p95).put("limit_ms", 200)
            .put("minimum_ms", sorted.first()).put("maximum_ms", sorted.last())
            .put("device", Build.DEVICE).put("hardware", Build.HARDWARE).put("api", Build.VERSION.SDK_INT)
            .put("method", "Android real-frame monotonic wall-clock from ViewModel query update to production SearchContent draw with the expected active-tab result inside the lazy viewport; visible title asserted for populated samples and Empty state drawn for empty queries; initial read, IME and initial focus excluded")
            .put("phase_diagnostics_including_warmups", phases)
            .put("observations", JSONArray().also { array -> observations.forEach { (case, elapsed) ->
                array.put(JSONObject().put("family", case.family).put("count", case.count).put("elapsed_ms", elapsed))
            } })
        assertEquals(before, runBlocking { cards.allCardsWithBenefits() })
        assertArrayEquals(preferenceBefore, preferenceFile.readBytes())
        return result.put("preservation_passed", true)
            .put("viewport_width", activity.window.decorView.width)
            .put("viewport_height", activity.window.decorView.height)
    }

    private fun onMain(block: () -> Unit) {
        val task = java.util.concurrent.FutureTask<Unit> { block() }
        check(android.os.Handler(android.os.Looper.getMainLooper()).post(task))
        task.get(10, java.util.concurrent.TimeUnit.SECONDS)
    }
    private fun assertTrue(message: String, value: Boolean) { check(value) { message } }
    private fun assertTrue(value: Boolean) { check(value) }
    private fun assertEquals(expected: Any?, actual: Any?) { check(expected == actual) { "Expected $expected but was $actual" } }
    private fun assertEquals(message: String, expected: Any?, actual: Any?) { check(expected == actual) { message } }
    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) { check(expected.contentEquals(actual)) }
}
