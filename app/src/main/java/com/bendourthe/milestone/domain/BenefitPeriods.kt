package com.bendourthe.milestone.domain

import java.time.LocalDate
import java.time.Month
import java.time.YearMonth

/** A credit period's last day and length, derived from the label the issuer shows beside it. */
data class BenefitPeriod(val end: LocalDate, val length: PeriodLength)

/**
 * Turns a period label into an end date, anchored to the date the benefits were read (plan 5.1). Recognized forms:
 * one month ("Nov", "November"), ending on that month's last day in the read date's year; a month range ("Jul - Dec",
 * "Jan - Jun"), the range containing or following the read date, ending on the last day of its last month; and a
 * yearly label ("Annual", "Yearly", "Calendar year"), ending on December 31 of the read date's year. Anything else is
 * null, so that credit never gets a reminder from a guessed date.
 */
object BenefitPeriods {
    private val MONTHS = Month.entries.associateBy { it.name.take(3).lowercase() }
    private val MONTH = "([A-Za-z]{3,9})\\.?"
    private val SINGLE = Regex("^$MONTH$")
    private val RANGE = Regex("^$MONTH\\s*(?:-|\\u2013|\\u2014|to)\\s*$MONTH$", RegexOption.IGNORE_CASE)
    private val YEARLY = Regex("^(annual|annually|yearly|per year|calendar year|each year)$", RegexOption.IGNORE_CASE)

    fun parse(label: String?, readDate: LocalDate): BenefitPeriod? {
        val text = label?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (YEARLY.matches(text)) return BenefitPeriod(LocalDate.of(readDate.year, 12, 31), PeriodLength.YEARLY)
        SINGLE.matchEntire(text)?.let { match ->
            val month = month(match.groupValues[1]) ?: return null
            return BenefitPeriod(YearMonth.of(readDate.year, month).atEndOfMonth(), PeriodLength.MONTHLY)
        }
        val range = RANGE.matchEntire(text) ?: return null
        val first = month(range.groupValues[1]) ?: return null
        val last = month(range.groupValues[2]) ?: return null
        val months = Math.floorMod(last.value - first.value, 12) + 1
        val end = (readDate.year - 1..readDate.year + 1).asSequence()
            .map { year -> YearMonth.of(year, first).plusMonths(months - 1L).atEndOfMonth() }
            .first { !it.isBefore(readDate) }
        return BenefitPeriod(end, lengthOf(months))
    }

    /** Full names and three-letter abbreviations, so "Sept" and "September" both read as September. */
    private fun month(word: String): Month? {
        val lower = word.lowercase()
        val month = MONTHS[lower.take(3)] ?: return null
        return month.takeIf { month.name.lowercase().startsWith(lower) || lower == "sept" }
    }

    private fun lengthOf(months: Int): PeriodLength = when {
        months <= 1 -> PeriodLength.MONTHLY
        months <= 3 -> PeriodLength.QUARTERLY
        months <= 6 -> PeriodLength.HALF_YEAR
        else -> PeriodLength.YEARLY
    }
}
