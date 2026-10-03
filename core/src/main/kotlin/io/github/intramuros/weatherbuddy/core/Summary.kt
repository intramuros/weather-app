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

/** How warm it feels, in the bands the buddy's outfits follow (see [Outfit.dress]). */
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
 * Finished pictures for styles drawn as whole scenes (see [Style.wholeScenes]),
 * each with the girl dressed for one kind of weather and one [Warmth].
 */
enum class ScenePicture(
    val slug: String,
    val kind: SceneKind,
    val warmth: Warmth,
    /** A bright daytime sky, which looks wrong at night. */
    val daylight: Boolean = false,
) {
    CLEAR_WARM("clear-warm", SceneKind.CLEAR, Warmth.WARM, daylight = true),
    PARTLY_CLOUDY_COLD("partly-cloudy-cold", SceneKind.PARTLY_CLOUDY, Warmth.COLD),
    CLOUDY_COLD("cloudy-cold", SceneKind.CLOUDY, Warmth.COLD),
    WINDY_COOL("windy-cool", SceneKind.WINDY, Warmth.COOL),
    RAIN_COLD("rain-cold", SceneKind.RAIN, Warmth.COLD),
    STORM_COOL("storm-cool", SceneKind.STORM, Warmth.COOL),
    STORM_COLD("storm-cold", SceneKind.STORM, Warmth.COLD),
    SNOW_COLD("snow-cold", SceneKind.SNOW, Warmth.COLD),
    SNOW_FREEZING("snow-freezing", SceneKind.SNOW, Warmth.FREEZING),
    ;

    companion object {
        /** Each band of warmth between the outfit and the weather costs more than a slightly different sky. */
        private const val WARMTH_WEIGHT = 3
        private const val NIGHT_PENALTY = 4

        /**
         * The picture whose outfit best fits how warm it feels, among pictures of the
         * same or a similar sky. Clothes matter more than the sky: a cold, clear day
         * gets a cloudy picture with a coat, not a sunny one with a dress.
         */
        fun choose(conditions: Conditions, scene: Scene): ScenePicture {
            val kind = SceneKind.of(Condition.of(scene))
            val warmth = Warmth.of(conditions.apparentTemperatureC)
            val night = scene.timeOfDay == TimeOfDay.NIGHT
            return entries.minBy { picture ->
                WARMTH_WEIGHT * abs(picture.warmth.ordinal - warmth.ordinal) +
                    kind.distanceTo(picture.kind) +
                    if (night && picture.daylight) NIGHT_PENALTY else 0
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
