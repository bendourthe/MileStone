package com.bendourthe.milestone.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import kotlin.math.abs

/**
 * Page swipes for the whole app: a horizontal swipe to the right goes back one page, to the left reopens the page
 * the user last went back from. A drag a child already used (an alert row's swipe, a list scroll) stays the child's,
 * and a swipe claims the touch only when its direction has somewhere to go, so taps and rows keep working.
 */
internal fun Modifier.pageSwipes(canBack: () -> Boolean, canForward: () -> Boolean, onBack: () -> Unit, onForward: () -> Unit): Modifier =
    pointerInput(Unit) {
        val distance = 96.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!canBack() && !canForward()) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            var claimed = false
            var dx = 0f
            while (true) {
                // The Main pass reaches children first, so a consumed change here means a child owns the drag.
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!claimed && change.isConsumed) return@awaitEachGesture
                dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (!claimed) {
                    if (abs(dy) > slop && abs(dy) >= abs(dx)) return@awaitEachGesture
                    claimed = abs(dx) > slop && abs(dx) > 2 * abs(dy) && (if (dx > 0) canBack() else canForward())
                }
                // Consuming a claimed swipe cancels the tap a child would otherwise fire on release.
                if (claimed) change.consume()
                if (!change.pressed) break
            }
            if (claimed && abs(dx) >= distance) if (dx > 0) onBack() else onForward()
        }
    }

/**
 * The pages the user went back from, newest last, as typed routes a forward swipe can reopen. Any navigation that
 * is not that reopening clears them, as a browser's forward history does.
 */
internal class ForwardPages(private val routeOf: (NavBackStackEntry) -> Any?) {
    private val pages = ArrayDeque<Any>()
    private var shown: NavBackStackEntry? = null
    private var below: NavBackStackEntry? = null

    val next: Any? get() = pages.lastOrNull()

    /** Called on every page change with the page now [current] and the page under it. */
    fun onPageChanged(current: NavBackStackEntry?, under: NavBackStackEntry?) {
        val left = shown
        when {
            // One page back: the new page is the one that was under the page left.
            left != null && current != null && current == below -> routeOf(left)?.let(pages::addLast) ?: pages.clear()
            // The page a forward swipe reopened, placed on top of the page left.
            current != null && left != null && under == left && next != null && routeOf(current) == next -> pages.removeLast()
            current != left -> pages.clear()
        }
        shown = current
        below = under
    }
}

/** The typed route of a page a forward swipe may reopen: the Benefits program and card pages. */
private fun forwardRoute(entry: NavBackStackEntry): Any? = with(entry.destination) {
    when {
        hasRoute(ProgramBenefits::class) -> entry.toRoute<ProgramBenefits>()
        hasRoute(CardBenefits::class) -> entry.toRoute<CardBenefits>()
        else -> null
    }
}

/** Wires [pageSwipes] to [navController]: back below a tab's first page only, forward to [ForwardPages.next]. */
@Composable
internal fun rememberPageSwipes(navController: NavHostController, isTabRoot: (NavBackStackEntry) -> Boolean): Modifier {
    val forward = remember(navController) { ForwardPages(::forwardRoute) }
    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { controller, _, _ ->
            forward.onPageChanged(controller.currentBackStackEntry, controller.previousBackStackEntry)
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
    return Modifier.pageSwipes(
        canBack = { navController.currentBackStackEntry?.let { !isTabRoot(it) && navController.previousBackStackEntry != null } == true },
        canForward = { forward.next != null },
        onBack = { navController.popBackStack() },
        onForward = { forward.next?.let { navController.navigate(it) { launchSingleTop = true } } },
    )
}
