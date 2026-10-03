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
    fun everyConditionHasAPicture() {
        assertEquals(ScenePicture.PARTLY_CLOUDY, ScenePicture.of(Condition.CLEAR))
        assertEquals(ScenePicture.CLOUDY, ScenePicture.of(Condition.FOG))
        assertEquals(ScenePicture.RAIN, ScenePicture.of(Condition.HEAVY_RAIN))
        assertEquals(ScenePicture.STORM, ScenePicture.of(Condition.HAIL))
        assertEquals(ScenePicture.SNOW, ScenePicture.of(Condition.SNOW))
        assertEquals(ScenePicture.entries.toSet(), Condition.entries.map(ScenePicture::of).toSet())
    }
}
