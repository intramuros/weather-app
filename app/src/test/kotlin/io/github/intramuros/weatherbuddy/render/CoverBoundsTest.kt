package io.github.intramuros.weatherbuddy.render

import io.github.intramuros.weatherbuddy.render.Compositor.Companion.FOCUS_Y
import io.github.intramuros.weatherbuddy.render.Compositor.Companion.buddyBounds
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

    @Test
    fun squareSceneFillsASquareWidget() {
        assertEquals(Compositor.Bounds(0f, 0f, 600f, 600f), coverBounds(300, 300, 600, 600))
    }

    @Test
    fun buddyMatchesTheSceneScaleAndStandsAtBuddyX() {
        val scene = coverBounds(300, 300, 600, 600)
        val buddy = buddyBounds(135, 300, 600, 600, buddyX = 0.32f)
        assertEquals(scene.height, buddy.height)
        assertEquals(scene.top, buddy.top)
        assertEquals(270f, buddy.width)
        assertEquals(0.32f * 600, buddy.left + buddy.width / 2, 0.01f)
    }

    @Test
    fun wallpaperCentresTheBuddyOnTheScene() {
        // A 9:20 phone shows the middle 135 of the scene's 300 pixels: exactly the buddy's frame.
        val scene = coverBounds(300, 300, 1080, 2400)
        val buddy = buddyBounds(135, 300, 1080, 2400, buddyX = 0.5f)
        assertEquals(Compositor.Bounds(0f, 0f, 1080f, 2400f), buddy)
        assertEquals(scene.left + scene.width / 2, buddy.left + buddy.width / 2, 0.01f)
    }

    @Test
    fun buddyFollowsTheSceneOnAWideWidget() {
        val scene = coverBounds(1200, 1200, 600, 300)
        val buddy = buddyBounds(540, 1200, 600, 300, buddyX = 0.32f)
        assertEquals(scene.top, buddy.top)
        assertEquals(scene.height, buddy.height)
        assertTrue(scene.top < 0f)
    }
}
