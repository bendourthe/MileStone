package com.bendourthe.milestone.sync.extract

import com.bendourthe.milestone.data.catalog.StaticCatalogRepository
import com.bendourthe.milestone.domain.BenefitSection
import com.bendourthe.milestone.domain.Enrollment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenefitsProtocolTest {
    private val spec = StaticCatalogRepository.AMEX_BENEFITS
    private val rewards = "https://global.americanexpress.com/rewards?inav=x"
    private val activity = "https://global.americanexpress.com/card-benefits/activity?account_key=x"

    private fun parse(json: String, url: String? = rewards) = BridgeProtocol.parse(json, "n1", pageUrl = url, benefits = spec)

    private val cardsJson = """{"type":"cards","nonce":"n1","complete":true,"cards":[{"name":"Platinum Card®","lastDigits":"71008"},{"name":"Hotel Card","lastDigits":"42005"}]}"""

    private val benefitsJson = """{"type":"benefits","nonce":"n1","lastDigits":"71008","page":"activity","trackers":[
        {"group":"Spend Progress (1)","title":"Lounge Access","spent":"${'$'}4,770","remaining":"${'$'}70,230 to go","target":75000,"period":null,"done":false,"description":"d"},
        {"group":"Credit Earned (1)","title":"${'$'}50 Credit","spent":"${'$'}0","remaining":"${'$'}50 to go","target":50,"period":"Oct - Dec","done":false,"description":null},
        {"group":"Completed Benefits (2)","title":"Congratulations!","spent":null,"remaining":null,"target":null,"period":null,"done":false,"description":"summary"}
      ],"listed":[{"key":"purchase-protection","title":"Purchase Protection","description":"Covers purchases","enrollment":"Enrolled"}]}"""

    @Test
    fun cardsAreParsedAndNormalized() {
        val cards = parse(cardsJson) as BridgeMessage.Cards
        assertTrue(cards.list.complete)
        assertEquals(listOf("Platinum Card" to "71008", "Hotel Card" to "42005"), cards.list.cards.map { it.name to it.lastDigits })
    }

    @Test
    fun benefitsAreParsedIntoSectionsAmountsAndTheList() {
        val message = parse(benefitsJson, activity) as BridgeMessage.Benefits
        assertEquals("71008" to "activity", message.lastDigits to message.page)
        assertEquals(
            listOf(BenefitSection.SPEND_PROGRESS, BenefitSection.CREDIT_EARNED, BenefitSection.LISTED),
            message.drafts.map { it.section },
        )
        val lounge = message.drafts[0]
        assertEquals(listOf(477_000L, 7_023_000L, 7_500_000L), listOf(lounge.earnedCents, lounge.remainingCents, lounge.targetCents))
        assertEquals("Oct - Dec", message.drafts[1].periodLabel)
        assertEquals(Enrollment.ENROLLED to "purchase-protection", message.drafts[2].let { it.enrollment to it.issuerKey })
    }

    @Test
    fun benefitsMessagesFromAnyOtherPageOrWithoutASpecAreDropped() {
        listOf(
            "https://global.americanexpress.com/overview",
            "https://global.americanexpress.com/rewardsx",
            "https://www.americanexpress.com/rewards",
            "http://global.americanexpress.com/rewards",
            "https://global.americanexpress.com:8443/rewards",
            "https://evil.example/rewards",
            null,
        ).forEach { assertNull(it, parse(cardsJson, it)) }
        assertNull(BridgeProtocol.parse(cardsJson, "n1", pageUrl = rewards))
        assertNull(BridgeProtocol.parse(cardsJson, "other", pageUrl = rewards, benefits = spec))
    }

    @Test
    fun unknownFieldsWrongTypesAndOverlongTextAreDropped() {
        assertNull(parse(cardsJson.replace("\"complete\":true", "\"complete\":true,\"accountKey\":\"X\"")))
        assertNull(parse(cardsJson.replace("\"lastDigits\":\"42005\"", "\"lastDigits\":\"42005\",\"number\":\"1\"")))
        assertNull(parse(benefitsJson.replace("\"done\":false,\"description\":\"d\"", "\"done\":\"no\",\"description\":\"d\""), activity))
        assertNull(parse(benefitsJson.replace("\"title\":\"Lounge Access\"", "\"title\":\"${"x".repeat(121)}\""), activity))
        assertNull(parse(benefitsJson.replace("\"enrollment\":\"Enrolled\"", "\"enrollment\":\"Maybe\""), activity))
        assertNull(parse(benefitsJson.replace("\"key\":\"purchase-protection\"", "\"key\":\"a/b\""), activity))
        assertNull(parse(benefitsJson.replace("\"page\":\"activity\"", "\"page\":\"other\""), activity))
        assertNull(parse(benefitsJson.replace("\"lastDigits\":\"71008\"", "\"lastDigits\":\"123\""), activity))
    }

    @Test
    fun capsLimitCardsAndBenefits() {
        val card = """{"name":"C","lastDigits":"%05d"}"""
        val many = (1..21).joinToString(",") { card.format(it) }
        assertNull(parse("""{"type":"cards","nonce":"n1","complete":true,"cards":[$many]}"""))
        val listedItem = """{"key":"k%d","title":"T","description":null,"enrollment":null}"""
        val tooMany = (1..151).joinToString(",") { listedItem.format(it) }
        assertNull(parse("""{"type":"benefits","nonce":"n1","lastDigits":"71008","page":"rewards","trackers":[],"listed":[$tooMany]}"""))
        assertNull(BridgeProtocol.parse("x".repeat(300_000), "n1", pageUrl = rewards, benefits = spec))
    }

    @Test
    fun duplicateOrInvalidDigitsMakeTheListIncomplete() {
        val dup = cardsJson.replace("42005", "71008")
        val cards = parse(dup) as BridgeMessage.Cards
        assertFalse(cards.list.complete)
        assertEquals(0, cards.list.cards.size)
    }

    @Test
    fun unreadableReasonsAreAFixedSet() {
        assertEquals(BridgeMessage.BenefitsUnreadable("switch"), parse("""{"type":"benefitsUnreadable","nonce":"n1","why":"switch"}"""))
        assertNull(parse("""{"type":"benefitsUnreadable","nonce":"n1","why":"card 1234"}"""))
    }

    @Test
    fun benefitsConfigCarriesTheSelectorsAndTarget() {
        val config = BridgeProtocol.benefitsConfig(spec, "n1", 30_000, listCards = false, targetDigits = "42005")
        assertTrue(config.contains("\"mode\":\"benefits\"") && config.contains("\"targetDigits\":\"42005\""))
        spec.selectors.keys.forEach { assertTrue(it, config.contains("\"$it\":")) }
    }

    @Test
    fun amountsParseToCents() {
        mapOf(
            "$75.00" to 7_500L, "$71,230 To Go" to 7_123_000L, "$0" to 0L, "$5 earned" to 500L, "$1,234.56" to 123_456L,
            "Earned $15.00 of $15.00" to 1_500L,
        ).forEach { (text, cents) -> assertEquals(text, cents, BenefitAmountParser.parse(text)) }
        listOf(null, "", "no amount", "75", "$").forEach { assertNull(it, BenefitAmountParser.parse(it)) }
    }

    @Test
    fun sectionsFollowGroupsThenTheRewardsGridRules() {
        assertEquals(BenefitSection.COMPLETED, BenefitsReader.section("Completed Benefits (2)", "X", false, null, null, null))
        assertEquals(BenefitSection.CREDIT_EARNED, BenefitsReader.section("Credit Earned (3)", "X", false, null, null, null))
        assertEquals(BenefitSection.SPEND_PROGRESS, BenefitsReader.section("Spend Progress (1)", "X", false, null, null, null))
        assertEquals(BenefitSection.COMPLETED, BenefitsReader.section("Track Your Progress", "X Credit", true, "Nov", null, null))
        assertEquals(BenefitSection.CREDIT_EARNED, BenefitsReader.section("Track Your Progress", "Hotel Credit", false, null, "$50 to go", 5_000))
        assertEquals(BenefitSection.CREDIT_EARNED, BenefitsReader.section(null, "Stays", false, "Jan - Mar", null, null))
        assertEquals(BenefitSection.SPEND_PROGRESS, BenefitsReader.section(null, "Lounge Access", false, null, "$70,230 to go", null))
        assertEquals(BenefitSection.OTHER, BenefitsReader.section(null, "Something", false, null, null, null))
    }
}
