package io.github.intramuros.weatherbuddy.core

import kotlin.math.abs

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

    /** Dry, with gusts strong enough to matter (see [Wind]). */
    WINDY,
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
                Precipitation.NONE -> when {
                    scene.wind != Wind.CALM && scene.sky != Sky.FOG -> WINDY
                    else -> when (scene.sky) {
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
}

/** How warm it feels, in the bands the buddy's outfits in the [ScenePicture]s follow. */
enum class Warmth {
    FREEZING,
    COLD,
    COOL,
    MILD,
    WARM,
    HOT,
    ;

    companion object {
        fun of(feelsLikeC: Double): Warmth = when {
            feelsLikeC >= 25 -> HOT
            feelsLikeC >= 20 -> WARM
            feelsLikeC >= 15 -> MILD
            feelsLikeC >= 10 -> COOL
            feelsLikeC >= 3 -> COLD
            else -> FREEZING
        }
    }
}

/** The kind of sky a [ScenePicture] shows. */
enum class SceneKind {
    CLEAR,
    PARTLY_CLOUDY,
    CLOUDY,
    FOG,
    WINDY,
    RAIN,
    STORM,
    SNOW,
    ;

    /** 0 for the same kind, small for a sky that would pass, [UNRELATED] otherwise. */
    fun distanceTo(other: SceneKind): Int = when {
        other == this -> 0
        else -> SIMILAR[this]?.get(other) ?: UNRELATED
    }

    companion object {
        const val UNRELATED = 100

        private val SIMILAR = mapOf(
            CLEAR to mapOf(PARTLY_CLOUDY to 1, CLOUDY to 2, WINDY to 2),
            PARTLY_CLOUDY to mapOf(CLEAR to 1, CLOUDY to 1, WINDY to 2),
            CLOUDY to mapOf(PARTLY_CLOUDY to 1, FOG to 1, WINDY to 1, CLEAR to 2),
            FOG to mapOf(CLOUDY to 1, PARTLY_CLOUDY to 2),
            WINDY to mapOf(CLOUDY to 1, PARTLY_CLOUDY to 2, CLEAR to 2),
            RAIN to mapOf(STORM to 1),
            STORM to mapOf(RAIN to 1),
        )

        fun of(condition: Condition): SceneKind = when (condition) {
            Condition.CLEAR -> CLEAR
            Condition.PARTLY_CLOUDY -> PARTLY_CLOUDY
            Condition.CLOUDY -> CLOUDY
            Condition.FOG -> FOG
            Condition.WINDY -> WINDY
            Condition.DRIZZLE, Condition.RAIN, Condition.HEAVY_RAIN -> RAIN
            Condition.THUNDERSTORM, Condition.HAIL -> STORM
            Condition.SNOW -> SNOW
        }
    }
}

/**
 * The finished pictures (see [Art]), each with the girl dressed for one kind
 * of weather and one [Warmth].
 */
enum class ScenePicture(
    val slug: String,
    val kind: SceneKind,
    val warmth: Warmth,
    /** Only right at this time of day (a sun, a starry sky); `null` for dusky skies that suit both. */
    val time: TimeOfDay? = null,
) {
    CLEAR_HOT("clear-hot", SceneKind.CLEAR, Warmth.HOT, TimeOfDay.DAY),
    CLEAR_WARM("clear-warm", SceneKind.CLEAR, Warmth.WARM, TimeOfDay.DAY),
    CLEAR_FREEZING("clear-freezing", SceneKind.CLEAR, Warmth.FREEZING, TimeOfDay.DAY),
    CLEAR_NIGHT_HOT("clear-night-hot", SceneKind.CLEAR, Warmth.HOT, TimeOfDay.NIGHT),
    CLEAR_NIGHT_WARM("clear-night-warm", SceneKind.CLEAR, Warmth.WARM, TimeOfDay.NIGHT),
    CLEAR_NIGHT_COLD("clear-night-cold", SceneKind.CLEAR, Warmth.COLD, TimeOfDay.NIGHT),
    PARTLY_CLOUDY_MILD("partly-cloudy-mild", SceneKind.PARTLY_CLOUDY, Warmth.MILD, TimeOfDay.DAY),
    PARTLY_CLOUDY_COLD("partly-cloudy-cold", SceneKind.PARTLY_CLOUDY, Warmth.COLD, TimeOfDay.DAY),
    CLOUDY_COOL("cloudy-cool", SceneKind.CLOUDY, Warmth.COOL),
    CLOUDY_COLD("cloudy-cold", SceneKind.CLOUDY, Warmth.COLD),
    CLOUDY_FREEZING("cloudy-freezing", SceneKind.CLOUDY, Warmth.FREEZING),
    FOG_COOL("fog-cool", SceneKind.FOG, Warmth.COOL),
    FOG_FREEZING("fog-freezing", SceneKind.FOG, Warmth.FREEZING),
    WINDY_MILD("windy-mild", SceneKind.WINDY, Warmth.MILD, TimeOfDay.DAY),
    WINDY_COOL("windy-cool", SceneKind.WINDY, Warmth.COOL, TimeOfDay.DAY),
    WINDY_NIGHT_COOL("windy-night-cool", SceneKind.WINDY, Warmth.COOL, TimeOfDay.NIGHT),
    WINDY_FREEZING("windy-freezing", SceneKind.WINDY, Warmth.FREEZING, TimeOfDay.DAY),
    WINDY_NIGHT_FREEZING("windy-night-freezing", SceneKind.WINDY, Warmth.FREEZING, TimeOfDay.NIGHT),
    RAIN_HOT("rain-hot", SceneKind.RAIN, Warmth.HOT),
    RAIN_WARM("rain-warm", SceneKind.RAIN, Warmth.WARM, TimeOfDay.DAY),
    RAIN_MILD("rain-mild", SceneKind.RAIN, Warmth.MILD),
    RAIN_NIGHT_COOL("rain-night-cool", SceneKind.RAIN, Warmth.COOL, TimeOfDay.NIGHT),
    RAIN_COLD("rain-cold", SceneKind.RAIN, Warmth.COLD),
    RAIN_FREEZING("rain-freezing", SceneKind.RAIN, Warmth.FREEZING),
    STORM_WARM("storm-warm", SceneKind.STORM, Warmth.WARM),
    STORM_COOL("storm-cool", SceneKind.STORM, Warmth.COOL),
    STORM_COLD("storm-cold", SceneKind.STORM, Warmth.COLD),
    SNOW_COLD("snow-cold", SceneKind.SNOW, Warmth.COLD),
    SNOW_FREEZING("snow-freezing", SceneKind.SNOW, Warmth.FREEZING, TimeOfDay.DAY),
    SNOW_NIGHT_FREEZING("snow-night-freezing", SceneKind.SNOW, Warmth.FREEZING, TimeOfDay.NIGHT),
    ;

    companion object {
        /** Each band of warmth between the outfit and the weather costs more than a slightly different sky. */
        private const val WARMTH_WEIGHT = 3
        private const val WRONG_TIME_PENALTY = 4

        /**
         * The picture whose outfit best fits how warm it feels, among pictures of the
         * same or a similar sky. Clothes matter more than the sky: a cold, clear day
         * gets a cloudy picture with a coat, not a sunny one with a dress. A sky for
         * the wrong time of day (sun at night, stars by day) counts against a picture.
         */
        fun choose(conditions: Conditions, scene: Scene): ScenePicture {
            val kind = SceneKind.of(Condition.of(scene))
            val warmth = Warmth.of(conditions.apparentTemperatureC)
            return entries.minBy { picture ->
                val wrongTime = picture.time != null && picture.time != scene.timeOfDay
                WARMTH_WEIGHT * abs(picture.warmth.ordinal - warmth.ordinal) +
                    kind.distanceTo(picture.kind) +
                    if (wrongTime) WRONG_TIME_PENALTY else 0
            }
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
