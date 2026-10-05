package io.github.intramuros.weatherbuddy.ui

import io.github.intramuros.weatherbuddy.core.HourForecast
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HourlyForecastTest {
    @Test
    fun keepsCurrentHourAtForecastLocationAndOnlySixHours() {
        val zone = zoneOrDefault("Europe/Amsterdam")
        val start = Instant.parse("2026-10-05T12:37:00Z").atZone(zone).truncatedTo(ChronoUnit.HOURS).toLocalDateTime()
        val hours = (12..23).map { hour("2026-10-05T$it:00") }
        assertEquals((14..19).map { "2026-10-05T$it:00" }, upcomingHours(hours, start).map { it.first.time })
    }

    @Test
    fun advancingAnHourExpiresThePreviousColumnAcrossMidnight() {
        val start = LocalDateTime.parse("2026-10-05T23:00")
        val hours = listOf(hour("2026-10-05T23:00"), hour("2026-10-06T00:00"), hour("2026-10-06T01:00"))
        assertEquals(hours, upcomingHours(hours, start).map { it.first })
        assertEquals(hours.drop(1), upcomingHours(hours, start.plusHours(1)).map { it.first })
    }

    @Test
    fun oldEmptyAndMalformedForecastsAreLeftOut() {
        val start = LocalDateTime.parse("2026-10-05T14:00")
        assertTrue(upcomingHours(emptyList(), start).isEmpty())
        assertTrue(upcomingHours(listOf(hour("2026-10-04T14:00"), hour("bad time")), start).isEmpty())
        val last = hour("2026-10-05T15:00")
        assertEquals(listOf(last), upcomingHours(listOf(hour("bad time"), last), start).map { it.first })
    }

    @Test
    fun absentOrInvalidTimeZoneFallsBackToDevice() {
        assertEquals(ZoneId.systemDefault(), zoneOrDefault(null))
        assertEquals(ZoneId.systemDefault(), zoneOrDefault("invalid zone"))
    }

    private fun hour(time: String) = HourForecast(time, 0, 14.0, null, 0.0, true)
}
