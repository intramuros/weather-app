package io.github.intramuros.weatherbuddy.data

import io.github.intramuros.weatherbuddy.core.Conditions
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WeatherSnapshotTest {
    private val conditions = """{
        "temperatureC": 14.2, "apparentTemperatureC": 13.0,
        "windSpeedKmh": 10.0, "windGustsKmh": 15.0, "uvIndex": null,
        "weatherCode": 3, "isDay": true, "precipitationMm": 0.0
    }"""

    @Test
    fun olderSnapshotsDecodeWithoutALocation() {
        val snapshot = Json.decodeFromString<WeatherSnapshot>("""{"conditions": $conditions, "fetchedAtMillis": 1}""")
        assertNull(snapshot.location)
    }

    @Test
    fun keepsItsLocation() {
        val snapshot = WeatherSnapshot(Json.decodeFromString<Conditions>(conditions), 1, Location(52.37, 4.89))
        assertEquals(snapshot, Json.decodeFromString(Json.encodeToString(snapshot)))
    }
}
