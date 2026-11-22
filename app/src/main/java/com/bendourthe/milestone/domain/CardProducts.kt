package com.bendourthe.milestone.domain

/**
 * Public card products of the issuers MileStone reads, from the issuers' own product pages (researched 2026-11-20).
 * A product decides a card's Personal or Business section and its offline artwork when the issuer's card name
 * contains every word of one of its keyword sets. Generated from the research catalog; owner data never defines it.
 */
object CardProducts {
    data class Product(val programId: String, val name: String, val segment: CardSegment, val keywords: List<List<String>>, val art: String)

    val all: List<Product> = listOf(
        Product("chase", "Chase Sapphire Reserve", CardSegment.PERSONAL, listOf(listOf("sapphire", "reserve")), "card_chase_sapphire_reserve"),
        // Chase lists a Sapphire Preferred card as "CHASE SAPPHIRE" in its card selector, so "sapphire" alone is this
        // product; "sapphire" with "reserve" scores higher and stays the Reserve.
        Product("chase", "Chase Sapphire Preferred", CardSegment.PERSONAL, listOf(listOf("sapphire", "preferred"), listOf("sapphire")), "card_chase_sapphire_preferred"),
        Product("chase", "Chase Freedom Unlimited", CardSegment.PERSONAL, listOf(listOf("freedom", "unlimited")), "card_chase_freedom_unlimited"),
        Product("chase", "Chase Freedom Flex", CardSegment.PERSONAL, listOf(listOf("freedom", "flex")), "card_chase_freedom_flex"),
        Product("chase", "Chase Freedom Rise", CardSegment.PERSONAL, listOf(listOf("freedom", "rise")), "card_chase_freedom_rise"),
        Product("chase", "Slate", CardSegment.PERSONAL, listOf(listOf("slate")), "card_chase_slate"),
        Product("chase", "United Gateway Card", CardSegment.PERSONAL, listOf(listOf("united", "gateway")), "card_chase_united_gateway"),
        Product("chase", "United Explorer Card", CardSegment.PERSONAL, listOf(listOf("united", "explorer")), "card_chase_united_explorer"),
        Product("chase", "United Quest Card", CardSegment.PERSONAL, listOf(listOf("united", "quest")), "card_chase_united_quest"),
        Product("chase", "United Club Infinite Card", CardSegment.PERSONAL, listOf(listOf("united", "club", "infinite"), listOf("united", "club")), "card_chase_united_club_infinite"),
        Product("chase", "Southwest Rapid Rewards Plus Credit Card", CardSegment.PERSONAL, listOf(listOf("southwest", "plus")), "card_chase_southwest_plus"),
        Product("chase", "Southwest Rapid Rewards Priority Credit Card", CardSegment.PERSONAL, listOf(listOf("southwest", "priority")), "card_chase_southwest_priority"),
        Product("chase", "Southwest Rapid Rewards Premier Credit Card", CardSegment.PERSONAL, listOf(listOf("southwest", "premier")), "card_chase_southwest_premier"),
        Product("chase", "Marriott Bonvoy Boundless Credit Card", CardSegment.PERSONAL, listOf(listOf("marriott", "boundless"), listOf("bonvoy", "boundless")), "card_chase_marriott_bonvoy_boundless"),
        Product("chase", "Marriott Bonvoy Bountiful Credit Card", CardSegment.PERSONAL, listOf(listOf("marriott", "bountiful"), listOf("bonvoy", "bountiful")), "card_chase_marriott_bonvoy_bountiful"),
        Product("chase", "Marriott Bonvoy Bold Credit Card", CardSegment.PERSONAL, listOf(listOf("marriott", "bold"), listOf("bonvoy", "bold")), "card_chase_marriott_bonvoy_bold"),
        Product("chase", "IHG One Rewards Premier Credit Card", CardSegment.PERSONAL, listOf(listOf("ihg", "premier")), "card_chase_ihg_one_rewards_premier"),
        Product("chase", "IHG One Rewards Traveler Credit Card", CardSegment.PERSONAL, listOf(listOf("ihg", "traveler")), "card_chase_ihg_one_rewards_traveler"),
        Product("chase", "World of Hyatt Credit Card", CardSegment.PERSONAL, listOf(listOf("hyatt")), "card_chase_world_of_hyatt"),
        Product("chase", "Aeroplan Credit Card", CardSegment.PERSONAL, listOf(listOf("aeroplan")), "card_chase_aeroplan"),
        Product("chase", "British Airways Visa Signature Card", CardSegment.PERSONAL, listOf(listOf("british", "airways")), "card_chase_british_airways"),
        Product("chase", "Aer Lingus Visa Signature Card", CardSegment.PERSONAL, listOf(listOf("aer", "lingus")), "card_chase_aer_lingus"),
        Product("chase", "Iberia Visa Signature Card", CardSegment.PERSONAL, listOf(listOf("iberia")), "card_chase_iberia"),
        Product("chase", "Disney Inspire Visa Card", CardSegment.PERSONAL, listOf(listOf("disney", "inspire")), "card_chase_disney_inspire"),
        Product("chase", "Disney Premier Visa Card", CardSegment.PERSONAL, listOf(listOf("disney", "premier")), "card_chase_disney_premier"),
        Product("chase", "Disney Visa Card", CardSegment.PERSONAL, listOf(listOf("disney")), "card_chase_disney_visa"),
        Product("chase", "Prime Visa", CardSegment.PERSONAL, listOf(listOf("prime", "visa"), listOf("amazon", "prime")), "card_chase_amazon_prime_visa"),
        Product("chase", "Instacart Mastercard", CardSegment.PERSONAL, listOf(listOf("instacart")), "card_chase_instacart"),
        Product("chase", "DoorDash Rewards Mastercard", CardSegment.PERSONAL, listOf(listOf("doordash")), "card_chase_doordash_rewards"),
        Product("chase", "Ink Business Preferred", CardSegment.BUSINESS, listOf(listOf("ink", "business", "preferred"), listOf("ink", "preferred")), "card_chase_ink_business_preferred"),
        Product("chase", "Ink Business Cash", CardSegment.BUSINESS, listOf(listOf("ink", "business", "cash"), listOf("ink", "cash")), "card_chase_ink_business_cash"),
        Product("chase", "Ink Business Unlimited", CardSegment.BUSINESS, listOf(listOf("ink", "business", "unlimited"), listOf("ink", "unlimited")), "card_chase_ink_business_unlimited"),
        Product("chase", "Ink Business Premier", CardSegment.BUSINESS, listOf(listOf("ink", "business", "premier"), listOf("ink", "premier")), "card_chase_ink_business_premier"),
        Product("chase", "Sapphire Reserve for Business Credit Card", CardSegment.BUSINESS, listOf(listOf("sapphire", "reserve", "business")), "card_chase_sapphire_reserve_for_business"),
        Product("chase", "United Business Card", CardSegment.BUSINESS, listOf(listOf("united", "business")), "card_chase_united_business"),
        Product("chase", "United Club Business Card", CardSegment.BUSINESS, listOf(listOf("united", "club", "business")), "card_chase_united_club_business"),
        Product("chase", "Southwest Rapid Rewards Premier Business Credit Card", CardSegment.BUSINESS, listOf(listOf("southwest", "premier", "business")), "card_chase_southwest_premier_business"),
        Product("chase", "Southwest Rapid Rewards Performance Business Credit Card", CardSegment.BUSINESS, listOf(listOf("southwest", "performance", "business")), "card_chase_southwest_performance_business"),
        Product("chase", "IHG One Rewards Premier Business Credit Card", CardSegment.BUSINESS, listOf(listOf("ihg", "premier", "business"), listOf("ihg", "business")), "card_chase_ihg_one_rewards_premier_business"),
        Product("chase", "World of Hyatt Business Credit Card", CardSegment.BUSINESS, listOf(listOf("hyatt", "business")), "card_chase_world_of_hyatt_business"),
        Product("amex", "The Platinum Card from American Express", CardSegment.PERSONAL, listOf(listOf("platinum")), "card_amex_platinum_card"),
        Product("amex", "American Express Gold Card", CardSegment.PERSONAL, listOf(listOf("gold", "card"), listOf("amex", "gold")), "card_amex_gold_card"),
        Product("amex", "American Express Green Card", CardSegment.PERSONAL, listOf(listOf("green", "card"), listOf("amex", "green")), "card_amex_green_card"),
        Product("amex", "Blue Cash Everyday Card from American Express", CardSegment.PERSONAL, listOf(listOf("blue", "cash", "everyday")), "card_amex_blue_cash_everyday"),
        Product("amex", "Blue Cash Preferred Card from American Express", CardSegment.PERSONAL, listOf(listOf("blue", "cash", "preferred")), "card_amex_blue_cash_preferred"),
        Product("amex", "Delta SkyMiles Blue American Express Card", CardSegment.PERSONAL, listOf(listOf("delta", "blue")), "card_amex_delta_skymiles_blue"),
        Product("amex", "Delta SkyMiles Gold American Express Card", CardSegment.PERSONAL, listOf(listOf("delta", "gold")), "card_amex_delta_skymiles_gold"),
        Product("amex", "Delta SkyMiles Platinum American Express Card", CardSegment.PERSONAL, listOf(listOf("delta", "platinum")), "card_amex_delta_skymiles_platinum"),
        Product("amex", "Delta SkyMiles Reserve American Express Card", CardSegment.PERSONAL, listOf(listOf("delta", "reserve")), "card_amex_delta_skymiles_reserve"),
        Product("amex", "Hilton Honors American Express Card", CardSegment.PERSONAL, listOf(listOf("hilton", "honors")), "card_amex_hilton_honors"),
        Product("amex", "Hilton Honors American Express Surpass Card", CardSegment.PERSONAL, listOf(listOf("hilton", "surpass")), "card_amex_hilton_honors_surpass"),
        Product("amex", "Hilton Honors American Express Aspire Card", CardSegment.PERSONAL, listOf(listOf("hilton", "aspire")), "card_amex_hilton_honors_aspire"),
        Product("amex", "Marriott Bonvoy Brilliant American Express Card", CardSegment.PERSONAL, listOf(listOf("marriott", "brilliant"), listOf("bonvoy", "brilliant")), "card_amex_marriott_bonvoy_brilliant"),
        Product("amex", "Marriott Bonvoy Bevy American Express Card", CardSegment.PERSONAL, listOf(listOf("marriott", "bevy"), listOf("bonvoy", "bevy")), "card_amex_marriott_bonvoy_bevy"),
        Product("amex", "The Business Platinum Card from American Express", CardSegment.BUSINESS, listOf(listOf("business", "platinum")), "card_amex_business_platinum"),
        Product("amex", "American Express Business Gold Card", CardSegment.BUSINESS, listOf(listOf("business", "gold")), "card_amex_business_gold"),
        Product("amex", "The Blue Business Plus Credit Card from American Express", CardSegment.BUSINESS, listOf(listOf("blue", "business", "plus")), "card_amex_blue_business_plus"),
        Product("amex", "Blue Business Cash Card from American Express", CardSegment.BUSINESS, listOf(listOf("blue", "business", "cash")), "card_amex_blue_business_cash"),
        Product("amex", "American Express Business Green Rewards Card", CardSegment.BUSINESS, listOf(listOf("business", "green")), "card_amex_business_green_rewards"),
        Product("amex", "Delta SkyMiles Gold Business American Express Card", CardSegment.BUSINESS, listOf(listOf("delta", "gold", "business")), "card_amex_delta_skymiles_gold_business"),
        Product("amex", "Delta SkyMiles Platinum Business American Express Card", CardSegment.BUSINESS, listOf(listOf("delta", "platinum", "business")), "card_amex_delta_skymiles_platinum_business"),
        Product("amex", "Delta SkyMiles Reserve Business American Express Card", CardSegment.BUSINESS, listOf(listOf("delta", "reserve", "business")), "card_amex_delta_skymiles_reserve_business"),
        Product("amex", "Hilton Honors American Express Business Card", CardSegment.BUSINESS, listOf(listOf("hilton", "business")), "card_amex_hilton_honors_business"),
        Product("amex", "Marriott Bonvoy Business American Express Card", CardSegment.BUSINESS, listOf(listOf("marriott", "business"), listOf("bonvoy", "business")), "card_amex_marriott_bonvoy_business"),
        Product("chase", "Chase Freedom", CardSegment.PERSONAL, listOf(listOf("freedom")), "card_chase_freedom"),
        Product("chase", "Ink Business Plus", CardSegment.BUSINESS, listOf(listOf("ink", "plus")), "card_chase_ink_business_plus"),
        Product("chase", "Ink Business Bold", CardSegment.BUSINESS, listOf(listOf("ink", "bold")), "card_chase_ink_business_bold"),
        Product("chase", "Amazon Rewards Visa Signature", CardSegment.PERSONAL, listOf(listOf("amazon")), "card_chase_amazon_rewards_visa_signature"),
        Product("chase", "Marriott Rewards Premier Plus", CardSegment.PERSONAL, listOf(listOf("marriott", "premier")), "card_chase_marriott_rewards_premier_plus"),
    )

    private val WORD = Regex("[a-z0-9]+")

    /**
     * The product whose longest keyword set the name contains in full; more and longer words win, so a business
     * variant ("delta gold business") beats its personal sibling. A name that says Business never matches a personal product.
     */
    fun match(programId: String, name: String): Product? {
        val words = WORD.findAll(name.lowercase()).map { it.value }.toSet()
        val business = "business" in words || words.firstOrNull() == "ink"
        return all.asSequence()
            .filter { it.programId == programId && !(business && it.segment == CardSegment.PERSONAL) }
            .mapNotNull { product -> product.keywords.filter { words.containsAll(it) }.maxOfOrNull(::score)?.let { product to it } }
            .maxByOrNull { it.second }?.first
    }

    private fun score(set: List<String>) = set.size * 100 + set.sumOf { it.length }
}