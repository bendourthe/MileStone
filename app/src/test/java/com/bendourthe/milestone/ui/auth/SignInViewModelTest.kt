package com.bendourthe.milestone.ui.auth

import app.cash.turbine.test
import com.bendourthe.milestone.data.credentials.AutoSignInStatus
import com.bendourthe.milestone.domain.ExtractionSpec
import com.bendourthe.milestone.domain.FailureReason
import com.bendourthe.milestone.domain.FakeCatalog
import com.bendourthe.milestone.domain.SelectorStrategy
import com.bendourthe.milestone.domain.SyncCapability
import com.bendourthe.milestone.domain.SyncResult
import com.bendourthe.milestone.domain.testProgram
import com.bendourthe.milestone.sync.extract.BridgeEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {
    private val hilton = testProgram(
        "hilton",
        "Hilton Honors",
        capability = SyncCapability.Automatic(ExtractionSpec.Selector(SelectorStrategy.CSS, ".points", Regex("/account"), Regex("/login"))),
    )
    private val catalog = FakeCatalog(listOf(hilton, testProgram("manual")))
    private val applied = mutableListOf<Pair<String, SyncResult>>()
    private val signedOut = mutableListOf<String>()
    private var applyResult: (SyncResult) -> SyncResult = { it }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val statuses = MutableStateFlow<Map<String, AutoSignInStatus>>(emptyMap())
    private val saved = mutableListOf<String>()
    private val allowed = mutableListOf<String>()

    private fun viewModel(shared: Boolean = false) = SignInViewModel(
        catalog,
        applyInteractive = { id, result -> applied += id to result; applyResult(result) },
        signOutProgram = { signedOut += it },
        sharedSession = shared,
        locale = Locale.US,
        autoSignInStatuses = statuses,
        saveSignIn = { id, _, _ -> saved += id },
        allowAutoSignIn = { allowed += it },
        connectHidden = { connected += it; hiddenResult },
    )

    private val connected = mutableListOf<String>()
    private var hiddenResult: SyncResult = SyncResult.Success(1_234)

    @Test
    fun connectWithoutSavedSignInAsksForItThenSignsInOutOfSight() = runTest {
        val vm = viewModel()
        vm.connect("hilton")
        assertEquals(hilton, vm.editingAutoSignIn.value)
        assertNull(vm.active.value)
        vm.messages.test {
            vm.saveAutoSignIn("owner", "secret", readTexts = false)
            assertEquals("returns to the dashboard without a message over the Add program button", SignInMessage(null, true), awaitItem())
            expectNoEvents()
        }
        assertEquals(listOf("hilton"), saved)
        assertEquals(listOf("hilton"), connected)
        assertNull(vm.editingAutoSignIn.value)
        assertNull(vm.active.value)
        assertEquals(emptySet<String>(), vm.connecting.value)
    }

    @Test
    fun connectWithSavedSignInLiftsAPauseAndSignsInOutOfSight() {
        statuses.value = mapOf("hilton" to AutoSignInStatus(saved = true, blocked = true, readTexts = false))
        val vm = viewModel()
        vm.connect("hilton")
        assertEquals(listOf("hilton"), allowed)
        assertEquals(listOf("hilton"), connected)
        assertNull(vm.editingAutoSignIn.value)
        assertNull(vm.active.value)
    }

    @Test
    fun connectOpensTheSiteOnlyWhenTheHiddenSignInNeedsTheOwner() = runTest {
        statuses.value = mapOf("hilton" to AutoSignInStatus(saved = true, blocked = false, readTexts = false))
        hiddenResult = SyncResult.NeedsReconnect
        val vm = viewModel()
        vm.messages.test {
            vm.connect("hilton")
            awaitItem()
            assertEquals(SignInMessage("Hilton Honors needs you to sign in once", false), awaitItem())
        }
        assertEquals(hilton, vm.active.value)
    }

    @Test
    fun connectOpensTheSiteWhenThePageWaitsForTheOwner() = runTest {
        statuses.value = mapOf("hilton" to AutoSignInStatus(saved = true, blocked = false, readTexts = false))
        hiddenResult = SyncResult.Failure(FailureReason.NeedsAttention)
        val vm = viewModel()
        vm.connect("hilton")
        assertEquals(hilton, vm.active.value)
    }

    @Test
    fun aSignInPageAfterSubmittingOpensTheSiteInsteadOfAskingForThePasswordAgain() = runTest {
        statuses.value = mapOf("hilton" to AutoSignInStatus(saved = true, blocked = false, readTexts = false))
        hiddenResult = SyncResult.Failure(FailureReason.PasswordRefused)
        val vm = viewModel()
        vm.messages.test {
            vm.connect("hilton")
            awaitItem()
            assertEquals(SignInMessage("Hilton Honors needs you to sign in once", false), awaitItem())
        }
        assertNull(vm.editingAutoSignIn.value)
        assertEquals(hilton, vm.active.value)
    }

    @Test
    fun aMissingCodeOpensTheSiteOnce() = runTest {
        statuses.value = mapOf("hilton" to AutoSignInStatus(saved = true, blocked = false, readTexts = false))
        hiddenResult = SyncResult.Failure(FailureReason.CodeMissing)
        val vm = viewModel()
        vm.connect("hilton")
        assertEquals(hilton, vm.active.value)
    }

    @Test
    fun connectReportsAFailedHiddenSignInWithoutOpeningTheSite() = runTest {
        statuses.value = mapOf("hilton" to AutoSignInStatus(saved = true, blocked = false, readTexts = false))
        hiddenResult = SyncResult.Failure(FailureReason.Timeout)
        val vm = viewModel()
        vm.messages.test {
            vm.connect("hilton")
            awaitItem()
            assertEquals(SignInMessage("Couldn't update Hilton Honors: the balance didn't appear in time", false), awaitItem())
        }
        assertNull(vm.active.value)
    }

    @Test
    fun connectIgnoresProgramsThatCannotSync() {
        val vm = viewModel()
        vm.connect("manual")
        assertNull(vm.editingAutoSignIn.value)
        assertNull(vm.active.value)
    }

    @Test
    fun opensOnlySyncablePrograms() {
        val vm = viewModel()
        vm.open("manual")
        assertNull(vm.active.value)
        vm.open("unknown")
        assertNull(vm.active.value)
        vm.open("hilton")
        assertEquals(hilton, vm.active.value)
    }

    @Test
    fun extractedBalanceIsAppliedAndReturnsToDashboard() = runTest {
        val vm = viewModel()
        vm.open("hilton")
        vm.messages.test {
            vm.onBridgeEvent(hilton, BridgeEvent.LoginRequired)
            assertEquals(hilton, vm.active.value)
            vm.onBridgeEvent(hilton, BridgeEvent.Extracted(82_410))
            assertEquals(SignInMessage(null, true), awaitItem())
        }
        assertNull(vm.active.value)
        assertEquals(listOf("hilton" to SyncResult.Success(82_410)), applied)
    }

    @Test
    fun failedSaveStaysOnScreen() = runTest {
        applyResult = { SyncResult.Failure(FailureReason.Unexpected) }
        val vm = viewModel()
        vm.open("hilton")
        vm.messages.test {
            vm.onBridgeEvent(hilton, BridgeEvent.Extracted(1))
            assertEquals(SignInMessage("Couldn't save the Hilton Honors balance", false), awaitItem())
        }
    }

    @Test
    fun failuresAndUnsupportedWriteNothing() = runTest {
        val vm = viewModel()
        vm.messages.test {
            vm.open("hilton")
            vm.onBridgeEvent(hilton, BridgeEvent.Failed(FailureReason.Tls))
            assertEquals("Couldn't update Hilton Honors: the connection wasn't secure", awaitItem().text)
            vm.open("hilton")
            vm.onBridgeEvent(hilton, BridgeEvent.Unsupported)
            assertEquals("This phone can't sign in to Hilton Honors. Update Android System WebView from the Play Store, then try again.", awaitItem().text)
        }
        assertEquals(emptyList<Pair<String, SyncResult>>(), applied)
    }

    @Test
    fun eventsAfterCloseAreIgnored() {
        val vm = viewModel()
        vm.open("hilton")
        vm.close()
        vm.onBridgeEvent(hilton, BridgeEvent.Extracted(5))
        assertEquals(emptyList<Pair<String, SyncResult>>(), applied)
    }

    @Test
    fun signOutNeedsConfirmation() = runTest {
        val vm = viewModel(shared = true)
        vm.requestSignOut("hilton")
        assertEquals(hilton, vm.pendingSignOut.value)
        vm.cancelSignOut()
        assertEquals(emptyList<String>(), signedOut)
        vm.messages.test {
            vm.requestSignOut("hilton")
            vm.confirmSignOut()
            assertEquals("Signed out of all programs", awaitItem().text)
        }
        assertEquals(listOf("hilton"), signedOut)
        assertNull(vm.pendingSignOut.value)
    }
}
