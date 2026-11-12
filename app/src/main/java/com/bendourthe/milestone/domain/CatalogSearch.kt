package com.bendourthe.milestone.domain

/** Case-insensitive match on name, id, or category; blank returns everything, symbols-only matches nothing. */
object CatalogSearch {
    fun filter(programs: List<Program>, query: String): List<Program> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return programs
        val normalized = trimmed.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim()
        if (normalized.isEmpty()) return emptyList()
        return programs.filter { program ->
            listOf(program.displayName, program.programId, program.category.label).any { it.lowercase().contains(normalized) }
        }
    }
}
