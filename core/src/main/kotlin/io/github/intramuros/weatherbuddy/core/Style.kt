package io.github.intramuros.weatherbuddy.core

/**
 * Art styles and the layer stack that turns a scene + outfit into a picture.
 *
 * Most styles ship the same set of transparent PNG layers at
 * `<style>/<category>/<slug>.png`, so switching style only swaps the folder.
 * Scenery layers (see [isScenery]) are square; the buddy's layers are a 9:20
 * frame of the same height, so the buddy can stand anywhere in the scene.
 *
 * Styles with [wholeScenes] instead ship one finished square picture per
 * [ScenePicture] at `<style>/scene/<slug>.webp`, plus the widget's icons for
 * it at `<style>/scene/<slug>-icons.webp`.
 */
enum class Style(
    val slug: String,
    val displayName: String,
    /** Scale layers with nearest-neighbour instead of smoothing, to keep pixels crisp. */
    val pixelated: Boolean = false,
    /** Drawn as one finished picture per kind of weather, rather than layers. */
    val wholeScenes: Boolean = false,
) {
    PIXEL_ART("pixel-art", "Pixel art", pixelated = true, wholeScenes = true),
    UKIYO_E("ukiyo-e", "Ukiyo-e"),
    DELFTS_BLAUW("delfts-blauw", "Delfts Blauw"),
    ;

    private fun layer(category: String, slug: String) = "${this.slug}/$category/$slug.png"

    private fun background(sky: Sky, time: TimeOfDay) = layer("background", "${sky.slug}-${time.slug}")

    private fun windFx(wind: Wind) = layer("fx", "wind-${wind.slug}")

    private fun scenePicture(picture: ScenePicture) = "$slug/scene/${picture.slug}.webp"

    /** The widget's icons for a [ScenePicture]: the same size, transparent elsewhere. */
    fun sceneIcons(picture: ScenePicture) = "$slug/scene/${picture.slug}-icons.webp"

    /**
     * Asset paths to draw, bottom-most first. [picture] is the scene to show for
     * [wholeScenes] styles (see [ScenePicture.choose]).
     */
    fun layers(scene: Scene, outfit: Outfit, picture: ScenePicture?): List<String> = if (wholeScenes) {
        listOf(scenePicture(requireNotNull(picture) { "$this is drawn as whole scenes" }))
    } else {
        layeredScene(scene, outfit)
    }

    private fun layeredScene(scene: Scene, outfit: Outfit): List<String> = buildList {
        val precipitation = layer("fx", scene.precipitation.slug).takeIf { scene.precipitation != Precipitation.NONE }
        // Under an open umbrella the rain falls behind the buddy.
        val rainBehind = outfit.has(Accessory.UMBRELLA)

        add(background(scene.sky, scene.timeOfDay))
        if (rainBehind) addIfNotNull(precipitation)
        add(layer("body", "base"))
        add(layer("face", outfit.expression.slug))
        add(layer("bottom", outfit.bottom.slug))
        add(layer("footwear", outfit.footwear.slug))
        add(layer("top", outfit.top.slug))
        outfit.outerwear?.let { add(layer("outerwear", it.slug)) }
        outfit.accessories.forEach { add(layer("accessory", it.slug)) }
        if (!rainBehind) addIfNotNull(precipitation)
        if (scene.wind != Wind.CALM) add(windFx(scene.wind))
    }

    /** Every picture this style's asset pack must contain (not counting [sceneIcons]). */
    fun requiredAssets(): List<String> = if (wholeScenes) {
        ScenePicture.entries.map(::scenePicture)
    } else {
        layerAssets()
    }

    private fun layerAssets(): List<String> = buildList {
        Sky.entries.forEach { sky -> TimeOfDay.entries.forEach { time -> add(background(sky, time)) } }
        add(layer("body", "base"))
        Expression.entries.forEach { add(layer("face", it.slug)) }
        Bottom.entries.forEach { add(layer("bottom", it.slug)) }
        Footwear.entries.forEach { add(layer("footwear", it.slug)) }
        Top.entries.forEach { add(layer("top", it.slug)) }
        Outerwear.entries.forEach { add(layer("outerwear", it.slug)) }
        Accessory.entries.forEach { add(layer("accessory", it.slug)) }
        Precipitation.entries.filter { it != Precipitation.NONE }.forEach { add(layer("fx", it.slug)) }
        Wind.entries.filter { it != Wind.CALM }.forEach { add(windFx(it)) }
    }

    companion object {
        private val SCENERY = setOf("background", "fx", "scene")

        fun fromSlug(slug: String?): Style? = entries.firstOrNull { it.slug == slug }

        /** Whole scenes, backgrounds and weather effects fill the scene; every other layer belongs to the buddy. */
        fun isScenery(path: String): Boolean = path.split('/').getOrNull(1) in SCENERY
    }
}

private fun <T> MutableList<T>.addIfNotNull(item: T?) {
    if (item != null) add(item)
}
