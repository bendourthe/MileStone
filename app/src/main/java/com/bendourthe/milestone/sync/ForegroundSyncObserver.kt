package com.bendourthe.milestone.sync

import android.os.SystemClock
import com.bendourthe.milestone.DiagnosticLog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/** Decides when coming back to MileStone counts as opening it afresh; lives as long as the process. */
class ReturnTracker(private val clock: () -> Long = SystemClock::elapsedRealtime, private val awayMs: Long = AWAY_MS) {
    private var leftAt: Long? = null

    fun left() {
        leftAt = clock()
    }

    /** True on the first start of the process and after at least [awayMs] away from the screen. */
    fun isFreshOpen(): Boolean = leftAt?.let { clock() - it >= awayMs } ?: true

    companion object {
        const val AWAY_MS = 15 * 60_000L
    }
}

/** Refreshes each time the app comes on screen and stops headless work when it leaves the screen. */
class ForegroundSyncObserver(
    private val host: WebViewHost,
    private val setHost: (WebViewHost?) -> Unit,
    private val returns: ReturnTracker,
    /** [freshOpen] asks for a summary of the programs that could not reconnect. */
    private val refreshAll: (freshOpen: Boolean) -> Unit,
    private val cancelHeadless: () -> Unit,
) : LifecycleEventObserver {
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> {
                setHost(host)
                try {
                    refreshAll(returns.isFreshOpen())
                } catch (e: Exception) {
                    DiagnosticLog.w(TAG, "Foreground refresh did not start: ${e.javaClass.simpleName}")
                }
            }
            Lifecycle.Event.ON_STOP -> {
                returns.left()
                cancelHeadless()
                setHost(null)
            }
            else -> Unit
        }
    }

    private companion object {
        const val TAG = "MileStoneSync"
    }
}
