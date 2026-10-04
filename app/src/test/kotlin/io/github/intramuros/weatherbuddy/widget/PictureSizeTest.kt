package io.github.intramuros.weatherbuddy.widget

import kotlin.test.Test
import kotlin.test.assertEquals

class PictureSizeTest {
    @Test
    fun keepsTheWidgetsShape() {
        assertEquals(550 to 800, pictureSize(110f, 160f))
        assertEquals(800 to 400, pictureSize(300f, 150f))
        assertEquals(800 to 800, pictureSize(180f, 180f))
    }

    @Test
    fun squareWhenTheSizeIsUnknown() {
        assertEquals(800 to 800, pictureSize(0f, 0f))
    }
}
