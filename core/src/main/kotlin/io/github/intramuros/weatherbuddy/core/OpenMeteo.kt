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
    private const val CURRENT_FIELDS = "temperature_2m,apparent_temperature,relative_humidity_2m,is_day,precipitation," +
        "weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m,uv_index"
    private const val DAILY_FIELDS = "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,wind_gusts_10m_max"

    /** Today and the next four days. */
    const val FORECAST_DAYS = 5

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * URL for the current conditions and the daily forecast at a location. The
     * days are local dates there (`timezone=auto`); past HARMONIE's ~2.5 days,
     * `knmi_seamless` continues with ECMWF.
     */
    fun forecastUrl(latitude: Double, longitude: Double): String =
        String.format(
            Locale.ROOT,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=%s&daily=%s&forecast_days=%d&models=knmi_seamless&wind_speed_unit=kmh&timezone=auto",
            latitude, longitude, CURRENT_FIELDS, DAILY_FIELDS, FORECAST_DAYS,
        )

    /**
     * Parses an Open-Meteo response with a `current` and, optionally, a `daily`
     * block. The rain nowcast is left empty; fill it from [Buienradar].
     *
     * @throws WeatherParseException if the response is malformed or lacks required current fields.
     */
    fun parse(body: String): Conditions {
        val response = try {
            json.decodeFromString<Response>(body)
        } catch (e: SerializationException) {
            throw WeatherParseException("invalid Open-Meteo response", e)
        } catch (e: IllegalArgumentException) {
            throw WeatherParseException("invalid Open-Meteo response", e)
        }
        val c = response.current
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
            humidityPercent = c.relative_humidity_2m,
            precipitationMm = c.precipitation ?: 0.0,
            forecast = response.daily?.let(::days).orEmpty(),
        )
    }

    /** Days the model has no code or temperatures for are left out. */
    private fun days(d: Daily): List<DayForecast> = d.time.indices.mapNotNull { i ->
        DayForecast(
            date = d.time[i],
            weatherCode = d.weather_code.getOrNull(i) ?: return@mapNotNull null,
            minC = d.temperature_2m_min.getOrNull(i) ?: return@mapNotNull null,
            maxC = d.temperature_2m_max.getOrNull(i) ?: return@mapNotNull null,
            precipitationMm = d.precipitation_sum.getOrNull(i) ?: 0.0,
            windGustsMaxKmh = d.wind_gusts_10m_max.getOrNull(i) ?: 0.0,
        )
    }

    private fun missing(field: String) = WeatherParseException("Open-Meteo response has no value for `$field`")

    @Serializable
    private class Response(val current: Current, val daily: Daily? = null)

    @Suppress("PropertyName")
    @Serializable
    private class Current(
        val temperature_2m: Double? = null,
        val apparent_temperature: Double? = null,
        val relative_humidity_2m: Double? = null,
        val is_day: Int? = null,
        val precipitation: Double? = null,
        val weather_code: Int? = null,
        val wind_speed_10m: Double? = null,
        val wind_direction_10m: Double? = null,
        val wind_gusts_10m: Double? = null,
        val uv_index: Double? = null,
    )

    @Suppress("PropertyName")
    @Serializable
    private class Daily(
        val time: List<String> = emptyList(),
        val weather_code: List<Int?> = emptyList(),
        val temperature_2m_max: List<Double?> = emptyList(),
        val temperature_2m_min: List<Double?> = emptyList(),
        val precipitation_sum: List<Double?> = emptyList(),
        val wind_gusts_10m_max: List<Double?> = emptyList(),
    )
}

class WeatherParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
