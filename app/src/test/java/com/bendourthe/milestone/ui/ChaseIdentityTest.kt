package com.bendourthe.milestone.ui

import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.domain.SearchEntry
import com.bendourthe.milestone.domain.SearchRevision
import com.bendourthe.milestone.domain.SearchTarget
import com.bendourthe.milestone.domain.SyncResult
import com.bendourthe.milestone.sync.extract.BridgeEvent
import com.bendourthe.milestone.ui.auth.SignInViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Additional sign-ins and local card ids in sign-in and Search (ADR-0009). */
@OptIn(ExperimentalCoroutinesApi::class)
class ChaseIdentityTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val catalog = StaticCatalogRepository()

    @Test
    fun anAdditionalSignInRecordsItsConnectionAndNeverWritesABalance() {
        val applied = mutableListOf<String>()
        val signedIn = mutableListOf<String>()
        val signIn = SignInViewModel(
            catalog,
            applyInteractive = { id, result -> applied += id; result },
            signOutProgram = {},
            sharedSession = false,
            connectionSignedIn = { signedIn += it },
        )
        signIn.openConnection("chase", "chase_2")
        assertEquals("chase_2", signIn.activeConnection.value)

        signIn.onBridgeEvent(catalog.program("chase")!!, BridgeEvent.Extracted(123_456))

        assertEquals(listOf("chase_2"), signedIn)
        assertTrue(applied.isEmpty())
        assertNull(signIn.activeConnection.value)
        assertNull(signIn.active.value)
    }

    @Test
    fun theProgramsOwnSignInStillWritesItsBalance() {
        val applied = mutableListOf<String>()
        val signIn = SignInViewModel(catalog, applyInteractive = { id, result -> applied += id; SyncResult.Success(0) }, signOutProgram = {}, sharedSession = false)
        signIn.open("chase")
        signIn.onBridgeEvent(catalog.program("chase")!!, BridgeEvent.Extracted(123_456))
        assertEquals(listOf("chase"), applied)
    }

    @Test
    fun equalEndingChaseCardsAreDistinctSearchResultsShowingTheirStoredEnding() {
        val revision = SearchRevision()
        val first = SearchEntry(SearchTarget.CardTarget(CardKey("chase", "c_0000000000000001"), revision), "Chase", "Synthetic Reserve", cardDigits = "4321")
        val second = SearchEntry(SearchTarget.CardTarget(CardKey("chase", "c_0000000000000002"), revision), "Chase", "Synthetic Reserve", cardDigits = "4321")
        assertNotEquals(first.id, second.id)
        assertEquals("4321", first.cardIdentity)
        assertEquals("4321", second.cardIdentity)
        assertTrue(first.id.contains("c_0000000000000001"))
    }
}
