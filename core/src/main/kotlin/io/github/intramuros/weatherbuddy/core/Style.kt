package io.github.intramuros.weatherbuddy.core

/**
 * Art styles and the layer stack that turns a scene + outfit into a picture.
 *
 * Every style ships the same set of transparent PNG layers at
 * `<style>/<category>/<slug>.png`, so switching style only swaps the folder.
 * Scenery layers (see [isScenery]) are square; the buddy's layers are a 9:20
 * frame of the same height, so the buddy can stand anywhere in the scene.
 * Animated parts are numbered frames: `hair/breezy-0.png` … `-3.png`.
 *
 * Styles with [wholeScenes] instead ship one finished square picture per
 * [ScenePicture] at `<style>/scene/<slug>.webp`, plus the widget's icons for
 * it at `<style>/scene/<slug>-icons.webp`. Only their weather moves.
 */
enum class Style(
    val slug: String,
    val displayName: String,
    /** Scale with nearest-neighbour instead of smoothing, to keep pixels crisp. */
    val pixelated: Boolean,
    /** Frames per second for the live wallpaper. Pixel art looks right choppy. */
    val fps: Int,
    /** Drawn as one finished picture per kind of weather, rather than layers. */
    val wholeScenes: Boolean = false,
) {
    PIXEL_ART("pixel-art", "Pixel art", pixelated = true, fps = 12, wholeScenes = true),
    UKIYO_E("ukiyo-e", "Ukiyo-e", pixelated = false, fps = 24),
    DELFTS_BLAUW("delfts-blauw", "Delfts Blauw", pixelated = false, fps = 24),
    ;

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
        // Hiroshige's rain: long, thin, dark lines.
        UKIYO_E -> when (kind) {
            ParticleKind.DROP -> ParticleLook(0xFF2E4A7D.toInt(), 0.5)
            ParticleKind.FLAKE -> ParticleLook(0xFFFFFFFF.toInt(), 0.5)
            ParticleKind.HAILSTONE -> ParticleLook(0xFFFAF6EC.toInt(), 0.5)
            ParticleKind.STREAK -> ParticleLook(0x992E4A7D.toInt(), 0.4)
            ParticleKind.LEAF -> ParticleLook(0xFFC0392B.toInt(), 0.5)
        }
        // Everything in cobalt, as if painted on the tile.
        DELFTS_BLAUW -> when (kind) {
            ParticleKind.DROP -> ParticleLook(0xFF1F3C88.toInt(), 0.8)
            ParticleKind.FLAKE -> ParticleLook(0xFF7A9BD8.toInt(), 0.8)
            ParticleKind.HAILSTONE -> ParticleLook(0xFF2F5BB7.toInt(), 0.8)
            ParticleKind.STREAK -> ParticleLook(0x991F3C88.toInt(), 0.6)
            ParticleKind.LEAF -> ParticleLook(0xFF2F5BB7.toInt(), 0.8)
        }
    }

    private fun path(category: String, slug: String) = "${this.slug}/$category/$slug.png"

    private fun sprite(category: String, slug: String) = Layer.Sprite(path(category, slug))

    private fun loop(category: String, slug: String, frames: Int, secondsEach: Double) =
        Layer.Sprite((0 until frames).map { Frame(path(category, "$slug-$it"), secondsEach) })

    private fun background(sky: Sky, time: TimeOfDay) = path("background", "${sky.slug}-${time.slug}")

    private fun windFx(wind: Wind) = path("fx", "wind-${wind.slug}")

    private fun scenePicture(picture: ScenePicture) = "$slug/scene/${picture.slug}.webp"

    /** The widget's icons for a [ScenePicture]: the same size, transparent elsewhere. */
    fun sceneIcons(picture: ScenePicture) = "$slug/scene/${picture.slug}-icons.webp"

    private fun face(expression: Expression): Layer.Sprite =
        if (expression == Expression.SLEEPY) {
            sprite("face", expression.slug) // Eyes already closed.
        } else {
            Layer.Sprite(
                listOf(
                    Frame(path("face", expression.slug), BLINK_EVERY_SECONDS),
                    Frame(path("face", "${expression.slug}-blink"), BLINK_SECONDS),
                ),
            )
        }

    private fun hair(wind: Wind): Layer.Sprite = when (wind) {
        Wind.CALM -> sprite("hair", "calm")
        Wind.BREEZY -> loop("hair", "breezy", HAIR_FRAMES, 0.15)
        Wind.STORMY -> loop("hair", "stormy", HAIR_FRAMES, 0.08)
    }

    private fun accessory(accessory: Accessory, wind: Wind): Layer.Sprite = when {
        accessory == Accessory.SCARF && wind != Wind.CALM ->
            loop("accessory", "scarf-wind", SCARF_FRAMES, if (wind == Wind.STORMY) 0.07 else 0.12)
        else -> sprite("accessory", accessory.slug)
    }

    /**
     * The layer stack, bottom-most first. [picture] is the scene to show for
     * [wholeScenes] styles (see [ScenePicture.choose]).
     */
    fun layers(scene: Scene, outfit: Outfit, conditions: Conditions, picture: ScenePicture?): List<Layer> =
        if (wholeScenes) {
            wholeScene(requireNotNull(picture) { "$this is drawn as whole scenes" }, scene, conditions)
        } else {
            layeredScene(scene, outfit, conditions)
        }

    /** The finished picture, with moving weather over it in the live wallpaper. */
    private fun wholeScene(picture: ScenePicture, scene: Scene, conditions: Conditions): List<Layer> = buildList {
        add(Layer.Sprite(scenePicture(picture)))
        ParticleSpec.precipitation(scene, conditions)?.let { add(Layer.Particles(null, listOf(it))) }
        ParticleSpec.wind(scene, conditions).takeIf { it.isNotEmpty() }?.let { add(Layer.Particles(null, it)) }
    }

    private fun layeredScene(scene: Scene, outfit: Outfit, conditions: Conditions): List<Layer> = buildList {
        val precipitation = ParticleSpec.precipitation(scene, conditions)
            ?.let { Layer.Particles(path("fx", scene.precipitation.slug), listOf(it)) }
        // Under an open umbrella the rain falls behind the buddy.
        val rainBehind = outfit.has(Accessory.UMBRELLA)

        add(Layer.Sprite(background(scene.sky, scene.timeOfDay)))
        if (rainBehind && precipitation != null) add(precipitation)
        add(sprite("body", "base"))
        add(face(outfit.expression))
        add(sprite("bottom", outfit.bottom.slug))
        add(sprite("footwear", outfit.footwear.slug))
        add(sprite("top", outfit.top.slug))
        add(hair(scene.wind))
        outfit.outerwear?.let { add(sprite("outerwear", it.slug)) }
        outfit.accessories.forEach { add(accessory(it, scene.wind)) }
        if (!rainBehind && precipitation != null) add(precipitation)
        if (scene.wind != Wind.CALM) add(Layer.Particles(windFx(scene.wind), ParticleSpec.wind(scene, conditions)))
    }

    /** Every picture this style's asset pack must contain (not counting [sceneIcons]). */
    fun requiredAssets(): List<String> = if (wholeScenes) {
        ScenePicture.entries.map(::scenePicture)
    } else {
        layerAssets()
    }

    private fun layerAssets(): List<String> = buildList {
        Sky.entries.forEach { sky -> TimeOfDay.entries.forEach { time -> add(background(sky, time)) } }
        add(path("body", "base"))
        Expression.entries.forEach { addAll(face(it).assets) }
        Bottom.entries.forEach { add(path("bottom", it.slug)) }
        Footwear.entries.forEach { add(path("footwear", it.slug)) }
        Top.entries.forEach { add(path("top", it.slug)) }
        Wind.entries.forEach { addAll(hair(it).assets) }
        Outerwear.entries.forEach { add(path("outerwear", it.slug)) }
        Accessory.entries.forEach { add(path("accessory", it.slug)) }
        addAll(accessory(Accessory.SCARF, Wind.BREEZY).assets)
        Precipitation.entries.filter { it != Precipitation.NONE }.forEach { add(path("fx", it.slug)) }
        Wind.entries.filter { it != Wind.CALM }.forEach { add(windFx(it)) }
    }

    companion object {
        const val HAIR_FRAMES = 4
        const val SCARF_FRAMES = 3
        private const val BLINK_EVERY_SECONDS = 3.5
        private const val BLINK_SECONDS = 0.15
        private val SCENERY = setOf("background", "fx", "scene")

        fun fromSlug(slug: String?): Style? = entries.firstOrNull { it.slug == slug }

        /** Background and weather effects fill the scene; every other layer belongs to the buddy. */
        fun isScenery(path: String): Boolean = path.split('/').getOrNull(1) in SCENERY
    }
}
