package io.github.intramuros.weatherbuddy.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/** A georeferenced Buienradar picture; timestamps without an offset in the feed are UTC. */
data class RadarFrame(val timeSeconds: Long, val url: String, val isForecast: Boolean)

/** Current and future pictures, oldest first, sampled at roughly ten-minute intervals. */
data class RadarIndex(val frames: List<RadarFrame>)

/** A rectangle in Web Mercator tile units, used to position the country-wide forecast overlay. */
data class TileRect(val x: Double, val y: Double, val width: Double, val height: Double)

/** A slippy-map tile; [x] and [y] count from the top left of the world at zoom [z]. */
data class TileId(val z: Int, val x: Int, val y: Int)

/** A point on the world map at some zoom, in tile units: the integer part is the tile, the rest the position in it. */
data class TilePoint(val x: Double, val y: Double)

/**
 * Buienradar's rain forecast over OpenStreetMap base tiles. The WebmercatorNL image product covers
 * longitude 0–10° and latitude 49.5–54.8°. Downloaded frames retain the provider's timestamps.
 */
object Radar {
    const val FRAME_WIDTH = 640
    const val FRAME_HEIGHT = 554
    const val INDEX_URL = "https://image.buienradar.nl/2.0/metadata/sprite/RadarMapRainWebmercatorNL" +
        "?width=$FRAME_WIDTH&height=$FRAME_HEIGHT&extension=png" +
        "&renderBackground=false&renderText=false&renderBranding=false&history=0&forecast=36&skip=0"

    private val json = Json { ignoreUnknownKeys = true }

    /** @throws WeatherParseException if the feed is malformed or contains no future pictures. */
    fun parseIndex(text: String, nowSeconds: Long): RadarIndex {
        val raw = try {
            json.decodeFromString<RawIndex>(text)
        } catch (e: SerializationException) {
            throw WeatherParseException("invalid Buienradar forecast response", e)
        } catch (e: IllegalArgumentException) {
            throw WeatherParseException("invalid Buienradar forecast response", e)
        }
        val available = raw.times.map { frame ->
            val time = try {
                // Buienradar uses ISO timestamps without a zone. Explicit offsets are accepted too.
                val stamp = if (frame.timestamp.endsWith('Z') || frame.timestamp.drop(10).contains('+') ||
                    frame.timestamp.drop(10).contains('-')) frame.timestamp else frame.timestamp + "Z"
                Instant.parse(stamp).epochSecond
            } catch (e: DateTimeParseException) {
                throw WeatherParseException("invalid Buienradar forecast timestamp", e)
            }
            val uri = try { URI(frame.url) } catch (e: IllegalArgumentException) {
                throw WeatherParseException("invalid Buienradar image URL", e)
            } catch (e: java.net.URISyntaxException) {
                throw WeatherParseException("invalid Buienradar image URL", e)
            }
            if (uri.scheme != "https" || uri.host?.endsWith(".buienradar.nl") != true) {
                throw WeatherParseException("invalid Buienradar image host")
            }
            RadarFrame(time, frame.url, isForecast = time > nowSeconds)
        }.filter { it.timeSeconds in (nowSeconds - 10 * 60)..(nowSeconds + 3 * 60 * 60) }
            .sortedBy { it.timeSeconds }.distinctBy { it.timeSeconds }
        if (available.none { it.isForecast }) throw WeatherParseException("Buienradar has no future rain pictures")
        // Full-country bitmaps cost more memory than individual tiles. Keep the whole forecast horizon
        // at ten-minute intervals (about twenty images), always retaining its final available picture.
        val sampled = mutableListOf<RadarFrame>()
        for (frame in available) {
            if (sampled.isEmpty() || frame.timeSeconds - sampled.last().timeSeconds >= 10 * 60) sampled.add(frame)
        }
        if (sampled.last() != available.last()) sampled.add(available.last())
        return RadarIndex(sampled)
    }

    fun covers(latitude: Double, longitude: Double): Boolean = latitude in 49.5..54.8 && longitude in 0.0..10.0

    fun forecastArea(zoom: Int): TileRect {
        val nw = project(54.8, 0.0, zoom)
        val se = project(49.5, 10.0, zoom)
        return TileRect(nw.x, nw.y, se.x - nw.x, se.y - nw.y)
    }

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
    private class RawIndex(val times: List<RawFrame>)

    @Serializable
    private class RawFrame(val timestamp: String, val url: String)
}
