package com.bendourthe.milestone.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenefitsTest {
    private val card = CardKey("amex", "1008")

    private fun draft(title: String, section: BenefitSection = BenefitSection.CREDIT_EARNED, period: String? = "Jul - Dec") =
        BenefitDraft(section, title, null, null, null, period, completed = section == BenefitSection.COMPLETED)

    @Test
    fun lastDigitsAreFourOrFiveDigits() {
        listOf("1008", "21005").forEach { assertTrue(it, CardKey.isValidLastDigits(it)) }
        listOf("", "123", "123456", "10O8", "1 08", "-1008").forEach { assertFalse(it, CardKey.isValidLastDigits(it)) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun cardKeyRejectsInvalidDigits() {
        CardKey("amex", "123")
    }

    @Test
    fun cardNamesLoseTrademarkSignsAndExtraSpaces() {
        assertEquals("Platinum Card", normalizeCardName("  Platinum Card® "))
        assertEquals("Delta SkyMiles Gold Card", normalizeCardName("Delta SkyMiles®  Gold™ Card"))
    }

    @Test
    fun invalidDigitsAreDroppedAndMarkTheListIncomplete() {
        val list = CardList.from(listOf(CardDraft("Gold", "1008"), CardDraft("Odd", "12")), readComplete = true)
        assertEquals(listOf("1008"), list.cards.map { it.lastDigits })
        assertFalse(list.complete)
    }

    @Test
    fun duplicateDigitsAreNeverMergedAndMarkTheListIncomplete() {
        val list = CardList.from(
            listOf(CardDraft("Gold", "1008"), CardDraft("Blue A", "7777"), CardDraft("Blue B", "7777")),
            readComplete = true,
        )
        assertEquals(listOf("1008"), list.cards.map { it.lastDigits })
        assertFalse(list.complete)
    }

    @Test
    fun aValidListKeepsTheReadsCompleteness() {
        val drafts = listOf(CardDraft("Gold®", "1008"), CardDraft("Platinum", "21005"))
        assertTrue(CardList.from(drafts, readComplete = true).complete)
        assertFalse(CardList.from(drafts, readComplete = false).complete)
        assertEquals("Gold", CardList.from(drafts, readComplete = true).cards.first().name)
    }

    @Test
    fun stableKeyIgnoresSectionSoAUsedCreditKeepsItsKey() {
        val earned = assignBenefitKeys(card, listOf(draft("Dining Credit"))).single()
        val used = assignBenefitKeys(card, listOf(draft("Dining Credit", BenefitSection.COMPLETED))).single()
        assertEquals(earned.stableKey, used.stableKey)
    }

    @Test
    fun stableKeyIgnoresCaseSpacingAndTrademarksButNotThePeriod() {
        val a = assignBenefitKeys(card, listOf(draft("Uber™  Cash"))).single()
        val b = assignBenefitKeys(card, listOf(draft("uber cash "))).single()
        val c = assignBenefitKeys(card, listOf(draft("Uber Cash", period = "Nov"))).single()
        assertEquals(a.stableKey, b.stableKey)
        assertNotEquals(a.stableKey, c.stableKey)
        assertEquals("Uber Cash", a.title)
    }

    @Test
    fun stableKeyDependsOnTheCard() {
        val other = CardKey("amex", "21005")
        assertNotEquals(
            assignBenefitKeys(card, listOf(draft("Dining Credit"))).single().stableKey,
            assignBenefitKeys(other, listOf(draft("Dining Credit"))).single().stableKey,
        )
    }

    @Test
    fun listedBenefitsAreKeyedByIssuerKeyAndNeverCollideWithTrackers() {
        val tracker = assignBenefitKeys(card, listOf(draft("Uber One Credit"))).single()
        val listed = BenefitDraft(
            BenefitSection.LISTED, "Uber One Credit", null, null, null, null, completed = false,
            description = "  Up to \$120®  a year ", enrollment = Enrollment.ENROLLMENT_REQUIRED, issuerKey = "Uber-One-Credit",
        )
        val renamed = listed.copy(title = "Uber One Membership Credit")
        val keyed = assignBenefitKeys(card, listOf(listed)).single()

        assertNotEquals(tracker.stableKey, keyed.stableKey)
        assertEquals(keyed.stableKey, assignBenefitKeys(card, listOf(renamed)).single().stableKey)
        assertEquals("Up to \$120 a year", keyed.description)
        assertEquals(Enrollment.ENROLLMENT_REQUIRED, keyed.enrollment)
    }

    @Test
    fun identicalTitlesAreToldApartByPositionAndReorderingKeepsKeys() {
        val first = assignBenefitKeys(card, listOf(draft("Credit"), draft("Other"), draft("Credit")))
        assertEquals(listOf(0, 0, 1), first.map { it.position })
        assertEquals(3, first.map { it.stableKey }.toSet().size)

        val reordered = assignBenefitKeys(card, listOf(draft("Other"), draft("Credit"), draft("Credit")))
        assertEquals(first.map { it.stableKey }.toSet(), reordered.map { it.stableKey }.toSet())
    }
}
