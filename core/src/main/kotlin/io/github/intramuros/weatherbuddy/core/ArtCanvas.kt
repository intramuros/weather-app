package io.github.intramuros.weatherbuddy.core

/**
 * The logical coordinate space all art is designed in, at the pixel-art
 * style's native size. Other styles draw the same layout at a higher
 * resolution. Particles are simulated in scene units.
 */
object ArtCanvas {
    /** The scene (background and weather) is square. */
    const val SCENE_SIZE = 300.0

    /** The buddy's frame: 9:20, as tall as the scene. */
    const val BUDDY_WIDTH = 135.0

    /** Where the buddy stands. Rain stops here. */
    const val GROUND = 262.0
}
