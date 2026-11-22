package com.bendourthe.milestone.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry

/** The page being left fades out first, then the new page fades in, so the two never show at the same time. */
private const val OUT_MS = 140
private const val IN_MS = 220

/** How far a page drifts while it fades, as a fraction of the screen width. */
private const val DRIFT = 0.12f

/**
 * Page changes as a horizontal fade in the swipe's direction. Going back (a swipe to the right), the page being left
 * fades out drifting right and the earlier page fades in from the left; going forward, the reverse. A tab switch
 * fades without drifting. A back swipe seeks this same transition with the finger.
 */
internal class PageMotion(private val isTabRoot: (NavBackStackEntry) -> Boolean) {
    private fun isTabSwitch(scope: AnimatedContentTransitionScope<NavBackStackEntry>) =
        isTabRoot(scope.initialState) && isTabRoot(scope.targetState)

    fun enter(scope: AnimatedContentTransitionScope<NavBackStackEntry>, back: Boolean): EnterTransition {
        val fade = fadeIn(tween(IN_MS, delayMillis = OUT_MS, easing = LinearOutSlowInEasing))
        if (isTabSwitch(scope)) return fade
        return fade + slideInHorizontally(tween(IN_MS, delayMillis = OUT_MS, easing = FastOutSlowInEasing)) { width ->
            val drift = (width * DRIFT).toInt()
            if (back) -drift else drift
        }
    }

    fun exit(scope: AnimatedContentTransitionScope<NavBackStackEntry>, back: Boolean): ExitTransition {
        val fade = fadeOut(tween(OUT_MS, easing = FastOutSlowInEasing))
        if (isTabSwitch(scope)) return fade
        return fade + slideOutHorizontally(tween(OUT_MS, easing = FastOutSlowInEasing)) { width ->
            val drift = (width * DRIFT).toInt()
            if (back) drift else -drift
        }
    }
}
