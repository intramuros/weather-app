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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

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
    Triple("pixel-art-snow", Style.PIXEL_ART, weather(73, -3.0, 20.0, 30.0, from = 240.0)),
    Triple("pixel-art-storm-from-east", Style.PIXEL_ART, weather(95, 14.0, 45.0, 75.0, from = 90.0, rainMmH = 5.0)),
    Triple("ukiyo-e-storm-from-east", Style.UKIYO_E, weather(65, 9.0, 50.0, 80.0, from = 90.0, rainMmH = 6.0)),
    Triple("delfts-blauw-snow", Style.DELFTS_BLAUW, weather(73, -3.0, 25.0, 40.0, from = 200.0)),
)

private const val SECONDS = 4.0
private const val OUT_WIDTH = 270
private const val OUT_HEIGHT = 600

private fun renderGif(assets: File, plan: RenderPlan, style: Style, file: File) {
    val images = HashMap<String, BufferedImage>()
    fun image(path: String) = images.getOrPut(path) { ImageIO.read(File(assets, path)) }
    val fields = plan.layers.filterIsInstance<Layer.Particles>().associateWith { layer ->
        layer.specs.mapIndexed { i, spec -> spec to ParticleField(spec, seed = i + 1L) }
    }
    // Same layout as the wallpaper: the square scene covers the frame, the buddy stands in the middle.
    val sceneSize = OUT_HEIGHT.toDouble()
    val sceneLeft = (OUT_WIDTH - sceneSize) / 2
    fun bounds(path: String): Rectangle2D.Double {
        val img = image(path)
        val w = sceneSize * img.width / img.height
        val left = if (Style.isScenery(path)) sceneLeft else (OUT_WIDTH - w) / 2
        return Rectangle2D.Double(left, 0.0, w, sceneSize)
    }

    val fps = style.fps
    val frames = (0 until (SECONDS * fps).toInt()).map { n ->
        val t = n.toDouble() / fps
        val frame = BufferedImage(OUT_WIDTH, OUT_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = frame.createGraphics()
        val interpolation = if (style.pixelated) {
            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
        } else {
            RenderingHints.VALUE_INTERPOLATION_BILINEAR
        }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation)
        val aa = if (style.pixelated) RenderingHints.VALUE_ANTIALIAS_OFF else RenderingHints.VALUE_ANTIALIAS_ON
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa)
        // Each layer flips in place, like in the app.
        fun mirrored(b: Rectangle2D.Double, mirror: Boolean, draw: () -> Unit) {
            val saved = g.transform
            if (mirror) {
                g.translate(b.centerX, 0.0)
                g.scale(-1.0, 1.0)
                g.translate(-b.centerX, 0.0)
            }
            draw()
            g.transform = saved
        }
        for (layer in plan.layers) {
            when (layer) {
                is Layer.Sprite -> {
                    val path = layer.frameAt(t)
                    val b = bounds(path)
                    mirrored(b, plan.mirrored) { g.drawImage(image(path), b.x.toInt(), b.y.toInt(), b.width.toInt(), b.height.toInt(), null) }
                }
                is Layer.Particles -> mirrored(Rectangle2D.Double(sceneLeft, 0.0, sceneSize, sceneSize), plan.particlesMirrored) {
                    g.translate(sceneLeft, 0.0)
                    g.scale(sceneSize / ArtCanvas.SCENE_SIZE, sceneSize / ArtCanvas.SCENE_SIZE)
                    for ((spec, field) in fields.getValue(layer)) {
                        val look = style.particleLook(spec.kind)
                        g.color = Color(look.argb, true)
                        g.stroke = BasicStroke(look.width.toFloat())
                        field.forEachAt(t) { x, y, dx, dy ->
                            when {
                                // Pixel art: whole scene pixels, like the app.
                                style.pixelated && spec.isDot -> g.fill(Rectangle2D.Double(floor(x), floor(y), spec.size, spec.size))
                                style.pixelated -> {
                                    val steps = maxOf(abs(dx), abs(dy)).roundToInt().coerceAtLeast(1)
                                    for (i in 0..steps) {
                                        g.fill(Rectangle2D.Double(floor(x + dx * i / steps), floor(y + dy * i / steps), 1.0, 1.0))
                                    }
                                }
                                spec.isDot -> g.fill(Ellipse2D.Double(x, y, spec.size, spec.size))
                                else -> g.draw(Line2D.Double(x, y, x + dx, y + dy))
                            }
                        }
                    }
                }
            }
        }
        g.dispose()
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
