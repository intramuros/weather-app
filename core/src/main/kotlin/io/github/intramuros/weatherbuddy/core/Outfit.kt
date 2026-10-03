package io.github.intramuros.weatherbuddy.core

import io.github.intramuros.weatherbuddy.core.Conditions.Companion.RAIN_THRESHOLD_MM_H

enum class Top(val slug: String) {
    TANK_TOP("tank-top"),
    T_SHIRT("t-shirt"),
    LONG_SLEEVE("long-sleeve"),
    SWEATER("sweater"),
}

enum class Bottom(val slug: String) { SHORTS("shorts"), TROUSERS("trousers") }

enum class Footwear(val slug: String) {
    SANDALS("sandals"),
    SNEAKERS("sneakers"),
    BOOTS("boots"),
    RAIN_BOOTS("rain-boots"),
}

enum class Outerwear(val slug: String) {
    LIGHT_JACKET("light-jacket"),
    RAINCOAT("raincoat"),
    COAT("coat"),
    PUFFER_COAT("puffer-coat"),
}

/** Declaration order is draw order (back to front). */
enum class Accessory(val slug: String) {
    SCARF("scarf"),
    GLOVES("gloves"),
    SUNGLASSES("sunglasses"),
    BEANIE("beanie"),
    SUN_HAT("sun-hat"),
    CLOSED_UMBRELLA("umbrella-closed"),
    UMBRELLA("umbrella-open"),
}

enum class Expression(val slug: String) {
    HAPPY("happy"),
    SLEEPY("sleepy"),
    SHIVERING("shivering"),
    SWEATY("sweaty"),
    SOGGY("soggy"),
    WINDSWEPT("windswept"),
}

data class Outfit(
    val top: Top,
    val bottom: Bottom,
    val footwear: Footwear,
    val outerwear: Outerwear?,
    /** Sorted in draw order, no duplicates. */
    val accessories: List<Accessory>,
    val expression: Expression,
) {
    fun has(accessory: Accessory) = accessory in accessories

    private data class Clothes(val top: Top, val bottom: Bottom, val footwear: Footwear, val outerwear: Outerwear?)

    companion object {
        /** Rain heavier than this (mm/h) calls for rain boots. */
        private const val RAIN_BOOTS_MM_H = 2.5

        /**
         * Dresses the buddy for the "feels like" temperature, rain in the next hour,
         * wind (umbrellas don't survive Dutch gusts) and sun.
         */
        fun dress(c: Conditions, scene: Scene): Outfit {
            val feels = c.apparentTemperatureC
            var (top, bottom, footwear, outerwear) = when {
                feels >= 25 -> Clothes(Top.TANK_TOP, Bottom.SHORTS, Footwear.SANDALS, null)
                feels >= 20 -> Clothes(Top.T_SHIRT, Bottom.SHORTS, Footwear.SNEAKERS, null)
                feels >= 15 -> Clothes(Top.LONG_SLEEVE, Bottom.TROUSERS, Footwear.SNEAKERS, null)
                feels >= 10 -> Clothes(Top.SWEATER, Bottom.TROUSERS, Footwear.SNEAKERS, Outerwear.LIGHT_JACKET)
                feels >= 3 -> Clothes(Top.SWEATER, Bottom.TROUSERS, Footwear.BOOTS, Outerwear.COAT)
                else -> Clothes(Top.SWEATER, Bottom.TROUSERS, Footwear.BOOTS, Outerwear.PUFFER_COAT)
            }

            val acc = sortedSetOf<Accessory>()
            if (feels < 8) acc += Accessory.SCARF
            if (feels < 3) acc += listOf(Accessory.BEANIE, Accessory.GLOVES)

            val wetNow = scene.precipitation.isWet
            val rainSoon = c.maxRainWithin(60) >= RAIN_THRESHOLD_MM_H
            val snowing = scene.precipitation == Precipitation.SNOW
            val calm = scene.wind == Wind.CALM

            if (wetNow || (rainSoon && !snowing)) {
                when {
                    calm -> acc += if (wetNow) Accessory.UMBRELLA else Accessory.CLOSED_UMBRELLA
                    outerwear == Outerwear.PUFFER_COAT -> acc += Accessory.BEANIE
                    else -> outerwear = Outerwear.RAINCOAT
                }
                val heavy = scene.precipitation == Precipitation.HEAVY_RAIN ||
                    scene.precipitation == Precipitation.HAIL ||
                    c.maxRainWithin(30) >= RAIN_BOOTS_MM_H
                if (footwear != Footwear.SANDALS && (heavy || (wetNow && !calm))) {
                    footwear = Footwear.RAIN_BOOTS
                }
            }

            if (snowing) {
                footwear = Footwear.BOOTS
                acc += listOf(Accessory.SCARF, Accessory.BEANIE, Accessory.GLOVES)
            }

            val sunny = scene.timeOfDay == TimeOfDay.DAY &&
                (scene.sky == Sky.CLEAR || scene.sky == Sky.PARTLY_CLOUDY) &&
                scene.precipitation == Precipitation.NONE
            if (sunny) {
                // Without a UV value, fall back to temperature as a rough proxy.
                val uv = c.uvIndex
                val glasses = if (uv != null) uv >= 3 else c.temperatureC >= 15
                val hat = if (uv != null) uv >= 6 else feels >= 25
                if (glasses) acc += Accessory.SUNGLASSES
                if (hat && scene.wind != Wind.STORMY && Accessory.BEANIE !in acc) acc += Accessory.SUN_HAT
            }

            val expression = when {
                scene.wind == Wind.STORMY -> Expression.WINDSWEPT
                wetNow && Accessory.UMBRELLA !in acc -> Expression.SOGGY
                feels < 0 -> Expression.SHIVERING
                feels >= 28 -> Expression.SWEATY
                scene.timeOfDay == TimeOfDay.NIGHT -> Expression.SLEEPY
                else -> Expression.HAPPY
            }

            return Outfit(top, bottom, footwear, outerwear, acc.toList(), expression)
        }
    }
}
