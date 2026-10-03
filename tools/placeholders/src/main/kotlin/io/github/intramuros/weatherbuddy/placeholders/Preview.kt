package io.github.intramuros.weatherbuddy.placeholders

import io.github.intramuros.weatherbuddy.core.ArtCanvas
import io.github.intramuros.weatherbuddy.core.Conditions
import io.github.intramuros.weatherbuddy.core.Layer
import io.github.intramuros.weatherbuddy.core.ParticleField
import io.github.intramuros.weatherbuddy.core.RainStep
import io.github.intramuros.weatherbuddy.core.RenderPlan
import io.github.intramuros.weatherbuddy.core.Style
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.FileImageOutputStream

/**
 * Renders a few seconds of the live wallpaper as animated GIFs, using the same
 * plan, frame timing and particle simulation as the app, so the animation can
 * be checked without a phone.
 *
 * Usage: `./gradlew :tools:placeholders:preview` (writes to build/previews/).
 */
fun main(args: Array<String>) {
    val assets = File(args[0])
    val out = File(args[1]).apply { mkdirs() }
    for ((name, style, conditions) in SCENARIOS) {
        val file = File(out, "$name.gif")
        renderGif(assets, RenderPlan.plan(conditions, style), style, file)
        println("Wrote $file")
    }
}

private fun weather(
    code: Int,
    feels: Double,
    wind: Double,
    gusts: Double,
    from: Double,
    rainMmH: Double? = null,
) = Conditions(
    temperatureC = feels,
    apparentTemperatureC = feels,
    windSpeedKmh = wind,
    windGustsKmh = gusts,
    windDirectionDeg = from,
    uvIndex = null,
    weatherCode = code,
    isDay = true,
    precipitationMm = 0.0,
    rainNowcast = rainMmH?.let { mm -> List(24) { RainStep(14 + it / 12, it % 12 * 5, mm) } }.orEmpty(),
)

private val SCENARIOS = listOf(
    Triple("pixel-art-windy-rain", Style.PIXEL_ART, weather(63, 12.0, 30.0, 50.0, from = 240.0, rainMmH = 2.0)),
    Triple("pixel-art-calm-rain", Style.PIXEL_ART, weather(61, 14.0, 8.0, 15.0, from = 240.0, rainMmH = 1.0)),
    Triple("ukiyo-e-storm-from-east", Style.UKIYO_E, weather(65, 9.0, 50.0, 80.0, from = 90.0, rainMmH = 6.0)),
    Triple("delfts-blauw-snow", Style.DELFTS_BLAUW, weather(73, -3.0, 25.0, 40.0, from = 200.0)),
)

private const val SECONDS = 4.0
private const val OUT_WIDTH = 270
private const val OUT_HEIGHT = 600

private fun renderGif(assets: File, plan: RenderPlan, style: Style, file: File) {
    val images = HashMap<String, BufferedImage>()
    fun image(path: String) = images.getOrPut(path) { ImageIO.read(File(assets, path)) }
    val first = image(plan.stillLayers.first())
    val (artW, artH) = first.width to first.height
    val fields = plan.layers.filterIsInstance<Layer.Particles>().associateWith { layer ->
        layer.specs.mapIndexed { i, spec -> spec to ParticleField(spec, seed = i + 1L) }
    }

    val fps = style.fps
    val frames = (0 until (SECONDS * fps).toInt()).map { n ->
        val t = n.toDouble() / fps
        val art = BufferedImage(artW, artH, BufferedImage.TYPE_INT_ARGB)
        val g = art.createGraphics()
        val aa = if (style.pixelated) RenderingHints.VALUE_ANTIALIAS_OFF else RenderingHints.VALUE_ANTIALIAS_ON
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa)
        for (layer in plan.layers) {
            when (layer) {
                is Layer.Sprite -> g.drawImage(image(layer.frameAt(t)), 0, 0, artW, artH, null)
                is Layer.Particles -> {
                    val saved = g.transform
                    g.scale(artW / ArtCanvas.WIDTH, artH / ArtCanvas.HEIGHT)
                    for ((spec, field) in fields.getValue(layer)) {
                        val look = style.particleLook(spec.kind)
                        g.color = Color(look.argb, true)
                        g.stroke = BasicStroke(look.width.toFloat())
                        field.forEachAt(t) { x, y, dx, dy ->
                            when {
                                !spec.isDot -> g.draw(Line2D.Double(x, y, x + dx, y + dy))
                                style.pixelated -> g.fill(Rectangle2D.Double(x, y, spec.size, spec.size))
                                else -> g.fill(Ellipse2D.Double(x, y, spec.size, spec.size))
                            }
                        }
                    }
                    g.transform = saved
                }
            }
        }
        g.dispose()

        val frame = BufferedImage(OUT_WIDTH, OUT_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val fg = frame.createGraphics()
        val interpolation = if (style.pixelated) {
            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
        } else {
            RenderingHints.VALUE_INTERPOLATION_BILINEAR
        }
        fg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation)
        if (plan.mirrored) fg.transform(AffineTransform(-1.0, 0.0, 0.0, 1.0, OUT_WIDTH.toDouble(), 0.0))
        fg.drawImage(art, 0, 0, OUT_WIDTH, OUT_HEIGHT, null)
        fg.dispose()
        frame
    }
    writeGif(frames, delayCentiseconds = 100 / fps, file)
}

private fun writeGif(frames: List<BufferedImage>, delayCentiseconds: Int, file: File) {
    val writer = ImageIO.getImageWritersByFormatName("gif").next()
    file.delete()
    FileImageOutputStream(file).use { stream ->
        writer.output = stream
        writer.prepareWriteSequence(null)
        for ((i, frame) in frames.withIndex()) {
            val params = writer.defaultWriteParam
            val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(frame), params)
            val format = metadata.nativeMetadataFormatName
            val root = metadata.getAsTree(format) as IIOMetadataNode
            root.child("GraphicControlExtension").apply {
                setAttribute("disposalMethod", "none")
                setAttribute("userInputFlag", "FALSE")
                setAttribute("transparentColorFlag", "FALSE")
                setAttribute("delayTime", delayCentiseconds.toString())
                setAttribute("transparentColorIndex", "0")
            }
            if (i == 0) {
                val loop = IIOMetadataNode("ApplicationExtension").apply {
                    setAttribute("applicationID", "NETSCAPE")
                    setAttribute("authenticationCode", "2.0")
                    userObject = byteArrayOf(1, 0, 0) // Loop forever.
                }
                root.child("ApplicationExtensions").appendChild(loop)
            }
            metadata.setFromTree(format, root)
            writer.writeToSequence(IIOImage(frame, null, metadata), params)
        }
        writer.endWriteSequence()
    }
    writer.dispose()
}

private fun IIOMetadataNode.child(name: String): IIOMetadataNode {
    for (i in 0 until length) {
        val node = item(i)
        if (node.nodeName == name) return node as IIOMetadataNode
    }
    return IIOMetadataNode(name).also { appendChild(it) }
}
