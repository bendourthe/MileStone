package com.bendourthe.milestone.domain

import kotlin.random.Random

object AdversarialParity {
    private var comparisons = 0
    private var assertions = 0
    private var membershipChecks = 0
    private val revision = SearchRevision()
    private fun verify(condition: Boolean, label: String) {
        assertions++
        check(condition) { "case=$label assertion=$assertions" }
    }
    private fun equivalent(expected: SearchResults, actual: SearchResults, label: String) {
        comparisons++
        verify(expected == actual, "$label-structured")
        verify(expected.count == actual.count && expected.initialTab == actual.initialTab, "$label-derived")
        for (tab in SearchTab.entries) {
            verify(expected.groups(tab) == actual.groups(tab), "$label-tabgroups")
            verify(expected.count(tab) == actual.count(tab), "$label-tabcount")
        }
        for (id in (expected.groups + actual.groups).map { it.programId }.distinct() + "absent") {
            val a = expected.cards(id)
            val b = actual.cards(id)
            verify(a == b, "$label-cardgroups")
            verify(a.map { it.sections } == b.map { it.sections }, "$label-sections")
        }
    }
    private fun corpus(seed: Int): List<SearchEntry> {
        val rng = Random(seed)
        val words = listOf("lounge", "travel", "caf\u00e9", "\u03a3\u039f\u03a3", "\u0130stanbul", "\u6771\u4eac", "alpha-beta", "\uD801\uDC00", "welcome", "dining")
        val result = mutableListOf<SearchEntry>()
        repeat(4) { p ->
            val id = "synthetic$p"
            val name = listOf("Zulu", "Alpha", "ALPHA", "\u03a3\u039f\u03a3")[p]
            result += SearchEntry(SearchTarget.ProgramTarget(id, revision), name, "program $p", programDisplay = SearchProgramDisplay("S", null, p.toLong(), "synthetic"))
            repeat(3) { c ->
                val card = CardKey(id, (1000 + c).toString())
                val cn = if (c < 2) "Same Card" else "Other Card"
                result += SearchEntry(SearchTarget.CardTarget(card, revision), name, cn, description = "card description ${words[(p+c)%words.size]}")
                repeat(9) { n ->
                    val title = "${words[(n+p)%words.size]} item ${n%3}"
                    result += SearchEntry(SearchTarget.BenefitTarget(card, "item${n%7}", BenefitsCategory.entries[n%BenefitsCategory.entries.size], revision),
                        if (n == 0) "First Input $p" else name, title, cn,
                        if (n%3 == 0) "x".repeat(200) + " lounge " + "z".repeat(220) else "${words[(n+c+2)%words.size]} description ${card.lastDigits}",
                        listOf(title.uppercase(), "alias ${words[(n+4)%words.size]}", "", " "),
                        freshnessNote = "synthetic freshness", completed = n%2 == 0, period = "synthetic period", displayOrder = listOf(Int.MIN_VALUE, 0, 0, 3, Int.MAX_VALUE)[n%5], enrollment = "synthetic enrollment")
                }
            }
            // Duplicate comparator keys with observably distinct non-sort fields exercise stable ties.
            val twin = result.last()
            result += twin.copy(description = "lounge stable first", freshnessNote = "first")
            result += twin.copy(description = "lounge stable second", freshnessNote = "second")
            // Last duplicate program entry owns metadata and identity, independent of hit sorting.
            result += SearchEntry(SearchTarget.ProgramTarget(id, revision), name, "identity $p", programDisplay = SearchProgramDisplay("T", null, (p+10).toLong(), "last synthetic"))
        }
        return result.shuffled(rng)
    }
    @JvmStatic fun main(args: Array<String>) {
        val queries = listOf("", " ", "!@#$%^&*", "lounge", "LOUNGE", "travel", "caf\u00e9", "cafe\u0301", "\u03c2\u03bf\u03c2", "\u03c3\u03bf\u03c3", "istanbul", "\u0130STANBUL", "\u6771\u4eac", "\uD801\uDC28", "alpha beta", "same card", "other", "description", "alias", "welcome", "dining", "identity", "identity 1", "synthetic0", "program", "First Input", "Zulu lounge", "Alpha dining", "1000", "1001", "1002", "notfound", "lounge notfound", "lounge travel", "\u0000lounge\uFEFF", "lounge\ntravel", "x".repeat(256), " ".repeat(255)+"lounge", " ".repeat(256)+"lounge", "x".repeat(255)+"\uD801\uDC00", "x".repeat(257), "lounge ".repeat(1000), "'; DROP TABLE--", "../../etc/passwd", "\$(whoami)")
        var queryCases = 0
        repeat(64) { seed ->
            val rows = corpus(seed)
            val prepared = GlobalSearch.prepare(rows)
            for ((qi, query) in queries.withIndex()) {
                val expected = ReferenceGlobalSearch.match(query, rows)
                equivalent(expected, GlobalSearch.match(query, rows), "s$seed-q$qi-public")
                equivalent(expected, GlobalSearch.match(query, prepared), "s$seed-q$qi-prepared")
                queryCases++
            }
            val mutableRows = rows.toMutableList()
            val snapshot = GlobalSearch.prepare(mutableRows)
            mutableRows.clear()
            mutableRows += corpus(seed+1000).take(1)
            equivalent(ReferenceGlobalSearch.match("lounge", rows), GlobalSearch.match("lounge", snapshot), "s$seed-copiedmembership")
            membershipChecks++
        }
        val card = CardKey("identity-only", "7654")
        val parent = SearchEntry(SearchTarget.ProgramTarget("identity-only", revision), "Parent", "Parent")
        val benefit = SearchEntry(SearchTarget.BenefitTarget(card, "b", BenefitsCategory.entries.first(), revision), "Parent", "Unique", "Card", "prefix "+"x".repeat(220)+" needle "+"z".repeat(200), listOf("needle alias"))
        val rows = listOf(parent, benefit)
        fun checked(q: String): SearchResults {
            val expected = ReferenceGlobalSearch.match(q, rows)
            equivalent(expected, GlobalSearch.match(q, rows), "edge-public")
            equivalent(expected, GlobalSearch.match(q, GlobalSearch.prepare(rows)), "edge-prepared")
            return expected
        }
        verify(checked("Card").count == 0, "parent-only-no-own-token")
        verify(checked("Card Unique").count == 1, "parent-plus-own-token")
        verify(checked("Parent").count == 2, "program-expansion")
        verify(checked("7654").count == 0, "digits-excluded")
        val context = checked("needle").groups.single().hits.single().descriptionContext!!
        verify(context.startsWith("...") && context.endsWith("...") && context.length == 156 && !context.contains("alias"), "description-priority-window")
        verify(checked(" ".repeat(256)+"needle").count == 0, "bound-before-normalization")
        verify(GlobalSearch.boundQuery("x".repeat(300)).length == 256, "exact-query-bound")
        val orphan = benefit.copy(programName = "Parent", title = "Orphan")
        equivalent(ReferenceGlobalSearch.match("orphan", listOf(orphan)), GlobalSearch.match("orphan", GlobalSearch.prepare(listOf(orphan))), "orphan")
        equivalent(ReferenceGlobalSearch.match("anything", emptyList()), GlobalSearch.match("anything", GlobalSearch.prepare(emptyList())), "empty")
        println("{\"status\":\"PASS\",\"corpora\":64,\"queries_per_corpus\":"+queries.size+",\"query_cases\":"+queryCases+",\"result_comparisons\":"+comparisons+",\"assertions\":"+assertions+",\"copied_membership_checks\":"+membershipChecks+"}")
    }
}
