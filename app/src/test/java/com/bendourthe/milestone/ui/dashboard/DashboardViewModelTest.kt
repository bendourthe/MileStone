package com.bendourthe.milestone.ui.dashboard

import app.cash.turbine.test
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.FailureReason
import com.bendourthe.milestone.domain.SortMode
import com.bendourthe.milestone.domain.SyncResult
import com.bendourthe.milestone.domain.usecase.ObserveDashboard
import com.bendourthe.milestone.sync.ProgramSyncState
import com.bendourthe.milestone.ui.FakeAccounts
import com.bendourthe.milestone.ui.FakeSettings
import com.bendourthe.milestone.ui.awaitUntil
import com.bendourthe.milestone.ui.testCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val accounts = FakeAccounts()
    private val settings = FakeSettings()
    private val sync = MutableStateFlow<Map<String, ProgramSyncState>>(emptyMap())
    private val now = 1_000_000_000L

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = DashboardViewModel(testCatalog, ObserveDashboard(accounts, testCatalog), settings, accounts, sync, { now }, Locale.US)

    private fun ids(state: DashboardUiState) = state.tiles.map { it.programId }

    @Test
    fun syncedBalanceShowsFormattedTile() = runTest(dispatcher) {
        accounts.addAccount("hilton", AccountStatus.CONNECTED, now)
        accounts.applySyncResult("hilton", SyncResult.Success(82_410), now)
        viewModel().uiState.test {
            val tile = awaitUntil { it.tiles.isNotEmpty() }.tiles.single()
            assertEquals("82,410 pts", tile.formattedBalance)
            assertEquals("Last updated: just now", tile.lastUpdatedLabel)
        }
    }

    @Test
    fun nullBalanceRendersDashesAndSyncFlagsPropagate() = runTest(dispatcher) {
        accounts.addAccount("amex", AccountStatus.NEEDS_RECONNECT, 0)
        sync.value = mapOf("amex" to ProgramSyncState.Syncing)
        viewModel().uiState.test {
            val tile = awaitUntil { it.tiles.isNotEmpty() }.tiles.single()
            assertEquals("--", tile.formattedBalance)
            assertEquals("Connecting...", tile.syncStatus)
            assertTrue(tile.isSyncing)
            assertTrue(tile.needsReconnect)

            sync.value = mapOf("amex" to ProgramSyncState.SigningIn)
            assertEquals("Signing in...", awaitUntil { it.tiles.single().syncStatus != "Connecting..." }.tiles.single().syncStatus)
            sync.value = mapOf("amex" to ProgramSyncState.AwaitingCode)
            assertEquals("Waiting for the texted code...", awaitUntil { it.tiles.single().syncStatus != "Signing in..." }.tiles.single().syncStatus)
            sync.value = emptyMap()
            assertNull(awaitUntil { !it.tiles.single().isSyncing }.tiles.single().syncStatus)
        }
    }

    @Test
    fun anEndedSessionWithASavedSignInAsksForNothingAndStaysRefreshable() = runTest(dispatcher) {
        accounts.addAccount("amex", AccountStatus.NEEDS_RECONNECT, 0)
        accounts.addAccount("hilton", AccountStatus.NEEDS_RECONNECT, 0)
        val vm = DashboardViewModel(testCatalog, ObserveDashboard(accounts, testCatalog), settings, accounts, sync, { now }, Locale.US, savedSignIns = MutableStateFlow(setOf("amex")))
        vm.uiState.test {
            val tiles = awaitUntil { it.tiles.size == 2 }.tiles.associateBy { it.programId }
            assertFalse(tiles.getValue("amex").needsReconnect)
            assertTrue(tiles.getValue("amex").hasSavedSignIn)
            assertTrue(tiles.getValue("hilton").needsReconnect)
            assertFalse(tiles.getValue("hilton").hasSavedSignIn)
        }
    }

    @Test
    fun moveFromThreeToZeroPersistsInCustomMode() = runTest(dispatcher) {
        listOf("amex", "chase", "delta", "hilton").forEach { accounts.addAccount(it, AccountStatus.UNSUPPORTED, 0) }
        val vm = viewModel()
        vm.uiState.test {
            vm.setSortMode(SortMode.CUSTOM)
            assertEquals(listOf("amex", "chase", "delta", "hilton"), ids(awaitUntil { it.canReorder && it.tiles.size == 4 }))
            vm.onMove(3, 0)
            assertEquals(listOf("hilton", "amex", "chase", "delta"), ids(awaitItem()))
            vm.onDragEnd()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(listOf("hilton", "amex", "chase", "delta")), accounts.reorderCalls)
    }

    @Test
    fun movesIgnoredInAlphabeticalMode() = runTest(dispatcher) {
        listOf("amex", "chase").forEach { accounts.addAccount(it, AccountStatus.UNSUPPORTED, 0) }
        val vm = viewModel()
        vm.uiState.test {
            awaitUntil { it.tiles.size == 2 }
            vm.onMove(1, 0)
            vm.onDragEnd()
            expectNoEvents()
        }
        assertTrue(accounts.reorderCalls.isEmpty())
    }

    @Test
    fun failedReorderRevertsAndReportsOnce() = runTest(dispatcher) {
        listOf("amex", "chase").forEach { accounts.addAccount(it, AccountStatus.UNSUPPORTED, 0) }
        accounts.failReorder = true
        settings.setSortMode(SortMode.CUSTOM)
        val vm = viewModel()
        vm.messages.test {
            vm.uiState.test {
                awaitUntil { it.canReorder && it.tiles.size == 2 }
                vm.onMove(1, 0)
                assertEquals(listOf("chase", "amex"), ids(awaitItem()))
                vm.onDragEnd()
                assertEquals(listOf("amex", "chase"), ids(awaitItem()))
            }
            assertEquals("Could not save the new order", awaitItem())
        }
    }

    @Test
    fun removeDeletesAccount() = runTest(dispatcher) {
        accounts.addAccount("amex", AccountStatus.UNSUPPORTED, 0)
        viewModel().remove("amex")
        assertTrue(accounts.accounts.value.isEmpty())
    }

    @Test
    fun openSummaryNamesEachProgramThatIsStillDisconnectedAndWhy() {
        val tile = TileUiState("a", "Alpha", "A", null, 0, "pts", "--", "Never", null, false, null, canSync = true, isConnected = true)
        assertNull(DashboardViewModel.openSummary(listOf(tile), setOf("a")))
        val summary = DashboardViewModel.openSummary(
            listOf(
                tile,
                tile.copy(programId = "b", displayName = "Beta", isConnected = false, errorReason = FailureReason.PasswordRefused),
                tile.copy(programId = "c", displayName = "Gamma", isConnected = false, needsReconnect = true),
                tile.copy(programId = "d", displayName = "Delta", isConnected = false, needsReconnect = true, canSync = false),
            ),
            setOf("a", "b", "c", "d"),
        )
        assertEquals("Couldn't reconnect: Beta (Password refused), Gamma (Sign in needed)", summary)
    }

    @Test
    fun openSummaryNeverAnnouncesAnOlderErrorOnAProgramThatWasNotRefreshed() {
        val tile = TileUiState("e", "Aeroplan", "A", null, 0, "pts", "--", "Never", null, false, null, canSync = true, isConnected = true)
            .copy(isConnected = false, errorReason = FailureReason.Timeout)
        assertNull(DashboardViewModel.openSummary(listOf(tile), emptySet()))
        assertNull(DashboardViewModel.openSummary(listOf(tile), setOf("other")))
    }

    @Test
    fun lastUpdatedLabels() {
        assertEquals("Last updated: just now", DashboardViewModel.lastUpdatedLabel(now + 60_000, now))
        assertEquals("Last updated: 5 min ago", DashboardViewModel.lastUpdatedLabel(now - 5 * 60_000, now))
        assertEquals("Last updated: 3 h ago", DashboardViewModel.lastUpdatedLabel(now - 3 * 3_600_000, now))
        assertEquals("Last updated: 2 d ago", DashboardViewModel.lastUpdatedLabel(now - 2 * 86_400_000, now))
        assertEquals("Never", DashboardViewModel.lastUpdatedLabel(null, now))
    }
}
