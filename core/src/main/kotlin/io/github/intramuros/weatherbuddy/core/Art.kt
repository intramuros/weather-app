package io.github.intramuros.weatherbuddy.core

/**
 * The pixel-art pictures and how the weather moving over them looks.
 *
 * Each [ScenePicture] is one finished square picture at `scenes/<slug>.webp`,
 * plus the widget's icons for it at `scenes/<slug>-icons.webp`. Only their
 * weather moves.
 */
object Art {
    /** Frames per second for the live wallpaper. Pixel art looks right choppy. */
    const val FPS = 12

    /** The picture, as a path in the app's assets. */
    fun scene(picture: ScenePicture) = "scenes/${picture.slug}.webp"

    /** The widget's icons for a picture: the same size, transparent elsewhere. */
    fun sceneIcons(picture: ScenePicture) = "scenes/${picture.slug}-icons.webp"

    /** The ARGB colour particles of this kind are painted in. */
    fun particleColor(kind: ParticleKind): Int = when (kind) {
        ParticleKind.DROP -> 0xFF639BFF.toInt()
        ParticleKind.FLAKE -> 0xFFFFFFFF.toInt()
        ParticleKind.HAILSTONE -> 0xFFE0F0FF.toInt()
        ParticleKind.STREAK -> 0xCCFFFFFF.toInt()
        ParticleKind.LEAF -> 0xFF6ABE30.toInt()
    }
}
