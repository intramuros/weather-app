package io.github.intramuros.weatherbuddy.render

import io.github.intramuros.weatherbuddy.render.Compositor.Companion.FOCUS_Y
import io.github.intramuros.weatherbuddy.render.Compositor.Companion.coverBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverBoundsTest {
    @Test
    fun sameAspectRatioFillsExactly() {
        assertEquals(Compositor.Bounds(0f, 0f, 1080f, 2400f), coverBounds(135, 300, 1080, 2400))
    }

    @Test
    fun squareWidgetCropsAroundTheBuddy() {
        val b = coverBounds(135, 300, 360, 360)
        assertEquals(360f, b.width)
        assertEquals(800f, b.height)
        // The buddy's middle lands in the middle of the widget.
        assertEquals(180f, b.top + FOCUS_Y * b.height, 0.01f)
    }

    @Test
    fun neverExposesEmptySpace() {
        for ((w, h) in listOf(360 to 360, 1440 to 2400, 1080 to 1920, 2400 to 1080, 400 to 100)) {
            val b = coverBounds(135, 300, w, h)
            assertTrue(b.left <= 0f && b.top <= 0f, "$w×$h: $b")
            assertTrue(b.left + b.width >= w - 0.01f && b.top + b.height >= h - 0.01f, "$w×$h: $b")
        }
    }
}
