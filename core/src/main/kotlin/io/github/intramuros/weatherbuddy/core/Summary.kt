package io.github.intramuros.weatherbuddy.core

/** The one-word weather label shown next to the buddy ("Rain", "Clear", …). */
enum class Condition {
    CLEAR,
    PARTLY_CLOUDY,
    CLOUDY,
    FOG,
    DRIZZLE,
    RAIN,
    HEAVY_RAIN,
    SNOW,
    HAIL,
    THUNDERSTORM,
    ;

    companion object {
        /** Follows the [Scene], so the label always matches the picture. */
        fun of(scene: Scene): Condition = when {
            scene.sky == Sky.THUNDERSTORM -> THUNDERSTORM
            else -> when (scene.precipitation) {
                Precipitation.DRIZZLE -> DRIZZLE
                Precipitation.RAIN -> RAIN
                Precipitation.HEAVY_RAIN -> HEAVY_RAIN
                Precipitation.SNOW -> SNOW
                Precipitation.HAIL -> HAIL
                Precipitation.NONE -> when (scene.sky) {
                    Sky.CLEAR -> CLEAR
                    Sky.PARTLY_CLOUDY -> PARTLY_CLOUDY
                    Sky.OVERCAST -> CLOUDY
                    Sky.FOG -> FOG
                    Sky.THUNDERSTORM -> THUNDERSTORM
                }
            }
        }
    }
}

/**
 * Ready-made pictures for styles drawn as whole scenes (see [Style.wholeScenes]):
 * one per kind of weather, each with the buddy already dressed for it.
 */
enum class ScenePicture(val slug: String) {
    PARTLY_CLOUDY("partly-cloudy"),
    CLOUDY("cloudy"),
    RAIN("rain"),
    STORM("storm"),
    SNOW("snow"),
    ;

    companion object {
        fun of(condition: Condition): ScenePicture = when (condition) {
            Condition.CLEAR, Condition.PARTLY_CLOUDY -> PARTLY_CLOUDY
            Condition.CLOUDY, Condition.FOG -> CLOUDY
            Condition.DRIZZLE, Condition.RAIN, Condition.HEAVY_RAIN -> RAIN
            Condition.THUNDERSTORM, Condition.HAIL -> STORM
            Condition.SNOW -> SNOW
        }
    }
}

/** The eight main compass points, for "wind from the NW". */
enum class CompassPoint {
    N, NE, E, SE, S, SW, W, NW;

    companion object {
        fun fromDegrees(degrees: Double): CompassPoint {
            val normalised = (degrees % 360 + 360) % 360
            return entries[((normalised + 22.5) / 45).toInt() % entries.size]
        }
    }
}
