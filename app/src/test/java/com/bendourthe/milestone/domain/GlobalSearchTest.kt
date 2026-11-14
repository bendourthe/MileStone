package com.bendourthe.milestone.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class GlobalSearchTest {
    private val revision = SearchRevision()
    private val card = CardKey("amex", "1008")
    private val program = SearchEntry(SearchTarget.ProgramTarget("amex", revision), "American Express", "American Express",
        aliases = listOf("amex", "Credit cards"))
    private val product = SearchEntry(SearchTarget.CardTarget(card, revision), "American Express", "Delta Gold")
    private val lounge = SearchEntry(SearchTarget.BenefitTarget(card, "lounge", BenefitsCategory.OTHER, revision),
        "American Express", "Lounge access", "Delta Gold", "International terminal club")
    private val corpus = listOf(program, product, lounge)

    @Test fun contextualTokensUseParentButRequireAnItemToken() {
        assertEquals(listOf(lounge.target), hits("Delta lounge").map { it.entry.target })
        assertEquals(listOf(program.target), hits("American Express").map { it.entry.target })
        assertEquals(listOf(product.target), hits("Delta").map { it.entry.target })
        assertEquals(0, GlobalSearch.match("Delta unavailable", corpus).count)
    }

    @Test fun partialCaseWhitespaceAndPunctuationShareNormalization() {
        assertEquals(listOf(lounge.target), hits("  DeLtA--LOUnge! ").map { it.entry.target })
        assertEquals(listOf(product.target), hits("Delt").map { it.entry.target })
        assertEquals(listOf(program.target), hits("amex").map { it.entry.target })
        assertEquals(listOf(program.target), hits("credit cards").map { it.entry.target })
    }

    @Test fun blankAndSymbolsOnlyNeverDumpTheCorpus() {
        for (query in listOf("", "  \n\t", "!@#$%_-")) assertEquals(0, GlobalSearch.match(query, corpus).count)
    }

    @Test fun inputIsBoundedAt256Characters() {
        assertEquals(256, GlobalSearch.boundQuery("a".repeat(300)).length)
        val entry = lounge.copy(title = "a".repeat(256), description = null)
        assertEquals(1, GlobalSearch.match("a".repeat(300), listOf(entry)).count)
    }

    @Test fun rootCaseMatchingDoesNotDependOnDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(1, GlobalSearch.match("ind", listOf(product.copy(title = "INDIGO"))).count)
        } finally { Locale.setDefault(previous) }
    }

    @Test fun nullDescriptionsAndIdentityDigitsAreNotSearchableFields() {
        assertEquals(1, GlobalSearch.match("lounge", listOf(lounge.copy(description = null))).count)
        assertEquals(0, GlobalSearch.match("1008", corpus).count)
        assertEquals(0, GlobalSearch.match("1008", listOf(product.copy(title = "Delta ending 1008"),
            lounge.copy(description = "Card ending 1008", cardName = "Delta 1008"))).count)
        assertEquals(0, GlobalSearch.match("lounge", listOf(program, product)).count)
    }

    @Test fun retainedAliasesAndFarDescriptionContextCanMatch() {
        val entry = lounge.copy(aliases = listOf("Club statement benefit"),
            description = "prefix ".repeat(60) + "terminal access " + "suffix ".repeat(60))
        assertEquals(1, GlobalSearch.match("statement", listOf(entry)).count)
        val hit = GlobalSearch.match("terminal", listOf(entry)).groups.single().hits.single()
        assertTrue(hit.descriptionContext!!.contains("terminal"))
        assertTrue(hit.descriptionContext.length <= 156)
    }

    @Test fun anAliasOnlyHitExplainsTheRetainedSourceTitle() {
        val entry = lounge.copy(title = "Lounge Credit", description = null, aliases = listOf("Terminal Statement Credit"))
        val hit = GlobalSearch.match("statement", listOf(entry)).groups.single().hits.single()
        assertEquals(entry.target, hit.entry.target)
        assertEquals("Terminal Statement Credit", hit.descriptionContext)
    }

    @Test fun groupsAndKindsHaveDeterministicOrderingAndAccurateCounts() {
        val other = program.copy(target = SearchTarget.ProgramTarget("alpha", revision), programName = "Alpha", title = "American program")
        val entries = listOf(lounge.copy(title = "American lounge"), product.copy(title = "American card"), program, other)
        val result = GlobalSearch.match("American", entries)
        assertEquals(listOf("Alpha", "American Express"), result.groups.map { it.programName })
        assertEquals(4, result.count)
        assertEquals(listOf(SearchKind.PROGRAM, SearchKind.CARD, SearchKind.BENEFIT), result.groups.last().hits.map { it.entry.kind })
    }

    @Test fun equalTitlesAcrossCardsAndPeriodsRemainDistinct() {
        val entries = listOf(lounge.copy(target = SearchTarget.BenefitTarget(card, "January", BenefitsCategory.OTHER, revision)),
            lounge.copy(target = SearchTarget.BenefitTarget(card, "February", BenefitsCategory.OTHER, revision), completed = true),
            lounge.copy(target = SearchTarget.BenefitTarget(CardKey("amex", "21005"), "January", BenefitsCategory.OTHER, revision)))
        val result = GlobalSearch.match("lounge", entries)
        assertEquals(3, result.count)
        assertEquals(3, result.groups.single().hits.map { it.entry.id }.toSet().size)
        assertEquals(result, GlobalSearch.match("lounge", entries.reversed()))
    }

    private fun hits(query: String) = GlobalSearch.match(query, corpus).groups.flatMap { it.hits }
}
