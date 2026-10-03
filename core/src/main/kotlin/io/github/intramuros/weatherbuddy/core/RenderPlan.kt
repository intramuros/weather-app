package io.github.intramuros.weatherbuddy.core

import kotlin.math.PI
import kotlin.math.sin

/**
 * Everything needed to draw one picture, still or animated.
 *
 * The pipeline is pure and offline-testable:
 * 1. The app fetches JSON/text from the weather APIs and turns it into
 *    [Conditions] with [OpenMeteo] and [Buienradar].
 * 2. [plan] decides what the scene looks like and how the buddy is dressed.
 * 3. [layers] is the stack the app draws bottom-to-top: [stillLayers] for a
 *    still picture, or sprites and particles over time for the live wallpaper.
 */
data class RenderPlan(
    val scene: Scene,
    val outfit: Outfit,
    val layers: List<Layer>,
    /** The finished scene shown, for [Style.wholeScenes] styles. */
    val picture: ScenePicture?,
    /**
     * Art is drawn with the wind blowing to the right; when the real wind blows
     * to the west, layered art is mirrored so her hair follows it. Finished
     * pictures are never mirrored: they are composed (and their widget text
     * placed) one way round.
     */
    val mirrored: Boolean,
    /** Whether rain, snow and wind particles blow to the left. */
    val particlesMirrored: Boolean,
) {
    val stillLayers: List<String> get() = layers.mapNotNull { it.still }

    companion object {
        fun plan(conditions: Conditions, style: Style): RenderPlan {
            val scene = Scene.from(conditions)
            val outfit = Outfit.dress(conditions, scene)
            val picture = if (style.wholeScenes) ScenePicture.choose(conditions, scene) else null
            val west = blowsWest(conditions, scene)
            return RenderPlan(
                scene = scene,
                outfit = outfit,
                layers = style.layers(scene, outfit, conditions, picture),
                picture = picture,
                mirrored = west && !style.wholeScenes,
                particlesMirrored = west,
            )
        }

        /** Wind *from* the east half (roughly 20°–160°) blows towards the west, i.e. screen-left. */
        private fun blowsWest(c: Conditions, scene: Scene): Boolean {
            val from = c.windDirectionDeg ?: return false
            return scene.wind != Wind.CALM && sin(from * PI / 180) > 0.34
        }
    }
}
