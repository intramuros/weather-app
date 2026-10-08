package io.github.intramuros.weatherbuddy.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RainHistogramTest {
    @Test
    fun dutchClockIsIndependentOfDeviceZoneAndKeepsIntensity() {
        val fetched = Instant.parse("2026-10-08T12:02:00Z")
        val points = RainHistogram.intervals(listOf(RainStep(14, 0, 0.0), RainStep(14, 5, 2.0)), fetched, fetched)
        assertEquals(listOf(Instant.parse("2026-10-08T12:00:00Z"), Instant.parse("2026-10-08T12:05:00Z")), points.map { it.start })
        assertEquals(listOf(0.0, 2.0), points.map { it.mmPerHour })
    }

    @Test
    fun midnightForecastDoesNotWrapBackwards() {
        val fetched = Instant.parse("2026-10-08T21:57:00Z")
        val points = RainHistogram.intervals(listOf(RainStep(23, 55, 1.0), RainStep(0, 0, 2.0), RainStep(0, 5, 0.0)), fetched, fetched)
        assertEquals(listOf("2026-10-08T21:55:00Z", "2026-10-08T22:00:00Z", "2026-10-08T22:05:00Z"), points.map { it.start.toString() })
    }

    @Test
    fun cachedForecastExpiresWithoutBeingRelabelledTomorrow() {
        val fetched = Instant.parse("2026-10-08T12:00:00Z")
        val steps = listOf(RainStep(14, 0, 0.0), RainStep(14, 5, 3.0), RainStep(14, 10, 0.0))
        assertEquals(listOf(3.0, 0.0), RainHistogram.intervals(steps, fetched, fetched.plusSeconds(300)).map { it.mmPerHour })
        assertTrue(RainHistogram.intervals(steps, fetched, fetched.plusSeconds(900)).isEmpty())
        assertTrue(RainHistogram.intervals(steps, fetched, fetched.plusSeconds(86400)).isEmpty())
    }

    @Test
    fun missingIntervalsKeepTheirRealSpacingAndDuplicatesDoNotLoseLaterData() {
        val fetched = Instant.parse("2026-10-08T12:00:00Z")
        val points = RainHistogram.intervals(
            listOf(RainStep(14, 0, 1.0), RainStep(14, 0, 1.0), RainStep(14, 15, 2.0)), fetched, fetched,
        )
        assertEquals(listOf("2026-10-08T12:00:00Z", "2026-10-08T12:15:00Z"), points.map { it.start.toString() })
    }

    @Test
    fun daylightSavingRepeatedHourKeepsFiveMinuteSteps() {
        val fetched = Instant.parse("2026-10-25T00:57:00Z")
        val points = RainHistogram.intervals(listOf(RainStep(2, 55, 1.0), RainStep(2, 0, 2.0), RainStep(2, 5, 0.0)), fetched, fetched)
        assertEquals(listOf("2026-10-25T00:55:00Z", "2026-10-25T01:00:00Z", "2026-10-25T01:05:00Z"), points.map { it.start.toString() })
    }

    @Test
    fun unavailableDataIsDifferentFromDryDataAndScaleFitsHeavyRain() {
        val fetched = Instant.parse("2026-10-08T12:00:00Z")
        assertTrue(RainHistogram.intervals(emptyList(), fetched, fetched).isEmpty())
        val dry = RainHistogram.intervals(listOf(RainStep(14, 0, 0.0)), fetched, fetched)
        assertEquals(1, dry.size)
        assertEquals(1.0, RainHistogram.axisMaximum(dry))
        assertEquals(1.0, RainHistogram.axisMaximum(emptyList()))
        val heavy = RainHistogram.intervals(listOf(RainStep(14, 0, 37.0)), fetched, fetched)
        assertEquals(40.0, RainHistogram.axisMaximum(heavy))
        assertTrue(RainHistogram.intervals(listOf(RainStep(14, 0, Double.NaN), RainStep(14, 5, -1.0)), fetched, fetched).isEmpty())
    }
}
