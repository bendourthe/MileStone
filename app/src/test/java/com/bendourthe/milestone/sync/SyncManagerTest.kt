package com.bendourthe.milestone.sync

import android.webkit.WebView
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.ExtractionSpec
import com.bendourthe.milestone.domain.FailureReason
import com.bendourthe.milestone.domain.FakeCatalog
import com.bendourthe.milestone.domain.LoyaltyAccount
import com.bendourthe.milestone.domain.RefreshInterval
import com.bendourthe.milestone.domain.SelectorStrategy
import com.bendourthe.milestone.domain.SyncCapability
import com.bendourthe.milestone.domain.SyncResult
import com.bendourthe.milestone.domain.testProgram
import com.bendourthe.milestone.ui.FakeAccounts
import com.bendourthe.milestone.ui.FakeSessions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SyncManagerTest {
    private val base = 1_000_000_000L
    private val ids = (1..14).map { "p$it" }
    private val spec = ExtractionSpec.Selector(SelectorStrategy.CSS, ".points", Regex("/account"), Regex("/login"))
    private val catalog = FakeCatalog(ids.map { testProgram(it, capability = SyncCapability.Automatic(spec)) } + testProgram("manual"))
    private val accounts = FakeAccounts()
    private val sessions = FakeSessions()
    private val fetches = mutableListOf<String>()
    private var running = 0
    private var maxRunning = 0
    private var behavior: suspend (String) -> SyncResult = { SyncResult.Success(5_000) }

    /** What a session read that may not sign in returns, when it differs from [behavior]. */
    private var readBehavior: (suspend (String) -> SyncResult)? = null
    private val signIns = mutableListOf<String>()
    private var hostRegistered = true

    private object Host : WebViewHost {
        override suspend fun <T> withAttachedWebView(block: suspend (WebView) -> T): T = error("unused by the fake source")
    }

    private val autoSignIn = mutableSetOf<String>()
    private val results = mutableListOf<Triple<String, SyncResult, RefreshReason?>>()

    private fun TestScope.manager() = SyncManager(
        accounts = accounts,
        sessions = sessions,
        catalog = catalog,
        source = { program, _, _, signIn ->
            fetches += program.programId
            if (signIn) signIns += program.programId
            running++
            maxRunning = maxOf(maxRunning, running)
            try {
                readBehavior?.takeUnless { signIn }?.invoke(program.programId) ?: behavior(program.programId)
            } finally {
                running--
            }
        },
        scope = backgroundScope,
        host = { if (hostRegistered) Host else null },
        clock = { base + testScheduler.currentTime },
        canSignInAutomatically = { it in autoSignIn },
        onResult = { id, result, reason -> results += Triple(id, result, reason) },
    )

    @Test
    fun disconnectedProgramWithSavedSignInReconnectsByItself() = runTest {
        connected("p1", status = AccountStatus.NEEDS_RECONNECT)
        connected("p2", status = AccountStatus.NEEDS_RECONNECT)
        autoSignIn += "p1"
        manager().refreshAll(RefreshReason.ON_START).join()
        assertEquals(listOf("p1"), fetches)
        assertEquals(AccountStatus.CONNECTED, account("p1").status)
        assertEquals(Triple("p1", SyncResult.Success(5_000), RefreshReason.ON_START), results.single())
    }

    @Test
    fun aSignedOutProgramWhoseSessionStillWorksReadsWithoutWaitingBehindASignIn() = runTest {
        connected("p1", status = AccountStatus.NEEDS_RECONNECT)
        connected("p2", status = AccountStatus.NEEDS_RECONNECT)
        connected("p3", status = AccountStatus.NEEDS_RECONNECT)
        autoSignIn += listOf("p1", "p2", "p3")
        readBehavior = { id -> if (id == "p1") SyncResult.NeedsReconnect else SyncResult.Success(6_000) }
        behavior = { delay(100_000); SyncResult.Success(5_000) }
        val refresh = manager().refreshAll(RefreshReason.ON_START)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(AccountStatus.CONNECTED, account("p2").status)
        assertEquals(AccountStatus.CONNECTED, account("p3").status)
        assertEquals(AccountStatus.NEEDS_RECONNECT, account("p1").status)
        refresh.join()
        assertEquals(AccountStatus.CONNECTED, account("p1").status)
        assertEquals(listOf("p1"), signIns)
    }

    @Test
    fun aKeepAliveReadNeverSignsIn() = runTest {
        connected("p1")
        autoSignIn += "p1"
        behavior = { SyncResult.NeedsReconnect }
        manager().refreshAll(RefreshReason.KEEP_ALIVE).join()
        assertEquals(listOf("p1"), fetches)
        assertTrue(signIns.isEmpty())
    }

    @Test
    fun savedSignInGetsTheLongerBudget() = runTest {
        connected("p1")
        connected("p2")
        autoSignIn += "p1"
        behavior = { delay(SyncManager.FETCH_TIMEOUT_MS + 1_000); SyncResult.Success(5_000) }
        manager().refreshAll(RefreshReason.ON_START).join()
        assertEquals(AccountStatus.CONNECTED, account("p1").status)
        assertEquals(FailureReason.Timeout, account("p2").lastError)
    }

    @Test
    fun interactiveResultsAreReportedWithoutAReason() = runTest {
        connected("p1")
        manager().applyInteractive("p1", SyncResult.Success(9))
        assertEquals(Triple("p1", SyncResult.Success(9), null), results.single())
    }

    @Test
    fun connectAddsATileWhateverTheOutcome() = runTest {
        val manager = manager()
        behavior = { SyncResult.NeedsReconnect }
        assertEquals(SyncResult.NeedsReconnect, manager.connect("p1"))
        assertEquals(AccountStatus.NEEDS_RECONNECT, account("p1").status)
        behavior = { SyncResult.Failure(FailureReason.Timeout) }
        assertEquals(SyncResult.Failure(FailureReason.Timeout), manager.connect("p2"))
        assertEquals(FailureReason.Timeout, account("p2").lastError)
        behavior = { SyncResult.Success(7_000) }
        assertEquals(SyncResult.Success(7_000), manager.connect("p1"))
        assertEquals(7_000L, account("p1").balance)
        assertEquals(AccountStatus.CONNECTED, account("p1").status)
        assertEquals(Triple("p1", SyncResult.Success(7_000), null), results.last())
        assertTrue(manager.state.value.isEmpty())
    }

    @Test
    fun connectShowsTheTileWhileItSignsIn() = runTest {
        val manager = manager()
        behavior = { delay(60_000); SyncResult.Success(7_000) }
        backgroundScope.launch { manager.connect("p1") }
        runCurrent()
        assertNull(account("p1").balance)
        assertEquals(ProgramSyncState.Syncing, manager.state.value["p1"])
        manager.markStep("p1", ProgramSyncState.SigningIn)
        assertEquals(ProgramSyncState.SigningIn, manager.state.value["p1"])
        advanceTimeBy(61_000)
        assertEquals(7_000L, account("p1").balance)
        manager.markStep("p1", ProgramSyncState.AwaitingCode)
        assertTrue(manager.state.value.isEmpty())
    }

    @Test
    fun anAddedProgramSignsInAtOnceWhileBackgroundRefreshesHoldEveryPermit() = runTest {
        connected("p1")
        connected("p2")
        val manager = manager()
        behavior = { if (it == "p3") SyncResult.Success(7_000) else { delay(120_000); SyncResult.Success(5_000) } }
        manager.refreshAll(RefreshReason.MANUAL)
        runCurrent()
        val added = backgroundScope.launch { manager.connect("p3") }
        runCurrent()
        assertTrue(added.isCompleted)
        assertEquals(7_000L, account("p3").balance)
    }

    @Test
    fun removingAProgramStopsItsSignInSoNothingKeepsRunningOnADestroyedPage() = runTest {
        val manager = manager()
        behavior = { delay(SyncManager.AUTO_SIGN_IN_TIMEOUT_MS); SyncResult.Success(7_000) }
        val signingIn = backgroundScope.launch { manager.connect("p1") }
        runCurrent()
        manager.stop("p1")
        assertTrue(signingIn.isCancelled)
        assertEquals(0, running)
        assertTrue(manager.state.value.isEmpty())
    }

    @Test
    fun connectGivesTheHiddenSignInTheLongerBudget() = runTest {
        connected("p1", status = AccountStatus.NEEDS_RECONNECT)
        behavior = { delay(SyncManager.FETCH_TIMEOUT_MS + 1_000); SyncResult.Success(5_000) }
        assertEquals(SyncResult.Success(5_000), manager().connect("p1"))
        assertEquals(AccountStatus.CONNECTED, account("p1").status)
    }

    @Test
    fun connectRefusesProgramsThatCannotSync() = runTest {
        assertEquals(SyncResult.Unsupported, manager().connect("manual"))
        assertTrue(fetches.isEmpty())
    }

    private suspend fun connected(
        id: String,
        status: AccountStatus = AccountStatus.CONNECTED,
        lastAttempt: Long? = null,
        failures: Int = 0,
        balance: Long? = 4_000,
        updated: Long? = null,
    ) {
        accounts.accounts.value = accounts.accounts.value + LoyaltyAccount(id, balance, null, updated, lastAttempt, failures, accounts.accounts.value.size, status, null)
        sessions.markConnected(id, 1)
    }

    private fun account(id: String) = accounts.accounts.value.single { it.programId == id }

    @Test
    fun successUpdatesBalanceAndVerifiesSession() = runTest {
        connected("p1")
        manager().refreshAll(RefreshReason.ON_START).join()
        assertEquals(5_000L, account("p1").balance)
        assertEquals(AccountStatus.CONNECTED, account("p1").status)
        assertEquals(base, sessions.get("p1")?.lastVerifiedEpochMs)
    }

    @Test
    fun loginPageYieldsNeedsReconnect() = runTest {
        connected("p1")
        behavior = { SyncResult.NeedsReconnect }
        manager().refreshAll(RefreshReason.ON_START).join()
        assertEquals(AccountStatus.NEEDS_RECONNECT, account("p1").status)
    }

    @Test
    fun timeoutIsMeasuredFromPermitAcquisition() = runTest {
        listOf("p1", "p2", "p3").forEach { connected(it) }
        behavior = { delay(120_000); SyncResult.Success(1) }
        manager().refreshAll(RefreshReason.ON_START).join()
        assertEquals(listOf(FailureReason.Timeout, FailureReason.Timeout, FailureReason.Timeout), listOf("p1", "p2", "p3").map { account(it).lastError })
        assertEquals(listOf(base + 30_000, base + 30_000, base + 60_000), listOf("p1", "p2", "p3").map { account(it).lastAttemptEpochMs })
    }

    @Test
    fun concurrentRequestsShareOneFetch() = runTest {
        connected("p1")
        behavior = { delay(1_000); SyncResult.Success(5_000) }
        val manager = manager()
        val all = manager.refreshAll(RefreshReason.ON_START)
        runCurrent()
        val manual = manager.refresh("p1", RefreshReason.MANUAL)
        all.join()
        manual.join()
        assertEquals(listOf("p1"), fetches)
    }

    @Test
    fun openingTheAppReplacesARunningKeepAliveSoTheSavedSignInIsUsed() = runTest {
        connected("p1")
        autoSignIn += "p1"
        behavior = { delay(1_000); SyncResult.Success(5_000) }
        val manager = manager()
        val keepAlive = manager.refreshAll(RefreshReason.KEEP_ALIVE)
        runCurrent()
        val open = manager.refreshAll(RefreshReason.ON_START)
        keepAlive.join()
        open.join()
        assertEquals(listOf("p1", "p1"), fetches)
        assertEquals(listOf(RefreshReason.ON_START), results.map { it.third })
        assertTrue(manager.state.value.isEmpty())
    }

    @Test
    fun openingTheAppSignsInAProgramLeftSignedOutMinutesAgo() = runTest {
        connected("p1", status = AccountStatus.NEEDS_RECONNECT, lastAttempt = base - 60_000)
        connected("p2", status = AccountStatus.NEEDS_RECONNECT, lastAttempt = base - 60_000)
        autoSignIn += "p1"
        manager().refreshAll(RefreshReason.ON_START).join()
        assertEquals(listOf("p1"), fetches)
    }

    @Test
    fun atMostTwoFetchesAtOnceAcrossFourteenPrograms() = runTest {
        ids.forEach { connected(it) }
        behavior = { delay(1_000); SyncResult.Success(5_000) }
        val manager = manager()
        val job = manager.refreshAll(RefreshReason.ON_START)
        runCurrent()
        assertEquals(ids.toSet(), manager.state.value.keys)
        job.join()
        assertEquals(14, fetches.size)
        assertEquals(2, maxRunning)
        assertTrue(manager.state.value.isEmpty())
    }

    @Test
    fun openingCatchesUpOnlyOnAMissedSundayRefresh() = runTest {
        // base is Monday 1970-01-12 13:46 UTC, so the last weekly refresh time was Sunday 1970-01-11 00:00 UTC.
        val hour = 60 * 60_000L
        connected("sinceSunday", updated = base - hour)
        connected("signedOutSinceSunday", status = AccountStatus.NEEDS_RECONNECT, updated = base - hour)
        connected("beforeSunday", updated = base - 40 * hour)
        connected("future", updated = base + 24 * hour)
        accounts.accounts.value += LoyaltyAccount("failedSinceSunday", 4_000, null, base - 40 * hour, base - 10 * 60_000L, 1, 9, AccountStatus.ERROR, FailureReason.Timeout)
        accounts.accounts.value += LoyaltyAccount("failedBeforeSunday", 4_000, null, base - 50 * hour, base - 40 * hour, 1, 10, AccountStatus.ERROR, FailureReason.Timeout)
        autoSignIn += listOf("signedOutSinceSunday", "failedSinceSunday", "failedBeforeSunday")
        val programs = listOf("sinceSunday", "signedOutSinceSunday", "beforeSunday", "future", "failedSinceSunday", "failedBeforeSunday")
        val manager = SyncManager(
            accounts, sessions, FakeCatalog(programs.map { testProgram(it, capability = SyncCapability.Automatic(spec)) }),
            { program, _, _, _ -> fetches += program.programId; SyncResult.Success(5_000) },
            backgroundScope, { Host }, { base + testScheduler.currentTime },
            canSignInAutomatically = { it in autoSignIn },
            scheduledBefore = { now -> RefreshInterval.WEEKLY.lastAt(now, ZoneOffset.UTC) },
        )
        manager.refreshAll(RefreshReason.ON_START).join()
        assertEquals(setOf("beforeSunday", "future", "failedBeforeSunday"), fetches.toSet())
        fetches.clear()
        manager.refreshAll(RefreshReason.MANUAL).join()
        assertEquals(programs.toSet(), fetches.toSet())
    }

    @Test
    fun theScheduledRefreshUpdatesEveryBalanceOlderThanItsTimeAndMaySignIn() = runTest {
        val hour = 60 * 60_000L
        connected("p1", updated = base - 40 * hour)
        connected("p2", status = AccountStatus.NEEDS_RECONNECT, updated = base - 40 * hour)
        connected("p3", updated = base - hour)
        autoSignIn += "p2"
        val manager = SyncManager(
            accounts, sessions, catalog,
            { program, _, _, _ -> fetches += program.programId; SyncResult.Success(5_000) },
            backgroundScope, { Host }, { base + testScheduler.currentTime },
            canSignInAutomatically = { it in autoSignIn },
            scheduledBefore = { now -> RefreshInterval.WEEKLY.lastAt(now, ZoneOffset.UTC) },
        )
        manager.refreshAll(RefreshReason.SCHEDULED).join()
        assertEquals(setOf("p1", "p2"), fetches.toSet())
        assertEquals(AccountStatus.CONNECTED, account("p2").status)
    }

    @Test
    fun aTimedOutSignInIsRetriedOnceOneProgramAtATime() = runTest {
        connected("p1", status = AccountStatus.NEEDS_RECONNECT)
        connected("p2", status = AccountStatus.NEEDS_RECONNECT)
        connected("p3", status = AccountStatus.NEEDS_RECONNECT)
        autoSignIn += listOf("p1", "p2", "p3")
        readBehavior = { SyncResult.NeedsReconnect }
        var signingIn = 0
        var maxSigningIn = 0
        behavior = { id ->
            maxSigningIn = maxOf(maxSigningIn, ++signingIn)
            delay(1_000)
            signingIn--
            when {
                id == "p3" -> SyncResult.Failure(FailureReason.Timeout)
                signIns.count { it == id } == 1 -> SyncResult.Failure(FailureReason.Timeout)
                else -> SyncResult.Success(5_000)
            }
        }
        manager().refreshAll(RefreshReason.ON_START).join()
        assertEquals(listOf("p1", "p2", "p3", "p1", "p2", "p3"), signIns)
        assertEquals(1, maxSigningIn)
        assertEquals(listOf(AccountStatus.CONNECTED, AccountStatus.CONNECTED, AccountStatus.ERROR), listOf("p1", "p2", "p3").map { account(it).status })
    }

    @Test
    fun aRefusedPasswordOrAMissingCodeIsNeverRetriedAndNothingIsRetriedWithoutASavedSignIn() = runTest {
        connected("p1", status = AccountStatus.NEEDS_RECONNECT)
        connected("p2", status = AccountStatus.NEEDS_RECONNECT)
        connected("p3")
        autoSignIn += listOf("p1", "p2")
        behavior = { id ->
            when (id) {
                "p1" -> SyncResult.Failure(FailureReason.PasswordRefused)
                "p2" -> SyncResult.Failure(FailureReason.CodeMissing)
                else -> SyncResult.Failure(FailureReason.Timeout)
            }
        }
        manager().refreshAll(RefreshReason.MANUAL).join()
        assertEquals(listOf("p1", "p2", "p3"), fetches.sorted())
    }

    @Test
    fun signInsTakeTurnsWhileSessionReadsRunBesideThem() = runTest {
        connected("in1", status = AccountStatus.NEEDS_RECONNECT)
        connected("in2", status = AccountStatus.NEEDS_RECONNECT)
        connected("p1")
        connected("p2")
        val catalog = FakeCatalog(listOf("in1", "in2", "p1", "p2").map { testProgram(it, capability = SyncCapability.Automatic(spec)) })
        var signingIn = 0
        var maxSigningIn = 0
        val manager = SyncManager(
            accounts, sessions, catalog,
            { program, _, _, mayType ->
                if (!mayType && program.programId.startsWith("in")) return@SyncManager SyncResult.NeedsReconnect
                val signIn = mayType
                if (signIn) maxSigningIn = maxOf(maxSigningIn, ++signingIn)
                running++
                maxRunning = maxOf(maxRunning, running)
                delay(1_000)
                running--
                if (signIn) signingIn--
                SyncResult.Success(5_000)
            },
            backgroundScope, { Host }, { base + testScheduler.currentTime },
            canSignInAutomatically = { it.startsWith("in") },
        )
        manager.refreshAll(RefreshReason.ON_START).join()
        assertEquals(1, maxSigningIn)
        assertEquals(3, maxRunning)
        assertEquals(2_000L, testScheduler.currentTime)
    }

    @Test
    fun ineligibleAccountsAreNeverFetched() = runTest {
        connected("p1", status = AccountStatus.NEEDS_RECONNECT)
        connected("p2", status = AccountStatus.UNSUPPORTED)
        connected("manual")
        connected("p3")
        sessions.clear("p3")
        val manager = manager()
        manager.refreshAll(RefreshReason.ON_START).join()
        manager.refresh("p1", RefreshReason.MANUAL).join()
        assertEquals(emptyList<String>(), fetches)
    }

    @Test
    fun noRegisteredHostWritesNothing() = runTest {
        connected("p1")
        hostRegistered = false
        manager().refreshAll(RefreshReason.ON_START).join()
        assertEquals(emptyList<String>(), fetches)
        assertEquals(emptyList<Pair<String, SyncResult>>(), accounts.syncWrites)
    }

    @Test
    fun backoffAfterThreeFailures() = runTest {
        assertEquals(20 * 60_000L, SyncManager.waitAfter(3))
        assertEquals(6 * 60 * 60_000L, SyncManager.waitAfter(30))
        connected("p1", status = AccountStatus.ERROR, failures = 3, lastAttempt = base - 15 * 60_000)
        connected("p2", status = AccountStatus.ERROR, failures = 3, lastAttempt = base - 21 * 60_000)
        manager().refreshAll(RefreshReason.KEEP_ALIVE).join()
        assertEquals(listOf("p2"), fetches)
    }

    @Test
    fun keepAliveNeverSignsInAndSkipsStoppedSignIns() = runTest {
        connected("p1", status = AccountStatus.NEEDS_RECONNECT)
        connected("p2", status = AccountStatus.ERROR)
        accounts.accounts.value = accounts.accounts.value.map { if (it.programId == "p2") it.copy(lastError = FailureReason.CodeMissing) else it }
        connected("p3")
        autoSignIn += listOf("p1", "p3")
        behavior = { delay(SyncManager.FETCH_TIMEOUT_MS + 1_000); SyncResult.Success(5_000) }
        manager().refreshAll(RefreshReason.KEEP_ALIVE).join()
        assertEquals(listOf("p3"), fetches)
        assertTrue(signIns.isEmpty())
    }

    @Test
    fun aKeepAliveTimeoutOrLoadingBalanceLeavesAWorkingTileUnchanged() = runTest {
        connected("p1")
        connected("p2")
        behavior = { id -> if (id == "p1") SyncResult.Failure(FailureReason.Timeout) else SyncResult.Failure(FailureReason.Unparseable) }
        manager().refreshAll(RefreshReason.KEEP_ALIVE).join()
        for (id in listOf("p1", "p2")) {
            assertEquals(AccountStatus.CONNECTED, account(id).status)
            assertNull(account(id).lastError)
            assertEquals(0, account(id).consecutiveFailures)
        }
        assertTrue(results.isEmpty())
    }

    @Test
    fun aKeepAliveMissNeverChangesAnErrorAlreadyOnTheTile() = runTest {
        // A timeout from a scheduled sign-in is real news; a later quiet keep-alive miss must not hide it.
        connected("p1", status = AccountStatus.ERROR)
        connected("p2", status = AccountStatus.ERROR)
        accounts.accounts.value = accounts.accounts.value.map {
            it.copy(lastError = if (it.programId == "p1") FailureReason.Timeout else FailureReason.Network(503), consecutiveFailures = 0)
        }
        behavior = { SyncResult.Failure(FailureReason.Timeout) }
        manager().refreshAll(RefreshReason.KEEP_ALIVE).join()
        assertEquals(FailureReason.Timeout, account("p1").lastError)
        assertEquals(FailureReason.Network(503), account("p2").lastError)
        assertTrue(results.isEmpty())
    }

    @Test
    fun aRefreshNeverStartsBesideASignInTheOwnerStarted() = runTest {
        connected("p1")
        autoSignIn += "p1"
        behavior = { delay(10_000); SyncResult.Success(5_000) }
        val manager = manager()
        val connect = backgroundScope.launch { manager.connect("p1") }
        runCurrent()
        manager.refresh("p1", RefreshReason.MANUAL).join()
        manager.refreshAll(RefreshReason.ON_START).join()
        // Only the user's sign-in fetched; neither refresh nor a retry submitted the password beside it.
        assertEquals(listOf("p1"), fetches)
        connect.join()
    }

    @Test
    fun aNetworkFailureDoesNotMarkTheSessionEnded() = runTest {
        connected("p1")
        autoSignIn += "p1"
        readBehavior = { SyncResult.Failure(FailureReason.Network(null)) }
        val manager = manager()
        manager.refreshAll(RefreshReason.KEEP_ALIVE).join()
        fetches.clear()
        readBehavior = null
        manager.refresh("p1", RefreshReason.MANUAL).join()
        // The user's refresh reads the session first instead of typing the password over it.
        assertTrue(signIns.isEmpty())
    }

    @Test
    fun aKeepAliveThatLandsSignedOutStillShowsOnTheTile() = runTest {
        connected("p1")
        behavior = { SyncResult.NeedsReconnect }
        manager().refreshAll(RefreshReason.KEEP_ALIVE).join()
        assertEquals(AccountStatus.NEEDS_RECONNECT, account("p1").status)
    }

    @Test
    fun aSessionThatLastReadSignedOutSignsInWithoutAnotherRead() = runTest {
        connected("p1")
        autoSignIn += "p1"
        readBehavior = { SyncResult.Failure(FailureReason.Timeout) }
        val manager = manager()
        manager.refreshAll(RefreshReason.KEEP_ALIVE).join()
        assertEquals(listOf("p1"), fetches)
        fetches.clear()
        manager.refresh("p1", RefreshReason.MANUAL).join()
        assertEquals(listOf("p1"), fetches)
        assertEquals(listOf("p1"), signIns)
        assertEquals(AccountStatus.CONNECTED, account("p1").status)
        // After a success the session is trusted again, so the next refresh reads before any sign-in.
        fetches.clear()
        readBehavior = null
        advanceTimeBy(SyncManager.MANUAL_REPEAT_WINDOW_MS + 1)
        manager.refresh("p1", RefreshReason.MANUAL).join()
        assertEquals(listOf("p1"), fetches)
        assertEquals(listOf("p1"), signIns)
    }

    @Test
    fun aSessionReadThatWorksIsTriedBeforeASignIn() = runTest {
        connected("p1")
        autoSignIn += "p1"
        readBehavior = { SyncResult.Success(7_000) }
        manager().refresh("p1", RefreshReason.MANUAL).join()
        assertTrue(signIns.isEmpty())
    }

    @Test
    fun anOwnerRefreshThatTimesOutStillShowsOnTheTile() = runTest {
        connected("p1")
        behavior = { SyncResult.Failure(FailureReason.Timeout) }
        manager().refreshAll(RefreshReason.MANUAL).join()
        assertEquals(FailureReason.Timeout, account("p1").lastError)
    }

    @Test
    fun aRefusedPasswordIsNotRetriedOnOpenWhileBlocked() = runTest {
        connected("p1", status = AccountStatus.ERROR)
        accounts.accounts.value = accounts.accounts.value.map { it.copy(lastError = FailureReason.PasswordRefused) }
        manager().refreshAll(RefreshReason.ON_START).join()
        assertTrue(fetches.isEmpty())
        autoSignIn += "p1"
        manager().refreshAll(RefreshReason.ON_START).join()
        assertEquals(listOf("p1"), fetches)
    }

    @Test
    fun manualIgnoresWindowsButDropsRepeatsWithinTenSeconds() = runTest {
        connected("p1", status = AccountStatus.ERROR, failures = 5, lastAttempt = base - 60_000)
        val manager = manager()
        manager.refresh("p1", RefreshReason.MANUAL).join()
        advanceTimeBy(5_000)
        manager.refresh("p1", RefreshReason.MANUAL).join()
        advanceTimeBy(6_000)
        manager.refresh("p1", RefreshReason.MANUAL).join()
        assertEquals(listOf("p1", "p1"), fetches)
    }

    @Test
    fun backgroundingMidSyncLeavesQueuedTilesUnchanged() = runTest {
        listOf("p1", "p2", "p3").forEach { connected(it) }
        behavior = { delay(10_000); SyncResult.Success(5_000) }
        val manager = manager()
        manager.refreshAll(RefreshReason.ON_START)
        advanceTimeBy(1_000)
        manager.cancelHeadless()
        advanceTimeBy(60_000)
        assertEquals(emptyList<Pair<String, SyncResult>>(), accounts.syncWrites)
        assertEquals(listOf(AccountStatus.CONNECTED), listOf("p1", "p2", "p3").map { account(it).status }.distinct())
        assertTrue(manager.state.value.isEmpty())
    }

    @Test
    fun implausibleHeadlessReadIsRejected() = runTest {
        connected("p1", balance = 100_000)
        behavior = { SyncResult.Success(0) }
        manager().refreshAll(RefreshReason.ON_START).join()
        assertEquals(100_000L, account("p1").balance)
        assertEquals(FailureReason.Implausible, account("p1").lastError)
    }

    @Test
    fun sourceExceptionBecomesUnexpectedForThatProgramOnly() = runTest {
        connected("p1")
        connected("p2")
        behavior = { id -> if (id == "p1") error("boom") else SyncResult.Success(5_000) }
        manager().refreshAll(RefreshReason.ON_START).join()
        assertEquals(FailureReason.Unexpected, account("p1").lastError)
        assertEquals(5_000L, account("p2").balance)
    }

    @Test
    fun firstLoginCreatesConnectedAccountAndSession() = runTest {
        val manager = manager()
        assertEquals(SyncResult.Success(82_410), manager.applyInteractive("p1", SyncResult.Success(82_410)))
        assertEquals(82_410L, account("p1").balance)
        assertEquals(AccountStatus.CONNECTED, account("p1").status)
        assertEquals(base, sessions.get("p1")?.connectedAtEpochMs)
        assertTrue(manager.state.value.isEmpty())
    }

    @Test
    fun repeatLoginUpdatesTheSameAccount() = runTest {
        val manager = manager()
        manager.applyInteractive("p1", SyncResult.Success(82_410))
        manager.applyInteractive("p1", SyncResult.Success(90_000))
        assertEquals(listOf(90_000L), accounts.accounts.value.map { it.balance })
    }

    @Test
    fun interactiveLoginCancelsHeadlessFetch() = runTest {
        connected("p1")
        behavior = { delay(10_000); SyncResult.Success(1) }
        val manager = manager()
        manager.refreshAll(RefreshReason.ON_START)
        advanceTimeBy(1_000)
        manager.applyInteractive("p1", SyncResult.Success(82_410))
        advanceTimeBy(60_000)
        assertEquals(listOf("p1" to SyncResult.Success(82_410)), accounts.syncWrites)
    }

    @Test
    fun repositoryErrorSurfacesAsUnexpectedWithoutSession() = runTest {
        val failing = object : com.bendourthe.milestone.domain.AccountRepository by accounts {
            override suspend fun applySyncResult(
                programId: String, result: SyncResult, completedEpochMs: Long, createIfMissing: Boolean,
            ) = error("disk full")
        }
        val manager = SyncManager(failing, sessions, catalog, { _, _, _, _ -> SyncResult.Unsupported }, backgroundScope, { Host }, { base })
        assertEquals(SyncResult.Failure(FailureReason.Unexpected), manager.applyInteractive("p1", SyncResult.Success(1)))
        assertNull(sessions.get("p1"))
    }

    @Test
    fun plausibilityRule() {
        val implausible = SyncResult.Failure(FailureReason.Implausible)
        val cases = listOf(
            Triple(SyncResult.Success(0), 5_000L, false) to implausible,
            Triple(SyncResult.Success(499), 5_000L, false) to implausible,
            Triple(SyncResult.Success(500), 5_000L, false) to SyncResult.Success(500),
            Triple(SyncResult.Success(0), 0L, false) to SyncResult.Success(0),
            Triple(SyncResult.Success(0), null, false) to SyncResult.Success(0),
            Triple(SyncResult.Success(0), 5_000L, true) to SyncResult.Success(0),
            Triple(SyncResult.NeedsReconnect, 5_000L, false) to SyncResult.NeedsReconnect,
        )
        for ((input, expected) in cases) {
            assertEquals(input.toString(), expected, SyncManager.checkPlausible(input.first, input.second, input.third))
        }
    }
}
