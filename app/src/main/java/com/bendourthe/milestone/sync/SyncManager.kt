package com.bendourthe.milestone.sync

import com.bendourthe.milestone.DiagnosticLog
import com.bendourthe.milestone.data.session.SessionStore
import com.bendourthe.milestone.domain.AccountRepository
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.BenefitPeriods
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.domain.CardRepository
import com.bendourthe.milestone.domain.CatalogRepository
import com.bendourthe.milestone.domain.assignBenefitKeys
import com.bendourthe.milestone.domain.FailureReason
import com.bendourthe.milestone.domain.LoyaltyAccount
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.domain.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** The only component that writes scraped balances, for both interactive logins and headless refreshes. */
class SyncManager(
    private val accounts: AccountRepository,
    private val sessions: SessionStore,
    private val catalog: CatalogRepository,
    private val source: BalanceSource,
    private val scope: CoroutineScope,
    private val host: () -> WebViewHost?,
    private val clock: () -> Long = System::currentTimeMillis,
    /** True when a saved, unblocked sign-in lets a disconnected program reconnect without the user. */
    private val canSignInAutomatically: (programId: String) -> Boolean = { false },
    /** Every written result, interactive or headless, with the reason that started it (null when interactive). */
    private val onResult: (programId: String, result: SyncResult, reason: RefreshReason?) -> Unit = { _, _, _ -> },
    /** The latest scheduled refresh time at or before the given time (the user's setting); older balances are due. */
    private val scheduledBefore: suspend (nowMs: Long) -> Long = { it },
    /** Reads cards and benefits after a saved balance, for programs whose catalog entry has a benefits page. */
    private val benefitsSource: BenefitsSource? = null,
    private val cards: CardRepository? = null,
    /** The phone's time zone, which anchors credit period ends to the read date. */
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val _state = MutableStateFlow<Map<String, ProgramSyncState>>(emptyMap())
    val state: StateFlow<Map<String, ProgramSyncState>> = _state.asStateFlow()

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val inFlight = mutableMapOf<String, Job>()
    private val inFlightReason = mutableMapOf<String, RefreshReason>()
    private val connecting = mutableMapOf<String, Job>()

    /** At most one benefits read per program; guarded by the same lock as [inFlight]. */
    private val benefitsJobs = mutableMapOf<String, Job>()
    private val lastManualRequest = mutableMapOf<String, Long>()
    private val permits = Semaphore(MAX_CONCURRENT_FETCHES)

    /** Programs that must sign in take turns on their own permit, so a slow sign-in never holds up quick reads. */
    private val signInPermits = Semaphore(1)

    private fun lock(programId: String) = locks.getOrPut(programId) { Mutex() }

    /** Programs whose last session read did not reach the balance; kept in memory only, so a restart reads first again. */
    private val sessionEnded: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Applies a result from a login the user is watching; returns what was written. */
    suspend fun applyInteractive(programId: String, result: SyncResult): SyncResult {
        cancel(programId)
        return lock(programId).withLock {
            _state.update { it + (programId to ProgramSyncState.Syncing) }
            try {
                val now = clock()
                accounts.applySyncResult(programId, result, now, createIfMissing = true)
                if (result is SyncResult.Success) {
                    sessions.markConnected(programId, now)
                    sessionEnded -= programId
                    catalog.program(programId)?.let { startBenefits(it, null) }
                }
                onResult(programId, result, null)
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticLog.w(TAG, "Interactive result for $programId not applied: ${e.javaClass.simpleName}")
                SyncResult.Failure(FailureReason.Unexpected)
            } finally {
                _state.update { it - programId }
            }
        }
    }

    /**
     * Signs a program in out of sight with its saved sign-in, for Add program and a tapped sign-in chip. A program
     * without a tile gets one as the sign-in starts, so the user watches it connect and then sees its balance or why
     * it stopped.
     */
    suspend fun connect(programId: String): SyncResult {
        val program = catalog.program(programId)?.takeIf { it.canSync } ?: return SyncResult.Unsupported
        cancel(programId)
        val self = currentCoroutineContext()[Job]
        if (self != null) synchronized(inFlight) { connecting[programId] = self }
        _state.update { it + (programId to ProgramSyncState.Syncing) }
        try {
            accounts.addAccount(programId, AccountStatus.NEEDS_RECONNECT, clock())
            // The user is waiting on this one, so it never queues behind background refreshes.
            val webViewHost = host() ?: return SyncResult.Failure(FailureReason.Unexpected)
            val result = fetch(program, webViewHost, AUTO_SIGN_IN_TIMEOUT_MS, signIn = true)
            lock(programId).withLock {
                val now = clock()
                accounts.applySyncResult(programId, result, now, createIfMissing = result != SyncResult.Unsupported)
                if (result is SyncResult.Success) {
                    sessions.markConnected(programId, now)
                    sessionEnded -= programId
                    startBenefits(program, null)
                }
                onResult(programId, result, null)
            }
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticLog.w(TAG, "Connect for $programId not applied: ${e.javaClass.simpleName}")
            return SyncResult.Failure(FailureReason.Unexpected)
        } finally {
            synchronized(inFlight) { if (connecting[programId] === self) connecting.remove(programId) }
            _state.update { it - programId }
        }
    }

    /**
     * Starts every eligible headless refresh, then signs in once more, one program at a time, each one whose sign-in
     * timed out or stopped unfinished. The returned job completes when all of them have. [started] receives the
     * programs this run refreshes.
     */
    fun refreshAll(reason: RefreshReason, started: (List<String>) -> Unit = {}): Job = scope.launch {
        val now = clock()
        val eligible = accounts.observeAccounts().first()
            .filter { isEligible(it, reason, now) }
            .map { it.programId }
        DiagnosticLog.i(TAG, "Refresh ${reason.name}: ${eligible.ifEmpty { listOf("none") }.joinToString()}")
        started(eligible)
        eligible.mapNotNull { start(it, reason) }.joinAll()
        retryOnce(eligible, reason)
    }

    fun refresh(programId: String, reason: RefreshReason): Job = scope.launch {
        val now = clock()
        if (reason == RefreshReason.MANUAL) {
            val dropped = synchronized(inFlight) {
                val last = lastManualRequest[programId]
                (last != null && now >= last && now - last < MANUAL_REPEAT_WINDOW_MS).also { if (!it) lastManualRequest[programId] = now }
            }
            if (dropped) return@launch
        }
        val account = accounts.observeAccounts().first().firstOrNull { it.programId == programId } ?: return@launch
        if (!isEligible(account, reason, now)) return@launch
        start(programId, reason)?.join()
        retryOnce(listOf(programId), reason)
    }

    /**
     * A refused password and a missing texted code are never retried: one could lock the account, the other sends
     * another text. The background keep-alive never signs in, so it never retries either.
     */
    private suspend fun retryOnce(programIds: List<String>, reason: RefreshReason) {
        if (reason == RefreshReason.KEEP_ALIVE) return
        val retry = accounts.observeAccounts().first()
            .filter { it.programId in programIds && it.status != AccountStatus.CONNECTED && isRetryable(it.lastError) && maySignIn(it.programId, reason) }
        for (account in retry) {
            if (host() == null) return
            DiagnosticLog.i(TAG, "Signing ${account.programId} in again after ${account.lastError?.code}")
            start(account.programId, reason)?.join()
        }
    }

    /** Moves a running sync to a later step; ignored once the sync has finished. */
    fun markStep(programId: String, step: ProgramSyncState) {
        _state.update { if (programId in it) it + (programId to step) else it }
    }

    /** Cancels one program's refresh and any sign-in started from Add program. */
    fun cancel(programId: String) {
        jobsOf(programId).forEach { it.cancel() }
    }

    /** Cancels like [cancel] and waits until the cancelled work has released its WebView, before Remove or Sign out. */
    suspend fun stop(programId: String) {
        jobsOf(programId).forEach { it.cancelAndJoin() }
    }

    private fun jobsOf(programId: String): List<Job> = synchronized(inFlight) {
        listOfNotNull(inFlight.remove(programId), connecting.remove(programId), benefitsJobs.remove(programId))
    }

    /** Cancels every headless job, benefits reads included, when the app leaves the foreground. */
    fun cancelHeadless() {
        val jobs = synchronized(inFlight) {
            (inFlight.values + benefitsJobs.values).toList().also {
                inFlight.clear()
                benefitsJobs.clear()
            }
        }
        jobs.forEach { it.cancel() }
    }

    /** Deletes the program's cards, benefits, and reminder records at Sign out, after [stop]; the account row stays. */
    suspend fun clearCards(programId: String) {
        val repository = cards ?: return
        lock(programId).withLock { repository.deleteProgramCards(programId) }
    }

    /**
     * Starts the program's benefits read as its own job after a saved balance (ADR-0008), unless this is the
     * background keep-alive, the program has no benefits page, or a read for it is already running.
     */
    private fun startBenefits(program: Program, reason: RefreshReason?) {
        if (reason == RefreshReason.KEEP_ALIVE || program.benefits == null || benefitsSource == null || cards == null) return
        synchronized(inFlight) {
            if (benefitsJobs[program.programId]?.isActive == true) return
            val job = scope.launch(start = CoroutineStart.LAZY) { runBenefits(program) }
            benefitsJobs[program.programId] = job
            job.invokeOnCompletion { synchronized(inFlight) { if (benefitsJobs[program.programId] === job) benefitsJobs.remove(program.programId) } }
            job.start()
        }
    }

    private suspend fun runBenefits(program: Program) {
        val source = benefitsSource ?: return
        val read = try {
            permits.withPermit {
                val webViewHost = host() ?: return
                withTimeout(BENEFITS_TIMEOUT_MS + BENEFITS_MARGIN_MS) { source.read(program, webViewHost, BENEFITS_TIMEOUT_MS) }
            }
        } catch (_: TimeoutCancellationException) {
            DiagnosticLog.i(TAG, "Benefits read for ${program.programId} used its whole budget; stored benefits kept")
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticLog.w(TAG, "Benefits read for ${program.programId} failed: ${e.javaClass.simpleName}")
            return
        }
        applyBenefits(program.programId, read)
    }

    /** Writes one benefits read under the program's lock: the card list, then each card that was read. */
    internal suspend fun applyBenefits(programId: String, read: BenefitsRead) {
        val repository = cards ?: return
        lock(programId).withLock {
            try {
                val now = clock()
                val readDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                read.cards?.let { repository.applyCardList(programId, it, now) }
                var applied = 0
                read.benefits.forEach { (digits, drafts) ->
                    if (!CardKey.isValidLastDigits(digits)) return@forEach
                    val key = CardKey(programId, digits)
                    val dated = drafts.map { draft ->
                        BenefitPeriods.parse(draft.periodLabel, readDate)?.let { draft.copy(periodEnd = it.end, periodLength = it.length) } ?: draft
                    }
                    repository.applyCardBenefits(key, assignBenefitKeys(key, dated), now)
                    applied++
                }
                DiagnosticLog.i(
                    TAG,
                    "Benefits for $programId: cards=${read.cards?.cards?.size ?: "none"} complete=${read.cards?.complete} " +
                        "applied=$applied failed=${read.failed.size} stop=${read.stop ?: "none"}",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticLog.w(TAG, "Benefits for $programId not saved: ${e.javaClass.simpleName}, ${read.benefits.size} cards")
            }
        }
    }

    /** The background keep-alive only reuses sessions; a saved password is typed with the app open or at the scheduled refresh. */
    private fun maySignIn(programId: String, reason: RefreshReason) =
        reason != RefreshReason.KEEP_ALIVE && canSignInAutomatically(programId)

    private suspend fun isEligible(account: LoyaltyAccount, reason: RefreshReason, now: Long): Boolean {
        val automatic = maySignIn(account.programId, reason)
        when (account.status) {
            AccountStatus.CONNECTED -> Unit
            AccountStatus.ERROR -> if (account.lastError?.isSignInStop == true && !automatic) return false
            AccountStatus.NEEDS_RECONNECT -> if (!automatic) return false
            AccountStatus.UNSUPPORTED -> return false
        }
        if (catalog.program(account.programId)?.canSync != true) return false
        if (host() == null || (sessions.get(account.programId) == null && !automatic)) return false
        if (reason == RefreshReason.MANUAL) return true
        // Opening MileStone only catches up a scheduled refresh the phone missed: a program updated or tried since the
        // last scheduled time, including one that failed, waits for the next one or the user's Refresh.
        if (reason == RefreshReason.ON_START) {
            val latest = listOfNotNull(account.lastUpdatedEpochMs, account.lastAttemptEpochMs).maxOrNull() ?: return true
            return latest > now || latest < scheduledBefore(now)
        }
        // The scheduled refresh updates balances older than its time and tries every failed program once.
        if (reason == RefreshReason.SCHEDULED) {
            if (account.status == AccountStatus.ERROR) return true
            val updated = account.lastUpdatedEpochMs ?: return true
            return updated > now || updated < scheduledBefore(now)
        }
        val last = account.lastAttemptEpochMs ?: return true
        if (last > now) return true
        return now - last >= waitAfter(account.consecutiveFailures)
    }

    /**
     * Joins a running refresh, except a keep-alive one when this refresh may sign in: the keep-alive never types a
     * password, so the user opening MileStone while it runs replaces it.
     */
    private fun start(programId: String, reason: RefreshReason): Job? = synchronized(inFlight) {
        // A sign-in the user started (Add program, a tapped chip) owns the program until it ends; a refresh or its
        // retry never runs beside it, so one password is never submitted twice at once.
        if (connecting[programId]?.isActive == true) return null
        inFlight[programId]?.takeIf { it.isActive }?.let { running ->
            if (inFlightReason[programId] != RefreshReason.KEEP_ALIVE || !maySignIn(programId, reason)) return running
            running.cancel()
        }
        _state.update { it + (programId to ProgramSyncState.Syncing) }
        val job = scope.launch(start = CoroutineStart.LAZY) { runFetch(programId, reason) }
        inFlight[programId] = job
        inFlightReason[programId] = reason
        job.invokeOnCompletion {
            synchronized(inFlight) {
                val current = inFlight[programId]
                if (current === job) {
                    inFlight.remove(programId)
                    inFlightReason.remove(programId)
                }
                if (current == null || current === job) _state.update { it - programId }
            }
        }
        job.start()
        job
    }

    private suspend fun runFetch(programId: String, reason: RefreshReason) {
        val program = catalog.program(programId) ?: return
        val signIn = maySignIn(programId, reason)
        // A program shown as signed out often still has a live session, so every program with one reads it first
        // beside the others; only a session that has really ended queues for a sign-in. A session whose last read
        // already ended signed out or timed out is not read again when this refresh may sign in: the sign-in reads the
        // balance anyway, and the read would only spend its whole budget on a signed-out page.
        val skipRead = signIn && programId in sessionEnded
        if (skipRead) DiagnosticLog.i(TAG, "Signing $programId in without a session read: its last read did not reach the balance")
        val read = if (sessions.get(programId) != null && !skipRead) {
            permits.withPermit { fetch(program, host() ?: return, FETCH_TIMEOUT_MS, signIn = false) }
        } else {
            null
        }
        when {
            read is SyncResult.Success -> sessionEnded -= programId
            read != null && endsSession(read) -> sessionEnded += programId
        }
        val result = if (signIn && (read == null || needsSignIn(read))) {
            signInPermits.withPermit { fetch(program, host() ?: return, AUTO_SIGN_IN_TIMEOUT_MS, signIn = true) }
        } else {
            read ?: return
        }
        if (result is SyncResult.Success) sessionEnded -= programId
        lock(programId).withLock {
            try {
                val account = accounts.observeAccounts().first().firstOrNull { it.programId == programId }
                val checked = checkPlausible(result, account?.balance, interactive = false)
                if (account != null && isQuietMiss(reason, checked)) {
                    DiagnosticLog.i(TAG, "Keep-alive miss for $programId (${(checked as SyncResult.Failure).reason.code}) left the tile unchanged")
                    return@withLock
                }
                if (checked == SyncResult.Failure(FailureReason.Implausible)) {
                    DiagnosticLog.i(TAG, "Implausible read for $programId kept the saved balance")
                }
                accounts.applySyncResult(programId, checked, clock())
                if (checked is SyncResult.Success) {
                    if (sessions.get(programId) == null) sessions.markConnected(programId, clock()) else sessions.markVerified(programId, clock())
                    startBenefits(program, reason)
                }
                onResult(programId, checked, reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticLog.w(TAG, "Result for $programId not saved: ${e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun fetch(program: Program, webViewHost: WebViewHost, budget: Long, signIn: Boolean): SyncResult = try {
        withTimeout(budget) { source.fetch(program, webViewHost, budget, signIn) }
    } catch (_: TimeoutCancellationException) {
        DiagnosticLog.i(TAG, "Fetch for ${program.programId} used its whole ${budget / 1000} s budget")
        SyncResult.Failure(FailureReason.Timeout)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DiagnosticLog.w(TAG, "Fetch for ${program.programId} failed: ${e.javaClass.simpleName}")
        SyncResult.Failure(FailureReason.Unexpected)
    }

    companion object {
        private const val TAG = "MileStoneSync"
        const val MAX_CONCURRENT_FETCHES = 2
        const val FETCH_TIMEOUT_MS = 30_000L

        /** Room for a sign-in, a texted code, and the dashboard. */
        const val AUTO_SIGN_IN_TIMEOUT_MS = 180_000L
        const val MIN_INTERVAL_MS = 5 * 60_000L
        const val MAX_BACKOFF_MS = 6 * 60 * 60_000L
        const val MANUAL_REPEAT_WINDOW_MS = 10_000L

        /** The whole benefits read: a first page plus one or two pages per card, each up to 30 s. */
        const val BENEFITS_TIMEOUT_MS = 120_000L
        private const val BENEFITS_MARGIN_MS = 5_000L

        /** Failures a second sign-in may fix (null is a plain sign-out); a refused password or a texted code never is. */
        fun isRetryable(reason: FailureReason?): Boolean = when (reason) {
            null, FailureReason.Timeout, FailureReason.SignInUnfinished, FailureReason.NeedsAttention,
            FailureReason.RendererGone, FailureReason.Unexpected, is FailureReason.Network -> true
            else -> false
        }

        /**
         * The keep-alive only keeps sessions fresh. A page that never showed a balance, or showed one still loading, is
         * not news for the tile; a sign-in page or any other failure still is.
         */
        fun isQuietMiss(reason: RefreshReason, result: SyncResult): Boolean =
            reason == RefreshReason.KEEP_ALIVE && result is SyncResult.Failure && isQuietReason(result.reason)

        fun isQuietReason(reason: FailureReason): Boolean = reason == FailureReason.Timeout || reason == FailureReason.Unparseable

        /**
         * A session read that shows the session has ended: a sign-in page, or a balance page that never showed the
         * balance (Delta's signed-out home). A network or renderer failure says nothing about the session.
         */
        fun endsSession(read: SyncResult): Boolean =
            read == SyncResult.NeedsReconnect || read == SyncResult.Failure(FailureReason.Timeout)

        /** A session read that ended on a sign-in page, or that a sign-in may fix, goes on to sign in. */
        fun needsSignIn(read: SyncResult): Boolean =
            read == SyncResult.NeedsReconnect || (read is SyncResult.Failure && isRetryable(read.reason))

        /** How long after the last attempt an automatic refresh may run again. */
        fun waitAfter(consecutiveFailures: Int): Long {
            if (consecutiveFailures <= 0) return MIN_INTERVAL_MS
            val shift = (consecutiveFailures - 1).coerceAtMost(20)
            return (MIN_INTERVAL_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
        }

        /** Headless results that drop to zero or below 10 percent of the stored balance are treated as a bad read. */
        fun checkPlausible(result: SyncResult, storedBalance: Long?, interactive: Boolean): SyncResult {
            if (interactive || result !is SyncResult.Success || storedBalance == null || storedBalance <= 0) return result
            val implausible = result.balance == 0L || result.balance * 10 < storedBalance
            return if (implausible) SyncResult.Failure(FailureReason.Implausible) else result
        }
    }
}
