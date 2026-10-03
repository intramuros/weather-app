package io.github.intramuros.weatherbuddy.core

fun conditions(code: Int, feels: Double, gusts: Double) = Conditions(
    temperatureC = feels,
    apparentTemperatureC = feels,
    windSpeedKmh = gusts / 2,
    windGustsKmh = gusts,
    uvIndex = null,
    weatherCode = code,
    isDay = true,
    precipitationMm = 0.0,
)

/** 5-minute steps starting at 14:00. */
fun nowcast(vararg mmPerHour: Double) =
    mmPerHour.mapIndexed { i, mm -> RainStep(hour = 14 + i / 12, minute = i % 12 * 5, mmPerHour = mm) }
