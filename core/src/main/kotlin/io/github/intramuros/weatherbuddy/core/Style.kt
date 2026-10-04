package io.github.intramuros.weatherbuddy.core

/**
 * Art styles. Every style draws the same [ScenePicture]s, one finished square
 * picture each at `scenes/<style>/<picture>.webp`, so switching style only
 * swaps the folder. Only the weather moves, as particles over the picture.
 *
 * A style ships either all of its pictures or none: the app only offers the
 * styles whose folder is complete.
 */
enum class Style(
    val slug: String,
    val displayName: String,
    /** Scale with nearest-neighbour instead of smoothing, and snap particles to the art's pixel grid. */
    val pixelated: Boolean,
    /** Frames per second for the live wallpaper. Pixel art looks right choppy. */
    val fps: Int,
    /**
     * Whether each picture comes with the widget's icons ([sceneIcons]) and text
     * spots measured on it. Otherwise the widget draws its text in the sky.
     */
    val hasWidgetIcons: Boolean,
) {
    PIXEL_ART("pixel-art", "Pixel art", pixelated = true, fps = 12, hasWidgetIcons = true),
    ANIME("anime", "Anime", pixelated = false, fps = 24, hasWidgetIcons = false),
    UKIYO_E("ukiyo-e", "Ukiyo-e", pixelated = false, fps = 24, hasWidgetIcons = false),
    DELFT_BLUE("delft-blue", "Delft blue", pixelated = false, fps = 24, hasWidgetIcons = false),
    MUCHA("mucha", "Mucha", pixelated = false, fps = 24, hasWidgetIcons = false),
    ;

    /** The picture, as a path in the app's assets. */
    fun scene(picture: ScenePicture) = "scenes/$slug/${picture.slug}.webp"

    /** The widget's icons for a picture: the same size, transparent elsewhere. */
    fun sceneIcons(picture: ScenePicture) = "scenes/$slug/${picture.slug}-icons.webp"

    /** How this style paints a particle: ARGB colour and line width in [ArtCanvas] units. */
    data class ParticleLook(val argb: Int, val width: Double)

    fun particleLook(kind: ParticleKind): ParticleLook = when (this) {
        PIXEL_ART -> when (kind) {
            ParticleKind.DROP -> ParticleLook(0xFF639BFF.toInt(), 1.0)
            ParticleKind.FLAKE -> ParticleLook(0xFFFFFFFF.toInt(), 1.0)
            ParticleKind.HAILSTONE -> ParticleLook(0xFFE0F0FF.toInt(), 1.0)
            ParticleKind.STREAK -> ParticleLook(0xCCFFFFFF.toInt(), 1.0)
            ParticleKind.LEAF -> ParticleLook(0xFF6ABE30.toInt(), 1.0)
        }
        // Thin, glassy rain and soft snow, as in an anime film.
        ANIME -> when (kind) {
            ParticleKind.DROP -> ParticleLook(0xB3DCEBFF.toInt(), 0.4)
            ParticleKind.FLAKE -> ParticleLook(0xF2FFFFFF.toInt(), 0.5)
            ParticleKind.HAILSTONE -> ParticleLook(0xFFEAF4FF.toInt(), 0.5)
            ParticleKind.STREAK -> ParticleLook(0x80FFFFFF.toInt(), 0.3)
            ParticleKind.LEAF -> ParticleLook(0xFF8FBF4A.toInt(), 0.5)
        }
        // Fine, straight indigo rain lines and paper-white snow, as in a Hiroshige print.
        UKIYO_E -> when (kind) {
            ParticleKind.DROP -> ParticleLook(0x99404C6E.toInt(), 0.35)
            ParticleKind.FLAKE -> ParticleLook(0xF2FBF6EC.toInt(), 0.6)
            ParticleKind.HAILSTONE -> ParticleLook(0xFFF1EDE4.toInt(), 0.5)
            ParticleKind.STREAK -> ParticleLook(0x66404C6E.toInt(), 0.3)
            ParticleKind.LEAF -> ParticleLook(0xFFC8553D.toInt(), 0.6)
        }
        // Cobalt only, like the paint. A middle blue, since the sky is cream by day and deep blue at night.
        DELFT_BLUE -> when (kind) {
            ParticleKind.DROP -> ParticleLook(0xB34A6CC8.toInt(), 0.4)
            ParticleKind.FLAKE -> ParticleLook(0xE67D9AE0.toInt(), 0.6)
            ParticleKind.HAILSTONE -> ParticleLook(0xFF9DB3EA.toInt(), 0.5)
            ParticleKind.STREAK -> ParticleLook(0x804A6CC8.toInt(), 0.3)
            ParticleKind.LEAF -> ParticleLook(0xFF2B4BAA.toInt(), 0.6)
        }
        // Fine powder-blue rain and cream snow from the poster's palette, autumn-ochre leaves.
        MUCHA -> when (kind) {
            ParticleKind.DROP -> ParticleLook(0x998AA6BE.toInt(), 0.35)
            ParticleKind.FLAKE -> ParticleLook(0xF2FBF3E2.toInt(), 0.6)
            ParticleKind.HAILSTONE -> ParticleLook(0xFFF3EBDA.toInt(), 0.5)
            ParticleKind.STREAK -> ParticleLook(0x668AA6BE.toInt(), 0.3)
            ParticleKind.LEAF -> ParticleLook(0xFFD08A3A.toInt(), 0.6)
        }
    }

    companion object {
        fun fromSlug(slug: String?): Style? = entries.firstOrNull { it.slug == slug }
    }
}
