package io.github.intramuros.weatherbuddy.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PictureSizeTest {
    private val plenty = Int.MAX_VALUE

    @Test
    fun keepsTheWidgetsShape() {
        assertEquals(550 to 800, pictureSize(110f, 160f, plenty))
        assertEquals(800 to 400, pictureSize(300f, 150f, plenty))
        assertEquals(800 to 800, pictureSize(180f, 180f, plenty))
    }

    @Test
    fun squareWhenTheSizeIsUnknown() {
        assertEquals(800 to 800, pictureSize(0f, 0f, plenty))
    }

    @Test
    fun shrinksToTheMemoryBudget() {
        // Two sizes on a 480 × 800 screen: 1.5 screens, less room for the rest, shared.
        val maxPixels = (480 * 800 * 1.5 * 0.9 / 2).toInt()
        val (w, h) = pictureSize(180f, 180f, maxPixels)
        assertEquals(w, h)
        assertTrue(w * h <= maxPixels, "$w×$h")
        assertTrue(w * h > maxPixels * 0.99, "$w×$h")
        val (tw, th) = pictureSize(110f, 160f, maxPixels)
        assertTrue(tw * th <= maxPixels, "$tw×$th")
        assertEquals(110f / 160f, tw.toFloat() / th, 0.01f)
    }
}
