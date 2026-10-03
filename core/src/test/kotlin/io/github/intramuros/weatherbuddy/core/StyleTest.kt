package io.github.intramuros.weatherbuddy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StyleTest {
    /**
     * A grid of conditions covering every code family, temperature band,
     * wind class, UV level, day/night and radar state.
     */
    private fun conditionGrid(): List<Conditions> {
        val codes = listOf(0, 1, 2, 3, 45, 51, 61, 63, 65, 71, 80, 82, 85, 95, 96, 99)
        val temps = listOf(-5.0, 0.5, 5.0, 12.0, 17.0, 22.0, 29.0)
        val gusts = listOf(10.0, 45.0, 80.0)
        val uvs = listOf(null, 4.0, 8.0)
        val nowcasts = listOf(
            emptyList(),
            nowcast(*DoubleArray(24)),
            nowcast(*DoubleArray(24) { if (it >= 6) 1.0 else 0.0 }),
            nowcast(*DoubleArray(24) { 0.3 }),
            nowcast(*DoubleArray(24) { 6.0 }),
        )
        return buildList {
            for (code in codes) for (temp in temps) for (gust in gusts) for (uv in uvs)
                for (rain in nowcasts) for (isDay in listOf(true, false)) {
                    add(conditions(code, temp, gust).copy(uvIndex = uv, isDay = isDay, rainNowcast = rain))
                }
        }
    }

    @Test
    fun everyDrawnLayerIsARequiredAssetAndEveryAssetIsReachable() {
        val grid = conditionGrid()
        for (style in Style.entries) {
            val required = style.requiredAssets().toSet()
            val used = mutableSetOf<String>()
            for (c in grid) {
                for (asset in RenderPlan.plan(c, style).layers.flatMap { it.assets }) {
                    assertTrue(asset in required, "$asset not in asset list")
                    used += asset
                }
            }
            assertEquals(emptyList(), (required - used).sorted(), "never drawn for $style")
        }
    }

    @Test
    fun requiredAssetCount() {
        // 45 still images + 9 hair frames + 5 blinks + 3 windy-scarf frames.
        assertEquals(62, Style.UKIYO_E.requiredAssets().size)
        assertEquals(ScenePicture.entries.size, Style.PIXEL_ART.requiredAssets().size)
    }

    @Test
    fun wholeSceneStylesDrawOnePictureMatchingTheWeather() {
        val snow = RenderPlan.plan(conditions(71, -2.0, 10.0), Style.PIXEL_ART)
        assertEquals(listOf("pixel-art/scene/snow-freezing.webp"), snow.stillLayers)
        assertEquals(ScenePicture.SNOW_FREEZING, snow.picture)
        val storm = conditions(95, 14.0, 40.0)
        assertEquals(listOf("pixel-art/scene/storm-cool.webp"), RenderPlan.plan(storm, Style.PIXEL_ART).stillLayers)
        assertEquals("pixel-art/scene/storm-cool-icons.webp", Style.PIXEL_ART.sceneIcons(ScenePicture.STORM_COOL))
        assertTrue(Style.isScenery("pixel-art/scene/storm-cool.webp"))
        assertEquals(null, RenderPlan.plan(storm, Style.UKIYO_E).picture)
    }

    @Test
    fun wholeScenesGetMovingWeatherOnTop() {
        val layers = RenderPlan.plan(conditions(63, 8.0, 45.0), Style.PIXEL_ART).layers
        assertTrue(layers.first() is Layer.Sprite)
        val particles = layers.filterIsInstance<Layer.Particles>()
        assertEquals(listOf(ParticleKind.DROP, ParticleKind.STREAK), particles.flatMap { p -> p.specs.map { it.kind } })
        // The finished picture already shows its weather, so a still picture adds nothing.
        assertTrue(particles.all { it.still == null })
    }

    @Test
    fun sceneryIsBackgroundAndEffects() {
        val scenery = Style.UKIYO_E.requiredAssets().filter(Style::isScenery)
        assertEquals(10 + 5 + 2, scenery.size)
        assertTrue(scenery.all { "/background/" in it || "/fx/" in it })
    }

    @Test
    fun umbrellaPutsRainBehindTheBuddy() {
        val c = conditions(63, 12.0, 10.0).copy(precipitationMm = 1.0)
        assertEquals(
            listOf(
                "delfts-blauw/background/overcast-day.png",
                "delfts-blauw/fx/rain.png",
                "delfts-blauw/body/base.png",
                "delfts-blauw/face/happy.png",
                "delfts-blauw/bottom/trousers.png",
                "delfts-blauw/footwear/sneakers.png",
                "delfts-blauw/top/sweater.png",
                "delfts-blauw/hair/calm.png",
                "delfts-blauw/outerwear/light-jacket.png",
                "delfts-blauw/accessory/umbrella-open.png",
            ),
            RenderPlan.plan(c, Style.DELFTS_BLAUW).stillLayers,
        )
    }

    @Test
    fun windAnimatesHairAndScarf() {
        val plan = RenderPlan.plan(conditions(3, 5.0, 45.0), Style.UKIYO_E)
        val animated = plan.layers.filterIsInstance<Layer.Sprite>().filter { it.isAnimated }.map { it.still }
        assertEquals(
            listOf(
                "ukiyo-e/face/happy.png",
                "ukiyo-e/hair/breezy-0.png",
                "ukiyo-e/accessory/scarf-wind-0.png",
            ),
            animated,
        )
        val particles = plan.layers.filterIsInstance<Layer.Particles>()
        assertEquals(listOf("ukiyo-e/fx/wind-breezy.png"), particles.map { it.still })
    }

    @Test
    fun eastWindMirrorsLayeredArt() {
        val windy = conditions(3, 12.0, 45.0)
        val east = RenderPlan.plan(windy.copy(windDirectionDeg = 90.0), Style.UKIYO_E)
        assertTrue(east.mirrored && east.particlesMirrored)
        assertFalse(RenderPlan.plan(windy.copy(windDirectionDeg = 240.0), Style.UKIYO_E).mirrored)
        assertFalse(RenderPlan.plan(windy.copy(windDirectionDeg = null), Style.UKIYO_E).mirrored)
        // No visible wind, nothing to follow.
        assertFalse(RenderPlan.plan(conditions(3, 12.0, 10.0).copy(windDirectionDeg = 90.0), Style.UKIYO_E).mirrored)
    }

    @Test
    fun finishedPicturesStayPutWhileTheirWeatherFollowsTheWind() {
        val plan = RenderPlan.plan(conditions(63, 8.0, 45.0).copy(windDirectionDeg = 90.0), Style.PIXEL_ART)
        assertFalse(plan.mirrored)
        assertTrue(plan.particlesMirrored)
    }
}
