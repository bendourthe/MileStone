package com.bendourthe.milestone.domain

import java.util.Locale

/**
 * How a card's name is shown: the product name only, in capitalized words. The issuer's own name and a trailing
 * "Card" are dropped ("CHASE SAPPHIRE PREFERRED" shows as "Sapphire Preferred", "Hilton Honors American Express
 * Surpass Card" as "Hilton Honors Surpass"), and a name the issuer writes in capitals is recased. Display only: the
 * stored name stays as read, because artwork matching and card identity use it.
 */
object CardNames {
    private val ISSUER_WORDS = listOf("american express", "amex", "chase", "capital one", "citi", "barclays", "wells fargo", "bank of america")
    private val ACRONYMS = setOf("IHG", "MGM", "AARP", "USAA", "JP", "J.P.", "AA", "UA", "BJ'S")
    private val SMALL = setOf("of", "and", "the", "by", "for", "at")
    private val SPACES = Regex("\\s+")

    fun display(issuer: String, name: String): String {
        val issuers = (ISSUER_WORDS + issuer.lowercase(Locale.ROOT)).distinct().sortedByDescending { it.length }
        var text = name
        issuers.forEach { words -> text = text.replace(Regex("(?i)(?<![\\p{L}])" + Regex.escape(words) + "(?![\\p{L}])"), " ") }
        text = text.replace(SPACES, " ").trim().removeSuffix(" Card").removeSuffix(" CARD").removeSuffix(" card").trim()
        if (text.isEmpty()) return name.trim()
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
