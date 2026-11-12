package com.bendourthe.milestone.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RefreshIntervalTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun dailyIsEachLocalMidnight() {
        assertEquals(at("2026-11-11T00:00"), RefreshInterval.DAILY.lastAt(at("2026-11-11T06:58"), zone))
        assertEquals(at("2026-11-12T00:00"), RefreshInterval.DAILY.nextAt(at("2026-11-11T06:58"), zone))
    }

    @Test
    fun weeklyIsSundayAtMidnight() {
        // 2026-11-11 is a Wednesday.
        assertEquals(at("2026-11-08T00:00"), RefreshInterval.WEEKLY.lastAt(at("2026-11-11T06:58"), zone))
        assertEquals(at("2026-11-15T00:00"), RefreshInterval.WEEKLY.nextAt(at("2026-11-11T06:58"), zone))
        assertEquals(at("2026-11-15T00:00"), RefreshInterval.WEEKLY.lastAt(at("2026-11-15T00:00"), zone))
        assertEquals(at("2026-11-22T00:00"), RefreshInterval.WEEKLY.nextAt(at("2026-11-15T00:00"), zone))
    }

    @Test
    fun monthlyIsMidnightStartingTheLastDayOfTheMonth() {
        assertEquals(at("2026-10-31T00:00"), RefreshInterval.MONTHLY.lastAt(at("2026-11-11T06:58"), zone))
        assertEquals(at("2026-11-30T00:00"), RefreshInterval.MONTHLY.nextAt(at("2026-11-11T06:58"), zone))
        assertEquals(at("2026-11-30T00:00"), RefreshInterval.MONTHLY.lastAt(at("2026-11-30T18:00"), zone))
        assertEquals(at("2026-12-31T00:00"), RefreshInterval.MONTHLY.nextAt(at("2026-11-30T00:00"), zone))
        assertEquals(at("2027-02-28T00:00"), RefreshInterval.MONTHLY.nextAt(at("2027-01-31T09:00"), zone))
    }

    @Test
    fun aDaylightSavingChangeKeepsMidnight() {
        // Clocks fell back on Sunday 2026-11-01 in Los Angeles.
        assertEquals(at("2026-11-01T00:00"), RefreshInterval.WEEKLY.lastAt(at("2026-11-01T12:00"), zone))
        assertEquals(at("2026-11-02T00:00"), RefreshInterval.DAILY.nextAt(at("2026-11-01T12:00"), zone))
    }
}
