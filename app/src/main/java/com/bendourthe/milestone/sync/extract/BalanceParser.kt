package com.bendourthe.milestone.sync.extract

import com.bendourthe.milestone.domain.BalanceUnit

/** Parses the trimmed text of a balance element into a point count, or null when it is not exactly one valid number. */
object BalanceParser {
    const val MAX_BALANCE = 10_000_000_000L
    private val placeholders = setOf("--", "n/a", "loading", "...", "\u2026", "-")
    private val numberToken = Regex("""\d[\d, ]*\d|\d""")
    private val grouped = Regex("""\d{1,3}([, ]\d{3})+""")
    private val plain = Regex("""\d+""")

    fun parse(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.lowercase() in placeholders) return null
        if ('.' in text) return null
        val tokens = numberToken.findAll(text).map { it.value.trim(',', ' ') }.filter { it.isNotEmpty() }.toList()
        if (tokens.size != 1) return null
        val token = tokens.single()
        val start = text.indexOf(token)
        if (start > 0 && text[start - 1] == '-') return null
        if (!plain.matches(token) && !grouped.matches(token)) return null
        val value = token.filter(Char::isDigit).toLongOrNull() ?: return null
        return value.takeIf { it <= MAX_BALANCE }
    }

    private val dollars = Regex("""\$(\d{1,3}(?:,\d{3})+|\d+)\.(\d{2})""")

    /** Parses one US dollar amount such as "$1,234.56", ignoring whitespace between its pieces, into cents. */
    fun parseCents(raw: String?): Long? {
        val text = raw?.filterNot(Char::isWhitespace).orEmpty()
        val match = dollars.matchEntire(text) ?: return null
        val cents = (match.groupValues[1].filter(Char::isDigit) + match.groupValues[2]).toLongOrNull() ?: return null
        return cents.takeIf { it <= MAX_BALANCE }
    }

    fun parse(raw: String?, unit: BalanceUnit): Long? = when (unit) {
        BalanceUnit.POINTS -> parse(raw)
        BalanceUnit.CASH_CENTS -> parseCents(raw)
    }
}
