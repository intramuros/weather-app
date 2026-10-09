package io.github.intramuros.weatherbuddy.ui

import io.github.intramuros.weatherbuddy.core.ForecastRadar
import io.github.intramuros.weatherbuddy.core.ForecastRadarFrame
import io.github.intramuros.weatherbuddy.core.RainInterval
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RainPlaybackTest {
    private val now = Instant.parse("2026-10-09T12:00:00Z")
    private fun frame(minutes: Long) = ForecastRadarFrame(now.plusSeconds(minutes * 60), "https://image.buienradar.nl/$minutes.png")
    private fun rain(minutes: Long) = RainInterval(now.plusSeconds(minutes * 60), 1.0)

    @Test fun missingRaintextStillSelectsAndPlaysLoadedMaps() {
        val state = RainPlayback()
        state.updateForecast(emptyList(), now)
        assertNull(state.time)
        state.frames = listOf(frame(0))
        state.updateForecast(emptyList(), now)
        assertEquals(now, state.time)
        assertEquals(frame(0), ForecastRadar.frameAt(state.frames, state.time!!))
        assertFalse(state.canPlay)
        state.frames = listOf(frame(0), frame(5))
        state.updateForecast(emptyList(), now)
        assertTrue(state.canPlay)
        state.time = ForecastRadar.nextTime(state.times, state.time)
        assertEquals(frame(5), ForecastRadar.frameAt(state.frames, state.time!!))
    }

    @Test fun missingMapFramesStillAllowHistogramAutoplay() {
        val state = RainPlayback()
        state.updateForecast(listOf(rain(0), rain(5)), now)
        assertTrue(state.playing)
        assertTrue(state.canPlay)
        state.time = ForecastRadar.nextTime(state.times, state.time)
        assertEquals(1, ForecastRadar.intervalAt(state.intervals, state.time!!))
    }

    @Test fun oneLoadedMapKeepsHistogramPlayableUntilSharedPlaybackIsReady() {
        val state = RainPlayback()
        val rain = listOf(rain(0), rain(5), rain(10))
        state.frames = listOf(frame(0))
        state.updateForecast(rain, now)
        assertTrue(state.canPlay)
        state.time = ForecastRadar.nextTime(state.times, state.time)
        assertEquals(1, ForecastRadar.intervalAt(state.intervals, state.time!!))
        assertNull(ForecastRadar.frameAt(state.frames, state.time!!))

        state.frames = listOf(frame(0), frame(10))
        state.updateForecast(rain, now)
        assertTrue(state.canPlay)
        state.time = ForecastRadar.nextTime(state.times, state.time)
        assertEquals(2, ForecastRadar.intervalAt(state.intervals, state.time!!))
        assertEquals(frame(10), ForecastRadar.frameAt(state.frames, state.time!!))
    }

    @Test fun manualSelectionStaysPausedWhenMapsArriveAndResumeSkipsMissingImages() {
        val state = RainPlayback()
        val rain = listOf(rain(0), rain(5), rain(10))
        state.updateForecast(rain, now)
        state.select(now.plusSeconds(300))
        state.frames = listOf(frame(0), frame(10))
        state.updateForecast(rain, now)
        assertEquals(now.plusSeconds(300), state.time)
        assertFalse(state.playing)
        state.toggle()
        assertTrue(state.playing)
        assertEquals(now.plusSeconds(600), ForecastRadar.nextTime(state.times, state.time))
    }

    @Test fun expiredFramesAreNotReplayedWhenRaintextIsUnavailable() {
        val state = RainPlayback()
        state.frames = listOf(frame(-5), frame(0), frame(5))
        state.updateForecast(emptyList(), now)
        assertEquals(listOf(now, now.plusSeconds(300)), state.times)
        state.updateForecast(emptyList(), now.plusSeconds(600))
        assertNull(state.time)
        assertFalse(state.canPlay)
    }
}
