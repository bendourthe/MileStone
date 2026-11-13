package com.bendourthe.milestone.sync

import com.bendourthe.milestone.domain.BenefitDraft
import com.bendourthe.milestone.domain.BenefitSection
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
 * page failed, and why the read stopped early (null when it read every card it meant to). [sections] names, per card,
 * the sections its pages covered; only those are replaced when stored, so a read that reached only some of a card's
 * pages keeps what the others held. A card missing from [sections] covered every section.
 */
data class BenefitsRead(
    val cards: CardList?,
    val benefits: Map<String, List<BenefitDraft>>,
    val failed: Set<String> = emptySet(),
    val stop: BenefitsStop? = null,
    val sections: Map<String, Set<BenefitSection>> = emptyMap(),
) {
    fun sectionsOf(lastDigits: String): Set<BenefitSection> = sections[lastDigits] ?: ALL_SECTIONS

    companion object {
        /** What a page of trackers covers: everything except the benefits list. */
        val TRACKER_SECTIONS: Set<BenefitSection> = setOf(BenefitSection.SPEND_PROGRESS, BenefitSection.CREDIT_EARNED, BenefitSection.COMPLETED, BenefitSection.OTHER)

        /** What a benefits list covers. */
        val LIST_SECTIONS: Set<BenefitSection> = setOf(BenefitSection.LISTED)
        val ALL_SECTIONS: Set<BenefitSection> = BenefitSection.entries.toSet()
    }
}

/** Reads a program's cards and their benefits in the program's own session, without ever signing in. */
fun interface BenefitsSource {
    suspend fun read(program: Program, host: WebViewHost, budgetMs: Long): BenefitsRead
}
