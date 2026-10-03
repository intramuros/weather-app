package io.github.intramuros.weatherbuddy.core

/**
 * Everything needed to draw one picture.
 *
 * The pipeline is pure and offline-testable:
 * 1. The app fetches JSON/text from the weather APIs and turns it into
 *    [Conditions] with [OpenMeteo] and [Buienradar].
 * 2. [plan] decides what the scene looks like and how the buddy is dressed.
 * 3. [layers] is an ordered list of asset paths that the app stacks
 *    bottom-to-top into the final picture.
 */
data class RenderPlan(
    val scene: Scene,
    val outfit: Outfit,
    val layers: List<String>,
    /** The finished scene shown, for [Style.wholeScenes] styles. */
    val picture: ScenePicture?,
) {
    companion object {
        fun plan(conditions: Conditions, style: Style): RenderPlan {
            val scene = Scene.from(conditions)
            val outfit = Outfit.dress(conditions, scene)
            val picture = if (style.wholeScenes) ScenePicture.choose(conditions, scene) else null
            return RenderPlan(scene, outfit, style.layers(scene, outfit, picture), picture)
        }
    }
}
