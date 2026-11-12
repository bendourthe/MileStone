package com.bendourthe.milestone.sync.extract

import com.bendourthe.milestone.domain.BalanceUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class BalanceParserTest {
    @Test
    fun acceptedForms() {
        mapOf(
            "142,500" to 142_500L,
            "142 500" to 142_500L,
            "142500" to 142_500L,
            "142,500 pts" to 142_500L,
            "  142,500 points " to 142_500L,
            "Miles: 1,234,567" to 1_234_567L,
            "0" to 0L,
            "7" to 7L,
            "42 pts" to 42L,
            "999" to 999L,
            "10,000,000,000" to 10_000_000_000L,
        ).forEach { (raw, expected) -> assertEquals(raw, expected, BalanceParser.parse(raw)) }
    }

    @Test
    fun rejectedForms() {
        listOf(
            null, "", "   ", "--", "N/A", "Loading", "...", "\u2026",
            "142.500", "1,234.56", "12,34,567", "-500", "Balance: -500",
            "10,000,000,001", "99999999999999999999", "142,500 pts and 3 certificates", "Gold", "1,2345",
        ).forEach { raw -> assertEquals("$raw", null, BalanceParser.parse(raw)) }
    }

    @Test
    fun cashFormsParseToCents() {
        mapOf(
            "$4.56" to 456L,
            "$ 4 . 56" to 456L,
            "\$\n  1,234\n  .56" to 123_456L,
            "$0.00" to 0L,
        ).forEach { (raw, expected) -> assertEquals(raw, expected, BalanceParser.parse(raw, BalanceUnit.CASH_CENTS)) }
        listOf(null, "", "4.56", "$4.5", "$4", "-$4.56", "$4.56 and $1.00", "$1,23.45", "Rewards cash").forEach { raw ->
            assertEquals("$raw", null, BalanceParser.parse(raw, BalanceUnit.CASH_CENTS))
        }
    }
}
