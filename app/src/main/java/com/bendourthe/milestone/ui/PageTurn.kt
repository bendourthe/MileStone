package com.bendourthe.milestone.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.navigation.NavBackStackEntry

/** How long a page turn takes when it is not following a finger. */
internal const val PAGE_TURN_MS = 420

/** A tab switch is a quick crossfade rather than a page turn. */
private const val TAB_FADE_MS = 200

/**
 * The kind of page change running now, set by the NavHost transition specs and read while pages draw. Plain fields,
 * not Compose state: they change only when a transition starts, and the pages redraw every frame while it runs.
 */
internal class PageTurn(private val isTabRoot: (NavBackStackEntry) -> Boolean) {
    @Volatile var back: Boolean = false
        private set

    /** False for a switch between two tabs, which crossfades instead. */
    @Volatile var turning: Boolean = false
        private set

    private fun start(scope: AnimatedContentTransitionScope<NavBackStackEntry>, back: Boolean) {
        this.back = back
        turning = !(isTabRoot(scope.initialState) && isTabRoot(scope.targetState))
    }

    // The fades below only give the transition its length (the page stays fully opaque); the turn itself is drawn by
    // [pageTurnLayer]. Linear timing lets a back swipe seek the transition in step with the finger.
    fun enter(scope: AnimatedContentTransitionScope<NavBackStackEntry>, back: Boolean): EnterTransition {
        start(scope, back)
        return if (turning) fadeIn(tween(PAGE_TURN_MS, easing = LinearEasing), initialAlpha = 0.999f) else fadeIn(tween(TAB_FADE_MS))
    }

    fun exit(scope: AnimatedContentTransitionScope<NavBackStackEntry>, back: Boolean): ExitTransition {
        start(scope, back)
        return if (turning) fadeOut(tween(PAGE_TURN_MS, easing = LinearEasing), targetAlpha = 0.999f) else fadeOut(tween(TAB_FADE_MS))
    }
}

internal val LocalPageTurn = staticCompositionLocalOf<PageTurn?> { null }

/**
 * Draws one page of a page turn. Pages hinge on their right edge, like a book's spine: the page on top swings
 * between flat and edge-on (going back, the page being left swings away and uncovers the one under it; going forward,
 * the new page swings flat over the current one), and the page underneath is shaded while it is covered.
 */
@Composable
internal fun AnimatedVisibilityScope.pageTurnLayer(turn: PageTurn?): Modifier {
    if (turn == null) return Modifier
    // 0 while the page is shown, 1 when it is fully turned away or not yet turned in.
    val away by transition.animateFloat(transitionSpec = { tween(PAGE_TURN_MS, easing = FastOutSlowInEasing) }, label = "pageTurn") { state ->
        if (state == EnterExitState.Visible) 0f else 1f
    }
    return Modifier
        .graphicsLayer {
            val entering = transition.targetState == EnterExitState.Visible
            val onTop = if (turn.back) !entering else entering
            if (turn.turning && onTop && away > 0f) {
                transformOrigin = TransformOrigin(1f, 0.5f)
                rotationY = 90f * away
                cameraDistance = 8f * density
            } else {
                rotationY = 0f
            }
        }
        .drawWithContent {
            drawContent()
            if (!turn.turning || away <= 0f) return@drawWithContent
            val entering = transition.targetState == EnterExitState.Visible
            val onTop = if (turn.back) !entering else entering
            // The covered page darkens like paper in a shadow; the turning page darkens a little as it tilts.
            drawRect(Color.Black, alpha = if (onTop) 0.18f * away else 0.32f * away)
        }
}
