package com.bendourthe.milestone.sync.webview

import com.bendourthe.milestone.domain.ExtractionSpec
import com.bendourthe.milestone.domain.FailureReason
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.domain.SyncCapability
import com.bendourthe.milestone.sync.extract.BridgeEvent
import com.bendourthe.milestone.sync.extract.BridgeMessage
import com.bendourthe.milestone.sync.extract.BridgeProtocol
import com.bendourthe.milestone.sync.extract.PageSignals
import com.bendourthe.milestone.sync.extract.PageState
import com.bendourthe.milestone.sync.extract.PageStateClassifier
import com.bendourthe.milestone.sync.extract.SignInStop
import java.security.SecureRandom

sealed interface BridgeAction {
    data class Reply(val config: String) : BridgeAction
    data class Emit(val event: BridgeEvent) : BridgeAction
    data object NavigateToDashboard : BridgeAction

    /** Open the program's sign-in page, for a balance page that stays signed out without offering a form. */
    data object NavigateToLogin : BridgeAction

    /** Look up the saved sign-in and answer with [BridgeSession.fillCommand]. */
    data object FillCredentials : BridgeAction

    /** Wait for a texted code, then answer with [BridgeSession.codeCommand] or call [BridgeSession.codeUnavailable]. */
    data object AwaitCode : BridgeAction
}

/** What MileStone may do on the user's behalf in one session. */
data class AutoSignInPlan(val canFill: Boolean, val readTexts: Boolean) {
    companion object {
        val NONE = AutoSignInPlan(canFill = false, readTexts = false)
    }
}

/**
 * The decisions of one bridge page session, kept free of WebView types. Interactive sessions wait
 * while the user logs in; [headless] sessions end at the first login page or unrecognized page, unless
 * [autoSignIn] lets MileStone submit the saved sign-in once and enter one texted code.
 */
class BridgeSession(
    private val program: Program,
    val headless: Boolean = false,
    private val scriptTimeoutMs: Long = BridgeProtocol.DEFAULT_TIMEOUT_MS,
    private val autoSignIn: AutoSignInPlan = AutoSignInPlan.NONE,
    startAtLogin: Boolean = false,
    private val newNonce: () -> String = ::randomNonce,
) {
    /**
     * A hidden sign-in opens the sign-in page directly instead of first waiting on a balance page that, signed out,
     * may show neither a balance nor a form (Delta's home page).
     */
    private val startsAtLogin = startAtLogin && headless && autoSignIn.canFill && program.loginUrl != program.dashboardUrl

    /** The first page this session loads. */
    val firstUrl: String get() = if (!headless || startsAtLogin) program.loginUrl else program.dashboardUrl

    /** True once MileStone submitted any part of the saved sign-in in this session. */
    var signInSubmitted = false
        private set

    /** True once a submitted page carried the password; before that, a username-only step may be answered once more. */
    private var passwordSubmitted = false
    private var fills = 0

    /** Which sign-in fields each page filled, such as "username,lastName then password", for the stop log; never their values. */
    var filledFields = "none"
        private set
    private var codeRequested = false
    private var awaitingCode = false
    private val readsCodes get() = autoSignIn.readTexts && program.textKeywords.isNotEmpty()
    private val spec = (program.syncCapability as? SyncCapability.Automatic)?.spec as? ExtractionSpec.Selector

    /** Exact `https://host` origins the script and listener are limited to. */
    val originRules: Set<String> = program.allowedHosts.map { "https://$it" }.toSet()

    private val navigableHosts = program.allowedHosts + program.identityProviderHosts
    private var nonce: String? = null
    private var currentUrl: String? = null
    private var loginSeenOnPage = false
    private var redirectedToDashboard = false
    private var openedLogin = startsAtLogin
    private var extraWaits = 0

    var finished = false
        private set

    /** For host-only diagnostics: how many pages ran the script, and what the last timeout saw. */
    var readyCount = 0
        private set
    var lastTimeout: String? = null
        private set

    val isSupported: Boolean get() = spec != null

    fun onNavigated(url: String) {
        currentUrl = url
    }

    fun staysInWebView(url: String): Boolean = HostPolicy.isAllowed(url, navigableHosts)

    /** The copied text as a link this session may open, such as an emailed sign-in link, or null. */
    fun pastedLink(text: CharSequence?): String? =
        text?.toString()?.trim()?.takeIf { !headless && !finished && staysInWebView(it) }

    fun onMessage(data: String?, sourceOrigin: String, isMainFrame: Boolean): BridgeAction? {
        if (finished || spec == null || !isMainFrame || !HostPolicy.isAllowed(sourceOrigin, program.allowedHosts)) return null
        return when (val message = BridgeProtocol.parse(data, nonce, program.balanceUnit)) {
            null -> null
            BridgeMessage.Ready -> {
                readyCount++
                loginSeenOnPage = false
                arm(spec)
            }
            is BridgeMessage.Event -> onEvent(message.event)
            is BridgeMessage.Timeout -> if (awaitingCode) arm(spec) else onTimeout(spec, message.awaitingInput)
            BridgeMessage.CodeRequested -> onCodeRequested()
            BridgeMessage.Refused ->
                if (headless && passwordSubmitted) finish(BridgeEvent.AutoSignInStopped(SignInStop.REFUSED)) else onEvent(BridgeEvent.LoginRequired)
            BridgeMessage.PressedAgain -> {
                filledFields = "$filledFields then pressed again"
                null
            }
            is BridgeMessage.Filled -> {
                message.fields?.let { filledFields = if (filledFields == "none") it else "$filledFields then $it" }
                if (message.submitted && message.fields.orEmpty().split(',').contains("password")) passwordSubmitted = true
                if (!message.submitted && headless) finish(BridgeEvent.AutoSignInStopped(SignInStop.NOT_FILLED)) else null
            }
        }
    }

    /** The fill command for the current page; null once the session ended. */
    fun fillCommand(username: String, password: String, lastName: String = ""): String? =
        nonce?.takeIf { !finished }?.let { BridgeProtocol.fillCommand(it, username, password, lastName) }

    /** The saved sign-in vanished between the decision and the lookup. */
    fun credentialsUnavailable(): BridgeAction? = if (headless) finish(BridgeEvent.LoginRequired) else null

    fun codeCommand(code: String): String? {
        awaitingCode = false
        return nonce?.takeIf { !finished }?.let { BridgeProtocol.codeCommand(it, code) }
    }

    /** No matching text arrived in time; the user must finish this sign-in. */
    fun codeUnavailable(): BridgeAction? {
        awaitingCode = false
        return if (headless) finish(BridgeEvent.AutoSignInStopped(SignInStop.NO_CODE)) else null
    }

    private fun onCodeRequested(): BridgeAction? = when {
        readsCodes && !codeRequested && (signInSubmitted || !headless) -> {
            codeRequested = true
            awaitingCode = true
            BridgeAction.AwaitCode
        }
        headless -> finish(if (signInSubmitted) BridgeEvent.AutoSignInStopped(SignInStop.NO_CODE) else BridgeEvent.LoginRequired)
        else -> null
    }

    private fun arm(spec: ExtractionSpec.Selector): BridgeAction {
        val fresh = newNonce().also { nonce = it }
        return BridgeAction.Reply(
            BridgeProtocol.config(
                spec.strategy, spec.expression, fresh, scriptTimeoutMs,
                watchCodes = readsCodes,
                afterSignIn = headless && passwordSubmitted,
                openSignIn = headless && autoSignIn.canFill && !passwordSubmitted,
                reveal = spec.revealSelector,
            ),
        )
    }

    /** A page-level failure reported by the WebView client; only the first terminal event counts. */
    fun fail(reason: FailureReason): BridgeAction? = finish(BridgeEvent.Failed(reason))

    fun isDashboard(url: String): Boolean =
        PageStateClassifier.classify(url, PageSignals(hasVisiblePasswordField = false), program) == PageState.DASHBOARD

    private fun onEvent(event: BridgeEvent): BridgeAction? = when (event) {
        BridgeEvent.LoginRequired -> {
            loginSeenOnPage = true
            when {
                autoSignIn.canFill && !passwordSubmitted && fills < MAX_FILLS -> {
                    signInSubmitted = true
                    fills++
                    BridgeAction.FillCredentials
                }
                headless -> finish(if (signInSubmitted) BridgeEvent.AutoSignInStopped(SignInStop.UNFINISHED) else event)
                else -> BridgeAction.Emit(event)
            }
        }
        else -> finish(event)
    }

    /**
     * An interactive session never closes on a timeout: sign-in steps such as a texted code can share the dashboard
     * URL and sit where the script cannot see them, so it keeps watching until a balance, a sign-in failure, or Close.
     */
    private fun onTimeout(spec: ExtractionSpec.Selector, awaitingInput: Boolean): BridgeAction? {
        if (!headless && awaitingInput) return arm(spec)
        val url = currentUrl ?: return if (headless) finish(BridgeEvent.Failed(FailureReason.NeedsAttention)) else arm(spec)
        val state = PageStateClassifier.classify(url, PageSignals(loginSeenOnPage), program)
        lastTimeout = "state=$state awaitingInput=$awaitingInput loginSeen=$loginSeenOnPage"
        if (headless) {
            // A home page used as the balance page (Delta) shows neither a balance nor a form while signed out.
            if (state == PageState.DASHBOARD && autoSignIn.canFill && !signInSubmitted && !openedLogin && program.loginUrl != program.dashboardUrl) {
                openedLogin = true
                return BridgeAction.NavigateToLogin
            }
            // Started at the sign-in page but it showed no form and nothing was typed: the session may still be alive
            // on a sign-in address that does not move on, so the balance page gets one look.
            if (startsAtLogin && !signInSubmitted && !loginSeenOnPage && !redirectedToDashboard) {
                redirectedToDashboard = true
                return BridgeAction.NavigateToDashboard
            }
            // A page after the password that asks for nothing and is not the balance page (Delta's landing page) is
            // usually a signed-in page that does not show the balance, so the balance page opens once.
            if (passwordSubmitted && !awaitingInput && state == PageState.OTHER && !redirectedToDashboard && HostPolicy.isAllowed(url, program.allowedHosts)) {
                redirectedToDashboard = true
                return BridgeAction.NavigateToDashboard
            }
            // After the saved sign-in went in, a page that is still loading and asks for nothing gets more time, as the
            // visible browser does; the sync's own budget still bounds the whole sign-in.
            if (signInSubmitted && !awaitingInput && state != PageState.LOGIN && extraWaits < MAX_EXTRA_WAITS) {
                extraWaits++
                return arm(spec)
            }
            return finish(
                when {
                    signInSubmitted && state != PageState.DASHBOARD -> BridgeEvent.AutoSignInStopped(SignInStop.UNFINISHED)
                    state == PageState.LOGIN -> BridgeEvent.LoginRequired
                    state == PageState.DASHBOARD -> BridgeEvent.Failed(FailureReason.Timeout)
                    else -> BridgeEvent.Failed(FailureReason.NeedsAttention)
                },
            )
        }
        return if (state == PageState.OTHER && !redirectedToDashboard && HostPolicy.isAllowed(url, program.allowedHosts)) {
            redirectedToDashboard = true
            BridgeAction.NavigateToDashboard
        } else {
            arm(spec)
        }
    }

    private fun finish(event: BridgeEvent): BridgeAction? {
        if (finished) return null
        finished = true
        return BridgeAction.Emit(event)
    }

    private companion object {
        /** A username-only step, then the password step. */
        const val MAX_FILLS = 2
        const val MAX_EXTRA_WAITS = 3
        val random = SecureRandom()

        fun randomNonce(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
