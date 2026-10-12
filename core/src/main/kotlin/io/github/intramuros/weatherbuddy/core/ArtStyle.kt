package io.github.intramuros.weatherbuddy.core

import kotlinx.serialization.Serializable

/**
 * A style the pictures can be in: one of the app's own [Style]s, or a
 * [CustomStyle] the user designed and had drawn on their phone. Every style
 * draws the same [ScenePicture]s; only the weather moves, as particles over them.
 */
sealed interface ArtStyle {
    /** Identifies the style in the settings and in file names. */
    val slug: String

    val displayName: String

    /** Scale with nearest-neighbour instead of smoothing, and snap particles to the art's pixel grid. */
    val pixelated: Boolean

    /** Frames per second for the live wallpaper. Pixel art looks right choppy. */
    val fps: Int

    /** How this style paints a particle. */
    fun particleLook(kind: ParticleKind): ParticleLook
}

/** A particle's ARGB colour and line width in [ArtCanvas] units. */
@Serializable
data class ParticleLook(val argb: Int, val width: Double)
