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
    /**
     * Art is drawn with the wind blowing to the right; when the real wind blows
     * to the west, the whole picture is mirrored so rain and hair follow it.
     */
    val mirrored: Boolean,
) {
    val stillLayers: List<String> get() = layers.map { it.still }

    companion object {
        fun plan(conditions: Conditions, style: Style): RenderPlan {
            val scene = Scene.from(conditions)
            val outfit = Outfit.dress(conditions, scene)
            return RenderPlan(scene, outfit, style.layers(scene, outfit, conditions), blowsWest(conditions, scene))
        }

        /** Wind *from* the east half (roughly 20°–160°) blows towards the west, i.e. screen-left. */
        private fun blowsWest(c: Conditions, scene: Scene): Boolean {
            val from = c.windDirectionDeg ?: return false
            return scene.wind != Wind.CALM && sin(from * PI / 180) > 0.34
        }
    }
}
