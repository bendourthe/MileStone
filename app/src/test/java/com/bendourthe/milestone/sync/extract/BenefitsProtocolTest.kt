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

    private val readyJson = """{"kind":"activity","trackers":2,"listed":0,"waitedMs":2400,"bottom":false,"activityLink":false,"viewAllLink":true,"switched":false,"reason":"stable"}"""

    private fun withReady(ready: String) = benefitsJson.replace("\"listed\":[{", "\"ready\":$ready,\"listed\":[{")

    @Test
    fun pageDiagnosticsAreParsedAndLoggedAsCountsOnly() {
        val message = parse(withReady(readyJson), activity) as BridgeMessage.Benefits
        val ready = message.ready!!
        assertEquals("activity" to 2400L, ready.kind to ready.waitedMs)
        val line = ready.logLine()
        assertEquals(
            "kind=activity trackers=2 listed=0 waited=2400 bottom=false activity=false viewAll=true switched=false reason=stable",
            line,
        )
        // The message carries card digits and titles; the log line must hold none of them.
        listOf("71008", "Lounge Access", "Purchase Protection", "Congratulations", "$").forEach { assertFalse(it, line.contains(it)) }
    }

    @Test
    fun malformedDiagnosticsDropOnlyTheDiagnosticsNeverTheBenefits() {
        listOf(
            readyJson.replace(",\"reason\":\"stable\"", ""),
            readyJson.replace("\"reason\":\"stable\"", "\"reason\":\"stable\",\"lastDigits\":\"71008\""),
            readyJson.replace("\"reason\":\"stable\"", "\"reason\":\"card 71008\""),
            readyJson.replace("\"kind\":\"activity\"", "\"kind\":\"overview\""),
            readyJson.replace("\"trackers\":2", "\"trackers\":2.5"),
            readyJson.replace("\"trackers\":2", "\"trackers\":2e1"),
            readyJson.replace("\"trackers\":2", "\"trackers\":-1"),
            readyJson.replace("\"trackers\":2", "\"trackers\":61"),
            readyJson.replace("\"listed\":0", "\"listed\":151"),
            readyJson.replace("\"waitedMs\":2400", "\"waitedMs\":120001"),
            readyJson.replace("\"trackers\":2", "\"trackers\":\"2\""),
            readyJson.replace("\"bottom\":false", "\"bottom\":\"false\""),
            readyJson.replace("\"bottom\":false", "\"bottom\":0"),
            readyJson.replace("\"bottom\":false", "\"bottom\":{\"x\":1}"),
            "[1,2]",
            "\"stable\"",
            "null",
        ).forEach { bad ->
            val message = parse(withReady(bad), activity) as BridgeMessage.Benefits
            assertNull(bad, message.ready)
            assertEquals(bad, 3, message.drafts.size)
        }
    }

    @Test
    fun unreadablePagesCarryDiagnosticsAndKotlinBuiltOnesHaveNone() {
        val json = """{"type":"benefitsUnreadable","nonce":"n1","why":"timeout","trackers":0,"switcher":1,"ready":${readyJson.replace("stable", "timeout")}}"""
        val message = parse(json, activity) as BridgeMessage.BenefitsUnreadable
        assertEquals("timeout", message.ready?.reason)
        assertNull(BridgeMessage.BenefitsUnreadable("timeout").ready)
    }

    @Test
    fun thePageKindComesFromThePathAndAMismatchIsDropped() {
        assertEquals("rewards", spec.kindOf(rewards))
        assertEquals("activity", spec.kindOf(activity))
        assertEquals("viewAll", spec.kindOf("https://global.americanexpress.com/card-benefits/view-all?account_key=x"))
        assertNull(spec.kindOf("https://global.americanexpress.com/overview"))
        // A message claiming the activity page while the address is Rewards, or the reverse, is dropped.
        assertNull(parse(benefitsJson, rewards))
        assertNull(parse(withReady(readyJson.replace("\"kind\":\"activity\"", "\"kind\":\"rewards\"")), activity))
        val onRewards = """{"type":"benefitsUnreadable","nonce":"n1","why":"timeout","ready":$readyJson}"""
        assertNull(parse(onRewards, rewards))
    }

    @Test
    fun aRewardsMessageCarryingListedBenefitsIsDropped() {
        val listedOnRewards = benefitsJson.replace("\"page\":\"activity\"", "\"page\":\"rewards\"")
        assertNull(parse(listedOnRewards, rewards))
        val withoutList = listedOnRewards.substringBefore(",\"listed\":[") + ",\"listed\":[]}"
        assertEquals("rewards", (parse(withoutList, rewards) as BridgeMessage.Benefits).page)
    }

    @Test
    fun anUnclassifiedPageIsAnUnreadableReason() {
        assertEquals(BridgeMessage.BenefitsUnreadable("page"), parse("""{"type":"benefitsUnreadable","nonce":"n1","why":"page"}"""))
    }

    @Test
    fun benefitsConfigCarriesTheSecondPageDecision() {
        assertTrue(BridgeProtocol.benefitsConfig(spec, "n1", 20_000, listCards = false).contains("\"secondPage\":true"))
        assertTrue(BridgeProtocol.benefitsConfig(spec, "n1", 20_000, listCards = false, secondPage = false).contains("\"secondPage\":false"))
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
        // The word beside the amount wins over a period: a spend threshold is never reminded as a credit.
        assertEquals(BenefitSection.SPEND_PROGRESS, BenefitsReader.section("Track Your Progress", "Bonus", false, "Jan - Mar", "\$4,000 to go", null, "spent"))
        assertEquals(BenefitSection.CREDIT_EARNED, BenefitsReader.section("Track Your Progress", "Stays", false, null, "\$50 to go", 5_000, "earned"))
    }

    @Test
    fun aTrackerLabelIsAcceptedAndUsed() {
        val json = """{"type":"benefits","nonce":"n1","lastDigits":"42005","page":"rewards","trackers":[
            {"group":"Track Your Progress","title":"Bonus","spent":"${'$'}1,000","remaining":"${'$'}4,000 to go","target":5000,"period":"Jan - Mar","label":"spent","done":false,"description":null}
          ],"listed":[]}"""
        val message = parse(json) as BridgeMessage.Benefits
        assertEquals(BenefitSection.SPEND_PROGRESS, message.drafts.single().section)
    }
}
