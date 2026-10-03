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
                "temperature_2m": 13.4, "apparent_temperature": 10.9, "is_day": 1,
                "precipitation": 0.3, "weather_code": 61,
                "wind_speed_10m": 24.1, "wind_direction_10m": 236.7, "wind_gusts_10m": 48.2, "uv_index": null
            }
        }"""
        val c = OpenMeteo.parseCurrent(json)
        assertEquals(13.4, c.temperatureC)
        assertEquals(10.9, c.apparentTemperatureC)
        assertTrue(c.isDay)
        assertEquals(61, c.weatherCode)
        assertEquals(236.7, c.windDirectionDeg)
        assertNull(c.uvIndex)
        assertTrue(c.rainNowcast.isEmpty())
    }

    @Test
    fun missingTemperatureIsAnError() {
        val e = assertFailsWith<WeatherParseException> {
            OpenMeteo.parseCurrent("""{"current": {"weather_code": 0, "is_day": 1}}""")
        }
        assertTrue("temperature_2m" in e.message!!)
    }

    @Test
    fun malformedJsonIsAnError() {
        assertFailsWith<WeatherParseException> { OpenMeteo.parseCurrent("<html>oops</html>") }
    }

    @Test
    fun urlRequestsKnmiModel() {
        val url = OpenMeteo.currentUrl(52.0907, 5.1214)
        assertTrue("latitude=52.0907&longitude=5.1214" in url)
        assertTrue("models=knmi_seamless" in url)
    }
}
