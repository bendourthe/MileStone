package com.bendourthe.milestone.ui

import app.cash.turbine.ReceiveTurbine

/** Skips emissions until one satisfies [predicate]; StateFlow conflation makes exact emission counts unreliable. */
suspend fun <T> ReceiveTurbine<T>.awaitUntil(predicate: (T) -> Boolean): T {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}
