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
        BenefitsReader.section(group(doc, el), title, el.selectFirst(s.getValue("trackerDone")) != null, period, remaining, target)
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
        assertEquals(listOf(BenefitSection.CREDIT_EARNED, BenefitSection.COMPLETED), sections(page(SYNTHETIC, "benefits-rewards-2.html")!!))
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

    @Test
    fun privateActivityCapturesShowEachSectionAndAFullList() {
        val names = listOf("amex-activity-platinum.html", "amex-activity-business-platinum.html")
        val docs = names.mapNotNull { page(PRIVATE, it) }
        assumeTrue("no private activity capture on this machine", docs.isNotEmpty())
        docs.forEach { doc ->
            val found = sections(doc).toSet()
            assertTrue("sections found: $found", found.containsAll(setOf(BenefitSection.SPEND_PROGRESS, BenefitSection.CREDIT_EARNED, BenefitSection.COMPLETED)))
            val list = listed(doc)
            assertTrue("listed benefits: ${list.size}", list.size >= 10)
            assertTrue("no enrollment ribbon found", list.any { it.second != null })
            assertTrue("the activity page has a current card", digits(doc.selectFirst(s.getValue("selected"))?.selectFirst(s.getValue("optionDigits"))) != null)
        }
    }

    @Test
    fun privateRewardsCapturesShowTrackersForCardsWithoutAnActivityPage() {
        val docs = listOf("amex-rewards-hilton-surpass.html", "amex-rewards-delta-gold.html").mapNotNull { page(PRIVATE, it) }
        assumeTrue("no private Rewards capture on this machine", docs.isNotEmpty())
        docs.forEach { doc ->
            assertTrue("no trackers", sections(doc).isNotEmpty())
            assertEquals("unexpected activity link", 0, doc.select(s.getValue("activityLink")).size)
        }
    }

    private companion object {
        const val SYNTHETIC = "fixtures"
        const val PRIVATE = "fixtures-private"
    }
}
