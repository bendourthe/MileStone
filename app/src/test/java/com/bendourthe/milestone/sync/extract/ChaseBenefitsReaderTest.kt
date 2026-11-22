package com.bendourthe.milestone.sync.extract

import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.Enrollment
import com.bendourthe.milestone.domain.PeriodLength
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** ADR-0009 Chase message validation and normalization, on synthetic messages only. */
class ChaseBenefitsReaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val spec = StaticCatalogRepository.CHASE_BENEFITS
    private val page = "https://secure.chase.com/web/auth/dashboard#/dashboard/benefits/synthetic"

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun row(title: String, tag: String? = null, value: String? = null) =
        """{"title":"$title","tag":${tag?.let { "\"$it\"" } ?: "null"},"value":${value?.let { "\"$it\"" } ?: "null"}}"""

    private fun groups(vararg rows: String) = json("""[{"title":"Synthetic group","rows":[${rows.joinToString(",")}]}]""")

    @Test
    fun rowsBecomeListedDraftsWithOnlyStatedAmountsPeriodsAndStatus() {
        val drafts = ChaseBenefitsReader.benefits(
            groups(row("Travel credit", "activated", "\$300 annual"), row("Dining credit", null, "Up to \$10 per month"),
                row("Lounge", "redeemed", null), row("Category", null, "5x points"), row("Shopping", "Activate", null)),
            json("[]"),
        )!!
        assertTrue(drafts.all { it.section == BenefitSection.LISTED && it.earnedCents == null && it.remainingCents == null })
        assertEquals(listOf(30_000L, 1_000L, null, null, null), drafts.map { it.targetCents })
        assertEquals(listOf(PeriodLength.YEARLY, PeriodLength.MONTHLY, null, null, null), drafts.map { it.periodLength })
        assertEquals(listOf(Enrollment.ENROLLED, null, null, null, Enrollment.ENROLLMENT_REQUIRED), drafts.map { it.enrollment })
        assertEquals(listOf(false, false, true, false, false), drafts.map { it.completed })
        assertEquals("5x points", drafts[3].description)
    }

    @Test
    fun trackersTakeEarnedOnlyFromStatedDollarAmounts() {
        val drafts = ChaseBenefitsReader.benefits(
            json("[]"),
            json(
                """[{"text":"Spend ${'$'}1,500 of ${'$'}4,000 toward the bonus","value":37.5,"max":100},
                    {"text":"${'$'}75,000 spend benefit","value":20000,"max":75000},
                    {"text":"Spend ${'$'}6,000 by Dec 31, 2026 to get a bonus","value":40,"max":100},
                    {"text":"Synthetic tracker","value":null,"max":null},
                    {"text":"Earn ${'$'}300 after you spend ${'$'}3,000","value":0,"max":3000}]""",
            ),
        )!!
        assertTrue(drafts.all { it.section == BenefitSection.SPEND_PROGRESS })
        // A reward and a target are not progress: the last tracker takes its earned amount from the bar (0), not $300.
        assertEquals(listOf(150_000L, 2_000_000L, null, null, 0L), drafts.map { it.earnedCents })
        assertEquals(listOf(400_000L, 7_500_000L, 600_000L, null, 300_000L), drafts.map { it.targetCents })
        assertEquals(listOf(250_000L, 5_500_000L, null, null, 300_000L), drafts.map { it.remainingCents })
    }

    @Test
    fun gaugeLabelSuppliesTheSpentAndTargetAmounts() {
        // Chase's gauge: the visible text names only the target; the accessible label states both amounts.
        val drafts = ChaseBenefitsReader.benefits(
            json("[]"),
            json(
                """[{"text":"Spend & unlock more benefits Spend ${'$'}75,000 by Dec 31, 2026 to get upgraded statuses","value":20,"max":100,
                     "label":"Gauge chart: ${'$'}15,250.50 received of ${'$'}75,000"},
                    {"text":"Spend ${'$'}6,000 by Dec 31, 2026","value":40,"max":100,"label":null},
                    {"text":"Spend ${'$'}6,000 by Dec 31, 2026","value":40,"max":100,"label":"Gauge chart"}]""",
            ),
        )!!
        assertEquals(listOf(1_525_050L, null, null), drafts.map { it.earnedCents })
        assertEquals(listOf(7_500_000L, 600_000L, 600_000L), drafts.map { it.targetCents })
        assertEquals(listOf(5_974_950L, null, null), drafts.map { it.remainingCents })
        assertEquals("Spend & unlock more benefits Spend \$75,000 by Dec 31, 2026 to get upgraded statuses", drafts[0].title)
    }

    @Test
    fun aCreditPagesUseMakesTheRowACreditWithUsedAndRemainingAmounts() {
        fun usage(text: String?, percent: Number?, status: String?) =
            """{"text":${text?.let { "\"$it\"" }},"percent":$percent,"status":${status?.let { "\"$it\"" }}}"""
        val drafts = ChaseBenefitsReader.benefits(
            json(
                """[{"title":"Synthetic group","rows":[
                    {"title":"${'$'}500 Credit for stays with The Edit","tag":null,"value":"${'$'}500 - Up to ${'$'}250 per transaction","usage":${usage("${'$'}0/${'$'}250", 0, null)}},
                    {"title":"${'$'}300 Annual Dining Credit","tag":null,"value":"${'$'}300 - ${'$'}150 Semiannually","usage":${usage("${'$'}50/${'$'}150", 33, null)}},
                    {"title":"${'$'}120 Lyft Credit","tag":"Activated","value":null,"usage":${usage(null, 25, null)}},
                    {"title":"Synthetic credit","tag":null,"value":"${'$'}100 annual","usage":${usage(null, 100, "Redeemed")}}]}]""",
            ),
            json("[]"),
        )!!
        assertEquals(listOf(BenefitSection.CREDIT_EARNED, BenefitSection.CREDIT_EARNED, BenefitSection.LISTED, BenefitSection.CREDIT_EARNED), drafts.map { it.section })
        assertEquals(listOf(0L, 25_000L, 25_000L), drafts[0].let { listOf(it.earnedCents, it.remainingCents, it.targetCents) })
        assertEquals(listOf(5_000L, 10_000L, 15_000L), drafts[1].let { listOf(it.earnedCents, it.remainingCents, it.targetCents) })
        assertEquals(PeriodLength.HALF_YEAR, drafts[1].periodLength)
        // A percentage alone needs the row's amount; the Lyft row states none, so it stays listed.
        assertEquals(listOf(10_000L, 0L, 10_000L), drafts[3].let { listOf(it.earnedCents, it.remainingCents, it.targetCents) })
        assertTrue(drafts[3].completed)
        assertNull(ChaseBenefitsReader.benefits(json("""[{"title":null,"rows":[{"title":"x","tag":null,"value":null,"usage":{"text":null}}]}]"""), json("[]")))
    }

    @Test
    fun theTilesOwnHeadingNamesTheTracker() {
        val drafts = ChaseBenefitsReader.benefits(
            json("[]"),
            json(
                """[{"text":"Spend & unlock more benefits Spend & unlock more benefits Spend ${'$'}75,000 by Dec 31, 2026.","value":20,"max":100,
                     "label":"Gauge chart: ${'$'}15,000 received of ${'$'}75,000","title":"Spend & unlock more benefits"},
                    {"text":"Spend ${'$'}6,000 by Dec 31, 2026","value":40,"max":100,"title":null}]""",
            ),
        )!!
        assertEquals(listOf("Spend & unlock more benefits", "Spend \$6,000 by Dec 31, 2026"), drafts.map { it.title })
        assertNull(ChaseBenefitsReader.benefits(json("[]"), json("""[{"text":"t","value":1,"max":2,"title":"${"x".repeat(121)}"}]""")))
    }

    @Test
    fun trackerWithAnUnknownFieldOrOversizedLabelIsRejected() {
        assertNull(ChaseBenefitsReader.benefits(json("[]"), json("""[{"text":"t","value":1,"max":2,"extra":"x"}]""")))
        assertNull(ChaseBenefitsReader.benefits(json("[]"), json("""[{"text":"t","value":1,"max":2,"label":"${"x".repeat(121)}"}]""")))
    }

    @Test
    fun halfYearPeriodsAreNotReadAsMonthly() {
        assertEquals(PeriodLength.HALF_YEAR, ChaseBenefitsReader.periodOf("Up to ${'$'}150 every six months"))
        assertEquals(PeriodLength.HALF_YEAR, ChaseBenefitsReader.periodOf("${'$'}50 every 6 months"))
        assertEquals(PeriodLength.MONTHLY, ChaseBenefitsReader.periodOf("${'$'}10 monthly"))
    }

    @Test
    fun malformedOrOversizedPayloadsAreRejected() {
        assertNull(ChaseBenefitsReader.benefits(json("""[{"title":null,"rows":[{"title":"x","tag":null}]}]"""), json("[]")))
        assertNull(ChaseBenefitsReader.benefits(json("""[{"title":null,"rows":[{"title":"","tag":null,"value":null}]}]"""), json("[]")))
        assertNull(ChaseBenefitsReader.benefits(json("[]"), json("""[{"text":"x","value":"1","max":null}]""")))
        val tooMany = (0..ChaseBenefitsReader.MAX_ROWS).joinToString(",") { row("Row $it") }
        assertNull(ChaseBenefitsReader.benefits(groups(tooMany), json("[]")))
    }

    @Test
    fun cardsValidateControlsEndingsAndASingleSelection() {
        fun card(control: String, digits: String?, selected: Boolean) =
            """{"control":"$control","name":"Synthetic","lastDigits":${digits?.let { "\"$it\"" } ?: "null"},"selected":$selected}"""
        assertEquals(2, ChaseBenefitsReader.cards(json("[${card("a", "1234", true)},${card("b", null, false)}]"))!!.size)
        assertNull(ChaseBenefitsReader.cards(json("[${card("a", "1234", true)},${card("b", "5678", true)}]")))
        assertNull(ChaseBenefitsReader.cards(json("[${card("a", "1234", true)},${card("a", "5678", false)}]")))
        assertNull(ChaseBenefitsReader.cards(json("[${card("4111-1111-1111-1111", "1234", true)}]")))
        assertNull(ChaseBenefitsReader.cards(json("[${card("a b", "1234", true)}]")))
        assertNull(ChaseBenefitsReader.cards(json("[${card("a", "12", true)}]")))
        assertFalse(ChaseBenefitsReader.cards(json("[${card("a", "1234", true)}]"))!!.single().toString().contains("1234"))
    }

    @Test
    fun protocolAcceptsChaseMessagesOnlyWithTheNonceOnTheChasePageAndExactFields() {
        val cards = """{"type":"chaseCards","nonce":"n","cards":[{"control":"a","name":"Synthetic","lastDigits":"1234","selected":true}],"complete":true}"""
        assertTrue(BridgeProtocol.parse(cards, "n", pageUrl = page, benefits = spec) is BridgeMessage.ChaseCards)
        assertNull(BridgeProtocol.parse(cards, "other", pageUrl = page, benefits = spec))
        assertNull(BridgeProtocol.parse(cards, "n", pageUrl = "https://evil.example/web/auth/dashboard", benefits = spec))
        assertNull(BridgeProtocol.parse(cards, "n", pageUrl = page, benefits = null))
        assertNull(BridgeProtocol.parse(cards.replace("\"complete\":true", "\"complete\":true,\"extra\":1"), "n", pageUrl = page, benefits = spec))

        val benefits = """{"type":"chaseBenefits","nonce":"n","control":"a","groups":[{"title":null,"rows":[${row("x")}]}],"trackers":[],"ready":{"groups":1,"rows":1,"trackers":0,"reason":"stable"}}"""
        val parsed = BridgeProtocol.parse(benefits, "n", pageUrl = page, benefits = spec) as BridgeMessage.ChaseBenefits
        assertEquals(1, parsed.drafts.size)
        assertFalse(parsed.toString().contains("\"a\""))
        assertEquals(0 to 0, parsed.credits to parsed.used)
        // Credit-page visit counts are for the log only: kept when in range, read as 0 otherwise.
        val visited = BridgeProtocol.parse(benefits.replace("\"reason\":\"stable\"", "\"reason\":\"stable\",\"credits\":5,\"used\":2"), "n", pageUrl = page, benefits = spec) as BridgeMessage.ChaseBenefits
        assertEquals(5 to 2, visited.credits to visited.used)
        val wild = BridgeProtocol.parse(benefits.replace("\"reason\":\"stable\"", "\"reason\":\"stable\",\"credits\":99,\"used\":\"x\""), "n", pageUrl = page, benefits = spec) as BridgeMessage.ChaseBenefits
        assertEquals(0 to 0, wild.credits to wild.used)

        val unreadable = """{"type":"chaseUnreadable","nonce":"n","why":"navigation","step":"more"}"""
        assertEquals(BridgeMessage.ChaseUnreadable("navigation", "more"), BridgeProtocol.parse(unreadable, "n", pageUrl = page, benefits = spec))
        assertEquals(BridgeMessage.ChaseUnreadable("overflow", "page"), BridgeProtocol.parse(unreadable.replace("navigation", "overflow").replace("\"more\"", "\"page\""), "n", pageUrl = page, benefits = spec))
        assertEquals(BridgeMessage.ChaseUnreadable("noControl", "benefits"), BridgeProtocol.parse(unreadable.replace("navigation", "noControl").replace("\"more\"", "\"benefits\""), "n", pageUrl = page, benefits = spec))
        // The home page's grouping: endings and booleans only, any other field or value rejects the message.
        val kinds = """{"type":"chaseKinds","nonce":"n","kinds":[{"digits":"1111","business":false},{"digits":"3333","business":true}]}"""
        assertEquals(BridgeMessage.ChaseKinds(mapOf("1111" to false, "3333" to true)), BridgeProtocol.parse(kinds, "n", pageUrl = page, benefits = spec))
        assertNull(BridgeProtocol.parse(kinds.replace("\"business\":true", "\"business\":true,\"heading\":\"Acme LLC\""), "n", pageUrl = page, benefits = spec))
        assertNull(BridgeProtocol.parse(kinds.replace("3333", "Acme"), "n", pageUrl = page, benefits = spec))
        val counted = unreadable.replace("}", ""","counts":"1,4,3,1,3"}""")
        assertEquals(BridgeMessage.ChaseUnreadable("navigation", "more", "1,4,3,1,3"), BridgeProtocol.parse(counted, "n", pageUrl = page, benefits = spec))
        // Counts carry digits only: anything else rejects the message rather than logging page text.
        assertNull(BridgeProtocol.parse(counted.replace("1,4,3,1,3", "Card 1234"), "n", pageUrl = page, benefits = spec))
        assertNull(BridgeProtocol.parse(unreadable.replace("navigation", "secret"), "n", pageUrl = page, benefits = spec))
        assertNull(BridgeProtocol.parse(unreadable.replace("\"more\"", "\"/web/x\""), "n", pageUrl = page, benefits = spec))
    }

    @Test
    fun bindingHashesAreStablePerKeyAndSeparatePrograms() {
        val file = tmp.root.resolve(BindingHasher.FILE_NAME)
        val first = BindingHasher.fromFile(file)
        val again = BindingHasher.fromFile(file)
        assertEquals(first.hash("chase", "ctl-alpha"), again.hash("chase", "ctl-alpha"))
        assertNotEquals(first.hash("chase", "ctl-alpha"), first.hash("amex", "ctl-alpha"))
        assertNotEquals(first.hash("chase", "ctl-alpha"), BindingHasher(ByteArray(32) { 1 }).hash("chase", "ctl-alpha"))
        assertTrue(Regex("^[0-9a-f]{64}$").matches(first.hash("chase", "ctl-alpha")))
        assertFalse(first.hash("chase", "ctl-alpha").contains("alpha"))
    }
}
