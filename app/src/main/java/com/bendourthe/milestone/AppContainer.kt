package com.bendourthe.milestone

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.bendourthe.milestone.data.RoomAccountRepository
import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.data.db.MileStoneDatabase
import com.bendourthe.milestone.data.session.SessionStore
import com.bendourthe.milestone.data.session.TinkSessionStore
import com.bendourthe.milestone.data.settings.DataStoreSettingsRepository
import com.bendourthe.milestone.domain.AccountRepository
import com.bendourthe.milestone.domain.AccountStatus
import com.bendourthe.milestone.domain.CatalogRepository
import com.bendourthe.milestone.domain.SettingsRepository
import com.bendourthe.milestone.domain.usecase.ObserveDashboard
import com.bendourthe.milestone.sync.SyncManager
import com.bendourthe.milestone.sync.WebViewHost
import com.bendourthe.milestone.sync.webview.HeadlessWebViewBalanceSource
import com.bendourthe.milestone.sync.webview.SessionProfiles
import com.bendourthe.milestone.sync.webview.WebViewProfileBackend
import com.bendourthe.milestone.data.credentials.FileCredentialVault
import com.bendourthe.milestone.data.credentials.KeystoreSecretBox
import com.bendourthe.milestone.domain.FailureReason
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.domain.SyncResult
import com.bendourthe.milestone.sync.RefreshReason
import com.bendourthe.milestone.sync.ReturnTracker
import com.bendourthe.milestone.sync.extract.SignInStop
import com.bendourthe.milestone.sync.background.Notifications
import com.bendourthe.milestone.sync.background.ScheduledRefreshScheduler
import com.bendourthe.milestone.sync.mfa.OneTimeCodeInbox
import com.bendourthe.milestone.sync.webview.AutoSignInSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File

private val Context.settingsStore by preferencesDataStore(DataStoreSettingsRepository.STORE_NAME)

/** Builds the app's data and sync objects once; created in [MileStoneApplication.onCreate]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: MileStoneDatabase by lazy { MileStoneDatabase.create(appContext) }
    val catalog: CatalogRepository by lazy { StaticCatalogRepository() }
    val accounts: AccountRepository by lazy { RoomAccountRepository(database) }
    val settings: SettingsRepository by lazy { DataStoreSettingsRepository(appContext.settingsStore) }
    val observeDashboard: ObserveDashboard by lazy { ObserveDashboard(accounts, catalog) }

    val profiles = WebViewProfileBackend()

    val sessions: SessionStore by lazy {
        val file = File(appContext.filesDir, TinkSessionStore.FILE_NAME)
        val aead = TinkSessionStore.keystoreAead(appContext, file) { scope.launch { reconnectConnected() } }
        TinkSessionStore(file, aead, scope) { reconnectConnected() }
    }

    val sessionProfiles: SessionProfiles by lazy { SessionProfiles(profiles, sessions, accounts) }

    val vault: FileCredentialVault by lazy { FileCredentialVault(File(appContext.noBackupFilesDir, FileCredentialVault.DIRECTORY), KeystoreSecretBox()) }
    private val vaultLoad = scope.async(start = CoroutineStart.LAZY) { vault.load() }

    /** Waits until saved sign-in statuses are read, so a refresh knows which programs can sign themselves in. */
    suspend fun vaultReady() = vaultLoad.await()

    val codeInbox = OneTimeCodeInbox()

    /** Set by the visible activity in `onStart` and cleared in `onStop`. */
    @Volatile
    var webViewHost: WebViewHost? = null

    /** Set only while the background refresh service runs. */
    @Volatile
    var backgroundHost: WebViewHost? = null

    /** Set only while the scheduled refresh runs in the background, the one background run that may sign in. */
    @Volatile
    var scheduledRun = false

    /** A saved password is used while MileStone is on screen or at the scheduled refresh, never by the keep-alive. */
    fun autoSignIn(program: Program): AutoSignInSupport =
        if (webViewHost == null && !scheduledRun) {
            AutoSignInSupport.NONE
        } else {
            AutoSignInSupport.forProgram(program, vault, codeInbox).reporting { step -> syncManager.markStep(program.programId, step) }
        }

    private fun canSignInAutomatically(programId: String): Boolean =
        vault.statuses.value[programId]?.let { it.saved && !it.blocked } == true

    /**
     * A refused password stops automatic sign-in at once, so a changed password cannot lock the account. A sign-in
     * that stopped on an unknown page a third time in a row stops too, after the open refresh's retry and one more
     * open; a missing texted code or form sends no wrong password.
     */
    private suspend fun onSignInStopped(programId: String, why: SignInStop) {
        val previous = accounts.observeAccounts().first().firstOrNull { it.programId == programId }
        val thirdUnfinished = previous?.lastError == FailureReason.SignInUnfinished && previous.consecutiveFailures >= 2
        if (why == SignInStop.REFUSED || (why == SignInStop.UNFINISHED && thirdUnfinished)) vault.block(programId)
    }

    val returns = ReturnTracker()

    private val openRefreshDone = Channel<Set<String>>(Channel.CONFLATED)

    /**
     * The programs refreshed when MileStone was opened afresh, once that refresh has finished. Nothing is sent when it
     * refreshed nothing, so an older error on a tile is never announced as news.
     */
    val openRefreshes: Flow<Set<String>> = openRefreshDone.receiveAsFlow()

    suspend fun refreshOnOpen(summarize: Boolean) {
        vaultReady()
        var refreshed = emptyList<String>()
        syncManager.refreshAll(RefreshReason.ON_START) { refreshed = it }.join()
        if (summarize && refreshed.isNotEmpty()) openRefreshDone.send(refreshed.toSet())
    }

    val syncManager: SyncManager by lazy {
        SyncManager(
            accounts, sessions, catalog,
            HeadlessWebViewBalanceSource(profiles, autoSignIn = ::autoSignIn, onAutoSignInStopped = ::onSignInStopped),
            scope,
            host = { webViewHost ?: backgroundHost },
            canSignInAutomatically = ::canSignInAutomatically,
            onResult = ::onSyncResult,
            scheduledBefore = { now -> settings.refreshInterval.first().lastAt(now) },
        )
    }

    private fun onSyncResult(programId: String, result: SyncResult, reason: RefreshReason?) {
        when (result) {
            is SyncResult.Success -> {
                Notifications.clearNeedsYou(appContext, programId)
                if (reason == null) scope.launch { vault.unblock(programId) }
            }
            SyncResult.NeedsReconnect -> if (reason == RefreshReason.KEEP_ALIVE && !canSignInAutomatically(programId)) {
                catalog.program(programId)?.let { Notifications.needsYou(appContext, it) }
            }
            else -> Unit
        }
    }

    /** Signing out also forgets the saved sign-in. */
    suspend fun signOut(programId: String) {
        syncManager.stop(programId)
        sessionProfiles.signOut(programId)
        vault.delete(programId)
    }

    init {
        vaultLoad.start()
        // Also runs at every process start, which includes the one after a restart clears the alarm.
        scope.launch { settings.refreshInterval.collect { ScheduledRefreshScheduler.schedule(appContext, it) } }
    }

    /** A lost session store means every connected program must sign in again. */
    private suspend fun reconnectConnected() {
        accounts.observeAccounts().first()
            .filter { it.status == AccountStatus.CONNECTED }
            .forEach { accounts.markNeedsReconnect(it.programId) }
    }
}
