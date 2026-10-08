package io.github.intramuros.weatherbuddy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RadarTest {
    private val index = """
        {"version":"2.0","generated":1791451827,"host":"https://tilecache.rainviewer.com/",
         "radar":{"past":[{"time":1791445200,"path":"/v2/radar/b"},{"time":1791444600,"path":"/v2/radar/a"}],
                  "nowcast":[{"time":1791451800,"path":"/v2/radar/n"}]},
         "satellite":{"infrared":[]}}
    """.trimIndent()

    @Test
    fun parsesIndexOldestFirst() {
        val parsed = Radar.parseIndex(index)
        assertEquals("https://tilecache.rainviewer.com", parsed.host)
        assertEquals(listOf("/v2/radar/a", "/v2/radar/b", "/v2/radar/n"), parsed.frames.map { it.path })
        assertEquals(1, parsed.forecastFrames)
    }

    @Test
    fun copesWithoutForecast() {
        val parsed = Radar.parseIndex("""{"host":"https://h","radar":{"past":[{"time":1,"path":"/p"}],"nowcast":[]}}""")
        assertEquals(0, parsed.forecastFrames)
        assertEquals(1, parsed.frames.size)
    }

    @Test
    fun rejectsUnusableIndex() {
        assertFailsWith<WeatherParseException> { Radar.parseIndex("nope") }
        assertFailsWith<WeatherParseException> { Radar.parseIndex("""{"host":"h","radar":{"past":[]}}""") }
    }

    @Test
    fun buildsTileUrls() {
        val parsed = Radar.parseIndex(index)
        assertEquals(
            "https://tilecache.rainviewer.com/v2/radar/a/256/7/65/42/2/1_1.png",
            Radar.radarTileUrl(parsed, parsed.frames[0], TileId(7, 65, 42)),
        )
        assertEquals("https://c.basemaps.cartocdn.com/dark_all/7/65/42.png", Radar.baseTileUrl(TileId(7, 65, 42), dark = true))
    }

    @Test
    fun projectsKnownPoints() {
        val origin = Radar.project(0.0, 0.0, 1)
        assertEquals(1.0, origin.x, 1e-9)
        assertEquals(1.0, origin.y, 1e-9)
        // The middle of the Netherlands sits in tile 65/42 at zoom 7.
        val nl = Radar.project(52.10, 5.18, 7)
        assertEquals(65, nl.x.toInt())
        assertEquals(42, nl.y.toInt())
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
