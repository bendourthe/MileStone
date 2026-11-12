package com.bendourthe.milestone.ui.catalog

import app.cash.turbine.test
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.ProgramCategory
import com.bendourthe.milestone.domain.FakeCatalog
import com.bendourthe.milestone.domain.testProgram
import com.bendourthe.milestone.ui.FakeAccounts
import com.bendourthe.milestone.ui.testCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val accounts = FakeAccounts()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun ids(state: CatalogUiState) = state.groups.values.flatten().map { it.program.programId }

    @Test
    fun programsNotAddedAreShownFirstAndTheFilterSwitchesToAddedOrAll() = runTest(dispatcher) {
        accounts.addAccount("hilton", AccountStatus.UNSUPPORTED, 0)
        val vm = CatalogViewModel(testCatalog, accounts)
        vm.uiState.test {
            skipItems(1)
            val state = awaitItem()
            assertEquals(CatalogFilter.NOT_ADDED, state.filter)
            assertEquals(listOf("marriott", "hyatt"), state.groups.getValue(ProgramCategory.HOTEL).map { it.program.programId })
            assertFalse(state.isSearching)
            vm.setFilter(CatalogFilter.ADDED)
            val added = awaitItem()
            assertEquals(listOf("hilton"), ids(added))
            assertTrue(added.groups.getValue(ProgramCategory.HOTEL).single().isAdded)
            vm.setFilter(CatalogFilter.ALL)
            assertEquals(listOf("marriott", "hyatt", "hilton"), awaitItem().groups.getValue(ProgramCategory.HOTEL).map { it.program.programId })
        }
    }

    @Test
    fun searchIsDebouncedAndMatchesCategoryAndName() = runTest(dispatcher) {
        val vm = CatalogViewModel(testCatalog, accounts)
        vm.uiState.test {
            skipItems(2)
            vm.setQuery("hotel")
            advanceUntilIdle()
            assertEquals(listOf("marriott", "hyatt", "hilton"), ids(awaitItem()))
            vm.setQuery("h")
            vm.setQuery("hi")
            vm.setQuery("hil")
            advanceUntilIdle()
            val state = awaitItem()
            assertEquals(listOf("hilton"), ids(state))
            vm.setQuery("%%")
            advanceUntilIdle()
            assertTrue(awaitItem().isEmpty)
        }
    }

    @Test
    fun aProgramThatCannotSyncIsListedAndSelectingItSaysSo() = runTest(dispatcher) {
        val catalog = FakeCatalog(testCatalog.programs + testProgram("united", "United MileagePlus", ProgramCategory.AIRLINE))
        val vm = CatalogViewModel(catalog, accounts)
        vm.uiState.test {
            skipItems(1)
            val airlines = awaitItem().groups.getValue(ProgramCategory.AIRLINE)
            assertEquals(listOf("delta", "united"), airlines.map { it.program.programId })
            assertFalse(airlines.last().canSync)
        }
        vm.events.test {
            vm.onProgramSelected("united")
            assertEquals(CatalogEvent.ShowMessage("United MileagePlus can't sign in from MileStone yet"), awaitItem())
        }
    }

    @Test
    fun selectionEmitsOneShotEvents() = runTest(dispatcher) {
        val vm = CatalogViewModel(testCatalog, accounts)
        vm.events.test {
            vm.onProgramSelected("hilton")
            assertEquals(CatalogEvent.Connect("hilton"), awaitItem())
            vm.onProgramSelected("nope")
            assertEquals(CatalogEvent.ShowMessage("Unknown program"), awaitItem())
        }
    }
}
