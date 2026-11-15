package com.bendourthe.milestone.ui.search

import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.domain.*
import com.bendourthe.milestone.ui.benefits.BenefitsViewModel
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class SearchProjectionTest {
    private val card = Card("amex", "1008", "Delta Gold", 0, 10, 11)
    private val factory = SearchSnapshotFactory({ 100 }, ZoneId.of("UTC"), Locale.US)
    private val program = StaticCatalogRepository().program("amex")!!
    private fun input(rows: List<Benefit>, prefs: BenefitsDisplayPreferences = BenefitsDisplayPreferences()) =
        SearchInput(listOf(SearchProgramSource(program, null, AccountStatus.CONNECTED)), mapOf(card to rows), mapOf(card.key to prefs))
    private fun rows(vararg drafts: BenefitDraft) = assignBenefitKeys(card.key, drafts.toList())
    private fun tracker(title: String = "Lounge Credit", description: String? = null) =
        BenefitDraft(BenefitSection.CREDIT_EARNED, title, 0, 5000, 5000, "January", false, description = description)

    @Test fun programArtworkAndStoredBalanceAreDisplayOnlyAndObserveSourceRevisions() {
        val source = input(emptyList()).copy(programs = listOf(SearchProgramSource(program, null, AccountStatus.CONNECTED, 123456)))
        val first = factory.build(source)
        val entry = first.entries.single { it.kind == SearchKind.PROGRAM }
        assertEquals(program.logoKey, entry.programDisplay!!.logoKey)
        assertEquals(program.formatBalance(123456, Locale.US), entry.programDisplay.balance)
        assertEquals(0, GlobalSearch.match("123456", first.entries).count)
        assertEquals(0, GlobalSearch.match("123,456", first.entries).count)
        val changed = factory.build(source.copy(programs = source.programs.map { it.copy(balance = 987654) }))
        assertNotSame(first.revision, changed.revision)
        assertEquals(program.formatBalance(987654, Locale.US), changed.entries.first().programDisplay!!.balance)
        assertEquals(123456L, first.source.programs.single().balance)
    }

    @Test fun canonicalMergeKeepsAliasesDescriptionsEnrollmentAndRawRows() {
        val stored = rows(tracker(description = "Airport entry"), BenefitDraft(BenefitSection.LISTED,
            "Statement Lounge Benefit", null, null, null, null, false, description = "Terminal club", enrollment = Enrollment.ENROLLED))
        val snapshot = factory.build(input(stored))
        val result = GlobalSearch.match("statement", snapshot.entries)
        assertEquals(1, result.count)
        assertEquals(stored.first().stableKey, (result.groups.single().hits.single().entry.target as SearchTarget.BenefitTarget).benefitKey)
        assertEquals(1, GlobalSearch.match("terminal", snapshot.entries).count)
        val page = BenefitsViewModel.page(card, stored, 100, ZoneId.of("UTC"), Locale.US)
        assertEquals(1, page.sections.sumOf { it.items.size })
        assertEquals("Enrolled", page.sections.flatMap { it.items }.single().enrollment)
        assertNotNull(page.listFreshnessNote)
        assertEquals(page.listFreshnessNote, snapshot.entries.single { it.target is SearchTarget.CardTarget }.freshnessNote)
        assertEquals(2, stored.size)
    }

    @Test fun localWelcomeAndCollapsedCompletedItemsRemainSearchable() {
        val stored = rows(tracker("Dining Credit")).map { it.copy(completed = true) }
        val prefs = BenefitsDisplayPreferences(collapsed = BenefitsCategory.entries.toSet(),
            welcomeBonus = WelcomeBonusSetup(5000, 5000, null, "80000 points"))
        val snapshot = factory.build(input(stored, prefs))
        val welcome = GlobalSearch.match("points", snapshot.entries).groups.single().hits.single().entry
        assertEquals("local:welcome", (welcome.target as SearchTarget.BenefitTarget).benefitKey)
        assertTrue(welcome.description!!.contains("80,000"))
        assertTrue(welcome.completed)
        assertEquals(1, GlobalSearch.match("Dining", snapshot.entries).count)
        assertEquals(1, stored.size)
    }

    @Test fun snapshotCopiesMutablePreferenceSectionMaps() {
        val sections = mutableMapOf(BenefitSection.LISTED to 1L)
        val prefs = BenefitsDisplayPreferences(readState = BenefitsReadState(generation = 1, sections = sections))
        val source = input(rows(tracker()), prefs)
        val first = factory.build(source)
        sections[BenefitSection.LISTED] = 2L
        val second = factory.build(source)
        assertNotSame(first.revision, second.revision)
        assertEquals(1L, first.source.preferences.getValue(card.key).readState.generationOf(BenefitSection.LISTED))
    }

    @Test fun equivalentReemissionRetainsRevisionButEarlierDuplicateRemovalInvalidatesIt() {
        val earlier = tracker("Lounge Credit", "Earlier entry")
        val later = tracker("Lounge Credit", "Later entry")
        val first = factory.build(input(rows(earlier, later)))
        val old = first.entries.first { it.target is SearchTarget.BenefitTarget }.target
        val repeat = factory.build(input(rows(earlier, later)))
        assertSame(first.revision, repeat.revision)
        val next = factory.build(input(rows(later)))
        assertNotSame(first.revision, next.revision)
        val reused = next.entries.first { it.target is SearchTarget.BenefitTarget }.target as SearchTarget.BenefitTarget
        assertEquals((old as SearchTarget.BenefitTarget).benefitKey, reused.benefitKey)
        assertNotEquals(old, reused)
        assertTrue(factory.contains(input(rows(later)), old))
    }

    @Test fun everySavedSortAndCompletedLastMatchDestinationSectionAndRowOrder() {
        val stored = rows(tracker("Shared Z Credit").copy(remainingCents = 9000),
            tracker("Shared A Credit").copy(remainingCents = 2000),
            tracker("Shared M Credit").copy(completed = true),
            BenefitDraft(BenefitSection.LISTED, "Shared Museum", null, null, null, null, false))
        BenefitsSort.entries.forEach { sort -> listOf(false, true).forEach { completedLast ->
            val prefs = BenefitsDisplayPreferences(sort = sort, completedLast = completedLast)
            val page = BenefitsViewModel.page(card, stored, 100, ZoneId.of("UTC"), Locale.US, prefs)
            val snapshot = factory.build(input(stored, prefs))
            val actual = GlobalSearch.match("Shared", snapshot.entries).groups.single().hits.map {
                (it.entry.target as SearchTarget.BenefitTarget).benefitKey
            }
            assertEquals("$sort completedLast=$completedLast", page.sections.flatMap { it.items }.map { it.key }, actual)
            assertEquals(actual, GlobalSearch.match("Shared", snapshot.entries.reversed()).groups.single().hits.map {
                (it.entry.target as SearchTarget.BenefitTarget).benefitKey
            })
        } }
    }
}
