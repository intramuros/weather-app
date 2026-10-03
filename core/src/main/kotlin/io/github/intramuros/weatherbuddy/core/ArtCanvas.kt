package io.github.intramuros.weatherbuddy.core

/**
 * The logical coordinate space all art is designed in: 135 × 300 (9:20),
 * the native size of the pixel-art style. Other styles draw the same layout
 * at a higher resolution. Particles are simulated in these units.
 */
object ArtCanvas {
    const val WIDTH = 135.0
    const val HEIGHT = 300.0

    /** Where the buddy stands. Rain stops here. */
    const val GROUND = 262.0
}
