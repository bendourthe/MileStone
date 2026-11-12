package com.bendourthe.milestone.sync

import com.bendourthe.milestone.domain.BenefitDraft
import com.bendourthe.milestone.domain.CardList
import com.bendourthe.milestone.domain.Program

/** Why a benefits read ended before it had read every card. */
enum class BenefitsStop {
    /** The page showed a sign-in form: the read never signs in, so it stops and keeps what is stored. */
    SIGNED_OUT,

    /** The read used its whole budget; cards not reached keep their stored benefits. */
    BUDGET,

    /** The device's WebView cannot run the page script, or the program has no benefits page. */
    UNSUPPORTED,
}

/**
 * One benefits read: the card list when it was read, each read card's drafts keyed by last digits, the cards whose
 * page failed, and why the read stopped early (null when it read every card it meant to).
 */
data class BenefitsRead(
    val cards: CardList?,
    val benefits: Map<String, List<BenefitDraft>>,
    val failed: Set<String> = emptySet(),
    val stop: BenefitsStop? = null,
)

/** Reads a program's cards and their benefits in the program's own session, without ever signing in. */
fun interface BenefitsSource {
    suspend fun read(program: Program, host: WebViewHost, budgetMs: Long): BenefitsRead
}
