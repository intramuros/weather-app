package io.github.intramuros.weatherbuddy.core

import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CustomStyleTest {
    private val style = CustomStyle(
        slug = "made-0a1b2c3d",
        displayName = "Stained glass",
        summary = "Lead lines and jewel colours.",
        description = "Medium: a stained-glass window.",
        particles = CustomStyle.Particles.of(Style.WATERCOLOUR),
        quality = ImageQuality.HIGH,
        createdAtMillis = 1,
    )

    @Test
    fun savesAndLoads() {
        val json = Json.encodeToString(style)
        assertTrue("\"name\":\"Stained glass\"" in json, json)
        assertEquals(style, Json.decodeFromString<CustomStyle>(json))
    }

    @Test
    fun movesLikeAPaintedStyle() {
        assertFalse(style.pixelated)
        assertEquals(24, style.fps)
        for (kind in ParticleKind.entries) assertEquals(Style.WATERCOLOUR.particleLook(kind), style.particleLook(kind))
    }

    @Test
    fun slugsNeverClashWithBuiltInStylesOrLeaveTheirFolder() {
        val slug = CustomStyle.newSlug(Random(7))
        assertTrue(CustomStyle.isSlug(slug), slug)
        assertEquals(null, Style.fromSlug(slug))
        for (bad in listOf(null, "", "pixel-art", "made-", "made-../../x", "made-0A1B2C3D", "made-0a1b2c3d/..")) {
            assertFalse(CustomStyle.isSlug(bad), bad)
        }
    }
}
