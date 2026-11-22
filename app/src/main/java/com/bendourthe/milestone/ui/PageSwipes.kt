package com.bendourthe.milestone.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/** A back swipe in progress, reported as it moves so the page change can follow the finger. */
internal interface BackDrag {
    fun start(x: Float, y: Float)
    /** [fraction] of the screen width dragged, 0 to 1. */
    fun move(x: Float, y: Float, fraction: Float)
    fun cancel()
    fun commit()
}

/**
 * Page swipes for the whole app: a horizontal swipe to the right goes back one page, to the left reopens the page
 * the user last went back from. A drag a child already used (an alert row's swipe, a list scroll) stays the child's,
 * and a swipe claims the touch only when its direction has somewhere to go, so taps and rows keep working. With a
 * [backDrag], a back swipe drives the page change while the finger moves and settles it on release.
 */
internal fun Modifier.pageSwipes(canBack: () -> Boolean, canForward: () -> Boolean, onBack: () -> Unit, onForward: () -> Unit,
                                 backDrag: BackDrag? = null): Modifier =
    pointerInput(Unit) {
        val distance = 96.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!canBack() && !canForward()) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            var claimed = false
            var dragging = false
            var dx = 0f
            // A started back drag always ends in commit or cancel, even if the touch is lost or the gesture is cancelled.
            try { while (true) {
                // The Main pass reaches children first, so a consumed change here means a child owns the drag.
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!claimed && change.isConsumed) return@awaitEachGesture
                dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (!claimed) {
                    if (abs(dy) > slop && abs(dy) >= abs(dx)) return@awaitEachGesture
                    claimed = abs(dx) > slop && abs(dx) > 2 * abs(dy) && (if (dx > 0) canBack() else canForward())
                }
                // Consuming a claimed swipe cancels the tap a child would otherwise fire on release.
                if (claimed) change.consume()
                if (claimed && backDrag != null && (dragging || dx > 0)) {
                    if (!dragging) { dragging = true; backDrag.start(change.position.x, change.position.y) }
                    backDrag.move(change.position.x, change.position.y, (dx / size.width).coerceIn(0f, 1f))
                }
                if (!change.pressed) break
            } } catch (error: CancellationException) {
                if (dragging) backDrag?.cancel()
                throw error
            }
            when {
                dragging -> if (dx >= distance) backDrag?.commit() else backDrag?.cancel()
                claimed && abs(dx) >= distance -> if (dx > 0) onBack() else onForward()
            }
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
    // The back gesture's own path: the NavHost seeks its page change with the drag and pops on release.
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val backDrag = remember(dispatcher) {
        dispatcher?.let { d ->
            object : BackDrag {
                override fun start(x: Float, y: Float) = d.dispatchOnBackStarted(BackEventCompat(x, y, 0f, BackEventCompat.EDGE_LEFT))
                override fun move(x: Float, y: Float, fraction: Float) = d.dispatchOnBackProgressed(BackEventCompat(x, y, fraction, BackEventCompat.EDGE_LEFT))
                override fun cancel() = d.dispatchOnBackCancelled()
                override fun commit() = d.onBackPressed()
            }
        }
    }
    return Modifier.pageSwipes(
        canBack = { navController.currentBackStackEntry?.let { !isTabRoot(it) && navController.previousBackStackEntry != null } == true },
        canForward = { forward.next != null },
        onBack = { navController.popBackStack() },
        onForward = { forward.next?.let { navController.navigate(it) { launchSingleTop = true } } },
        backDrag = backDrag,
    )
}
