package com.bendourthe.milestone.ui.search

import com.bendourthe.milestone.ui.FakeAccounts
import com.bendourthe.milestone.ui.FakeCards
import com.bendourthe.milestone.ui.testCatalog
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
        assertEquals(12, vm.firstVisibleItemIndex)
        assertEquals(42, vm.firstVisibleItemOffset)
        vm.clear()
        assertEquals("", vm.query.value)
        assertEquals(0, vm.firstVisibleItemIndex)
        assertTrue(vm.consumeScrollReset())
        assertEquals("", SearchViewModel(FakeAccounts(), FakeCards(), testCatalog).query.value)
    }
}
