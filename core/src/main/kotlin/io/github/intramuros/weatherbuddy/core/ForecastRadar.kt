package io.github.intramuros.weatherbuddy.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.math.abs

/** Buienradar's georeferenced transparent forecast images, in the same five-minute steps as raintext. */
data class ForecastRadarFrame(val time: Instant, val url: String)

object ForecastRadar {
    const val INDEX_URL = "https://image.buienradar.nl/2.0/metadata/sprite/RadarMapRainWebmercatorNL" +
        "?width=529&height=458&extension=png&renderBackground=false&renderText=false" +
        "&renderBranding=false&history=1&forecast=24&skip=0"
    const val NORTH = 54.8
    const val SOUTH = 49.5
    const val WEST = 0.0
    const val EAST = 10.0

    fun covers(latitude: Double, longitude: Double): Boolean = latitude in SOUTH..NORTH && longitude in WEST..EAST

    fun parse(text: String): List<ForecastRadarFrame> {
        try {
            val raw = Json { ignoreUnknownKeys = true }.decodeFromString<Metadata>(text)
            return raw.times.map { frame ->
                val time = try {
                    Instant.parse(frame.timestamp)
                } catch (_: java.time.DateTimeException) {
                    LocalDateTime.parse(frame.timestamp).toInstant(ZoneOffset.UTC)
                }
                if (!frame.url.startsWith("https://")) throw WeatherParseException("invalid forecast image URL")
                ForecastRadarFrame(time, frame.url)
            }.distinctBy { it.time }.sortedBy { it.time }.also {
                if (it.isEmpty()) throw WeatherParseException("no forecast radar images")
            }
        } catch (e: SerializationException) {
            throw WeatherParseException("invalid forecast radar metadata", e)
        } catch (e: IllegalArgumentException) {
            throw WeatherParseException("invalid forecast radar metadata", e)
        } catch (e: java.time.DateTimeException) {
            throw WeatherParseException("invalid forecast radar timestamp", e)
        }
    }

    /** Never stretch a historical frame or a missing image over an unrelated forecast time. */
    fun frameAt(frames: List<ForecastRadarFrame>, time: Instant): ForecastRadarFrame? =
        frames.minByOrNull { abs(it.time.epochSecond - time.epochSecond) }
            ?.takeIf { abs(it.time.epochSecond - time.epochSecond) <= 150 }

    fun intervalAt(intervals: List<RainInterval>, time: Instant): Int? =
        intervals.indexOfFirst { time >= it.start && time < it.end }.takeIf { it >= 0 }

    /** Prefer synchronized steps; let either source keep playing when the other is unavailable. */
    fun playbackTimes(intervals: List<RainInterval>, frames: List<ForecastRadarFrame>): List<Instant> {
        val rainTimes = intervals.map { it.start }.distinct().sorted()
        val shared = rainTimes.filter { frameAt(frames, it) != null }
        return when {
            shared.isNotEmpty() -> shared
            rainTimes.isNotEmpty() -> rainTimes
            else -> frames.map { it.time }.distinct().sorted()
        }
    }

    fun nextTime(times: List<Instant>, selected: Instant?): Instant? {
        if (times.isEmpty()) return null
        return times.firstOrNull { selected == null || it > selected } ?: times.first()
    }

    @Serializable private data class Metadata(val times: List<Image>)
    @Serializable private data class Image(val timestamp: String, val url: String)
}
