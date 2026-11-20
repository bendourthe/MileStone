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
                    {"text":"Synthetic tracker","value":null,"max":null}]""",
            ),
        )!!
        assertTrue(drafts.all { it.section == BenefitSection.SPEND_PROGRESS })
        assertEquals(listOf(150_000L, 2_000_000L, null, null), drafts.map { it.earnedCents })
        assertEquals(listOf(400_000L, 7_500_000L, 600_000L, null), drafts.map { it.targetCents })
        assertEquals(listOf(250_000L, 5_500_000L, null, null), drafts.map { it.remainingCents })
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

        val unreadable = """{"type":"chaseUnreadable","nonce":"n","why":"navigation","step":"more"}"""
        assertEquals(BridgeMessage.ChaseUnreadable("navigation", "more"), BridgeProtocol.parse(unreadable, "n", pageUrl = page, benefits = spec))
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
