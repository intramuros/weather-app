package io.github.intramuros.weatherbuddy.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * A style the user designed in a conversation ([StyleDesigner]), whose pictures
 * were drawn with their own OpenAI account and live on their phone. Like a
 * built-in [Style], it's only offered once all of its pictures are there.
 */
@Serializable
data class CustomStyle(
    override val slug: String,
    @SerialName("name") override val displayName: String,
    /** A sentence or two about the style, for the user. */
    val summary: String,
    /** The style's part of every picture's prompt ([ScenePrompts]), like `tools/scenes/<style>/style.txt`. */
    val description: String,
    val particles: Particles,
    /** Its pictures are all drawn at this quality, so they match the preview. */
    val quality: ImageQuality = ImageQuality.MEDIUM,
    val createdAtMillis: Long = 0,
) : ArtStyle {
    // Drawn pictures have no pixel grid, and move as smoothly as the painted styles.
    override val pixelated: Boolean get() = false
    override val fps: Int get() = 24

    override fun particleLook(kind: ParticleKind): ParticleLook = when (kind) {
        ParticleKind.DROP -> particles.drop
        ParticleKind.FLAKE -> particles.flake
        ParticleKind.HAILSTONE -> particles.hailstone
        ParticleKind.STREAK -> particles.streak
        ParticleKind.LEAF -> particles.leaf
    }

    @Serializable
    data class Particles(
        val drop: ParticleLook,
        val flake: ParticleLook,
        val hailstone: ParticleLook,
        val streak: ParticleLook,
        val leaf: ParticleLook,
    ) {
        companion object {
            fun of(style: ArtStyle) = Particles(
                drop = style.particleLook(ParticleKind.DROP),
                flake = style.particleLook(ParticleKind.FLAKE),
                hailstone = style.particleLook(ParticleKind.HAILSTONE),
                streak = style.particleLook(ParticleKind.STREAK),
                leaf = style.particleLook(ParticleKind.LEAF),
            )
        }
    }

    companion object {
        /** Made styles' slugs start with this, so they never clash with a built-in one. */
        const val SLUG_PREFIX = "made-"

        private val SLUG = Regex("""made-[0-9a-f]{8}""")

        /**
         * Drawn first, as the preview: a bright day with her in plain clothes, so she
         * shows clearly. Every other picture then copies how she looks in it.
         */
        val REFERENCE = ScenePicture.PARTLY_CLOUDY_MILD

        fun newSlug(random: Random = Random.Default): String =
            SLUG_PREFIX + random.nextBytes(4).joinToString("") { "%02x".format(it) }

        /** Whether [slug] could be a made style's, and so is safe to use as a folder name. */
        fun isSlug(slug: String?): Boolean = slug != null && SLUG.matches(slug)
    }
}

/** How carefully the image model draws: better costs more and takes longer. */
@Serializable
enum class ImageQuality(val apiValue: String) {
    MEDIUM("medium"),
    HIGH("high"),
}
