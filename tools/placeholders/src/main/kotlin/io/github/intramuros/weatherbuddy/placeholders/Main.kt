package io.github.intramuros.weatherbuddy.placeholders

import io.github.intramuros.weatherbuddy.core.Style
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.util.Random
import javax.imageio.ImageIO

/**
 * Generates placeholder art for every layer of the layered styles, so the app
 * can be tried end to end before the real art exists. Scenery (background and
 * effects) is drawn in a 300 × 300 logical canvas, the buddy in a 135 × 300
 * frame, both 4× larger with anti-aliasing. Styles drawn as whole scenes
 * (pixel art) have real pictures; see `tools/scenes/`.
 *
 * Usage: `./gradlew :tools:placeholders:run`
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "app/src/main/assets/styles")
    for (style in Style.entries.filter { !it.wholeScenes }) {
        val painter = Painter(style)
        for (path in style.requiredAssets()) {
            val file = File(out, path)
            file.parentFile.mkdirs()
            ImageIO.write(painter.paint(path), "png", file)
        }
        println("${style.displayName}: ${style.requiredAssets().size} layers")
    }
    println("Wrote placeholders to ${out.absolutePath}")
}

private const val W = 135.0
private const val H = 300.0
private const val GROUND = 262.0
private const val SCENE_W = 300.0

/** Where the buddy's frame sits when the scene is shown as a wallpaper. */
private const val FRAME_X = (SCENE_W - W) / 2

private class Palette(
    val skyDay: Color,
    val skyNight: Color,
    val ground: Color,
    val skin: Color,
    val outline: Color,
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val dark: Color,
    val water: Color,
    val light: Color,
)

private fun rgb(hex: Int) = Color(hex)

private val PALETTES = mapOf(
    Style.UKIYO_E to Palette(
        skyDay = rgb(0xEFE3C8), skyNight = rgb(0x1F2F4A), ground = rgb(0x8A9A5B), skin = rgb(0xF2DCC0),
        outline = rgb(0x1C1C1C), primary = rgb(0xC0392B), secondary = rgb(0x2E4A7D), accent = rgb(0xD4A23A),
        dark = rgb(0x3B2F2F), water = rgb(0x2E4A7D), light = rgb(0xFAF6EC),
    ),
    Style.DELFTS_BLAUW to Palette(
        skyDay = rgb(0xF7F7F2), skyNight = rgb(0x1F3C88), ground = rgb(0xDCE3F2), skin = rgb(0xFFFFFF),
        outline = rgb(0x1F3C88), primary = rgb(0x2F5BB7), secondary = rgb(0x7A9BD8), accent = rgb(0xA9BEE8),
        dark = rgb(0x1F3C88), water = rgb(0x1F3C88), light = rgb(0xFFFFFF),
    ),
)

private class Painter(private val style: Style) {
    private val p = PALETTES.getValue(style)
    private val scale = 4
    private lateinit var g: Graphics2D

    fun paint(path: String): BufferedImage {
        val width = if (Style.isScenery(path)) SCENE_W else W
        val image = BufferedImage((width * scale).toInt(), (H * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
        g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.scale(scale.toDouble(), scale.toDouble())
        g.stroke = BasicStroke(1f)
        val (_, category, file) = path.split('/')
        val slug = file.removeSuffix(".png")
        when (category) {
            "background" -> background(slug)
            "body" -> body()
            "face" -> face(slug)
            "bottom" -> bottom(slug)
            "footwear" -> footwear(slug)
            "top" -> top(slug)
            "outerwear" -> outerwear(slug)
            "accessory" -> accessory(slug)
            "fx" -> fx(slug)
            else -> error("no placeholder for $path")
        }
        g.dispose()
        return image
    }

    private fun fill(shape: Shape, color: Color, outlined: Boolean = true) {
        g.color = color
        g.fill(shape)
        if (outlined) {
            g.color = p.outline
            g.draw(shape)
        }
    }

    private fun rect(x: Double, y: Double, w: Double, h: Double) = Rectangle2D.Double(x, y, w, h)
    private fun oval(cx: Double, cy: Double, rx: Double, ry: Double = rx) =
        Ellipse2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry)
    private fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: Color = p.outline) {
        g.color = color
        g.draw(Line2D.Double(x1, y1, x2, y2))
    }

    private fun mix(a: Color, b: Color, t: Double) = Color(
        (a.red + (b.red - a.red) * t).toInt(),
        (a.green + (b.green - a.green) * t).toInt(),
        (a.blue + (b.blue - a.blue) * t).toInt(),
    )

    // ---- background -------------------------------------------------------

    private fun background(slug: String) {
        val night = slug.endsWith("-night")
        val sky = slug.removeSuffix("-night").removeSuffix("-day")
        val grey = Color(0x8C8C96)
        val base = if (night) p.skyNight else p.skyDay
        val skyColor = when (sky) {
            "overcast" -> mix(base, grey, 0.45)
            "fog" -> mix(base, Color.WHITE, 0.5)
            "thunderstorm" -> mix(base, Color(0x2B2B3A), 0.6)
            else -> base
        }
        fill(rect(0.0, 0.0, SCENE_W, GROUND), skyColor, outlined = false)
        fill(rect(0.0, GROUND, SCENE_W, H - GROUND), if (night) mix(p.ground, Color.BLACK, 0.4) else p.ground, outlined = false)
        g.translate(FRAME_X, 0.0)

        val celestial = if (night) p.light else p.accent
        when (sky) {
            "clear" -> fill(oval(100.0, 50.0, 14.0), celestial)
            "partly-cloudy" -> {
                fill(oval(100.0, 50.0, 14.0), celestial)
                cloud(70.0, 62.0, p.light)
            }
            "overcast" -> { cloud(35.0, 45.0, p.light); cloud(90.0, 70.0, p.light); cloud(55.0, 95.0, p.light) }
            "fog" -> for (y in listOf(150.0, 190.0, 230.0)) fill(rect(-FRAME_X, y, SCENE_W, 8.0), mix(skyColor, Color.WHITE, 0.6), false)
            "thunderstorm" -> {
                cloud(45.0, 50.0, grey)
                cloud(95.0, 60.0, grey)
                val bolt = Path2D.Double().apply {
                    moveTo(80.0, 70.0); lineTo(70.0, 95.0); lineTo(78.0, 95.0); lineTo(68.0, 120.0)
                    lineTo(88.0, 88.0); lineTo(80.0, 88.0); lineTo(88.0, 70.0); closePath()
                }
                fill(bolt, p.accent)
            }
        }
        // Style signatures: a tile border for Delft, a red seal for ukiyo-e.
        g.translate(-FRAME_X, 0.0)
        when (style) {
            Style.DELFTS_BLAUW -> {
                g.color = p.outline
                g.stroke = BasicStroke(3f)
                g.draw(rect(3.0, 3.0, SCENE_W - 6, H - 6))
                g.stroke = BasicStroke(1f)
                g.draw(rect(8.0, 8.0, SCENE_W - 16, H - 16))
            }
            Style.UKIYO_E -> fill(rect(FRAME_X + 112.0, 270.0, 14.0, 18.0), p.primary, outlined = false)
            else -> Unit
        }
    }

    private fun cloud(cx: Double, cy: Double, color: Color) {
        val shape = java.awt.geom.Area(oval(cx, cy, 18.0, 9.0)).apply {
            add(java.awt.geom.Area(oval(cx - 9, cy - 6, 9.0)))
            add(java.awt.geom.Area(oval(cx + 7, cy - 7, 10.0)))
        }
        fill(shape, color)
    }

    // ---- character --------------------------------------------------------

    private fun body() {
        fill(rect(44.0, 184.0, 8.0, 34.0), p.skin) // left arm
        fill(rect(83.0, 184.0, 8.0, 34.0), p.skin) // right arm
        fill(rect(56.0, 222.0, 10.0, 34.0), p.skin) // legs
        fill(rect(69.0, 222.0, 10.0, 34.0), p.skin)
        fill(rect(52.0, 182.0, 31.0, 42.0), p.skin) // torso
        fill(oval(67.5, 165.0, 17.0), p.skin) // head
        fill(Arc2D.Double(50.5, 147.0, 34.0, 22.0, 0.0, 180.0, Arc2D.CHORD), p.dark) // hair
    }

    private fun face(slug: String) {
        val eyeY = 165.0
        val c = p.outline
        when (slug) {
            "sleepy" -> { line(58.0, eyeY, 64.0, eyeY, c); line(71.0, eyeY, 77.0, eyeY, c) }
            "windswept" -> {
                line(58.0, eyeY - 2, 64.0, eyeY, c); line(77.0, eyeY - 2, 71.0, eyeY, c)
                for (i in 0..2) line(52.0, 152.0 + i * 4, 40.0, 150.0 + i * 5, p.dark)
            }
            else -> { fill(oval(61.0, eyeY, 2.0), c, false); fill(oval(74.0, eyeY, 2.0), c, false) }
        }
        val mouth = when (slug) {
            "happy" -> Arc2D.Double(62.0, 168.0, 11.0, 7.0, 180.0, 180.0, Arc2D.OPEN)
            "soggy", "windswept" -> Arc2D.Double(62.0, 173.0, 11.0, 6.0, 0.0, 180.0, Arc2D.OPEN)
            "sweaty" -> oval(67.5, 174.0, 3.0)
            "sleepy" -> oval(67.5, 174.0, 2.0)
            else -> Path2D.Double().apply { // shivering: zig-zag
                moveTo(61.0, 174.0); lineTo(64.0, 172.0); lineTo(67.0, 175.0); lineTo(70.0, 172.0); lineTo(74.0, 174.0)
            }
        }
        g.color = c
        g.draw(mouth)
        when (slug) {
            "sweaty" -> { fill(oval(84.0, 158.0, 2.0, 3.0), p.water); fill(oval(51.0, 162.0, 2.0, 3.0), p.water) }
            "shivering" -> { fill(oval(57.0, 171.0, 3.0, 2.0), p.secondary, false); fill(oval(78.0, 171.0, 3.0, 2.0), p.secondary, false) }
            "sleepy" -> { line(88.0, 140.0, 94.0, 140.0); line(94.0, 140.0, 88.0, 146.0); line(88.0, 146.0, 94.0, 146.0) }
            "soggy" -> fill(oval(80.0, 168.0, 2.0, 3.0), p.water)
        }
    }

    private fun bottom(slug: String) {
        val length = if (slug == "shorts") 14.0 else 32.0
        val color = if (slug == "shorts") p.accent else p.secondary
        fill(rect(55.0, 220.0, 25.0, 8.0), color)
        fill(rect(55.0, 222.0, 12.0, length), color)
        fill(rect(68.0, 222.0, 12.0, length), color)
    }

    private fun footwear(slug: String) {
        val (height, color) = when (slug) {
            "sandals" -> 3.0 to p.dark
            "sneakers" -> 7.0 to p.light
            "boots" -> 14.0 to p.dark
            else -> 18.0 to p.accent // rain boots
        }
        for (x in listOf(54.0, 68.0)) fill(rect(x, GROUND - height, 13.0, height), color)
    }

    private fun top(slug: String) {
        val color = when (slug) {
            "tank-top" -> p.light
            "t-shirt" -> p.primary
            "long-sleeve" -> p.secondary
            else -> p.primary // sweater
        }
        val sleeve = when (slug) {
            "tank-top" -> 0.0
            "t-shirt" -> 12.0
            else -> 32.0
        }
        if (sleeve > 0) {
            fill(rect(43.0, 183.0, 10.0, sleeve), color)
            fill(rect(82.0, 183.0, 10.0, sleeve), color)
        }
        fill(rect(51.0, 181.0, 33.0, 43.0), color)
        if (slug == "sweater") {
            for (y in listOf(195.0, 205.0)) line(52.0, y, 83.0, y, p.light)
            fill(rect(60.0, 179.0, 15.0, 4.0), color)
        }
    }

    private fun outerwear(slug: String) {
        val (color, bottomY) = when (slug) {
            "light-jacket" -> p.secondary to 226.0
            "raincoat" -> p.accent to 238.0
            "coat" -> p.dark to 242.0
            else -> p.primary to 228.0 // puffer coat
        }
        val inflate = if (slug == "puffer-coat") 3.0 else 0.0
        fill(rect(41.0 - inflate, 181.0, 12.0 + inflate, 36.0), color) // sleeves
        fill(rect(82.0, 181.0, 12.0 + inflate, 36.0), color)
        fill(rect(49.0 - inflate, 180.0, 16.0 + inflate, bottomY - 180), color) // open front panels
        fill(rect(70.0, 180.0, 16.0 + inflate, bottomY - 180), color)
        if (slug == "puffer-coat") {
            for (y in listOf(190.0, 200.0, 210.0, 220.0)) {
                line(46.0, y, 65.0, y); line(70.0, y, 89.0, y)
            }
        }
        if (slug == "raincoat") fill(Arc2D.Double(46.0, 140.0, 43.0, 40.0, 0.0, 180.0, Arc2D.OPEN), color) // hood
    }

    private fun accessory(slug: String) {
        when (slug) {
            "scarf" -> { fill(rect(53.0, 178.0, 29.0, 7.0), p.primary); fill(rect(72.0, 183.0, 7.0, 22.0), p.primary) }
            "gloves" -> { fill(oval(48.0, 220.0, 5.0), p.secondary); fill(oval(87.0, 220.0, 5.0), p.secondary) }
            "sunglasses" -> {
                fill(rect(56.0, 161.0, 10.0, 7.0), p.outline, false); fill(rect(69.0, 161.0, 10.0, 7.0), p.outline, false)
                line(66.0, 163.0, 69.0, 163.0)
            }
            "beanie" -> {
                fill(Arc2D.Double(49.5, 144.0, 36.0, 30.0, 0.0, 180.0, Arc2D.CHORD), p.secondary)
                fill(rect(49.5, 155.0, 36.0, 5.0), p.secondary)
                fill(oval(67.5, 143.0, 4.0), p.light)
            }
            "sun-hat" -> {
                fill(oval(67.5, 152.0, 30.0, 5.0), p.accent)
                fill(Arc2D.Double(53.0, 136.0, 29.0, 30.0, 0.0, 180.0, Arc2D.CHORD), p.accent)
                fill(rect(53.0, 147.0, 29.0, 3.0), p.primary, false)
            }
            "umbrella-closed" -> {
                g.stroke = BasicStroke(2f); line(89.0, 220.0, 104.0, GROUND); g.stroke = BasicStroke(1f)
                fill(Path2D.Double().apply { moveTo(92.0, 226.0); lineTo(102.0, 252.0); lineTo(96.0, 232.0); closePath() }, p.primary)
            }
            "umbrella-open" -> {
                line(88.0, 216.0, 88.0, 126.0)
                fill(Arc2D.Double(38.0, 100.0, 100.0, 56.0, 0.0, 180.0, Arc2D.CHORD), p.primary)
                for (x in listOf(63.0, 88.0, 113.0)) line(88.0, 100.0, x, 128.0, p.light)
            }
            else -> error("no placeholder for accessory $slug")
        }
    }

    // ---- effects ----------------------------------------------------------

    private fun fx(slug: String) {
        val random = Random(slug.hashCode().toLong())
        val diagonal = if (style == Style.UKIYO_E) 6.0 else 0.0
        fun drops(count: Int, length: Double, color: Color) = repeat(count) {
            val x = random.nextDouble() * SCENE_W
            val y = random.nextDouble() * GROUND
            line(x, y, x - diagonal * length / 10, y + length, color)
        }
        when (slug) {
            "drizzle" -> drops(55, 3.0, p.water)
            "rain" -> drops(130, 8.0, p.water)
            "heavy-rain" -> drops(310, 14.0, p.water)
            "snow" -> repeat(155) { fill(oval(random.nextDouble() * SCENE_W, random.nextDouble() * GROUND, 1.5), p.light) }
            "hail" -> repeat(110) { fill(oval(random.nextDouble() * SCENE_W, random.nextDouble() * GROUND, 2.0), p.light) }
            "wind-breezy", "wind-stormy" -> {
                val gusts = if (slug == "wind-stormy") 20 else 9
                repeat(gusts) {
                    val y = 30 + random.nextDouble() * 200
                    val x = random.nextDouble() * (SCENE_W - 50)
                    g.color = mix(p.light, p.secondary, 0.3)
                    g.draw(Arc2D.Double(x, y, 50.0, 12.0, 0.0, 160.0, Arc2D.OPEN))
                }
                if (slug == "wind-stormy") repeat(13) {
                    fill(oval(random.nextDouble() * SCENE_W, 40 + random.nextDouble() * 180, 3.0, 2.0), p.ground)
                }
            }
            else -> error("no placeholder for fx $slug")
        }
    }
}
