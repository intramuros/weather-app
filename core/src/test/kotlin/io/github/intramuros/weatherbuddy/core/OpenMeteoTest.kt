package io.github.intramuros.weatherbuddy.core

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
    }

    @Test
    fun parsesDailyBlock() {
        val json = """{
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
        val days = OpenMeteo.parse(json).forecast
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
    }
}
