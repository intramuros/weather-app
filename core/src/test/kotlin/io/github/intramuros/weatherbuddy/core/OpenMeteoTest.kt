package io.github.intramuros.weatherbuddy.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenMeteoTest {
    @Test
    fun parsesCurrentBlock() {
        val json = """{
            "latitude": 52.37, "longitude": 4.89,
            "current_units": {"temperature_2m": "°C"},
            "current": {
                "time": "2026-10-03T14:15", "interval": 900,
                "temperature_2m": 13.4, "apparent_temperature": 10.9, "relative_humidity_2m": 81, "is_day": 1,
                "precipitation": 0.3, "weather_code": 61,
                "wind_speed_10m": 24.1, "wind_direction_10m": 236.7, "wind_gusts_10m": 48.2, "uv_index": null
            }
        }"""
        val c = OpenMeteo.parse(json)
        assertEquals(13.4, c.temperatureC)
        assertEquals(10.9, c.apparentTemperatureC)
        assertTrue(c.isDay)
        assertEquals(61, c.weatherCode)
        assertEquals(236.7, c.windDirectionDeg)
        assertEquals(81.0, c.humidityPercent)
        assertNull(c.uvIndex)
        assertTrue(c.rainNowcast.isEmpty())
        assertTrue(c.forecast.isEmpty())
        assertTrue(c.hourly.isEmpty())
        assertNull(c.timeZone)
    }

    @Test
    fun parsesDailyBlock() {
        val json = """{
            "timezone": "Europe/Amsterdam",
            "current": {"temperature_2m": 14.2, "weather_code": 3, "is_day": 0},
            "daily_units": {"time": "iso8601", "temperature_2m_max": "°C"},
            "daily": {
                "time": ["2026-10-03", "2026-10-04", "2026-10-05"],
                "weather_code": [3, 61, null],
                "temperature_2m_max": [19.9, 15.2, 21.0],
                "temperature_2m_min": [6.8, 10.1, 9.5],
                "precipitation_sum": [0.0, 4.3, null],
                "wind_gusts_10m_max": [16.2, null, 29.2]
            }
        }"""
        val conditions = OpenMeteo.parse(json)
        assertEquals("Europe/Amsterdam", conditions.timeZone)
        val days = conditions.forecast
        // The third day has no weather code, so it's left out.
        assertEquals(
            listOf(
                DayForecast("2026-10-03", 3, minC = 6.8, maxC = 19.9, precipitationMm = 0.0, windGustsMaxKmh = 16.2),
                DayForecast("2026-10-04", 61, minC = 10.1, maxC = 15.2, precipitationMm = 4.3, windGustsMaxKmh = 0.0),
            ),
            days,
        )
    }

    @Test
    fun parsesHourlyBlockLeavingOutMissingCodesAndTemperatures() {
        val json = """{
            "timezone": "Europe/Amsterdam",
            "current": {"temperature_2m": 14.2, "weather_code": 3, "is_day": 1},
            "hourly": {
                "time": ["2026-10-03T17:00", "2026-10-03T18:00", "2026-10-03T19:00", "2026-10-03T20:00", "2026-10-03T21:00"],
                "weather_code": [3, 61, null, 0, 2],
                "temperature_2m": [14.2, 13.1, 12.0, null, 10.5],
                "precipitation_probability": [20, null, 40, 0],
                "precipitation": [0.0, 0.3, 1.0, 0.0, null],
                "is_day": [1, 0, 0, 0, null]
            }
        }"""
        val c = OpenMeteo.parse(json)
        assertEquals("Europe/Amsterdam", c.timeZone)
        assertEquals(
            listOf(
                HourForecast("2026-10-03T17:00", 3, 14.2, 20.0, 0.0, true),
                HourForecast("2026-10-03T18:00", 61, 13.1, null, 0.3, false),
                HourForecast("2026-10-03T21:00", 2, 10.5, null, 0.0, true),
            ),
            c.hourly,
        )
    }

    @Test
    fun hourlyOptionalFieldsCanBeAbsent() {
        val c = OpenMeteo.parse("""{
            "current": {"temperature_2m": 14.2, "weather_code": 3, "is_day": 1},
            "hourly": {"time": ["2026-10-03T14:00"], "weather_code": [0], "temperature_2m": [15]}
        }""")
        assertEquals(listOf(HourForecast("2026-10-03T14:00", 0, 15.0, null, 0.0, true)), c.hourly)
    }

    @Test
    fun olderSnapshotsDecodeWithoutHourlyForecast() {
        val c = Json.decodeFromString<Conditions>("""{
            "temperatureC": 14.2, "apparentTemperatureC": 13.0,
            "windSpeedKmh": 10.0, "windGustsKmh": 15.0, "uvIndex": null,
            "weatherCode": 3, "isDay": true, "precipitationMm": 0.0
        }""")
        assertTrue(c.hourly.isEmpty())
    }

    @Test
    fun missingTemperatureIsAnError() {
        val e = assertFailsWith<WeatherParseException> {
            OpenMeteo.parse("""{"current": {"weather_code": 0, "is_day": 1}}""")
        }
        assertTrue("temperature_2m" in e.message!!)
    }

    @Test
    fun malformedJsonIsAnError() {
        assertFailsWith<WeatherParseException> { OpenMeteo.parse("<html>oops</html>") }
    }

    @Test
    fun urlRequestsKnmiModel() {
        val url = OpenMeteo.forecastUrl(52.0907, 5.1214)
        assertTrue("latitude=52.0907&longitude=5.1214" in url)
        assertTrue("models=knmi_seamless" in url)
        assertTrue("&daily=" in url)
        assertTrue("forecast_days=${OpenMeteo.FORECAST_DAYS}" in url)
        assertTrue("&hourly=temperature_2m,weather_code,precipitation_probability,precipitation,is_day" in url)
        assertTrue("&forecast_hours=12" in url)
        assertTrue("timezone=auto" in url)
    }
}
