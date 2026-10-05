package io.github.intramuros.weatherbuddy.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SummaryTest {
    @Test
    fun compassPoints() {
        assertEquals(CompassPoint.N, CompassPoint.fromDegrees(0.0))
        assertEquals(CompassPoint.N, CompassPoint.fromDegrees(359.0))
        assertEquals(CompassPoint.N, CompassPoint.fromDegrees(22.4))
        assertEquals(CompassPoint.NE, CompassPoint.fromDegrees(22.5))
        assertEquals(CompassPoint.SW, CompassPoint.fromDegrees(236.65))
        assertEquals(CompassPoint.NW, CompassPoint.fromDegrees(315.0))
        assertEquals(CompassPoint.W, CompassPoint.fromDegrees(-90.0))
        assertEquals(CompassPoint.S, CompassPoint.fromDegrees(540.0))
    }

    @Test
    fun labelFollowsTheRadarNotJustTheModel() {
        // The model says rain, but the radar says it's dry right now.
        val dry = conditions(63, 12.0, 10.0).copy(rainNowcast = nowcast(*DoubleArray(24)))
        assertEquals(Condition.CLOUDY, Condition.of(Scene.from(dry)))

        val wet = conditions(3, 12.0, 10.0).copy(rainNowcast = nowcast(*DoubleArray(24) { 1.0 }))
        assertEquals(Condition.RAIN, Condition.of(Scene.from(wet)))
    }

    @Test
    fun labels() {
        fun label(code: Int) = Condition.of(Scene.from(conditions(code, 12.0, 10.0)))
        assertEquals(Condition.CLEAR, label(0))
        assertEquals(Condition.PARTLY_CLOUDY, label(2))
        assertEquals(Condition.CLOUDY, label(3))
        assertEquals(Condition.FOG, label(45))
        assertEquals(Condition.DRIZZLE, label(51))
        assertEquals(Condition.HEAVY_RAIN, label(65))
        assertEquals(Condition.SNOW, label(71))
        assertEquals(Condition.THUNDERSTORM, label(95))
        assertEquals(Condition.THUNDERSTORM, label(99))
    }

    @Test
    fun windyWhenDryAndGusty() {
        assertEquals(Condition.WINDY, Condition.of(Scene.from(conditions(3, 11.0, 45.0))))
        assertEquals(Condition.RAIN, Condition.of(Scene.from(conditions(63, 11.0, 45.0))))
        assertEquals(Condition.FOG, Condition.of(Scene.from(conditions(45, 11.0, 45.0))))
    }

    @Test
    fun dayLabels() {
        fun label(code: Int, gusts: Double = 20.0) =
            Condition.of(Scene.from(DayForecast("2026-10-04", code, 8.0, 14.0, 0.0, gusts)))
        assertEquals(Condition.CLEAR, label(0))
        assertEquals(Condition.RAIN, label(61))
        assertEquals(Condition.SNOW, label(73))
        assertEquals(Condition.WINDY, label(3, gusts = 50.0))
        assertEquals(Condition.RAIN, label(61, gusts = 50.0))
    }

    @Test
    fun hourlyScenesKeepDayNightAndCalmWind() {
        val night = HourForecast("2026-10-04T01:00", 0, 8.0, null, 0.0, false)
        assertEquals(Scene(Sky.CLEAR, Precipitation.NONE, TimeOfDay.NIGHT, Wind.CALM), Scene.from(night))
        val rain = night.copy(weatherCode = 61, isDay = true)
        assertEquals(Scene(Sky.OVERCAST, Precipitation.RAIN, TimeOfDay.DAY, Wind.CALM), Scene.from(rain))
        assertEquals(Condition.RAIN, Condition.of(Scene.from(rain)))
    }

    @Test
    fun warmthBands() {
        assertEquals(Warmth.HOT, Warmth.of(25.0))
        assertEquals(Warmth.WARM, Warmth.of(21.0))
        assertEquals(Warmth.MILD, Warmth.of(15.0))
        assertEquals(Warmth.COOL, Warmth.of(12.0))
        assertEquals(Warmth.COLD, Warmth.of(3.0))
        assertEquals(Warmth.FREEZING, Warmth.of(2.9))
    }

    private fun picture(code: Int, feels: Double, gusts: Double = 10.0, night: Boolean = false): ScenePicture {
        val c = conditions(code, feels, gusts).copy(isDay = !night)
        return ScenePicture.choose(c, Scene.from(c))
    }

    @Test
    fun picturesMatchTheWeather() {
        assertEquals(ScenePicture.CLEAR_HOT, picture(0, 30.0))
        assertEquals(ScenePicture.CLEAR_WARM, picture(0, 22.0))
        assertEquals(ScenePicture.CLEAR_NIGHT_COLD, picture(0, 6.0, night = true))
        assertEquals(ScenePicture.CLEAR_NIGHT_WARM, picture(0, 21.0, night = true))
        assertEquals(ScenePicture.CLEAR_FREEZING, picture(0, -2.0))
        assertEquals(ScenePicture.WINDY_MILD, picture(2, 17.0, gusts = 45.0))
        assertEquals(ScenePicture.RAIN_WARM, picture(63, 22.0))
        assertEquals(ScenePicture.CLEAR_NIGHT_HOT, picture(0, 27.0, night = true))
        assertEquals(ScenePicture.CLOUDY_FREEZING, picture(3, -2.0))
        assertEquals(ScenePicture.STORM_WARM, picture(95, 24.0))
        assertEquals(ScenePicture.WINDY_NIGHT_COOL, picture(3, 12.0, gusts = 45.0, night = true))
        assertEquals(ScenePicture.WINDY_NIGHT_FREEZING, picture(3, -3.0, gusts = 45.0, night = true))
        assertEquals(ScenePicture.FOG_FREEZING, picture(45, -3.0))
        assertEquals(ScenePicture.RAIN_FREEZING, picture(63, -1.0))
        assertEquals(ScenePicture.RAIN_NIGHT_COOL, picture(63, 14.0, night = true))
        assertEquals(ScenePicture.RAIN_MILD, picture(63, 16.0))
        assertEquals(ScenePicture.SNOW_NIGHT_FREEZING, picture(71, -1.0, night = true))
        assertEquals(ScenePicture.RAIN_HOT, picture(63, 27.0))
        assertEquals(ScenePicture.WINDY_FREEZING, picture(3, 1.0, gusts = 45.0))
        assertEquals(ScenePicture.CLEAR_NIGHT_COLD, picture(2, 6.0, night = true))
        assertEquals(ScenePicture.PARTLY_CLOUDY_MILD, picture(2, 17.0))
        assertEquals(ScenePicture.CLOUDY_COOL, picture(3, 12.0))
        assertEquals(ScenePicture.FOG_COOL, picture(45, 11.0))
        assertEquals(ScenePicture.RAIN_MILD, picture(63, 17.0))
        assertEquals(ScenePicture.CLOUDY_COLD, picture(3, 6.0))
        assertEquals(ScenePicture.WINDY_COOL, picture(3, 11.0, gusts = 45.0))
        assertEquals(ScenePicture.RAIN_COLD, picture(63, 6.0))
        assertEquals(ScenePicture.STORM_COLD, picture(95, 6.0))
        assertEquals(ScenePicture.STORM_COOL, picture(95, 12.0))
        assertEquals(ScenePicture.SNOW_COLD, picture(71, 4.0))
        assertEquals(ScenePicture.SNOW_FREEZING, picture(71, -2.0))
    }

    @Test
    fun clothesWinOverTheSky() {
        // A cold, sunny day: a coat under a few clouds, not a summer dress under the sun.
        assertEquals(ScenePicture.PARTLY_CLOUDY_COLD, picture(0, 4.0))
        // A warm, cloudy day: the summer dress rather than a coat.
        assertEquals(ScenePicture.CLEAR_WARM, picture(3, 23.0))
        // No sun at night, no stars by day.
        assertEquals(null, picture(0, 6.0).time?.takeIf { it == TimeOfDay.NIGHT })
        // Snow is only ever shown as snow.
        assertEquals(SceneKind.SNOW, picture(71, 20.0).kind)
    }

    @Test
    fun everyPictureCanBeChosen() {
        val chosen = buildSet {
            for (code in listOf(0, 2, 3, 45, 63, 71, 95)) for (feels in -5..30) for (gusts in listOf(10.0, 45.0)) {
                for (night in listOf(false, true)) add(picture(code, feels.toDouble(), gusts, night))
            }
        }
        assertEquals(ScenePicture.entries.toSet(), chosen)
    }
}
