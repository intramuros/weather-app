package io.github.intramuros.weatherbuddy.core

import kotlin.math.PI
import kotlin.math.sin

/**
 * Everything needed to draw one picture, still or animated.
 *
 * The pipeline is pure and offline-testable:
 * 1. The app fetches JSON/text from the weather APIs and turns it into
 *    [Conditions] with [OpenMeteo] and [Buienradar].
 * 2. [plan] decides what the scene looks like and which [ScenePicture] shows it.
 * 3. The app draws [picture] on its own for a still picture, or with
 *    [particles] moving over it for the live wallpaper.
 */
data class RenderPlan(
    val scene: Scene,
    val picture: ScenePicture,
    /** Rain, snow, hail and wind, drawn in this order. Empty when it's dry and calm. */
    val particles: List<ParticleSpec>,
    /**
     * Whether the particles blow to the left. The picture itself is never
     * mirrored: it is composed (and its widget text placed) one way round.
     */
    val particlesMirrored: Boolean,
) {
    companion object {
        fun plan(conditions: Conditions): RenderPlan {
            val scene = Scene.from(conditions)
            return RenderPlan(
                scene = scene,
                picture = ScenePicture.choose(conditions, scene),
                particles = listOfNotNull(ParticleSpec.precipitation(scene, conditions)) + ParticleSpec.wind(scene, conditions),
                particlesMirrored = blowsWest(conditions, scene),
            )
        }

        /** Wind *from* the east half (roughly 20°–160°) blows towards the west, i.e. screen-left. */
        private fun blowsWest(c: Conditions, scene: Scene): Boolean {
            val from = c.windDirectionDeg ?: return false
            return scene.wind != Wind.CALM && sin(from * PI / 180) > 0.34
        }
    }
}
