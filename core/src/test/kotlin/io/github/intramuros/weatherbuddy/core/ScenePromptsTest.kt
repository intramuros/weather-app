package io.github.intramuros.weatherbuddy.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScenePromptsTest {
    /** The scene tools, relative to this module (Gradle runs tests from the module's directory). */
    private val scenes = File("../tools/scenes")

    @Test
    fun usesTheSameTextsAsTheSceneTools() {
        assertEquals(File(scenes, "codex-common.txt").readText().trim(), ScenePrompts.common)
        assertEquals(File(scenes, "${ScenePrompts.EXAMPLE_STYLE.slug}/style.txt").readText().trim(), ScenePrompts.exampleStyle)
    }

    @Test
    fun everyPictureHasItsScene() {
        for (picture in ScenePicture.entries) assertTrue(ScenePrompts.scene(picture).startsWith("This scene: "), picture.slug)
        assertEquals(
            "This scene: rain, cold. She wears: coat, scarf, umbrella.",
            ScenePrompts.scene(ScenePicture.RAIN_COLD),
        )
    }

    /** As tools/scenes/codex_scene.sh puts it together, without its instructions for Codex. */
    @Test
    fun aPromptIsTheCommonPartThenTheStyleThenTheScene() {
        val prompt = ScenePrompts.forPicture("  Medium: stained glass.\n", ScenePicture.SNOW_COLD)
        assertEquals(
            "${ScenePrompts.common}\n\nMedium: stained glass.\n\nThis scene: snow, cold. She wears: coat, scarf, umbrella.",
            prompt,
        )
    }
}
