package com.bendourthe.milestone.domain
import java.util.Locale
object ReferenceGlobalSearch {
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

    fun match(query: String, entries: List<SearchEntry>): SearchResults {
        val normalized = normalize(boundQuery(query))
        if (normalized.isEmpty()) return SearchResults(emptyList())
        val tokens = normalized.split(' ')
        val programEntries = entries.filter { it.kind == SearchKind.PROGRAM }.associateBy { it.target.programId }
        val programs = programEntries.mapValues { it.value.programIdentityText }
        val scopedPrograms = programs.filterValues { identity -> tokens.all { it in identity } }.keys
        val hits = entries.mapNotNull { entry ->
            val programOnly = entry.kind != SearchKind.PROGRAM && entry.target.programId in scopedPrograms
            val programContext = programs[entry.target.programId].orEmpty()
            if (!programOnly && (tokens.none { it in entry.ownText } ||
                    tokens.any { it !in entry.ownText && it !in entry.parentText && it !in programContext })) null
            else SearchHit(entry, entry.contextSources.firstNotNullOfOrNull { (text, folded) -> context(text, folded, tokens) })
        }
        val ordering = compareBy<SearchHit> { it.entry.kind.ordinal }
            .thenBy { if (it.entry.kind == SearchKind.BENEFIT) it.entry.sortCardName else "" }
            .thenBy { if (it.entry.kind == SearchKind.BENEFIT) it.entry.cardIdentity else "" }
            .thenBy { if (it.entry.kind == SearchKind.BENEFIT) it.entry.displayOrder else 0 }
            .thenBy { it.entry.sortTitle }.thenBy { it.entry.id }
        return SearchResults(hits.groupBy { it.entry.target.programId }.map { (id, rows) ->
            SearchGroup(id, rows.first().entry.programName, rows.sortedWith(ordering), programEntries[id]?.programDisplay)
        }.sortedWith(compareBy<SearchGroup> { it.programName.lowercase(Locale.ROOT) }.thenBy { it.programId }))
    }

    internal fun contextSources(title: String, description: String?, aliases: List<String>): List<Pair<String, String>> = buildList {
        description?.takeIf { it.isNotBlank() }?.let { add(it to fold(it)) }
        if (aliases.isNotEmpty()) {
            val normalizedTitle = normalize(title)
            aliases.filter { it.isNotBlank() && normalize(it) != normalizedTitle }.forEach { add(it to fold(it)) }
        }
    }

    private fun context(description: String, folded: String, tokens: List<String>): String? {
        val position = tokens.map { folded.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: return null
        val start = (position - 45).coerceAtLeast(0)
        val end = (start + 150).coerceAtMost(description.length)
        return (if (start > 0) "..." else "") + description.substring(start, end) + (if (end < description.length) "..." else "")
    }
}
