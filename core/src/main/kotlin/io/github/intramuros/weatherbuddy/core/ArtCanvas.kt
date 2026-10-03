package io.github.intramuros.weatherbuddy.core

/**
 * The logical coordinate space particles are simulated in: the scene at the
 * pixel art's native size, one unit per art pixel.
 */
object ArtCanvas {
    /** The scene is square. */
    const val SCENE_SIZE = 300.0

    /** Where the buddy stands. Rain stops here. */
    const val GROUND = 262.0
}
