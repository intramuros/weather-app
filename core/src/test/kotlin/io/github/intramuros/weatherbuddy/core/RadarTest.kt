package io.github.intramuros.weatherbuddy.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RadarTest {
    private val now = Instant.parse("2026-10-08T12:00:00Z").epochSecond
    private fun feed(vararg timestamps: String) = """{"times":[${timestamps.mapIndexed { i, stamp ->
        """{"timestamp":"$stamp","url":"https://image-cdn.buienradar.nl/forecast-$i.png"}"""
    }.joinToString(",")}],"extra":"ignored"}"""

    @Test
    fun readsUtcForecastFramesOldestFirstWithExplicitForecastFlags() {
        val parsed = Radar.parseIndex(feed("2026-10-08T12:30:00", "2026-10-08T12:00:00", "2026-10-08T12:10:00"), now)
        assertEquals(listOf(now, now + 600, now + 1800), parsed.frames.map { it.timeSeconds })
        assertEquals(listOf(false, true, true), parsed.frames.map { it.isForecast })
    }

    @Test
    fun honoursExplicitOffsetsAndFractionalSeconds() {
        val parsed = Radar.parseIndex(feed("2026-10-08T14:00:00+02:00", "2026-10-08T12:10:00.000Z"), now)
        assertEquals(listOf(now, now + 600), parsed.frames.map { it.timeSeconds })
    }

    @Test
    fun retainsTheForecastHorizonWithABoundedNumberOfDecodedImages() {
        val parsed = Radar.parseIndex(feed(*(0..36).map { Instant.ofEpochSecond(now + it * 300).toString() }.toTypedArray()), now)
        assertEquals(now + 3 * 60 * 60, parsed.frames.last().timeSeconds)
        assertTrue(parsed.frames.size <= 21)
        assertTrue(parsed.frames.drop(1).all { it.isForecast })
    }

    @Test
    fun removesExpiredPicturesAndDuplicateTimes() {
        val parsed = Radar.parseIndex(feed("2026-10-08T11:00:00", "2026-10-08T12:20:00", "2026-10-08T12:20:00Z"), now)
        assertEquals(1, parsed.frames.size)
        assertEquals(now + 1200, parsed.frames.single().timeSeconds)
    }

    @Test
    fun rejectsPastOnlyAndMalformedFeedsInsteadOfCallingThemAForecast() {
        for (text in listOf("nope", "{}", """{"times":[]}""", feed("2026-10-08T11:55:00"), feed("invalid"))) {
            assertFailsWith<WeatherParseException> { Radar.parseIndex(text, now) }
        }
        assertFailsWith<WeatherParseException> {
            Radar.parseIndex("""{"times":[{"timestamp":"2026-10-08T12:30:00","url":"https://example.org/frame.png"}]}""", now)
        }
    }

    @Test
    fun buildsKeyFreeBaseTileUrls() {
        assertEquals("https://tile.openstreetmap.org/7/65/42.png", Radar.baseTileUrl(TileId(7, 65, 42)))
    }

    @Test
    fun projectsKnownPointsAndForecastCorners() {
        val origin = Radar.project(0.0, 0.0, 1)
        assertEquals(1.0, origin.x, 1e-9)
        assertEquals(1.0, origin.y, 1e-9)
        val nl = Radar.project(52.10, 5.18, 7)
        assertEquals(65, nl.x.toInt())
        assertEquals(42, nl.y.toInt())
        val area = Radar.forecastArea(7)
        val nw = Radar.project(54.8, 0.0, 7)
        val se = Radar.project(49.5, 10.0, 7)
        assertEquals(nw.x, area.x)
        assertEquals(nw.y, area.y)
        assertEquals(se.x, area.x + area.width, 1e-9)
        assertEquals(se.y, area.y + area.height, 1e-9)
        assertTrue(Radar.covers(52.10, 5.18))
        assertFalse(Radar.covers(40.71, -74.0))
    }

    @Test
    fun coversWindowAndWrapsColumns() {
        val tiles = Radar.tilesCovering(TilePoint(65.5, 42.5), 7, 2.0, 2.0)
        assertEquals(9, tiles.size)
        assertTrue(TileId(7, 65, 42) in tiles)
        val wrapped = Radar.tilesCovering(TilePoint(0.1, 3.5), 3, 1.0, 1.0)
        assertTrue(wrapped.any { it.x == 7 })
    }
}
