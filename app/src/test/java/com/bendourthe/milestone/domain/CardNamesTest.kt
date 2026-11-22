package com.bendourthe.milestone.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CardNamesTest {
    @Test
    fun showsTheProductNameOnlyInCapitalizedWords() {
        assertEquals("Sapphire Preferred", CardNames.display("Chase", "CHASE SAPPHIRE PREFERRED"))
        assertEquals("Ink Business Cash", CardNames.display("Chase", "INK BUSINESS CASH"))
        assertEquals("IHG One Rewards Premier", CardNames.display("Chase", "IHG ONE REWARDS PREMIER"))
        assertEquals("World of Hyatt", CardNames.display("Chase", "WORLD OF HYATT"))
        assertEquals("Hilton Honors Surpass", CardNames.display("American Express", "Hilton Honors American Express Surpass Card"))
        assertEquals("Platinum", CardNames.display("amex", "Platinum Card"))
        assertEquals("Delta SkyMiles Gold", CardNames.display("American Express", "Delta SkyMiles Gold American Express Card"))
    }

    @Test
    fun aNameThatIsOnlyTheIssuerStaysAsRead() {
        assertEquals("Chase", CardNames.display("Chase", "Chase"))
        assertEquals("Card", CardNames.display("amex", "Card"))
    }

    @Test
    fun aCustomNameIsTrimmedCappedAndBlankMeansTheIssuersName() {
        assertEquals("Sapphire Preferred", BenefitsDisplayPreferences.cleanCustomName("  Sapphire   Preferred \n"))
        assertEquals(null, BenefitsDisplayPreferences.cleanCustomName("   "))
        assertEquals(null, BenefitsDisplayPreferences.cleanCustomName(null))
        assertEquals(BenefitsDisplayPreferences.MAX_CUSTOM_NAME, BenefitsDisplayPreferences.cleanCustomName("x".repeat(90))!!.length)
    }

    @Test
    fun aCustomNameIsShownInsteadOfTheIssuersOnTheTileAndThePage() {
        val key = CardKey("chase", "1111")
        val tile = com.bendourthe.milestone.ui.benefits.CardTileUi(key, "CHASE SAPPHIRE", "", "", null, "", null, programName = "Chase")
        assertEquals("Sapphire", tile.displayName)
        assertEquals("Sapphire Preferred", tile.copy(customName = "Sapphire Preferred").displayName)
        val page = com.bendourthe.milestone.ui.benefits.CardPageUi("CHASE SAPPHIRE", "", null, emptyList(),
            preferences = BenefitsDisplayPreferences(customName = "Sapphire Preferred"))
        assertEquals("Sapphire Preferred", page.displayName)
    }
}
