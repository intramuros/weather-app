package io.github.intramuros.weatherbuddy.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ForecastRadarTest {
    private val start = Instant.parse("2026-10-08T12:00:00Z")
    private fun frame(minutes: Long) = ForecastRadarFrame(start.plusSeconds(minutes * 60), "https://image.buienradar.nl/$minutes.png")
    private fun interval(minutes: Long) = RainInterval(start.plusSeconds(minutes * 60), 1.0)

    @Test fun parsesUtcClocksWithAndWithoutZuluAndSortsFrames() {
        val frames = ForecastRadar.parse("""{"times":[{"timestamp":"2026-10-08T12:05:00","url":"https://image.buienradar.nl/5.png"},{"timestamp":"2026-10-08T12:00:00Z","url":"https://image.buienradar.nl/0.png"}],"extra":true}""")
        assertEquals(listOf(frame(0), frame(5)), frames)
        assertFailsWith<WeatherParseException> { ForecastRadar.parse("""{"times":[]}""") }
        assertFailsWith<WeatherParseException> { ForecastRadar.parse("""{"times":[{"timestamp":"bad","url":"https://image.buienradar.nl/0.png"}]}""") }
    }

    @Test fun matchingNeverReusesAnOldMapForFutureRain() {
        assertEquals(frame(5), ForecastRadar.frameAt(listOf(frame(0), frame(5)), start.plusSeconds(300)))
        assertNull(ForecastRadar.frameAt(listOf(frame(0)), start.plusSeconds(300)))
        assertNull(ForecastRadar.frameAt(emptyList(), start))
    }

    @Test fun sharedPlaybackSkipsMissingMapsAndLoopsAtTheEnd() {
        val times = ForecastRadar.playbackTimes(listOf(interval(0), interval(5), interval(10)), listOf(frame(0), frame(10)))
        assertEquals(listOf(start, start.plusSeconds(600)), times)
        assertEquals(start.plusSeconds(600), ForecastRadar.nextTime(times, start))
        assertEquals(start, ForecastRadar.nextTime(times, start.plusSeconds(600)))
        assertNull(ForecastRadar.nextTime(emptyList(), start))
    }

    @Test fun sharedCursorTracksTheActualIntervalAcrossMidnightAndGaps() {
        val intervals = listOf(interval(0), interval(10))
        assertEquals(0, ForecastRadar.intervalAt(intervals, start.plusSeconds(299)))
        assertNull(ForecastRadar.intervalAt(intervals, start.plusSeconds(300)))
        assertEquals(1, ForecastRadar.intervalAt(intervals, start.plusSeconds(600)))
        assertNull(ForecastRadar.intervalAt(intervals, start.minusSeconds(1)))
    }
}
