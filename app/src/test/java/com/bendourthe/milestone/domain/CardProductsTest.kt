package com.bendourthe.milestone.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Matching issuer card names against the public product list: segment and artwork per product. */
class CardProductsTest {
    private fun art(programId: String, name: String) = CardProducts.match(programId, name)?.art

    @Test
    fun theLongestFullyMatchingKeywordSetWins() {
        assertEquals("card_chase_sapphire_reserve", art("chase", "Sapphire Reserve (...1111)"))
        assertEquals("card_chase_sapphire_reserve_for_business", art("chase", "Sapphire Reserve for Business"))
        assertEquals("card_amex_delta_skymiles_gold", art("amex", "Delta SkyMiles® Gold Card"))
        assertEquals("card_amex_delta_skymiles_gold_business", art("amex", "Delta SkyMiles® Gold Business Card"))
        assertEquals("card_amex_business_platinum", art("amex", "Business Platinum Card®"))
        assertEquals("card_amex_platinum_card", art("amex", "The Platinum Card®"))
    }

    @Test
    fun businessProductsAreBusinessAndPersonalOnesPersonal() {
        assertEquals(CardSegment.BUSINESS, CardProducts.match("chase", "Ink Preferred")?.segment)
        assertEquals(CardSegment.BUSINESS, CardProducts.match("chase", "United℠ Business Card")?.segment)
        assertEquals(CardSegment.PERSONAL, CardProducts.match("chase", "Freedom Unlimited®")?.segment)
        assertEquals(CardSegment.PERSONAL, CardProducts.match("amex", "Blue Cash Preferred®")?.segment)
        assertEquals(CardSegment.BUSINESS, CardProducts.match("amex", "Blue Business Plus")?.segment)
    }

    @Test
    fun productsStayWithinTheirIssuerAndUnknownNamesMatchNothing() {
        assertNull(CardProducts.match("amex", "Sapphire Reserve"))
        assertNull(CardProducts.match("chase", "Synthetic Unknown Card"))
        assertNull(CardProducts.match("chase", "Pink Rewards"))
    }

    @Test
    fun olderChaseCardsStillHeldGetTheirOwnArtWithoutOverridingCurrentProducts() {
        assertEquals("card_chase_sapphire_preferred", art("chase", "CHASE SAPPHIRE"))
        assertEquals("card_chase_sapphire_reserve", art("chase", "CHASE SAPPHIRE RESERVE"))
        assertEquals("card_chase_freedom", art("chase", "Chase Freedom"))
        assertEquals("card_chase_freedom_flex", art("chase", "Chase Freedom Flex"))
        assertEquals("card_chase_amazon_prime_visa", art("chase", "Amazon Prime Visa"))
        assertEquals("card_chase_amazon_rewards_visa_signature", art("chase", "Amazon Visa Signature"))
        assertEquals(CardSegment.BUSINESS, CardProducts.match("chase", "Ink Plus")?.segment)
    }

    @Test
    fun everyProductHasBundledArtworkAndAKeywordSet() {
        assertEquals(70, CardProducts.all.size)
        CardProducts.all.forEach { product ->
            assert(product.keywords.isNotEmpty() && product.keywords.all { it.isNotEmpty() }) { product.name }
            assert(product.art.matches(Regex("^card_(chase|amex)_[a-z0-9_]+$"))) { product.art }
        }
    }
}
