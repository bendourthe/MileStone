package com.bendourthe.milestone.sync

import android.webkit.WebView
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.domain.SyncResult

/**
 * Lends an unused WebView attached to a visible window (a detached WebView may throttle timers and
 * never render), so the sync package never holds an activity. The block runs on the main thread;
 * the WebView is removed and destroyed when it returns or is cancelled.
 */
interface WebViewHost {
    suspend fun <T> withAttachedWebView(block: suspend (WebView) -> T): T
}

fun interface BalanceSource {
    /** Reads the balance; only with [signIn] may it type the saved sign-in when the session has ended. */
    suspend fun fetch(program: Program, host: WebViewHost, budgetMs: Long, signIn: Boolean): SyncResult
}

enum class RefreshReason { ON_START, MANUAL, KEEP_ALIVE, SCHEDULED }
