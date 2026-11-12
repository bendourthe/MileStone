package com.bendourthe.milestone.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BenefitPeriodsTest {
    private val nov12 = LocalDate.of(2026, 11, 12)

    private fun period(label: String?, read: LocalDate = nov12) = BenefitPeriods.parse(label, read)

    @Test
    fun singleMonthsEndOnThatMonthsLastDay() {
        assertEquals(BenefitPeriod(LocalDate.of(2026, 11, 30), PeriodLength.MONTHLY), period("Nov"))
        assertEquals(BenefitPeriod(LocalDate.of(2026, 11, 30), PeriodLength.MONTHLY), period("November"))
        assertEquals(BenefitPeriod(LocalDate.of(2026, 9, 30), PeriodLength.MONTHLY), period("September"))
        assertEquals(LocalDate.of(2026, 9, 30), period("Sept")?.end)
        assertEquals(LocalDate.of(2028, 2, 29), period("Feb", LocalDate.of(2028, 2, 10))?.end)
    }

    @Test
    fun rangesSeenInTheCaptureEndOnTheirLastMonth() {
        assertEquals(BenefitPeriod(LocalDate.of(2026, 12, 31), PeriodLength.HALF_YEAR), period("Jul - Dec"))
        assertEquals(BenefitPeriod(LocalDate.of(2026, 12, 31), PeriodLength.QUARTERLY), period("Oct - Dec"))
        assertEquals(BenefitPeriod(LocalDate.of(2027, 3, 31), PeriodLength.QUARTERLY), period("Jan - Mar"))
        assertEquals(LocalDate.of(2026, 12, 31), period("Oct – Dec")?.end)
        assertEquals(LocalDate.of(2026, 12, 31), period("oct to dec")?.end)
    }

    @Test
    fun aRangeAcrossTheYearBoundaryIsTheOneContainingOrFollowingTheReadDate() {
        assertEquals(LocalDate.of(2027, 6, 30), period("Jan - Jun", LocalDate.of(2026, 12, 20))?.end)
        assertEquals(LocalDate.of(2027, 1, 31), period("Nov - Jan", LocalDate.of(2026, 12, 20))?.end)
        assertEquals(PeriodLength.QUARTERLY, period("Nov - Jan", LocalDate.of(2026, 12, 20))?.length)
        assertEquals(LocalDate.of(2026, 12, 31), period("Jul - Dec", LocalDate.of(2026, 12, 31))?.end)
    }

    @Test
    fun yearlyLabelsEndOnDecember31() {
        assertEquals(BenefitPeriod(LocalDate.of(2026, 12, 31), PeriodLength.YEARLY), period("Annual"))
        assertEquals(BenefitPeriod(LocalDate.of(2026, 12, 31), PeriodLength.YEARLY), period("Jan - Dec"))
    }

    @Test
    fun unknownLabelsGiveNoDate() {
        listOf(null, "", "Ongoing", "2026", "Q4", "Novel", "Jan - ", "Mon - Fri", "Dec 2026").forEach { assertNull(it, period(it)) }
    }
}
