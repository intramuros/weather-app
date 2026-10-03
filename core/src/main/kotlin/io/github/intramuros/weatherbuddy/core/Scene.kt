package io.github.intramuros.weatherbuddy.core

import io.github.intramuros.weatherbuddy.core.Conditions.Companion.RAIN_THRESHOLD_MM_H

enum class Sky { CLEAR, PARTLY_CLOUDY, OVERCAST, FOG, THUNDERSTORM }

enum class Precipitation { NONE, DRIZZLE, RAIN, HEAVY_RAIN, SNOW, HAIL }

enum class TimeOfDay { DAY, NIGHT }

enum class Wind { CALM, BREEZY, STORMY }

/** What the world around the buddy looks like. */
data class Scene(
    val sky: Sky,
    val precipitation: Precipitation,
    val timeOfDay: TimeOfDay,
    val wind: Wind,
) {
    companion object {
        fun from(c: Conditions): Scene {
            val gusts = maxOf(c.windGustsKmh, c.windSpeedKmh)
            return Scene(
                sky = skyFromCode(c.weatherCode),
                precipitation = precipitation(c),
                timeOfDay = if (c.isDay) TimeOfDay.DAY else TimeOfDay.NIGHT,
                wind = when {
                    gusts < 35 -> Wind.CALM
                    gusts < 62 -> Wind.BREEZY
                    else -> Wind.STORMY
                },
            )
        }

        /** Sky for a WMO weather code. Showers get sunny spells, steady rain is grey. */
        private fun skyFromCode(code: Int) = when (code) {
            0, 1 -> Sky.CLEAR
            2, in 80..86 -> Sky.PARTLY_CLOUDY
            45, 48 -> Sky.FOG
            in 95..99 -> Sky.THUNDERSTORM
            else -> Sky.OVERCAST
        }

        private fun precipitationFromCode(code: Int) = when (code) {
            in 51..57 -> Precipitation.DRIZZLE
            65, 82 -> Precipitation.HEAVY_RAIN
            in 61..67, 80, 81, 95 -> Precipitation.RAIN
            in 71..77, 85, 86 -> Precipitation.SNOW
            96, 99 -> Precipitation.HAIL
            else -> Precipitation.NONE
        }

        /**
         * The radar nowcast is more accurate for "is it raining right now" than the
         * model, so it wins when present; the model code still decides snow vs. hail.
         */
        private fun precipitation(c: Conditions): Precipitation {
            val fromCode = precipitationFromCode(c.weatherCode)
            val now = c.rainNowMmH
                ?: return if (fromCode == Precipitation.NONE && c.precipitationMm >= RAIN_THRESHOLD_MM_H) {
                    Precipitation.RAIN
                } else {
                    fromCode
                }
            return when {
                now < RAIN_THRESHOLD_MM_H -> Precipitation.NONE
                fromCode == Precipitation.SNOW || fromCode == Precipitation.HAIL -> fromCode
                c.temperatureC <= 1.0 -> Precipitation.SNOW
                now < 0.5 -> Precipitation.DRIZZLE
                now < 4.0 -> Precipitation.RAIN
                else -> Precipitation.HEAVY_RAIN
            }
        }
    }
}
