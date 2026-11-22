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
}
