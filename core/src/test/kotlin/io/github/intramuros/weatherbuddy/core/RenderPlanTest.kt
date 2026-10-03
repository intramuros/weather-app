package io.github.intramuros.weatherbuddy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderPlanTest {
    /**
     * A grid of conditions covering every code family, temperature band,
     * wind class, day/night and radar state.
     */
    private fun conditionGrid(): List<Conditions> {
        val codes = listOf(0, 1, 2, 3, 45, 51, 61, 63, 65, 71, 80, 82, 85, 95, 96, 99)
        val temps = listOf(-5.0, 0.5, 5.0, 12.0, 17.0, 22.0, 29.0)
        val gusts = listOf(10.0, 45.0, 80.0)
        val nowcasts = listOf(
            emptyList(),
            nowcast(*DoubleArray(24)),
            nowcast(*DoubleArray(24) { if (it >= 6) 1.0 else 0.0 }),
            nowcast(*DoubleArray(24) { 0.3 }),
            nowcast(*DoubleArray(24) { 6.0 }),
        )
        return buildList {
            for (code in codes) for (temp in temps) for (gust in gusts)
                for (rain in nowcasts) for (isDay in listOf(true, false)) {
                    add(conditions(code, temp, gust).copy(isDay = isDay, rainNowcast = rain))
                }
        }
    }

    @Test
    fun everyPictureIsShownForSomeWeather() {
        val shown = conditionGrid().map { RenderPlan.plan(it).picture }.toSet()
        assertEquals(emptyList(), (ScenePicture.entries - shown).sorted(), "never shown")
    }

    @Test
    fun drawsOnePictureMatchingTheWeather() {
        val snow = RenderPlan.plan(conditions(71, -2.0, 10.0))
        assertEquals(ScenePicture.SNOW_FREEZING, snow.picture)
        assertEquals(ScenePicture.STORM_COOL, RenderPlan.plan(conditions(95, 14.0, 40.0)).picture)
    }

    @Test
    fun movingWeatherGoesOnTop() {
        val plan = RenderPlan.plan(conditions(63, 8.0, 45.0))
        assertEquals(listOf(ParticleKind.DROP, ParticleKind.STREAK), plan.particles.map { it.kind })
        assertEquals(emptyList(), RenderPlan.plan(conditions(0, 20.0, 10.0)).particles)
    }

    @Test
    fun particlesFollowAnEastWind() {
        val windy = conditions(3, 12.0, 45.0)
        assertTrue(RenderPlan.plan(windy.copy(windDirectionDeg = 90.0)).particlesMirrored)
        assertFalse(RenderPlan.plan(windy.copy(windDirectionDeg = 240.0)).particlesMirrored)
        assertFalse(RenderPlan.plan(windy.copy(windDirectionDeg = null)).particlesMirrored)
        // No visible wind, nothing to follow.
        assertFalse(RenderPlan.plan(conditions(3, 12.0, 10.0).copy(windDirectionDeg = 90.0)).particlesMirrored)
    }
}
