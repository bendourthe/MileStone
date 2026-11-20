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
enum class SearchTab(val label: String) { REWARDS("Rewards"), BENEFITS("Benefits") }

/** Only display fields enter matching. Identifiers and current monetary values never enter the corpus. */
data class SearchProgramDisplay(val monogram: String, val logoKey: String?, val brandColorArgb: Long, val balance: String)

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
    val displayOrder: Int = 0,
    val enrollment: String? = null,
    val programDisplay: SearchProgramDisplay? = null,
    /** The card's stored ending (ADR-0009); a legacy last-digits key supplies it when this is null. */
    val cardDigits: String? = null,
) {
    val kind: SearchKind get() = when (target) {
        is SearchTarget.ProgramTarget -> SearchKind.PROGRAM
        is SearchTarget.CardTarget -> SearchKind.CARD
        is SearchTarget.BenefitTarget -> SearchKind.BENEFIT
    }
    val id: String = when (val t = target) {
        is SearchTarget.ProgramTarget -> "program:${t.programId}"
        is SearchTarget.CardTarget -> "card:${t.card.programId}:${t.card.cardId}"
        is SearchTarget.BenefitTarget -> "benefit:${t.card.programId}:${t.card.cardId}:${t.benefitKey}"
    }
    // Snapshot fields do not change between keystrokes; avoid allocations in sort comparisons and lazy keys.
    internal val sortTitle = title.lowercase(Locale.ROOT)
    internal val sortCardName = cardName.orEmpty().lowercase(Locale.ROOT)
    private fun searchable(text: String): String {
        val digits = when (val t = target) {
            is SearchTarget.ProgramTarget -> null
            is SearchTarget.CardTarget -> cardDigits ?: t.card.displayDigits
            is SearchTarget.BenefitTarget -> cardDigits ?: t.card.displayDigits
        }
        return GlobalSearch.normalize(if (digits == null) text else text.replace(digits, " "))
    }
    internal val ownText = searchable((listOf(title) + aliases + listOfNotNull(description)).joinToString(" "))
    internal val programIdentityText = if (target is SearchTarget.ProgramTarget) GlobalSearch.normalize("$title ${target.programId}") else ""
    internal val parentText = searchable(when (target) {
        is SearchTarget.ProgramTarget -> ""
        is SearchTarget.CardTarget -> programName
        is SearchTarget.BenefitTarget -> "$programName ${cardName.orEmpty()}"
    })
    internal val contextSources = GlobalSearch.contextSources(title, description, aliases)
    internal val cardIdentity get() = when (val t = target) {
        is SearchTarget.ProgramTarget -> ""
        is SearchTarget.CardTarget -> (cardDigits ?: t.card.displayDigits).orEmpty()
        is SearchTarget.BenefitTarget -> (cardDigits ?: t.card.displayDigits).orEmpty()
    }
}

data class SearchHit(val entry: SearchEntry, val descriptionContext: String?)
data class SearchGroup(val programId: String, val programName: String, val hits: List<SearchHit>,
                       val programDisplay: SearchProgramDisplay? = null)
data class SearchCardGroup(val card: CardKey, val context: SearchEntry, val cardHit: SearchHit?, val benefits: List<SearchHit>) {
    val sections: Map<BenefitsCategory, List<SearchHit>> = benefits.groupBy { (it.entry.target as SearchTarget.BenefitTarget).category }
}

data class SearchResults(val groups: List<SearchGroup>) {
    // Prepared once alongside matching on the worker, never rebuilt during composition or draw.
    val count: Int = groups.sumOf { it.hits.size }
    private val rewards = partition(SearchTab.REWARDS)
    private val benefits = partition(SearchTab.BENEFITS)
    private val rewardsCount = rewards.sumOf { it.hits.size }
    private val benefitsCount = benefits.sumOf { it.hits.size }
    private val cards = benefits.associate { group -> group.programId to group.hits.groupBy { hit ->
        when (val target = hit.entry.target) {
            is SearchTarget.CardTarget -> target.card
            is SearchTarget.BenefitTarget -> target.card
            is SearchTarget.ProgramTarget -> error("Rewards result in Benefits")
        }
    }.map { (card, hits) -> SearchCardGroup(card, hits.first().entry,
        hits.firstOrNull { it.entry.kind == SearchKind.CARD }, hits.filter { it.entry.kind == SearchKind.BENEFIT }) } }
    private fun partition(tab: SearchTab): List<SearchGroup> = groups.mapNotNull { group ->
        val hits = group.hits.filter { (it.entry.kind == SearchKind.PROGRAM) == (tab == SearchTab.REWARDS) }
        group.copy(hits = hits).takeIf { hits.isNotEmpty() }
    }
    fun groups(tab: SearchTab): List<SearchGroup> = if (tab == SearchTab.REWARDS) rewards else benefits
    fun count(tab: SearchTab): Int = if (tab == SearchTab.REWARDS) rewardsCount else benefitsCount
    fun cards(programId: String): List<SearchCardGroup> = cards[programId].orEmpty()
    val initialTab: SearchTab = if (rewards.isEmpty()) SearchTab.BENEFITS else SearchTab.REWARDS
}

object GlobalSearch {
    const val MAX_QUERY_LENGTH = 256
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    fun boundQuery(query: String) = query.take(MAX_QUERY_LENGTH)
    // Simple Unicode folding avoids context-dependent final sigma and preserves UTF-16 offsets.
    private fun fold(text: String): String = buildString(text.length) {
        var index = 0
        while (index < text.length) {
            val point = text.codePointAt(index)
            appendCodePoint(Character.toLowerCase(Character.toUpperCase(point)))
            index += Character.charCount(point)
        }
    }
    internal fun normalize(text: String) = fold(text).replace(separators, " ").trim()
    fun isBlank(query: String) = normalize(boundQuery(query)).isBlank()

    internal class Prepared internal constructor(
        internal val rows: List<IndexedValue<SearchEntry>>,
        internal val programEntries: Map<String, SearchEntry>,
        internal val programIdentities: Map<String, String>,
    )

    private val entryOrdering = compareBy<SearchEntry> { it.kind.ordinal }
        .thenBy { if (it.kind == SearchKind.BENEFIT) it.sortCardName else "" }
        .thenBy { if (it.kind == SearchKind.BENEFIT) it.cardIdentity else "" }
        .thenBy { if (it.kind == SearchKind.BENEFIT) it.displayOrder else 0 }
        .thenBy { it.sortTitle }.thenBy { it.id }
    private val hitOrdering = Comparator<SearchHit> { left, right -> entryOrdering.compare(left.entry, right.entry) }

    internal fun prepare(entries: List<SearchEntry>): Prepared {
        val copied = entries.toList()
        val programs = copied.filter { it.kind == SearchKind.PROGRAM }.associateBy { it.target.programId }
        return Prepared(copied.withIndex().sortedWith { left, right -> entryOrdering.compare(left.value, right.value) },
            programs, programs.mapValues { it.value.programIdentityText })
    }

    fun match(query: String, entries: List<SearchEntry>): SearchResults {
        val normalized = normalize(boundQuery(query))
        if (normalized.isEmpty()) return SearchResults(emptyList())
        val programs = entries.filter { it.kind == SearchKind.PROGRAM }.associateBy { it.target.programId }
        return matching(normalized.split(' '), entries.withIndex(), programs,
            programs.mapValues { it.value.programIdentityText }, ordered = false)
    }

    internal fun match(query: String, prepared: Prepared): SearchResults {
        val normalized = normalize(boundQuery(query))
        if (normalized.isEmpty()) return SearchResults(emptyList())
        return matching(normalized.split(' '), prepared.rows, prepared.programEntries, prepared.programIdentities, ordered = true)
    }

    private fun matching(tokens: List<String>, rows: Iterable<IndexedValue<SearchEntry>>,
                         programEntries: Map<String, SearchEntry>, programs: Map<String, String>, ordered: Boolean): SearchResults {
        val scopedPrograms = programs.filterValues { identity -> tokens.all { it in identity } }.keys
        val groups = linkedMapOf<String, MutableList<SearchHit>>()
        val firstInput = mutableMapOf<String, Pair<Int, String>>()
        for ((index, entry) in rows) {
            val programId = entry.target.programId
            val programOnly = entry.kind != SearchKind.PROGRAM && programId in scopedPrograms
            if (!programOnly && !matches(entry, tokens, programs[programId].orEmpty())) continue
            val hit = SearchHit(entry, entry.contextSources.firstNotNullOfOrNull { (text, folded) -> context(text, folded, tokens) })
            groups.getOrPut(programId) { mutableListOf() }.add(hit)
            if (index < (firstInput[programId]?.first ?: Int.MAX_VALUE)) firstInput[programId] = index to entry.programName
        }
        return SearchResults(groups.map { (id, hits) ->
            SearchGroup(id, firstInput.getValue(id).second, if (ordered) hits else hits.sortedWith(hitOrdering), programEntries[id]?.programDisplay)
        }.sortedWith(compareBy<SearchGroup> { it.programName.lowercase(Locale.ROOT) }.thenBy { it.programId }))
    }

    private fun matches(entry: SearchEntry, tokens: List<String>, programContext: String): Boolean {
        var ownMatch = false
        for (token in tokens) {
            val own = token in entry.ownText
            if (own) ownMatch = true
            else if (token !in entry.parentText && token !in programContext) return false
        }
        return ownMatch
    }

    internal fun contextSources(title: String, description: String?, aliases: List<String>): List<Pair<String, String>> = buildList {
        description?.takeIf { it.isNotBlank() }?.let { add(it to fold(it)) }
        if (aliases.isNotEmpty()) {
            val normalizedTitle = normalize(title)
            aliases.filter { it.isNotBlank() && normalize(it) != normalizedTitle }.forEach { add(it to fold(it)) }
        }
    }

    private fun context(description: String, folded: String, tokens: List<String>): String? {
        var position = Int.MAX_VALUE
        for (token in tokens) {
            val found = folded.indexOf(token)
            if (found >= 0 && found < position) position = found
        }
        if (position == Int.MAX_VALUE) return null
        val start = (position - 45).coerceAtLeast(0)
        val end = (start + 150).coerceAtMost(description.length)
        return (if (start > 0) "..." else "") + description.substring(start, end) + (if (end < description.length) "..." else "")
    }
}
