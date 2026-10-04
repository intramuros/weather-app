package io.github.intramuros.weatherbuddy.render

import kotlin.test.Test
import kotlin.test.assertEquals

class OverlayPlacementTest {
    @Test
    fun squarePictureKeepsTheArtworksPositions() {
        val place = OverlayPlacement(600, 600)
        assertEquals(0.5f, place.scale)
        assertEquals(100f, place.x(200f))
        assertEquals(450f, place.x(900f))
        assertEquals(360f, place.leftColumnEnd)
        assertEquals(360f, place.rightColumnStart)
    }

    @Test
    fun tallPictureFitsTheWidthAtTheTop() {
        val place = OverlayPlacement(600, 900)
        assertEquals(0.5f, place.scale)
        assertEquals(450f, place.x(900f))
        assertEquals(600f, place.y(SceneLayout.UNITS))
    }

    @Test
    fun widePictureSpreadsTheColumnsToTheEdges() {
        val place = OverlayPlacement(1200, 600)
        assertEquals(0.5f, place.scale)
        // The left column stays put; the right one ends at the right edge.
        assertEquals(100f, place.x(200f))
        assertEquals(360f, place.leftColumnEnd)
        assertEquals(960f, place.rightColumnStart)
        assertEquals(1200f, place.x(SceneLayout.UNITS))
    }
}
