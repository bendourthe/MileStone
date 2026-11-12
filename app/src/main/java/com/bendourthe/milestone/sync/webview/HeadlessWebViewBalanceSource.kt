package com.bendourthe.milestone.sync.webview

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.bendourthe.milestone.domain.FailureReason
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.domain.SyncResult
import com.bendourthe.milestone.sync.BalanceSource
import com.bendourthe.milestone.sync.WebViewHost
import com.bendourthe.milestone.sync.extract.BridgeEvent
import com.bendourthe.milestone.sync.extract.SignInStop
import kotlinx.coroutines.flow.first

/**
 * Refreshes a connected program in a hidden WebView that reuses the program's session profile. With a saved
 * sign-in it signs in again by itself once; [onAutoSignInStopped] runs when that attempt was refused or unfinished.
 */
class HeadlessWebViewBalanceSource(
    private val profiles: WebViewProfileBackend,
    private val interceptor: ((WebResourceRequest) -> WebResourceResponse?)? = null,
    private val autoSignIn: (Program) -> AutoSignInSupport = { AutoSignInSupport.NONE },
    private val onAutoSignInStopped: suspend (programId: String, why: SignInStop) -> Unit = { _, _ -> },
) : BalanceSource {
    override suspend fun fetch(program: Program, host: WebViewHost, budgetMs: Long, signIn: Boolean): SyncResult {
        if (!program.canSync) return SyncResult.Unsupported
        val support = if (signIn) autoSignIn(program) else AutoSignInSupport.NONE
        val event = host.withAttachedWebView { webView ->
            val bridge = LoginBridge(
                webView, program, profiles, interceptor, headless = true,
                scriptTimeoutMs = scriptTimeout(budgetMs), autoSignIn = support, startAtLogin = signIn,
            )
            try {
                bridge.start()
                bridge.events.first()
            } finally {
                bridge.destroy()
            }
        }
        if (event is BridgeEvent.AutoSignInStopped) onAutoSignInStopped(program.programId, event.why)
        return toSyncResult(event)
    }

    companion object {
        private const val SCRIPT_MARGIN_MS = 2_000L

        /** How long one page may take to show the balance; a longer budget only allows more pages. */
        const val PAGE_BUDGET_MS = 30_000L

        fun scriptTimeout(budgetMs: Long): Long = (minOf(budgetMs, PAGE_BUDGET_MS) - SCRIPT_MARGIN_MS).coerceAtLeast(1_000)

        fun toSyncResult(event: BridgeEvent): SyncResult = when (event) {
            is BridgeEvent.Extracted -> SyncResult.Success(event.balance)
            BridgeEvent.LoginRequired -> SyncResult.NeedsReconnect
            is BridgeEvent.AutoSignInStopped -> SyncResult.Failure(
                when (event.why) {
                    SignInStop.REFUSED -> FailureReason.PasswordRefused
                    SignInStop.NO_CODE -> FailureReason.CodeMissing
                    SignInStop.NOT_FILLED, SignInStop.UNFINISHED -> FailureReason.SignInUnfinished
                },
            )
            is BridgeEvent.Failed -> SyncResult.Failure(event.reason)
            BridgeEvent.Unsupported -> SyncResult.Unsupported
        }
    }
}
