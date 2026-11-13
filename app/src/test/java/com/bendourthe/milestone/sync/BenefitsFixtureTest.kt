package com.bendourthe.milestone.sync

import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.sync.extract.BenefitsReader
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Checks the Amex benefits selectors (ADR-0008) against the committed synthetic pages and, when present on this
 * machine, the private captures. A private capture is never on CI, so its absence is a visible skip, never a pass.
 * Element finding mirrors `extract.js`; text is never printed, only counts and sections.
 */
class BenefitsFixtureTest {
    private val s = StaticCatalogRepository.AMEX_BENEFITS.selectors

    private fun page(folder: String, name: String): Document? =
        javaClass.classLoader!!.getResource("$folder/$name")?.readText()?.let { Jsoup.parse(it, "https://global.americanexpress.com/") }

    private fun options(doc: Document): List<Element> = doc.select(s.getValue("option")).filter { it.selectFirst(s.getValue("optionDigits")) != null }

    private fun digits(el: Element?) = el?.text()?.filter(Char::isDigit)?.takeIf { CardKey.isValidLastDigits(it) }

    /** The h2 before [el] in document order, as `extract.js` reads it. */
    private fun group(doc: Document, el: Element): String? = doc.select("h2").lastOrNull { it.siblingIndex() >= 0 && precedes(it, el) }?.text()

    private fun precedes(a: Element, b: Element): Boolean {
        val all = a.ownerDocument()!!.allElements
        return all.indexOf(a) < all.indexOf(b)
    }

    private fun sections(doc: Document): List<BenefitSection> = doc.select(s.getValue("tracker")).mapNotNull { el ->
        val title = el.selectFirst(s.getValue("trackerHeading"))?.text() ?: return@mapNotNull null
        val spent = el.selectFirst(s.getValue("trackerSpent"))?.text()
        val remaining = el.selectFirst(s.getValue("trackerRemaining"))?.text()
        if (title.startsWith("Congratulations") && spent == null && remaining == null) return@mapNotNull null
        val period = el.selectFirst(s.getValue("trackerSpentSection"))?.select("span")?.map { it.text() }
            ?.lastOrNull { Regex("^(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*(\\s*[-–]\\s*[a-z]{3,9})?$", RegexOption.IGNORE_CASE).matches(it) }
        val target = el.selectFirst("progress")?.attr("max")?.toDoubleOrNull()?.let { Math.round(it * 100) }
        val label = el.selectFirst(s.getValue("trackerSpentSection"))?.select("span")
            ?.filterNot { it.`is`(s.getValue("trackerSpent")) }?.map { it.text() }
            ?.firstOrNull { it != period && Regex("^[A-Za-z ]{2,20}$").matches(it) }
        BenefitsReader.section(group(doc, el), title, el.selectFirst(s.getValue("trackerDone")) != null, period, remaining, target, label)
    }

    private fun listed(doc: Document): List<Pair<String, String?>> = doc.select(s.getValue("listLink"))
        .filter { it.selectFirst("h4") != null }
        .map { link ->
            var root: Element = link
            while (root.parent() != null && root.parent()!!.select(s.getValue("listMore")).size <= 1) root = root.parent()!!
            val slug = link.attr("href").substringAfter("/detail/").substringBefore('/')
            val ribbon = root.select("div, span").firstOrNull { it.children().isEmpty() && it.ownText() in setOf("Enrolled", "Enrollment Required") }?.ownText()
            slug to ribbon
        }
        .distinctBy { it.first }

    @Test
    fun syntheticRewardsPageShowsTheCurrentCardAndAnActivityLink() {
        val doc = page(SYNTHETIC, "benefits-rewards.html")!!
        assertEquals("71008", digits(doc.selectFirst(s.getValue("selected"))?.selectFirst(s.getValue("optionDigits"))))
        assertEquals(1, doc.select(s.getValue("combobox")).size)
        assertEquals(0, options(doc).size)
    }

    @Test
    fun syntheticActivityPageHasEverySectionAndTheList() {
        val doc = page(SYNTHETIC, "benefits-activity.html")!!
        assertEquals(
            listOf(BenefitSection.SPEND_PROGRESS, BenefitSection.CREDIT_EARNED, BenefitSection.CREDIT_EARNED, BenefitSection.COMPLETED),
            sections(doc),
        )
        assertEquals(
            listOf("airline-fee-credit" to "Enrollment Required", "lounge-access" to "Enrolled", "purchase-protection" to null),
            listed(doc),
        )
    }

    @Test
    fun syntheticRewardsGridIsReadWithoutGroupNames() {
        assertEquals(
            listOf(BenefitSection.CREDIT_EARNED, BenefitSection.SPEND_PROGRESS, BenefitSection.COMPLETED),
            sections(page(SYNTHETIC, "benefits-rewards-2.html")!!),
        )
        assertEquals(emptyList<BenefitSection>(), sections(page(SYNTHETIC, "benefits-rewards-3.html")!!))
    }

    @Test
    fun privateSwitcherCaptureListsEveryCardWithLastDigits() {
        val doc = page(PRIVATE, "amex-rewards-switcher-open.html")
        assumeTrue("no private switcher capture on this machine", doc != null)
        val cards = options(doc!!)
        assertTrue("no card options", cards.isNotEmpty())
        cards.forEach { option ->
            assertTrue("an option without 4 or 5 last digits", digits(option.selectFirst(s.getValue("optionDigits"))) != null)
            assertTrue("an option without a name", option.selectFirst(s.getValue("optionName"))?.text()?.isNotBlank() == true)
        }
        assertEquals("duplicate last digits", cards.size, cards.map { digits(it.selectFirst(s.getValue("optionDigits"))) }.toSet().size)
    }

    /** Minimum counts per private capture, from the v0.3.0 capture record, under the app's own section rules. */
    private data class Recorded(val sections: Map<BenefitSection, Int>, val listed: Int = 0, val enrolled: Int = 0)

    private fun assertMeetsRecord(captures: Map<String, Recorded>) {
        val missing = captures.keys.filter { page(PRIVATE, it) == null }
        assumeTrue("private captures absent on this machine: $missing", missing.isEmpty())
        captures.forEach { (name, recorded) ->
            val doc = page(PRIVATE, name)!!
            val found = sections(doc).groupingBy { it }.eachCount()
            recorded.sections.forEach { (section, minimum) ->
                assertTrue("$name $section: ${found[section] ?: 0} < $minimum", (found[section] ?: 0) >= minimum)
            }
            val list = listed(doc)
            assertTrue("$name listed: ${list.size} < ${recorded.listed}", list.size >= recorded.listed)
            val enrolled = list.count { it.second == "Enrolled" }
            assertTrue("$name enrolled: $enrolled < ${recorded.enrolled}", enrolled >= recorded.enrolled)
            assertTrue("$name has no current card", digits(doc.selectFirst(s.getValue("selected"))?.selectFirst(s.getValue("optionDigits"))) != null)
        }
    }

    @Test
    fun privateActivityCapturesMeetTheirRecordedCounts() = assertMeetsRecord(
        mapOf(
            "amex-activity-platinum.html" to Recorded(mapOf(SPEND to 2, CREDIT to 4, DONE to 3), listed = 53, enrolled = 4),
            "amex-activity-business-platinum.html" to Recorded(mapOf(SPEND to 3, CREDIT to 4, DONE to 1), listed = 59, enrolled = 6),
        ),
    )

    @Test
    fun privateRewardsCapturesMeetTheirRecordedCountsWithoutAnActivityLink() {
        val captures = mapOf(
            "amex-rewards-hilton-surpass.html" to Recorded(mapOf(DONE to 1)),
            "amex-rewards-delta-gold.html" to Recorded(mapOf(SPEND to 1, CREDIT to 1)),
            "amex-rewards-delta-gold-switched.html" to Recorded(mapOf(SPEND to 1, CREDIT to 1)),
        )
        assertMeetsRecord(captures)
        captures.keys.forEach { assertEquals("$it has an activity link", 0, page(PRIVATE, it)!!.select(s.getValue("activityLink")).size) }
    }

    private companion object {
        const val SYNTHETIC = "fixtures"
        const val PRIVATE = "fixtures-private"
        val SPEND = BenefitSection.SPEND_PROGRESS
        val CREDIT = BenefitSection.CREDIT_EARNED
        val DONE = BenefitSection.COMPLETED
    }
}
