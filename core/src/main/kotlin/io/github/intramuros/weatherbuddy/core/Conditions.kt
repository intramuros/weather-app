package io.github.intramuros.weatherbuddy.core

import kotlinx.serialization.Serializable

/** Normalised weather at the user's location, independent of the data source. */
@Serializable
data class Conditions(
    val temperatureC: Double,
    /** "Feels like" temperature; drives clothing choices. */
    val apparentTemperatureC: Double,
    val windSpeedKmh: Double,
    val windGustsKmh: Double,
    /**
     * Where the wind blows from, in degrees clockwise from north (270 = from the west).
     * `null` in snapshots saved before it was fetched.
     */
    val windDirectionDeg: Double? = null,
    /** `null` when the model does not provide UV (e.g. KNMI HARMONIE). */
    val uvIndex: Double?,
    /** WMO weather interpretation code as used by Open-Meteo. */
    val weatherCode: Int,
    val isDay: Boolean,
    /** Relative humidity, 0–100. `null` in snapshots saved before it was fetched. */
    val humidityPercent: Double? = null,
    /** Precipitation over the preceding interval, in mm. */
    val precipitationMm: Double,
    /** Radar-based rain forecast for the next ~2 hours (Buienradar), in time order. */
    val rainNowcast: List<RainStep> = emptyList(),
    /** Today and the coming days, in date order. Empty in snapshots saved before it was fetched. */
    val forecast: List<DayForecast> = emptyList(),
) {
    /** Rain intensity right now according to the radar nowcast, if we have one. */
    val rainNowMmH: Double? get() = rainNowcast.firstOrNull()?.mmPerHour

    /** Strongest rain expected within the next [minutes] (5-minute steps). */
    fun maxRainWithin(minutes: Int): Double =
        rainNowcast.take(minutes / 5 + 1).maxOfOrNull { it.mmPerHour } ?: 0.0

    companion object {
        /** Below this intensity (mm/h) we treat the rain forecast as dry. */
        const val RAIN_THRESHOLD_MM_H = 0.1
    }
}

/** One 5-minute step of the rain forecast. */
@Serializable
data class RainStep(val hour: Int, val minute: Int, val mmPerHour: Double)

/** One day of the model forecast. */
@Serializable
data class DayForecast(
    /** The local date, as `yyyy-MM-dd`. */
    val date: String,
    /** WMO weather interpretation code for the day as a whole. */
    val weatherCode: Int,
    val minC: Double,
    val maxC: Double,
    /** Total over the day, in mm. */
    val precipitationMm: Double,
    val windGustsMaxKmh: Double,
)
