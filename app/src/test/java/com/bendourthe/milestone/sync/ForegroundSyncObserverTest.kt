package com.bendourthe.milestone.sync

import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ForegroundSyncObserverTest {
    private object Host : WebViewHost {
        override suspend fun <T> withAttachedWebView(block: suspend (WebView) -> T): T = error("unused")
    }

    private val calls = mutableListOf<String>()
    private var now = 0L
    private val returns = ReturnTracker(clock = { now })

    private fun observer(refreshAll: (Boolean) -> Unit = { calls += if (it) "refreshFresh" else "refresh" }) = ForegroundSyncObserver(
        host = Host,
        setHost = { calls += if (it == null) "clearHost" else "setHost" },
        returns = returns,
        refreshAll = refreshAll,
        cancelHeadless = { calls += "cancel" },
    )

    private fun drive(observer: ForegroundSyncObserver, vararg steps: Any) {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.addObserver(observer)
        steps.forEach { if (it is Lifecycle.State) owner.registry.currentState = it else now += it as Long }
    }

    @Test
    fun returningAfterFifteenMinutesCountsAsAFreshOpen() {
        drive(observer(), Lifecycle.State.STARTED, Lifecycle.State.CREATED, ReturnTracker.AWAY_MS, Lifecycle.State.STARTED)
        assertEquals(listOf("setHost", "refreshFresh", "cancel", "clearHost", "setHost", "refreshFresh"), calls)
    }

    @Test
    fun returningSoonerStillRefreshesWithoutASummary() {
        drive(observer(), Lifecycle.State.STARTED, Lifecycle.State.CREATED, ReturnTracker.AWAY_MS - 1, Lifecycle.State.STARTED)
        assertEquals(listOf("setHost", "refreshFresh", "cancel", "clearHost", "setHost", "refresh"), calls)
    }

    @Test
    fun refreshFailureIsSwallowedSoTheAppStillOpens() {
        drive(observer(refreshAll = { error("boom") }), Lifecycle.State.STARTED)
        assertEquals(listOf("setHost"), calls)
    }
}
