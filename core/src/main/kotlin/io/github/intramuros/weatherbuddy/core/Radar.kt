package io.github.intramuros.weatherbuddy.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/** One radar picture: [timeSeconds] is Unix time, [path] locates its tiles on the tile host. */
data class RadarFrame(val timeSeconds: Long, val path: String)

/** The radar pictures on offer, oldest first. [forecastFrames] counts the trailing ones that are predictions. */
data class RadarIndex(val host: String, val frames: List<RadarFrame>, val forecastFrames: Int)

/** A slippy-map tile; [x] and [y] count from the top left of the world at zoom [z]. */
data class TileId(val z: Int, val x: Int, val y: Int)

/** A point on the world map at some zoom, in tile units: the integer part is the tile, the rest the position in it. */
data class TilePoint(val x: Double, val y: Double)

/**
 * RainViewer's radar mosaic (free, needs a credit to rainviewer.com) over OpenStreetMap-style base tiles.
 *
 * The index lists the last couple of hours of 10-minute pictures; forecast frames only appear when
 * RainViewer offers them, so callers must cope with there being none.
 */
object Radar {
    const val INDEX_URL = "https://api.rainviewer.com/public/weather-maps.json"

    /** The most detailed zoom the free radar tiles serve. */
    const val MAX_ZOOM = 7

    private val json = Json { ignoreUnknownKeys = true }

    /** @throws WeatherParseException if [text] isn't a usable RainViewer index. */
    fun parseIndex(text: String): RadarIndex {
        val raw = try {
            json.decodeFromString<RawIndex>(text)
        } catch (e: SerializationException) {
            throw WeatherParseException("invalid RainViewer response", e)
        } catch (e: IllegalArgumentException) {
            throw WeatherParseException("invalid RainViewer response", e)
        }
        val past = raw.radar.past.sortedBy { it.time }
        val forecast = raw.radar.nowcast.sortedBy { it.time }
        if (past.isEmpty()) throw WeatherParseException("RainViewer has no radar pictures")
        return RadarIndex(
            host = raw.host.trimEnd('/'),
            frames = (past + forecast).map { RadarFrame(it.time, it.path) },
            forecastFrames = forecast.size,
        )
    }

    /** Colour scheme 2 ("Universal Blue"), smoothed, with snow shown. */
    fun radarTileUrl(index: RadarIndex, frame: RadarFrame, tile: TileId): String =
        "${index.host}${frame.path}/256/${tile.z}/${tile.x}/${tile.y}/2/1_1.png"

    /** Standard OpenStreetMap tiles; no API key, with attribution and HTTP caching required. */
    fun baseTileUrl(tile: TileId): String =
        "https://tile.openstreetmap.org/${tile.z}/${tile.x}/${tile.y}.png"

    /** Web Mercator position of a coordinate at [zoom]. */
    fun project(latitude: Double, longitude: Double, zoom: Int): TilePoint {
        val n = 2.0.pow(zoom)
        val lat = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE) * PI / 180
        val x = (longitude + 180) / 360 * n
        val y = (1 - ln(tan(lat) + 1 / kotlin.math.cos(lat)) / PI) / 2 * n
        return TilePoint(x, y)
    }

    /**
     * The tiles needed to cover a [widthTiles] x [heightTiles] window (in tile units) centred on [center].
     * Tiles outside the world's rows are dropped; columns wrap around.
     */
    fun tilesCovering(center: TilePoint, zoom: Int, widthTiles: Double, heightTiles: Double): List<TileId> {
        val n = 1 shl zoom
        val x0 = floor(center.x - widthTiles / 2).toInt()
        val x1 = floor(center.x + widthTiles / 2).toInt()
        val y0 = floor(center.y - heightTiles / 2).toInt().coerceAtLeast(0)
        val y1 = floor(center.y + heightTiles / 2).toInt().coerceAtMost(n - 1)
        return (y0..y1).flatMap { y -> (x0..x1).map { x -> TileId(zoom, Math.floorMod(x, n), y) } }
    }

    private const val MAX_LATITUDE = 85.0511

    @Serializable
    private class RawIndex(val host: String, val radar: RawRadar)

    @Serializable
    private class RawRadar(val past: List<RawFrame> = emptyList(), val nowcast: List<RawFrame> = emptyList())

    @Serializable
    private class RawFrame(val time: Long, val path: String)
}
