package io.github.intramuros.weatherbuddy.core

/**
 * The prompt each picture of a painted style is drawn from, as
 * `tools/scenes/codex_scene.sh` sends it: what every picture needs ([common]),
 * then the style's description, then the scene's weather and outfit ([scene]).
 *
 * The texts are the files in `tools/scenes`, packaged with this module, so
 * styles made on the phone are drawn exactly like the built-in ones.
 */
object ScenePrompts {
    /** What every picture needs, whatever its style: `tools/scenes/codex-common.txt`. */
    val common: String by lazy { resource("codex-common.txt") }

    /** A finished style's description, as an example of what one says: `tools/scenes/watercolour/style.txt`. */
    val exampleStyle: String by lazy { resource("watercolour/style.txt") }

    /** The style [exampleStyle] describes. */
    val EXAMPLE_STYLE = Style.WATERCOLOUR

    /** Each scene's line from the table in `tools/scenes/anime/PROMPTS.md`, by picture slug. */
    private val lines: Map<String, String> by lazy {
        Regex("""^\| `([a-z-]+)\.png` \| (.*) \|$""", RegexOption.MULTILINE)
            .findAll(resource("anime/PROMPTS.md"))
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** The scene's weather and outfit, e.g. "This scene: rain, cold. She wears: coat, scarf, umbrella." */
    fun scene(picture: ScenePicture): String =
        checkNotNull(lines[picture.slug]) { "no line for ${picture.slug} in anime/PROMPTS.md" }

    /** The whole prompt for [picture] in the style that [description] describes. */
    fun forPicture(description: String, picture: ScenePicture): String =
        listOf(common, description.trim(), scene(picture)).joinToString("\n\n")

    private fun resource(name: String): String {
        val stream = checkNotNull(ScenePrompts::class.java.getResourceAsStream("$PATH/$name")) { "$name is not packaged" }
        return stream.bufferedReader().use { it.readText().trim() }
    }

    /** Where the build puts the files (core/build.gradle.kts). */
    private const val PATH = "/weatherbuddy/prompts"
}
