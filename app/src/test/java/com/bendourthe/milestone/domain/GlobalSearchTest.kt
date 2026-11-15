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
        assertEquals(corpus.map { it.target }.toSet(), hits("American Express").map { it.entry.target }.toSet())
        assertEquals(listOf(product.target), hits("Delta").map { it.entry.target })
        assertEquals(0, GlobalSearch.match("Delta unavailable", corpus).count)
    }

    @Test fun partialCaseWhitespaceAndPunctuationShareNormalization() {
        assertEquals(listOf(lounge.target), hits("  DeLtA--LOUnge! ").map { it.entry.target })
        assertEquals(listOf(product.target), hits("Delt").map { it.entry.target })
        assertEquals(corpus.map { it.target }.toSet(), hits("amex").map { it.entry.target }.toSet())
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

    @Test fun unicodeCaseEquivalenceIsSymmetricAndDescriptionContextUsesTheSameFold() {
        val pairs = listOf("ΟΣ" to "οσ", "σ" to "ς", "ſ" to "S", "µ" to "Μ", "𐐀" to "𐐨")
        pairs.forEach { (upper, lower) ->
            listOf(upper to lower, lower to upper).forEach { (title, query) ->
                assertEquals("$title / $query", 1, GlobalSearch.match(query, listOf(product.copy(title = title))).count)
                val entry = lounge.copy(title = "Access", description = "prefix $title suffix")
                val hit = GlobalSearch.match(query, listOf(entry)).groups.single().hits.single()
                assertEquals("prefix $title suffix", hit.descriptionContext)
            }
        }
    }

    @Test fun benefitOnlyGroupsRetainProgramArtworkWithoutTurningMetadataIntoMatches() {
        val display = SearchProgramDisplay("AM", "amex", 0xff0077aa, "123,456 pts")
        val entries = listOf(program.copy(programDisplay = display), product, lounge)
        val result = GlobalSearch.match("terminal", entries)
        assertEquals(0, result.count(SearchTab.REWARDS))
        assertEquals(listOf(lounge.target), result.groups(SearchTab.BENEFITS).single().hits.map { it.entry.target })
        assertSame(display, result.groups(SearchTab.BENEFITS).single().programDisplay)
        assertEquals(0, GlobalSearch.match("123456", entries).count)
    }

    @Test fun programOnlyQueryIncludesEveryCardAndBenefitButMixedQueriesStillMatchTheItem() {
        val all = listOf(program) + (0 until 4).flatMap { index ->
            val key = CardKey("amex", (1008 + index).toString())
            listOf(product.copy(target = SearchTarget.CardTarget(key, revision), title = "Product $index"),
                lounge.copy(target = SearchTarget.BenefitTarget(key, "access", BenefitsCategory.OTHER, revision)),
                lounge.copy(target = SearchTarget.BenefitTarget(key, "dining", BenefitsCategory.CREDITS, revision), title = "Dining", description = null))
        }
        for (query in listOf("amex", "American Express")) {
            val result = GlobalSearch.match(query, all)
            assertEquals(1, result.count(SearchTab.REWARDS))
            assertEquals(12, result.count(SearchTab.BENEFITS))
            assertEquals(4, result.cards("amex").size)
            assertTrue(result.cards("amex").all { it.benefits.size == 2 })
        }
        assertEquals(listOf(lounge.target), hits("amex lounge").map { it.entry.target })
        assertEquals(0, GlobalSearch.match("amex unavailable", all).count)
        assertEquals(listOf(program.target), hits("credit cards").map { it.entry.target })
    }

    private fun hits(query: String) = GlobalSearch.match(query, corpus).groups.flatMap { it.hits }

    @Test fun resultTabsPartitionKindsAndBenefitRowsUseCanonicalDisplayOrder() {
        val rows = listOf(lounge.copy(title = "Shared A", displayOrder = 2),
            lounge.copy(title = "Shared Z", displayOrder = 0, target = SearchTarget.BenefitTarget(card, "z", BenefitsCategory.OTHER, revision)),
            program.copy(title = "Shared program"), product.copy(title = "Shared card"))
        val result = GlobalSearch.match("Shared", rows.reversed())
        assertEquals(SearchTab.REWARDS, result.initialTab)
        assertEquals(listOf(SearchKind.PROGRAM), result.groups(SearchTab.REWARDS).single().hits.map { it.entry.kind })
        val benefits = result.groups(SearchTab.BENEFITS).single().hits
        assertEquals(listOf("Shared card", "Shared Z", "Shared A"), benefits.map { it.entry.title })
        assertEquals(SearchTab.BENEFITS, GlobalSearch.match("Shared", rows.filter { it.kind != SearchKind.PROGRAM }).initialTab)
        assertEquals(result, GlobalSearch.match("Shared", rows))
    }

    @Test fun resultPresentationPartitionsAreBuiltOnceBeforeRendering() {
        var reads = 0
        val source = (0 until 2000).map { index -> SearchHit(lounge.copy(
            target = SearchTarget.BenefitTarget(card, "item$index", BenefitsCategory.OTHER, revision), displayOrder = index), null) }
        val tracked = object : AbstractList<SearchHit>() {
            override val size get() = source.size
            override fun get(index: Int): SearchHit { reads++; return source[index] }
        }
        val result = SearchResults(listOf(SearchGroup("amex", "American Express", tracked)))
        val afterConstruction = reads
        val first = result.groups(SearchTab.BENEFITS)
        val firstCards = result.cards("amex")
        val start = System.nanoTime()
        repeat(6) { result.groups(SearchTab.REWARDS); result.groups(SearchTab.BENEFITS); result.initialTab; result.cards("amex"); result.count(SearchTab.BENEFITS) }
        println("SearchPresentation repeated_source_reads=${reads - afterConstruction} elapsed_ns=${System.nanoTime() - start}")
        assertSame("Rendering reuses the prepared partition", first, result.groups(SearchTab.BENEFITS))
        assertSame("Rendering reuses the prepared card groups", firstCards, result.cards("amex"))
        assertEquals(source, firstCards.single().benefits)
        assertNull(firstCards.single().cardHit)
        assertEquals(2000, result.count(SearchTab.BENEFITS))
        assertEquals("Rendering never traverses source hits", afterConstruction, reads)
    }
}
