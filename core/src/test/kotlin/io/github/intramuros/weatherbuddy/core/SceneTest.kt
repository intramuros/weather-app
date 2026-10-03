package io.github.intramuros.weatherbuddy.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SceneTest {
    @Test
    fun modelCodeWithoutRadar() {
        assertEquals(
            Scene(Sky.OVERCAST, Precipitation.RAIN, TimeOfDay.DAY, Wind.CALM),
            Scene.from(conditions(63, 12.0, 20.0)),
        )
        assertEquals(
            Scene(Sky.THUNDERSTORM, Precipitation.HAIL, TimeOfDay.DAY, Wind.STORMY),
            Scene.from(conditions(99, 20.0, 80.0)),
        )
    }

    @Test
    fun radarOverridesModel() {
        val dryModelWetRadar = conditions(3, 12.0, 20.0).copy(rainNowcast = nowcast(5.0, 1.0))
        assertEquals(Precipitation.HEAVY_RAIN, Scene.from(dryModelWetRadar).precipitation)

        val wetModelDryRadar = conditions(63, 12.0, 20.0).copy(rainNowcast = nowcast(0.0, 3.0))
        assertEquals(Precipitation.NONE, Scene.from(wetModelDryRadar).precipitation)

        val nearFreezing = conditions(3, 0.5, 20.0).copy(rainNowcast = nowcast(1.0))
        assertEquals(Precipitation.SNOW, Scene.from(nearFreezing).precipitation)
    }
}
