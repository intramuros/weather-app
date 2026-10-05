package io.github.intramuros.weatherbuddy.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StyleTest {
    /** The app's assets, relative to this module (Gradle runs tests from the module's directory). */
    private val assets = File("../app/src/main/assets")

    private fun shipped(style: Style): Set<String> =
        File(assets, "scenes/${style.slug}").list()?.map { "scenes/${style.slug}/$it" }?.toSet().orEmpty()

    private fun expected(style: Style): Set<String> = ScenePicture.entries.flatMap {
        listOfNotNull(style.scene(it), if (style.hasWidgetIcons) style.sceneIcons(it) else null)
    }.toSet()

    @Test
    fun picturesLiveInTheStylesFolder() {
        assertEquals("scenes/anime/storm-cool.webp", Style.ANIME.scene(ScenePicture.STORM_COOL))
        assertEquals("scenes/pixel-art/storm-cool-icons.webp", Style.PIXEL_ART.sceneIcons(ScenePicture.STORM_COOL))
    }

    @Test
    fun pixelArtIsComplete() {
        assertEquals(expected(Style.PIXEL_ART), shipped(Style.PIXEL_ART))
    }

    /** A half-finished style would show pixel art for some weather and not others, so it's all or nothing. */
    @Test
    fun everyShippedStyleIsCompleteAndHasNothingElse() {
        assertTrue(assets.isDirectory, "run from the core module")
        for (style in Style.entries) {
            val files = shipped(style)
            if (files.isEmpty()) continue
            assertEquals(emptyList(), (expected(style) - files).sorted(), "missing from ${style.slug}")
            assertEquals(emptyList(), (files - expected(style)).sorted(), "unexpected in ${style.slug}")
        }
    }

    @Test
    fun slugsAreUnique() {
        assertEquals(Style.entries.size, Style.entries.map { it.slug }.toSet().size)
        assertEquals(Style.ANIME, Style.fromSlug("anime"))
        assertEquals(Style.UKIYO_E, Style.fromSlug("ukiyo-e"))
        assertEquals(Style.DELFT_BLUE, Style.fromSlug("delft-blue"))
        assertEquals(Style.MUCHA, Style.fromSlug("mucha"))
        assertEquals(Style.VAN_GOGH, Style.fromSlug("van-gogh"))
        assertEquals(Style.WATERCOLOUR, Style.fromSlug("watercolour"))
        assertEquals(Style.PAPER_CUT, Style.fromSlug("paper-cut"))
        assertEquals(Style.ART_DECO, Style.fromSlug("art-deco"))
        assertEquals(Style.POP_ART, Style.fromSlug("pop-art"))
    }
}
