package com.bendourthe.milestone.ui.benefits

import com.bendourthe.milestone.domain.*
import org.junit.Assert.*
import org.junit.Test

class BenefitsPresentationTest {
    private val key = CardKey("amex", "1234")
    private fun item(title: String, section: BenefitSection, period: String? = null, done: Boolean = false) =
        assignBenefitKeys(key, listOf(BenefitDraft(section, title, null, null, null, period, done))).single()

    @Test fun duplicateListEntryContributesEnrollmentAndDescriptionWithoutChangingStoredRows() {
        val tracker = item("\$50 Shopping Credit", BenefitSection.CREDIT_EARNED, "Nov")
        val listed = item("Shopping Credit", BenefitSection.LISTED).copy(enrollment = Enrollment.ENROLLMENT_REQUIRED, description = "Use the enrolled offer")
        val source = listOf(tracker, listed)
        val visible = BenefitsPresentation.visible(source)
        assertEquals(1, visible.size)
        assertEquals(tracker.stableKey, visible.single().stableKey)
        assertEquals(Enrollment.ENROLLMENT_REQUIRED, visible.single().enrollment)
        assertEquals(listed.description, visible.single().description)
        assertNull(source.first().enrollment)
        assertEquals(2, source.size)
    }

    @Test fun similarNamesDifferentPeriodsAndOtherCardsRemainVisible() {
        val source = listOf(
            item("Travel Credit", BenefitSection.CREDIT_EARNED, "Nov"),
            item("Travel Credit", BenefitSection.LISTED, "Dec"),
            item("Travel Insurance", BenefitSection.LISTED),
            item("Travel Credit", BenefitSection.LISTED).copy(cardKey = CardKey("amex", "5678")),
        )
        assertEquals(4, BenefitsPresentation.visible(source).size)
    }

    @Test fun ambiguousMultiplePeriodsAndConflictingEnrollmentAreNotHidden() {
        val source = listOf(item("Shopping", BenefitSection.CREDIT_EARNED, "Nov"), item("Shopping", BenefitSection.CREDIT_EARNED, "Dec"), item("Shopping", BenefitSection.LISTED))
        assertEquals(3, BenefitsPresentation.visible(source).size)
        val tracker = source.first().copy(enrollment = Enrollment.ENROLLED)
        val listed = source.last().copy(enrollment = Enrollment.ENROLLMENT_REQUIRED)
        assertEquals(2, BenefitsPresentation.visible(listOf(tracker, listed)).size)
    }

    @Test fun categoriesAndSortingKeepCompletedItemsInTheirCategory() {
        val source = listOf(item("Zebra Credit", BenefitSection.CREDIT_EARNED), item("Alpha Credit", BenefitSection.COMPLETED, done = true), item("Welcome offer", BenefitSection.SPEND_PROGRESS), item("Lounge access", BenefitSection.SPEND_PROGRESS))
        val alphabetical = BenefitsPresentation.groups(source, false)
        assertEquals(BenefitsCategory.entries, alphabetical.keys.toList())
        assertEquals(listOf("Alpha Credit", "Zebra Credit"), alphabetical.getValue(BenefitsCategory.CREDITS).map { it.title })
        assertEquals(listOf("Zebra Credit", "Alpha Credit"), BenefitsPresentation.groups(source, true).getValue(BenefitsCategory.CREDITS).map { it.title })
        assertEquals("Welcome offer", alphabetical.getValue(BenefitsCategory.WELCOME_BONUS).single().title)
        assertEquals("Lounge access", alphabetical.getValue(BenefitsCategory.SPEND_TRACKERS).single().title)
    }

    @Test fun emptyAndDescriptorOnlyTitlesCannotSuppressUnknownBenefits() {
        assertTrue(BenefitsPresentation.groups(emptyList(), false).values.all { it.isEmpty() })
        assertEquals(2, BenefitsPresentation.visible(listOf(item("Credit", BenefitSection.CREDIT_EARNED), item("Credit", BenefitSection.LISTED))).size)
    }

    @Test fun artworkMatchesAllFourProductsAndUnknownProductsUseTheFallback() {
        val images = listOf("Platinum Card", "Business Platinum Card", "Hilton Honors Surpass Card", "Gold Delta SkyMiles")
            .map { cardArtworkResource("amex", it) }
        assertTrue(images.all { it != null })
        assertEquals(4, images.distinct().size)
        assertNull(cardArtworkResource("amex", "Delta Platinum"))
        assertNull(cardArtworkResource("amex", "Hilton Aspire"))
        assertNull(cardArtworkResource("amex", "Unknown Card"))
        assertNull(cardArtworkResource("other", "Platinum Card"))
    }

    @Test fun manualBonusInputRejectsMalformedAndOverflowingValues() {
        assertNull(welcomeBonusInput("1", "0", "", ""))
        assertNull(welcomeBonusInput("-1", "10", "", ""))
        assertNull(welcomeBonusInput("1.001", "10", "", ""))
        assertNull(welcomeBonusInput("1", "9999999999999999999999999", "", ""))
        assertNull(welcomeBonusInput("1", "10", "2027-02-30", ""))
        assertEquals(123L, welcomeBonusInput("1.23", "10", "2027-02-01", "Reward")!!.spentCents)
    }

}
