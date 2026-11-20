package com.bendourthe.milestone.ui

import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.bendourthe.milestone.ui.theme.AuroraBackground
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bendourthe.milestone.BuildConfig
import com.bendourthe.milestone.MileStoneApplication
import com.bendourthe.milestone.sync.ForegroundSyncObserver
import com.bendourthe.milestone.sync.RefreshReason
import com.bendourthe.milestone.sync.background.Notifications
import com.bendourthe.milestone.sync.webview.ActivityWebViewHost
import com.bendourthe.milestone.sync.webview.AutoSignInSupport
import com.bendourthe.milestone.sync.webview.LoginBridge
import com.bendourthe.milestone.ui.auth.SignInViewModel
import com.bendourthe.milestone.ui.benefits.ConnectionsViewModel
import com.bendourthe.milestone.ui.catalog.CatalogViewModel
import com.bendourthe.milestone.ui.dashboard.DashboardViewModel
import com.bendourthe.milestone.ui.settings.SettingsViewModel
import com.bendourthe.milestone.ui.theme.MileStoneTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private lateinit var factory: ViewModelProvider.Factory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MileStoneApplication).container
        if (savedInstanceState == null) lifecycleScope.launch { container.sessionProfiles.deleteLeftoverProfiles(container.connectionSessions::additionalProfiles) }

        lifecycle.addObserver(
            ForegroundSyncObserver(
                host = ActivityWebViewHost(this),
                setHost = { container.webViewHost = it },
                returns = container.returns,
                refreshAll = { fresh -> container.scope.launch { container.refreshOnOpen(summarize = fresh) } },
                cancelHeadless = { container.syncManager.cancelHeadless() },
            ),
        )

        factory = viewModelFactory {
            initializer {
                DashboardViewModel(
                    container.catalog,
                    container.observeDashboard,
                    container.settings,
                    container.accounts,
                    container.syncManager.state,
                    signOut = { container.signOut(it) },
                    signOutAll = { container.signOutAll(it) },
                    requestRefreshAll = {
                        container.scope.launch {
                            container.vault.statuses.value.filterValues { it.blocked }.keys.forEach { container.vault.unblock(it) }
                            container.syncManager.refreshAll(RefreshReason.MANUAL)
                        }
                    },
                    openRefreshes = container.openRefreshes,
                    // Tapping Refresh is the user asking to try again, so it also lifts a stopped saved sign-in.
                    requestRefresh = { programId ->
                        container.scope.launch {
                            container.vault.unblock(programId)
                            container.syncManager.refresh(programId, RefreshReason.MANUAL)
                        }
                    },
                    savedSignIns = container.vault.statuses.map { statuses -> statuses.filterValues { it.saved }.keys },
                )
            }
            initializer { CatalogViewModel(container.catalog, container.accounts) }
            initializer { SettingsViewModel(container.settings) }
            initializer {
                SignInViewModel(
                    container.catalog,
                    applyInteractive = container.syncManager::applyInteractive,
                    signOutProgram = { container.signOut(it) },
                    sharedSession = !container.profiles.supportsProfiles,
                    autoSignInStatuses = container.vault.statuses,
                    saveSignIn = { id, credentials, readTexts -> container.vault.save(id, credentials, readTexts) },
                    allowAutoSignIn = { container.vault.unblock(it) },
                    connectHidden = { container.syncManager.connect(it) },
                    showSite = BuildConfig.BUILD_TYPE == "capture",
                    connectionSignedIn = { connectionId ->
                        container.sessions.markConnected(connectionId, System.currentTimeMillis())
                        container.connections.connection(connectionId)?.let { container.syncManager.refresh(it.programId, RefreshReason.MANUAL) }
                    },
                )
            }
            initializer { ConnectionsViewModel(container.connections, container.connectionSessions, container.sessions) }
        }

        val coldStart = savedInstanceState == null
        setContent {
            MileStoneTheme {
                var opening by rememberSaveable { mutableStateOf(coldStart) }
                LaunchedEffect(Unit) {
                    delay(OPENING_MS)
                    opening = false
                }
                val signInViewModel = viewModel<SignInViewModel>(factory = factory)
                AuroraBackground(Modifier.fillMaxSize()) {
                    MileStoneApp(
                        dashboardViewModel = viewModel<DashboardViewModel>(factory = factory),
                        catalogViewModel = viewModel<CatalogViewModel>(factory = factory),
                        settingsViewModel = viewModel<SettingsViewModel>(factory = factory),
                        catalog = container.catalog,
                        versionName = BuildConfig.VERSION_NAME,
                        signIn = signInViewModel,
                        createBridge = { context, program ->
                            // An additional sign-in uses its own profile and never the program's saved password.
                            val connection = signInViewModel.activeConnection.value
                            LoginBridge(
                                WebView(context), program, container.profiles,
                                autoSignIn = if (connection == null) container.autoSignIn(program) else AutoSignInSupport.NONE,
                                sessionKey = connection ?: program.programId,
                            )
                        },
                        connections = viewModel<ConnectionsViewModel>(factory = factory),
                    )
                }
                AnimatedVisibility(opening, enter = EnterTransition.None, exit = fadeOut(tween(450))) { OpeningScreen() }
            }
        }
        openRequestedSignIn(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequestedSignIn(intent)
    }

    /**
     * A "needs you" notification opens that program's sign-in. This activity is exported as the launcher, so any app can
     * send this intent: it is honored only with the notification's action, and it never lifts a paused sign-in.
     */
    private fun openRequestedSignIn(intent: Intent?) {
        if (intent?.action != Notifications.ACTION_SIGN_IN) return
        val programId = intent.getStringExtra(EXTRA_PROGRAM_ID) ?: return
        intent.removeExtra(EXTRA_PROGRAM_ID)
        ViewModelProvider(this, factory)[SignInViewModel::class.java].connect(programId, liftPause = false)
    }

    companion object {
        const val EXTRA_PROGRAM_ID = "com.bendourthe.milestone.extra.PROGRAM_ID"
        /** Room for the mark to build (1.4 s) and the name to finish typing (about 1.6 s) and stay readable before the fade. */
        private const val OPENING_MS = 2_000L
    }
}
