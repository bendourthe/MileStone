package com.bendourthe.milestone.ui.search

import com.bendourthe.milestone.ui.FakeAccounts
import com.bendourthe.milestone.ui.FakeCards
import com.bendourthe.milestone.ui.testCatalog
import com.bendourthe.milestone.domain.SearchTab
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class SearchMemoryTest {
    @Test fun onlyActualQueryChangesResetPositionAndFocusIsRequestedOnce() {
        val vm = SearchViewModel(FakeAccounts(), FakeCards(), testCatalog)
        assertTrue(vm.consumeInitialFocus())
        assertFalse(vm.consumeInitialFocus())
        vm.setQuery("lounge")
        assertTrue(vm.consumeScrollReset())
        vm.rememberPosition(12, 42)
        vm.setQuery("lounge")
        assertFalse(vm.consumeScrollReset())
        assertEquals(12 to 42, vm.position(SearchTab.REWARDS))
        vm.clear()
        assertEquals("", vm.query.value)
        assertEquals(0 to 0, vm.position(SearchTab.REWARDS))
        assertTrue(vm.consumeScrollReset())
        assertEquals("", SearchViewModel(FakeAccounts(), FakeCards(), testCatalog).query.value)
    }

    @Test fun tabsKeepIndependentMemoryPositionsAndNewEntryStartsFresh() {
        val vm = SearchViewModel(FakeAccounts(), FakeCards(), testCatalog)
        vm.setQuery("Delta")
        vm.selectTab(SearchTab.BENEFITS)
        vm.rememberPosition(9, 28, SearchTab.BENEFITS)
        vm.selectTab(SearchTab.REWARDS)
        vm.rememberPosition(2, 12, SearchTab.REWARDS)
        vm.selectTab(SearchTab.BENEFITS)
        vm.setQuery("Delta")
        assertEquals(SearchTab.BENEFITS, vm.selectedTab.value)
        assertEquals(9 to 28, vm.position(SearchTab.BENEFITS))
        assertEquals(2 to 12, vm.position(SearchTab.REWARDS))
        vm.setQuery("Lounge")
        assertNull(vm.selectedTab.value)
        SearchTab.entries.forEach { assertEquals(0 to 0, vm.position(it)) }
        assertNull(SearchViewModel(FakeAccounts(), FakeCards(), testCatalog).selectedTab.value)
    }
    @Test fun collapseChoicesAreMemoryOnlyAndResetOnlyOnQueryChange() {
        val vm = SearchViewModel(FakeAccounts(), FakeCards(), testCatalog)
        vm.setQuery("credit")
        vm.toggleGroup("program:amex")
        vm.toggleGroup("card:synthetic")
        assertEquals(setOf("program:amex", "card:synthetic"), vm.collapsed.value)
        vm.selectTab(SearchTab.REWARDS)
        vm.setQuery("credit")
        assertEquals(2, vm.collapsed.value.size)
        vm.toggleGroup("program:amex")
        assertEquals(setOf("card:synthetic"), vm.collapsed.value)
        vm.setQuery("lounge")
        assertTrue(vm.collapsed.value.isEmpty())
        assertTrue(SearchViewModel(FakeAccounts(), FakeCards(), testCatalog).collapsed.value.isEmpty())
    }

}
