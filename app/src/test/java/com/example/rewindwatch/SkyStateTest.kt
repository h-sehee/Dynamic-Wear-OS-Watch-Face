package com.example.rewindwatch

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the asymmetric dawn/sunset windows (dawn -30/+15 min, sunset -45/+20 min)
 * that drive the sky background. Boundaries are inclusive on the DAWN/SUNSET side.
 */
class SkyStateTest {
    private val sunrise = 1_700_000_000L                 // arbitrary epoch seconds
    private val sunset = sunrise + 13 * 3600             // 13h day
    private val schedule = SolarSchedule(sunrise, sunset)

    private fun fromSunrise(minutes: Long) = skyStateAt(sunrise + minutes * 60, schedule)
    private fun fromSunset(minutes: Long) = skyStateAt(sunset + minutes * 60, schedule)

    @Test
    fun dawnRunsFrom30MinBeforeSunriseTo15MinAfter() {
        assertEquals(SkyState.NIGHT, fromSunrise(-31))
        assertEquals(SkyState.DAWN, fromSunrise(-30))
        assertEquals(SkyState.DAWN, fromSunrise(0))
        assertEquals(SkyState.DAWN, fromSunrise(15))
        assertEquals(SkyState.DAY, fromSunrise(16))
    }

    @Test
    fun sunsetRunsFrom45MinBeforeSunsetTo20MinAfter() {
        assertEquals(SkyState.DAY, fromSunset(-46))
        assertEquals(SkyState.SUNSET, fromSunset(-45))
        assertEquals(SkyState.SUNSET, fromSunset(0))
        assertEquals(SkyState.SUNSET, fromSunset(20))
        assertEquals(SkyState.NIGHT, fromSunset(21))
    }

    @Test
    fun middayIsDayAndMidnightIsNight() {
        assertEquals(SkyState.DAY, fromSunrise(6 * 60))
        assertEquals(SkyState.NIGHT, fromSunset(6 * 60))
    }

    @Test
    fun scheduleFromAnEarlierDayStillClassifiesLaterDays() {
        val day = 86_400L
        // Next day, same clock times: the API was unreachable but the phases
        // must not collapse into NIGHT once yesterday's sunset has passed.
        assertEquals(SkyState.DAY, fromSunrise(day / 60 + 6 * 60))          // tomorrow noon-ish
        assertEquals(SkyState.DAWN, fromSunrise(day / 60 - 20))             // 20 min before tomorrow's sunrise
        assertEquals(SkyState.DAWN, fromSunrise(2 * day / 60))              // sunrise, two days later
        assertEquals(SkyState.SUNSET, fromSunset(3 * day / 60 - 10))        // 10 min before sunset, three days later
        assertEquals(SkyState.NIGHT, fromSunset(3 * day / 60 + 60))         // an hour after that sunset
    }

    @Test
    fun scheduleFromALaterDayClassifiesEarlierDays() {
        // A schedule captured for "tomorrow" (clock set forward, then corrected).
        assertEquals(SkyState.NIGHT, fromSunrise(-2 * 60))                  // 2h before today's sunrise
        assertEquals(SkyState.DAY, fromSunrise(-86_400 / 60 + 6 * 60))      // yesterday, mid-day
    }

    @Test
    fun fallbackScheduleClassifiesAWholeDay() {
        // Mirrors the 06:00-18:00 fallback used before the weather API answers.
        val six = 6 * 3600L
        val s = SolarSchedule(sunrise = six, sunset = 18 * 3600L)
        assertEquals(SkyState.NIGHT, skyStateAt(3 * 3600L, s))
        assertEquals(SkyState.DAWN, skyStateAt(six, s))
        assertEquals(SkyState.DAY, skyStateAt(12 * 3600L, s))
        assertEquals(SkyState.SUNSET, skyStateAt(18 * 3600L, s))
        assertEquals(SkyState.NIGHT, skyStateAt(23 * 3600L, s))
    }
}
