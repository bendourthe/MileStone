package com.bendourthe.milestone.domain

import java.util.Locale

/** An opaque, memory-only revision. Keys alone cannot prove identity after source rows change. */
class SearchRevision internal constructor() {
    override fun toString() = "SearchRevision"
}

sealed interface SearchTarget {
    val revision: SearchRevision
    val programId: String

    data class ProgramTarget(override val programId: String, override val revision: SearchRevision) : SearchTarget
    data class CardTarget(val card: CardKey, override val revision: SearchRevision) : SearchTarget {
        override val programId get() = card.programId
    }
    data class BenefitTarget(val card: CardKey, val benefitKey: String, val category: BenefitsCategory,
                             override val revision: SearchRevision) : SearchTarget {
        override val programId get() = card.programId
    }
}

enum class SearchKind(val label: String) { PROGRAM("Program"), CARD("Card"), BENEFIT("Benefit") }

/** Only display fields enter matching. Identifiers and current monetary values never enter the corpus. */
data class SearchEntry(
    val target: SearchTarget,
    val programName: String,
    val title: String,
    val cardName: String? = null,
    val description: String? = null,
    val aliases: List<String> = emptyList(),
    val freshnessNote: String? = null,
    val completed: Boolean = false,
    val period: String? = null,
) {
    val kind: SearchKind get() = when (target) {
        is SearchTarget.ProgramTarget -> SearchKind.PROGRAM
        is SearchTarget.CardTarget -> SearchKind.CARD
        is SearchTarget.BenefitTarget -> SearchKind.BENEFIT
    }
    val id: String get() = when (val t = target) {
        is SearchTarget.ProgramTarget -> "program:${t.programId}"
        is SearchTarget.CardTarget -> "card:${t.card.programId}:${t.card.lastDigits}"
        is SearchTarget.BenefitTarget -> "benefit:${t.card.programId}:${t.card.lastDigits}:${t.benefitKey}"
    }
    private fun searchable(text: String): String {
        val digits = when (val t = target) {
            is SearchTarget.ProgramTarget -> null
            is SearchTarget.CardTarget -> t.card.lastDigits
            is SearchTarget.BenefitTarget -> t.card.lastDigits
        }
        return GlobalSearch.normalize(if (digits == null) text else text.replace(digits, " "))
    }
    internal val ownText = searchable((listOf(title) + aliases + listOfNotNull(description)).joinToString(" "))
    internal val parentText = searchable(when (target) {
        is SearchTarget.ProgramTarget -> ""
        is SearchTarget.CardTarget -> programName
        is SearchTarget.BenefitTarget -> "$programName ${cardName.orEmpty()}"
    })
    internal val cardIdentity get() = when (val t = target) {
        is SearchTarget.ProgramTarget -> ""
        is SearchTarget.CardTarget -> t.card.lastDigits
        is SearchTarget.BenefitTarget -> t.card.lastDigits
    }
}

data class SearchHit(val entry: SearchEntry, val descriptionContext: String?)
data class SearchGroup(val programId: String, val programName: String, val hits: List<SearchHit>)
data class SearchResults(val groups: List<SearchGroup>) {
    val count: Int get() = groups.sumOf { it.hits.size }
}

object GlobalSearch {
    const val MAX_QUERY_LENGTH = 256
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    fun boundQuery(query: String) = query.take(MAX_QUERY_LENGTH)
    internal fun normalize(text: String) = text.lowercase(Locale.ROOT).replace(separators, " ").trim()
    fun isBlank(query: String) = normalize(boundQuery(query)).isBlank()

    fun match(query: String, entries: List<SearchEntry>): SearchResults {
        val normalized = normalize(boundQuery(query))
        if (normalized.isEmpty()) return SearchResults(emptyList())
        val tokens = normalized.split(' ')
        val hits = entries.mapNotNull { entry ->
            if (tokens.none { it in entry.ownText } || tokens.any { it !in entry.ownText && it !in entry.parentText }) null
            else SearchHit(entry, context(entry.description, tokens))
        }
        val ordering = compareBy<SearchHit> { it.entry.kind.ordinal }
            .thenBy { if (it.entry.kind == SearchKind.BENEFIT) it.entry.cardName.orEmpty().lowercase(Locale.ROOT) else "" }
            .thenBy { if (it.entry.kind == SearchKind.BENEFIT) it.entry.cardIdentity else "" }
            .thenBy { it.entry.title.lowercase(Locale.ROOT) }.thenBy { it.entry.id }
        return SearchResults(hits.groupBy { it.entry.target.programId }.map { (id, rows) ->
            SearchGroup(id, rows.first().entry.programName, rows.sortedWith(ordering))
        }.sortedWith(compareBy<SearchGroup> { it.programName.lowercase(Locale.ROOT) }.thenBy { it.programId }))
    }

    private fun context(description: String?, tokens: List<String>): String? {
        if (description.isNullOrBlank()) return null
        val position = tokens.map { description.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull() ?: return null
        val start = (position - 45).coerceAtLeast(0)
        val end = (start + 150).coerceAtMost(description.length)
        return (if (start > 0) "..." else "") + description.substring(start, end) + (if (end < description.length) "..." else "")
    }
}
