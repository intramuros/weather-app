package io.github.intramuros.weatherbuddy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LayerTest {
    private val blink = Layer.Sprite(listOf(Frame("open", 3.5), Frame("shut", 0.15)))

    @Test
    fun framesFollowTheirDurations() {
        assertEquals("open", blink.frameAt(0.0))
        assertEquals("open", blink.frameAt(3.49))
        assertEquals("shut", blink.frameAt(3.55))
        assertEquals("open", blink.frameAt(3.66))
    }

    @Test
    fun loopsForeverAndToleratesNegativeTime() {
        assertEquals(blink.frameAt(1.0), blink.frameAt(1.0 + 3.65 * 1000))
        assertEquals("shut", blink.frameAt(-0.1))
    }

    @Test
    fun stillIsTheFirstFrame() {
        assertEquals("open", blink.still)
        assertEquals("x", Layer.Sprite("x").frameAt(12.3))
    }

    @Test
    fun rejectsEmptyOrZeroLengthFrames() {
        assertFailsWith<IllegalArgumentException> { Layer.Sprite(emptyList()) }
        assertFailsWith<IllegalArgumentException> { Layer.Sprite(listOf(Frame("a", 0.0))) }
    }
}
