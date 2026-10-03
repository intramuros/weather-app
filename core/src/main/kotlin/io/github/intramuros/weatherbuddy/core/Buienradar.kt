package io.github.intramuros.weatherbuddy.core

import java.util.Locale
import kotlin.math.pow

/**
 * Buienradar 2-hour rain nowcast.
 *
 * Free to use on condition of crediting buienradar.nl with a link to
 * https://www.buienradar.nl. The response is plain text, one line per
 * 5 minutes: `value|HH:MM`, where `value` is 0..255.
 */
object Buienradar {
    fun raintextUrl(latitude: Double, longitude: Double): String =
        String.format(
            Locale.ROOT,
            "https://gpsgadget.buienradar.nl/data/raintext?lat=%.2f&lon=%.2f",
            latitude, longitude,
        )

    /** Converts Buienradar's 0..255 value to mm/h (`10^((value - 109) / 32)`). */
    fun valueToMmPerHour(value: Int): Double =
        if (value == 0) 0.0 else 10.0.pow((value - 109) / 32.0)

    /** @throws WeatherParseException on the first malformed line. */
    fun parseRaintext(text: String): List<RainStep> =
        text.lines().mapIndexedNotNull { index, raw ->
            val line = raw.trim()
            if (line.isEmpty()) {
                null
            } else {
                parseLine(line) ?: throw WeatherParseException("invalid Buienradar line ${index + 1}: \"$line\"")
            }
        }

    private fun parseLine(line: String): RainStep? {
        val (value, time) = line.split('|').takeIf { it.size == 2 } ?: return null
        val (hour, minute) = time.split(':').takeIf { it.size == 2 }?.map { it.toIntOrNull() } ?: return null
        val intensity = value.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) return null
        return RainStep(hour, minute, valueToMmPerHour(intensity))
    }
}
