package com.bendourthe.milestone.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardNamesTest {
    @Test
    fun aCardTheCatalogKnowsShowsTheCatalogsProductName() {
        // Chase's card selector calls a Sapphire Preferred "CHASE SAPPHIRE".
        assertEquals("Sapphire Preferred", CardNames.display("chase", "Chase", "CHASE SAPPHIRE"))
        assertEquals("Sapphire Preferred", CardNames.display("chase", "Chase", "CHASE SAPPHIRE PREFERRED"))
        assertEquals("Sapphire Reserve", CardNames.display("chase", "Chase", "Sapphire Reserve"))
        assertEquals("Ink Business Preferred", CardNames.display("chase", "Chase", "Ink Preferred"))
        assertEquals("IHG One Rewards Premier", CardNames.display("chase", "Chase", "IHG ONE REWARDS PREMIER"))
        assertEquals("World of Hyatt", CardNames.display("chase", "Chase", "WORLD OF HYATT"))
        assertEquals("Hilton Honors Surpass", CardNames.display("amex", "American Express", "Hilton Honors Surpass Card"))
        assertEquals("Platinum", CardNames.display("amex", "American Express", "Platinum Card"))
        assertEquals("Business Platinum", CardNames.display("amex", "American Express", "Business Platinum Card"))
        assertEquals("Delta SkyMiles Gold Business", CardNames.display("amex", "American Express", "Delta Gold Business Card"))
    }

    @Test
    fun aCardTheCatalogDoesNotKnowShowsTheIssuersNameCleaned() {
        assertEquals("Mystery Rewards", CardNames.display("chase", "Chase", "CHASE MYSTERY REWARDS"))
        assertEquals("Chase", CardNames.display("chase", "Chase", "Chase"))
        assertEquals("Card", CardNames.display("amex", "American Express", "Card"))
    }

    @Test
    fun everyCatalogProductHasAShortNameWithoutTheIssuerOrCard() {
        CardProducts.all.forEach { product ->
            val shown = CardNames.display(product.programId, "", product.name)
            assertTrue(product.name, shown.isNotBlank())
            assertFalse(product.name, Regex("(?i)\\b(card|american express|chase)\\b|^the\\b|\\bfrom$").containsMatchIn(shown))
        }
    }
}
