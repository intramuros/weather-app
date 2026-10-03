package io.github.intramuros.weatherbuddy.core

import kotlin.test.Test
import kotlin.test.assertEquals
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
                for (layer in RenderPlan.plan(c, style).layers) {
                    assertTrue(layer in required, "$layer not in asset list")
                    used += layer
                }
            }
            assertEquals(emptyList(), (required - used).sorted(), "never drawn for $style")
        }
    }

    @Test
    fun requiredAssetCount() {
        assertEquals(45, Style.PIXEL_ART.requiredAssets().size)
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
                "delfts-blauw/outerwear/light-jacket.png",
                "delfts-blauw/accessory/umbrella-open.png",
            ),
            RenderPlan.plan(c, Style.DELFTS_BLAUW).layers,
        )
    }
}
