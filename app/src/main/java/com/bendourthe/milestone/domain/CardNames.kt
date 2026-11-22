package com.bendourthe.milestone.domain

import java.util.Locale

/**
 * How a card's name is shown: the product name only, in capitalized words. A card the product catalog recognizes
 * ([CardProducts.match]) shows the catalog's product name, since an issuer's own label can be shorter than the product
 * (Chase lists a Sapphire Preferred as "CHASE SAPPHIRE"); any other card shows the issuer's name. Either way the
 * issuer's own name, a leading "The" and a trailing "Card", "Credit Card" or "Card from" are dropped ("The Platinum
 * Card from American Express" shows as "Platinum"), and a name written in capitals is recased. Display only: the
 * stored name stays as read, because artwork matching and card identity use it.
 */
object CardNames {
    private val ISSUER_WORDS = listOf("american express", "amex", "chase", "capital one", "citi", "barclays", "wells fargo", "bank of america")
    private val ACRONYMS = setOf("IHG", "MGM", "AARP", "USAA", "JP", "J.P.", "AA", "UA", "BJ'S")
    private val SMALL = setOf("of", "and", "the", "by", "for", "at")
    private val SPACES = Regex("\\s+")
    private val LEADING_THE = Regex("(?i)^the\\s+")
    private val TRAILING_CARD = Regex("(?i)(?:\\s+credit)?\\s+card(?:\\s+from)?$|\\s+from$")

    /** [programId] finds the catalog product; [issuer] is the program's own name, also dropped from the result. */
    fun display(programId: String, issuer: String, name: String): String {
        val source = CardProducts.match(programId, name)?.name ?: name
        return clean(issuer, source).ifEmpty { name.trim() }
    }

    private fun clean(issuer: String, name: String): String {
        val issuers = (ISSUER_WORDS + issuer.lowercase(Locale.ROOT)).filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }
        var text = name
        issuers.forEach { words -> text = text.replace(Regex("(?i)(?<![\\p{L}])" + Regex.escape(words) + "(?![\\p{L}])"), " ") }
        text = text.replace(SPACES, " ").trim()
        // "Card from" is followed by the issuer, already removed; repeat so "Credit Card from" also goes.
        repeat(2) { text = text.replace(TRAILING_CARD, "").trim() }
        text = text.replace(LEADING_THE, "").trim()
        if (text.isEmpty()) return ""
        return if (text.none(Char::isLowerCase)) recase(text) else text
    }

    private fun recase(text: String): String = text.split(' ').mapIndexed { index, word ->
        val lower = word.lowercase(Locale.ROOT)
        when {
            word.uppercase(Locale.ROOT) in ACRONYMS -> word
            index > 0 && lower in SMALL -> lower
            else -> lower.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }.joinToString(" ")
}
