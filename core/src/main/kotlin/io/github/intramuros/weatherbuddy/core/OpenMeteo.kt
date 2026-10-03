package io.github.intramuros.weatherbuddy.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Open-Meteo forecast API, using the KNMI HARMONIE model for the Netherlands.
 *
 * Free without an API key for non-commercial use; see https://open-meteo.com/en/terms.
 */
object OpenMeteo {
    private const val CURRENT_FIELDS = "temperature_2m,apparent_temperature,is_day,precipitation," +
        "weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m,uv_index"

    private val json = Json { ignoreUnknownKeys = true }

    /** URL for the current conditions at a location. */
    fun currentUrl(latitude: Double, longitude: Double): String =
        String.format(
            Locale.ROOT,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=%s&models=knmi_seamless&wind_speed_unit=kmh&timezone=auto",
            latitude, longitude, CURRENT_FIELDS,
        )

    /**
     * Parses an Open-Meteo `current` response. The rain nowcast is left empty;
     * fill it from [Buienradar].
     *
     * @throws WeatherParseException if the response is malformed or lacks required fields.
     */
    fun parseCurrent(body: String): Conditions {
        val c = try {
            json.decodeFromString<Response>(body).current
        } catch (e: SerializationException) {
            throw WeatherParseException("invalid Open-Meteo response", e)
        } catch (e: IllegalArgumentException) {
            throw WeatherParseException("invalid Open-Meteo response", e)
        }
        val temperature = c.temperature_2m ?: throw missing("temperature_2m")
        val windSpeed = c.wind_speed_10m ?: 0.0
        return Conditions(
            temperatureC = temperature,
            apparentTemperatureC = c.apparent_temperature ?: temperature,
            windSpeedKmh = windSpeed,
            windGustsKmh = c.wind_gusts_10m ?: windSpeed,
            windDirectionDeg = c.wind_direction_10m,
            uvIndex = c.uv_index,
            weatherCode = c.weather_code ?: throw missing("weather_code"),
            isDay = (c.is_day ?: throw missing("is_day")) != 0,
            precipitationMm = c.precipitation ?: 0.0,
        )
    }

    private fun missing(field: String) = WeatherParseException("Open-Meteo response has no value for `$field`")

    @Serializable
    private class Response(val current: Current)

    @Suppress("PropertyName")
    @Serializable
    private class Current(
        val temperature_2m: Double? = null,
        val apparent_temperature: Double? = null,
        val is_day: Int? = null,
        val precipitation: Double? = null,
        val weather_code: Int? = null,
        val wind_speed_10m: Double? = null,
        val wind_direction_10m: Double? = null,
        val wind_gusts_10m: Double? = null,
        val uv_index: Double? = null,
    )
}

class WeatherParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
