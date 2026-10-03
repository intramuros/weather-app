package io.github.intramuros.weatherbuddy.placeholders

import io.github.intramuros.weatherbuddy.core.Style
import java.awt.image.BufferedImage
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pixel-art placeholders, drawn pixel by pixel: a Dutch canal at the scene's
 * scale (300 × 300) and a chibi buddy in a 135 × 300 frame. The buddy's
 * layers get a dark outline, like a sprite.
 */
internal class PixelArtPainter {
    private lateinit var c: Pix

    fun paint(path: String): BufferedImage {
        val (_, category, file) = path.split('/')
        val slug = file.removeSuffix(".png")
        c = if (Style.isScenery(path)) Pix(SCENE, SCENE) else Pix(FRAME_W, SCENE)
        when (category) {
            "background" -> background(slug)
            "fx" -> fx(slug)
            "body" -> { body(); c.outline(OUTLINE) }
            "face" -> face(slug)
            "bottom" -> { bottom(slug); c.outline(OUTLINE) }
            "footwear" -> { footwear(slug); c.outline(OUTLINE) }
            "top" -> { top(slug); c.outline(OUTLINE) }
            "outerwear" -> { outerwear(slug); c.outline(OUTLINE) }
            "accessory" -> { accessory(slug); c.outline(OUTLINE) }
            else -> error("no placeholder for $path")
        }
        return c.image
    }

    // ---- background -------------------------------------------------------

    private class SkyLook(
        val top: Int,
        val bottom: Int,
        val cloud: Int,
        val cloudShade: Int,
        val night: Boolean,
    )

    private fun skyLook(sky: String, night: Boolean): SkyLook = when (sky) {
        "clear", "partly-cloudy" -> if (night) {
            SkyLook(0xFF141A33.toInt(), 0xFF2E3966.toInt(), 0xFF5D6690.toInt(), 0xFF454D74.toInt(), true)
        } else {
            SkyLook(0xFF4C8ED8.toInt(), 0xFFA8DAF2.toInt(), 0xFFFFFFFF.toInt(), 0xFFD3E1F0.toInt(), false)
        }
        "overcast" -> if (night) {
            SkyLook(0xFF272C4A.toInt(), 0xFF4D5480.toInt(), 0xFF7D85AE.toInt(), 0xFF626A94.toInt(), true)
        } else {
            SkyLook(0xFF8893A8.toInt(), 0xFFC2C9D4.toInt(), 0xFFD9DDE5.toInt(), 0xFFB0B7C5.toInt(), false)
        }
        "fog" -> if (night) {
            SkyLook(0xFF3A3F58.toInt(), 0xFF6A708A.toInt(), 0xFF8A90A8.toInt(), 0xFF747A94.toInt(), true)
        } else {
            SkyLook(0xFFB3BAC6.toInt(), 0xFFDCE0E6.toInt(), 0xFFE8EBEF.toInt(), 0xFFCDD2DA.toInt(), false)
        }
        else -> if (night) { // thunderstorm
            SkyLook(0xFF15172A.toInt(), 0xFF33364F.toInt(), 0xFF45496A.toInt(), 0xFF2F3250.toInt(), true)
        } else {
            SkyLook(0xFF3B4158.toInt(), 0xFF666C82.toInt(), 0xFF5A5F78.toInt(), 0xFF43475E.toInt(), false)
        }
    }

    private fun background(slug: String) {
        val night = slug.endsWith("-night")
        val sky = slug.removeSuffix("-night").removeSuffix("-day")
        val look = skyLook(sky, night)
        val random = Random(slug.hashCode().toLong())

        // Sky: banded gradient with a checkerboard dither between bands.
        val bands = 10
        for (y in 0 until WATER_TOP) {
            val t = y.toDouble() / WATER_TOP * bands
            val band = t.toInt()
            val frac = t - band
            for (x in 0 until SCENE) {
                val dither = frac > 0.75 && (x + y) % 2 == 0
                val b = (if (dither) band + 1 else band).coerceAtMost(bands)
                c[x, y] = mix(look.top, look.bottom, b.toDouble() / bands)
            }
        }

        if (night && (sky == "clear" || sky == "partly-cloudy")) {
            repeat(45) {
                val x = random.nextInt(SCENE)
                val y = random.nextInt(130)
                c[x, y] = if (random.nextInt(4) == 0) 0xFFFFF4C8.toInt() else 0xFFC8D0F0.toInt()
            }
        }
        if (sky == "clear" || sky == "partly-cloudy") {
            if (night) moon(126, 40) else sun(126, 42)
        }
        when (sky) {
            "partly-cloudy" -> {
                cloud(70, 70, 26, look)
                cloud(205, 92, 18, look)
            }
            "overcast", "thunderstorm" -> {
                for ((x, y, r) in listOf(
                    Triple(20, 30, 30), Triple(95, 22, 34), Triple(180, 30, 30), Triple(265, 24, 34),
                    Triple(55, 78, 26), Triple(150, 74, 30), Triple(240, 84, 24),
                )) cloud(x, y, r, look)
                if (sky == "thunderstorm") bolt(196, 96)
            }
            "fog" -> cloud(80, 60, 22, look)
        }

        church(look)
        houses(night, random)
        bridge(night)
        water(night)
        quay(night, random)
        bollards()
        lamp(night)
        if (sky == "fog") fog(look)
    }

    private fun sun(cx: Int, cy: Int) {
        c.ellipse(cx, cy, 15, 15, 0x66FFF3B0)
        c.ellipse(cx, cy, 11, 11, 0xFFFFE27A.toInt())
        c.ellipse(cx - 2, cy - 2, 6, 6, 0xFFFFF4C2.toInt())
    }

    private fun moon(cx: Int, cy: Int) {
        for (y in cy - 11..cy + 11) for (x in cx - 11..cx + 11) {
            if (inEllipse(x, y, cx, cy, 10, 10) && !inEllipse(x, y, cx + 5, cy - 3, 9, 9)) {
                c[x, y] = 0xFFF2EBCF.toInt()
            }
        }
    }

    /** A puffy cloud: a few overlapping blobs with a flat, shaded base. */
    private fun cloud(cx: Int, cy: Int, r: Int, look: SkyLook) {
        val blobs = listOf(
            Triple(cx - r, cy + r / 4, r * 2 / 3),
            Triple(cx, cy - r / 6, r),
            Triple(cx + r, cy + r / 5, r * 3 / 4),
        )
        val base = cy + r / 2
        for (y in cy - r * 2..base) for (x in cx - r * 2..cx + r * 2) {
            if (blobs.none { (bx, by, br) -> inEllipse(x, y, bx, by, br, br * 3 / 4) }) continue
            c[x, y] = if (y > base - 4) look.cloudShade else look.cloud
        }
    }

    private fun bolt(x: Int, y: Int) {
        val yellow = 0xFFFFE45C.toInt()
        val points = listOf(0 to 0, -3 to 8, 1 to 8, -4 to 20, 5 to 6, 1 to 6, 4 to 0)
        for (i in 0 until points.size - 1) {
            c.line(x + points[i].first, y + points[i].second, x + points[i + 1].first, y + points[i + 1].second, yellow)
        }
        c.rect(x - 1, y, 3, 8, yellow)
        c.rect(x - 3, y + 8, 4, 12, yellow)
    }

    private fun church(look: SkyLook) {
        val color = mix(look.bottom, 0xFF2A2E44.toInt(), 0.45)
        c.rect(116, 120, 16, WATER_TOP - 120, color)
        for (y in 92 until 120) {
            val half = (y - 92) * 8 / 28
            c.hline(124 - half, 124 + half, y, color)
        }
        c.rect(123, 84, 2, 8, color)
        c.ellipse(124, 132, 3, 3, mix(color, look.top, 0.5))
    }

    private val houseColours = intArrayOf(
        0xFF8C3B30.toInt(), 0xFF5E4536.toInt(), 0xFF3F4A5C.toInt(), 0xFFA45C44.toInt(),
        0xFF4E3B38.toInt(), 0xFF6F2F2C.toInt(), 0xFF7A6A58.toInt(), 0xFF2F3A4A.toInt(),
    )

    private fun houses(night: Boolean, random: Random) {
        val trim = if (night) 0xFF8890A8.toInt() else 0xFFEDE6D8.toInt()
        var x = -6
        var i = 0
        while (x < SCENE) {
            val w = 24 + random.nextInt(4) * 3
            val top = 150 + random.nextInt(4) * 6
            val dayColour = houseColours[i % houseColours.size]
            val colour = if (night) mix(dayColour, 0xFF1A1E36.toInt(), 0.62) else dayColour
            val shade = mix(colour, 0xFF000000.toInt(), 0.25)
            c.rect(x, top, w, WATER_TOP - top, colour)
            c.rect(x + w - 2, top, 2, WATER_TOP - top, shade)
            gable(x, top, w, i % 4, colour, trim)

            // Windows: tall Dutch sash windows, some lit at night.
            val columns = if (w >= 30) 3 else 2
            val gap = (w - columns * 5) / (columns + 1)
            var wy = top + 6
            while (wy < WATER_TOP - 12) {
                for (col in 0 until columns) {
                    val wx = x + gap + col * (5 + gap)
                    val lit = night && random.nextInt(10) < 6
                    window(wx, wy, lit, night, trim)
                }
                wy += 13
            }
            x += w
            i++
        }
    }

    private fun gable(x: Int, top: Int, w: Int, kind: Int, colour: Int, trim: Int) {
        val mid = x + w / 2
        when (kind) {
            0 -> { // step gable
                var step = 0
                while (step * 3 < w / 2 - 2) {
                    c.rect(x + step * 3, top - (step + 1) * 4, w - step * 6, 4, colour)
                    step++
                }
                c.hline(x + (step - 1) * 3, x + w - (step - 1) * 3 - 1, top - step * 4, trim)
            }
            1 -> { // neck gable with a little pediment
                val neck = w / 2
                c.rect(mid - neck / 2, top - 14, neck, 14, colour)
                c.ellipse(mid - neck / 2, top - 1, 4, 5, colour)
                c.ellipse(mid + neck / 2 - 1, top - 1, 4, 5, colour)
                for (k in 0..4) c.hline(mid - neck / 2 + k, mid + neck / 2 - k - 1, top - 15 - k, colour)
                c.hline(mid - neck / 2, mid + neck / 2 - 1, top - 14, trim)
            }
            2 -> { // bell gable
                for (y in top - 16 until top) for (xx in x + 2 until x + w - 2) {
                    if (inEllipse(xx, y, mid, top, w / 2 - 2, 16)) c[xx, y] = colour
                }
                c.ellipse(mid, top - 17, 2, 2, trim)
            }
            else -> { // spout gable: a plain triangle with a hoist beam
                for (k in 0 until w / 2) c.hline(x + k, x + w - k - 1, top - k * 2 / 3 - 1, colour)
                c.rect(mid - 1, top - w / 3, 2, 3, trim)
            }
        }
    }

    private fun window(x: Int, y: Int, lit: Boolean, night: Boolean, trim: Int) {
        c.rect(x, y, 5, 8, trim)
        val glass = when {
            lit -> 0xFFF6C66A.toInt()
            night -> 0xFF23283E.toInt()
            else -> 0xFF2D3647.toInt()
        }
        c.rect(x + 1, y + 1, 3, 6, glass)
        c.hline(x + 1, x + 3, y + 4, trim)
        if (lit) c[x + 1, y + 1] = 0xFFFFE9A8.toInt()
    }

    private fun bridge(night: Boolean) {
        val brick = if (night) 0xFF4A4558.toInt() else 0xFF8D5A48.toInt()
        val light = if (night) 0xFF6A6680.toInt() else 0xFFB98A6E.toInt()
        val left = 156
        val right = 268
        val deck = 214
        for (y in deck until WATER_TOP) for (x in left until right) {
            if (!inEllipse(x, y, (left + right) / 2, WATER_TOP + 2, 34, 15)) c[x, y] = brick
        }
        // Arch voussoirs and deck edge.
        for (y in deck until WATER_TOP) for (x in left until right) {
            if (inEllipse(x, y, (left + right) / 2, WATER_TOP + 2, 37, 18) &&
                !inEllipse(x, y, (left + right) / 2, WATER_TOP + 2, 34, 15) && (x + y) % 3 != 0
            ) c[x, y] = light
        }
        c.hline(left - 2, right + 1, deck, light)
        c.hline(left - 2, right + 1, deck - 6, light)
        var x = left
        while (x <= right) {
            c.rect(x, deck - 6, 1, 6, light)
            x += 7
        }
    }

    private fun water(night: Boolean) {
        val base = if (night) 0xFF1D2540.toInt() else 0xFF3C5E86.toInt()
        for (y in WATER_TOP until QUAY_TOP) {
            val depth = y - WATER_TOP
            val srcY = WATER_TOP - 1 - (depth * 1.6).toInt()
            val dx = if ((depth / 2) % 2 == 0) 1 else -1
            for (x in 0 until SCENE) {
                val src = c[(x + dx).coerceIn(0, SCENE - 1), srcY.coerceAtLeast(0)]
                c[x, y] = mix(src, base, if (depth % 3 == 0) 0.75 else 0.5)
            }
        }
        val ripple = if (night) 0xFF4A5680.toInt() else 0xFF8FB3D6.toInt()
        val random = Random(7)
        repeat(40) {
            val x = random.nextInt(SCENE)
            val y = WATER_TOP + 2 + random.nextInt(QUAY_TOP - WATER_TOP - 3)
            c.hline(x, x + 2 + random.nextInt(6), y, ripple)
        }
    }

    private fun quay(night: Boolean, random: Random) {
        val edge = if (night) 0xFF6A6E82.toInt() else 0xFF9A9EA8.toInt()
        val edgeLight = if (night) 0xFF8A8EA2.toInt() else 0xFFC4C8D0.toInt()
        c.rect(0, QUAY_TOP, SCENE, 3, edge)
        c.hline(0, SCENE - 1, QUAY_TOP, edgeLight)
        val stone = if (night) 0xFF353849.toInt() else 0xFF5B5E6C.toInt()
        val mortar = if (night) 0xFF262836.toInt() else 0xFF464856.toInt()
        val sheen = if (night) 0xFF4F5470.toInt() else 0xFF7E8494.toInt()
        for (y in QUAY_TOP + 3 until SCENE) {
            val row = (y - QUAY_TOP - 3) / 5
            val offset = if (row % 2 == 0) 0 else 5
            for (x in 0 until SCENE) {
                val mortarLine = (y - QUAY_TOP - 3) % 5 == 4 || (x + offset) % 10 == 0
                c[x, y] = if (mortarLine) mortar else stone
            }
        }
        repeat(70) {
            val x = random.nextInt(SCENE)
            val y = QUAY_TOP + 4 + random.nextInt(SCENE - QUAY_TOP - 4)
            c.hline(x, x + 1 + random.nextInt(3), y, sheen)
        }
    }

    private fun bollards() {
        val post = 0xFF5C2A2C.toInt()
        val shine = 0xFF8A4A44.toInt()
        for (x in listOf(132, 182, 232, 282)) {
            c.rect(x, QUAY_TOP - 9, 4, 10, post)
            c.hline(x + 1, x + 2, QUAY_TOP - 10, post)
            c.rect(x + 1, QUAY_TOP - 8, 1, 6, shine)
        }
    }

    private fun lamp(night: Boolean) {
        val iron = 0xFF23222E.toInt()
        val x = 20
        if (night) {
            for (y in 120..185) for (xx in 0..44) {
                val d = (xx - x) * (xx - x) + (y - 150) * (y - 150)
                if (d < 22 * 22 && (xx + y) % 2 == 0) c[xx, y] = mix(c[xx, y], 0xFFFFD27A.toInt(), if (d < 12 * 12) 0.45 else 0.22)
            }
            for (y in SCENE - 30 until SCENE) for (xx in 0..44) {
                if (inEllipse(xx, y, x, SCENE - 18, 20, 6) && (xx + y) % 2 == 0) {
                    c[xx, y] = mix(c[xx, y], 0xFFFFD27A.toInt(), 0.3)
                }
            }
        }
        c.rect(x - 1, 158, 3, QUAY_TOP + 6 - 158, iron)
        c.rect(x - 4, QUAY_TOP + 2, 9, 5, iron)
        c.rect(x - 3, 155, 7, 3, iron)
        c.rect(x - 6, 138, 13, 17, iron)
        c.rect(x - 4, 140, 9, 13, if (night) 0xFFFFD27A.toInt() else 0xFFCCD6E0.toInt())
        if (night) c.rect(x - 2, 142, 5, 9, 0xFFFFF0C0.toInt())
        c.rect(x, 140, 1, 13, iron)
        for (k in 0..3) c.hline(x - 6 + k * 2, x + 6 - k * 2, 137 - k, iron)
    }

    private fun fog(look: SkyLook) {
        for ((top, height) in listOf(150 to 18, 192 to 14, 228 to 12)) {
            for (y in top until top + height) for (x in 0 until SCENE) {
                val edge = y == top || y == top + height - 1
                if (!edge || (x + y) % 2 == 0) c[x, y] = mix(c[x, y], look.cloud, 0.6)
            }
        }
    }

    // ---- character --------------------------------------------------------

    private fun body() {
        // Long hair behind the head, falling past the shoulders.
        c.ellipse(CX, 161, 27, 25, HAIR_DARK)
        for (x in 40..94) {
            if (x < 50 || x > 84) c.rect(x, 165, 1, 34 + (x % 3) * 3, HAIR_DARK)
        }
        // Legs and feet.
        c.rect(57, 218, 9, 38, SKIN)
        c.rect(69, 218, 9, 38, SKIN)
        c.rect(64, 218, 2, 38, SKIN_SHADE)
        c.rect(76, 218, 2, 38, SKIN_SHADE)
        c.rect(56, 255, 10, 7, SKIN)
        c.rect(68, 255, 10, 7, SKIN)
        // Arms, hands and torso; the big chibi head hides the neck.
        c.rect(46, 187, 7, 28, SKIN)
        c.rect(82, 187, 7, 28, SKIN)
        c.ellipse(49, 217, 4, 4, SKIN)
        c.ellipse(85, 217, 4, 4, SKIN)
        c.rect(53, 184, 29, 36, SKIN)
        // Head: about 40 % of the buddy's height.
        c.ellipse(CX, HEAD_Y, 23, 21, SKIN)
        for (y in HEAD_Y - 18..HEAD_Y + 21) for (x in 80..90) {
            if (inEllipse(x, y, CX, HEAD_Y, 23, 21) && !inEllipse(x, y, CX - 2, HEAD_Y, 22, 21)) c[x, y] = SKIN_SHADE
        }
        // Fringe with a jagged edge, and side locks framing the face.
        for (y in 136..158) for (x in 40..94) {
            if (!inEllipse(x, y, CX, 158, 27, 21)) continue
            val fringe = 153 + FRINGE[(x - 40) % FRINGE.size]
            if (y <= fringe || x < 47 || x > 87) c[x, y] = HAIR
        }
        c.rect(42, 156, 6, 30, HAIR)
        c.rect(87, 156, 6, 30, HAIR)
        for (x in 42..92 step 3) c[x, 186 + x % 2] = HAIR
        // Shine on the hair and a little cowlick.
        for (x in 50..60) c[x, 145 + abs(x - 55) / 3] = HAIR_LIGHT
        c.hline(70, 76, 144, HAIR_LIGHT)
        c[66, 136] = HAIR; c[67, 135] = HAIR; c[68, 134] = HAIR; c[69, 134] = HAIR; c[70, 135] = HAIR
        // A heart hair clip.
        heart(83, 146, CLIP)
    }

    /** A 5 × 4 pixel heart with its top-left at ([x], [y]). */
    private fun heart(x: Int, y: Int, colour: Int) {
        c.hline(x, x + 1, y, colour); c.hline(x + 3, x + 4, y, colour)
        c.hline(x, x + 4, y + 1, colour)
        c.hline(x + 1, x + 3, y + 2, colour)
        c[x + 2, y + 3] = colour
        c[x, y] = mix(colour, 0xFFFFFFFF.toInt(), 0.5)
    }

    private fun face(slug: String) {
        /** Big sparkly eyes: dark top, warm bottom, two highlights and a lash flick. */
        fun eyes(top: Int = 160, height: Int = 10) {
            for ((ex, outer) in listOf(52 to -1, 76 to 1)) {
                c.rect(ex, top, 7, height, EYE)
                // Rounded bottom corners keep them soft.
                c[ex, top + height - 1] = SKIN; c[ex + 6, top + height - 1] = SKIN
                c.rect(ex + 1, top + height / 2, 5, height - height / 2 - 1, IRIS)
                c.hline(ex + 2, ex + 4, top + height - 2, IRIS_LIGHT)
                c.rect(ex + 1, top + 1, 2, 2, 0xFFFFFFFF.toInt())
                c[ex + 4, top + height - 3] = 0xFFFFFFFF.toInt()
                c.hline(ex, ex + 6, top - 1, EYE)
                c[if (outer < 0) ex - 1 else ex + 7, top] = EYE
                c[if (outer < 0) ex - 2 else ex + 8, top - 1] = EYE
            }
        }
        fun cheeks(colour: Int = BLUSH) {
            c.rect(47, 173, 5, 2, colour)
            c.rect(83, 173, 5, 2, colour)
            c[48, 172] = colour; c[84, 172] = colour
        }
        fun smile() {
            c[64, 175] = EYE; c[70, 175] = EYE
            c.hline(65, 69, 176, EYE)
            c.hline(66, 68, 177, MOUTH)
        }
        when (slug) {
            "happy" -> { eyes(); cheeks(); smile() }
            "sleepy" -> {
                // Closed, curved eyes.
                for (ex in listOf(52, 76)) {
                    c[ex, 166] = EYE; c[ex + 6, 166] = EYE
                    c.hline(ex + 1, ex + 5, 167, EYE)
                }
                cheeks()
                c.rect(66, 175, 2, 2, MOUTH)
                for ((i, y) in listOf(132, 124).withIndex()) {
                    val x = 96 + i * 6
                    val white = 0xFFFFFFFF.toInt()
                    c.hline(x, x + 3, y, white); c.line(x + 3, y, x, y + 3, white); c.hline(x, x + 3, y + 3, white)
                }
            }
            "shivering" -> {
                eyes(); cheeks(0xFF9FB8E8.toInt())
                for (x in 63..71) c[x, if (x % 2 == 0) 175 else 176] = EYE
                for (y in listOf(156, 164, 172)) { c.hline(36, 38, y, 0xFFFFFFFF.toInt()); c.hline(96, 98, y, 0xFFFFFFFF.toInt()) }
            }
            "sweaty" -> {
                eyes(); cheeks(0xFFF26B6B.toInt())
                c.rect(65, 175, 5, 3, EYE); c.hline(66, 68, 177, MOUTH)
                drop(95, 150); drop(39, 162)
            }
            "soggy" -> {
                eyes(top = 162, height = 8)
                // Teary eyes and a wobbly mouth.
                c.hline(52, 58, 169, 0xFF8FC8F0.toInt()); c.hline(76, 82, 169, 0xFF8FC8F0.toInt())
                cheeks()
                for (x in 64..70) c[x, if ((x / 2) % 2 == 0) 175 else 176] = EYE
                drop(85, 172)
            }
            else -> { // windswept: squeezed > < eyes, hair blowing
                for ((ex, dir) in listOf(53 to 1, 81 to -1)) {
                    c.line(ex, 162, ex + 4 * dir, 165, EYE); c.line(ex + 4 * dir, 165, ex, 168, EYE)
                }
                cheeks()
                c.ellipse(67, 176, 2, 1, MOUTH)
                for (k in 0..3) c.line(42, 150 + k * 7, 30 - k * 2, 146 + k * 8, HAIR)
            }
        }
    }

    private fun drop(x: Int, y: Int) {
        val blue = 0xFF8FC8F0.toInt()
        c[x, y] = blue
        c.rect(x - 1, y + 1, 3, 2, blue)
        c[x, y + 3] = blue
        c[x - 1, y + 1] = 0xFFFFFFFF.toInt()
    }

    private fun bottom(slug: String) {
        val (colour, shade) = if (slug == "shorts") 0xFF5A7AB8.toInt() to 0xFF435E94.toInt() else 0xFF3E4C78.toInt() to 0xFF2E3A5E.toInt()
        val length = if (slug == "shorts") 12 else 36
        c.rect(55, 216, 25, 8, colour)
        c.rect(55, 222, 12, length, colour)
        c.rect(68, 222, 12, length, colour)
        c.rect(65, 222, 2, length, shade)
        c.rect(78, 222, 2, length, shade)
        c.hline(55, 79, 216, shade)
    }

    private fun footwear(slug: String) {
        for (x in listOf(55, 68)) when (slug) {
            "sandals" -> {
                c.rect(x, 261, 12, 1, 0xFF6B4630.toInt())
                c.rect(x + 1, 257, 10, 1, 0xFF8A5A3A.toInt())
            }
            "sneakers" -> {
                c.rect(x, 254, 12, 7, 0xFFF2F2F0.toInt())
                c.rect(x, 261, 12, 1, 0xFF9AA0AE.toInt())
                c.rect(x + 2, 255, 1, 1, 0xFF5B6EE1.toInt()); c.rect(x + 5, 255, 1, 1, 0xFF5B6EE1.toInt())
                c.rect(x + 8, 257, 4, 2, 0xFF5B6EE1.toInt())
            }
            "boots" -> {
                c.rect(x, 240, 12, 21, 0xFF7E3036.toInt())
                c.rect(x + 9, 240, 3, 21, 0xFF5C2228.toInt())
                c.rect(x, 240, 12, 2, 0xFF9C4A4A.toInt())
                c.rect(x, 261, 12, 1, 0xFF3A1A1E.toInt())
            }
            else -> { // rain boots
                c.rect(x, 236, 12, 25, 0xFFF2C230.toInt())
                c.rect(x + 9, 236, 3, 25, 0xFFC9962A.toInt())
                c.rect(x + 2, 239, 1, 12, 0xFFFFE89A.toInt())
                c.rect(x, 261, 12, 1, 0xFF6A5020.toInt())
            }
        }
    }

    private fun top(slug: String) {
        val (colour, shade) = when (slug) {
            "tank-top" -> 0xFFF2F0EA.toInt() to 0xFFCFCBC2.toInt()
            "t-shirt" -> 0xFFEF6F6C.toInt() to 0xFFC85558.toInt()
            "long-sleeve" -> 0xFF3E8C8C.toInt() to 0xFF2E6B6B.toInt()
            else -> 0xFFD9A441.toInt() to 0xFFB38232.toInt() // sweater
        }
        val sleeve = when (slug) {
            "tank-top" -> 0
            "t-shirt" -> 12
            else -> 28
        }
        if (slug == "tank-top") {
            c.rect(54, 186, 27, 34, colour)
            c.rect(56, 183, 4, 4, colour); c.rect(75, 183, 4, 4, colour)
        } else {
            c.rect(51, 183, 33, 37, colour)
            c.rect(45, 185, 8, sleeve, colour); c.rect(82, 185, 8, sleeve, colour)
            c.rect(88, 185, 2, sleeve, shade)
            c.rect(62, 183, 11, 2, SKIN)
        }
        c.rect(80, 186, 3, 34, shade)
        if (slug == "sweater") {
            c.rect(51, 217, 33, 3, shade)
            c.rect(45, 211, 8, 2, shade); c.rect(82, 211, 8, 2, shade)
            for (x in 53..81 step 4) { c[x, 196] = 0xFFFFF2D0.toInt(); c[x + 2, 198] = 0xFFFFF2D0.toInt() }
        }
    }

    private fun outerwear(slug: String) {
        when (slug) {
            "coat" -> {
                val colour = 0xFF2E3A66.toInt(); val shade = 0xFF222A4C.toInt(); val light = 0xFF46548A.toInt()
                c.rect(43, 184, 10, 31, colour); c.rect(82, 184, 10, 31, colour)
                for (y in 183 until 242) {
                    val flare = (y - 214).coerceAtLeast(0) / 7
                    c.hline(50 - flare, 84 + flare, y, colour)
                    c.hline(80 + flare, 84 + flare, y, shade)
                }
                c.rect(89, 184, 3, 31, shade)
                c.rect(67, 188, 1, 54, shade)
                for (y in listOf(194, 204, 214)) c.rect(63, y, 2, 2, 0xFFE0B04A.toInt())
                for (k in 0..5) { c.hline(58 + k, 60 + k, 183 + k, light); c.hline(74 - k, 76 - k, 183 + k, light) }
                c.rect(43, 213, 10, 2, light); c.rect(82, 213, 10, 2, light)
            }
            "light-jacket" -> {
                val colour = 0xFF5E8C5A.toInt(); val shade = 0xFF466B45.toInt()
                c.rect(43, 184, 10, 30, colour); c.rect(82, 184, 10, 30, colour)
                c.rect(50, 183, 14, 40, colour); c.rect(71, 183, 14, 40, colour)
                c.rect(63, 186, 1, 37, shade); c.rect(71, 186, 1, 37, shade)
                c.rect(82, 186, 3, 37, shade); c.rect(89, 184, 3, 30, shade)
            }
            "raincoat" -> {
                val colour = 0xFFF2C230.toInt(); val shade = 0xFFC9962A.toInt()
                // Hood framing the face.
                for (y in 130..190) for (x in 34..100) {
                    if (inEllipse(x, y, CX, 161, 30, 28) && !inEllipse(x, y, CX, 164, 24, 22)) c[x, y] = colour
                }
                c.rect(43, 184, 10, 31, colour); c.rect(82, 184, 10, 31, colour)
                for (y in 183 until 240) {
                    val flare = (y - 210).coerceAtLeast(0) / 6
                    c.hline(50 - flare, 84 + flare, y, colour)
                    c.hline(80 + flare, 84 + flare, y, shade)
                }
                c.rect(67, 186, 1, 54, shade)
                for (y in listOf(192, 202, 212, 222)) c.rect(65, y, 2, 2, 0xFF3A3A48.toInt())
                c.rect(89, 184, 3, 31, shade)
            }
            else -> { // puffer coat
                val colour = 0xFFE07A3A.toInt(); val shade = 0xFFB45A2C.toInt()
                c.rect(41, 184, 12, 31, colour); c.rect(82, 184, 12, 31, colour)
                c.rect(47, 182, 41, 46, colour)
                c.rect(57, 177, 21, 8, colour)
                for (y in listOf(192, 201, 210, 219)) { c.hline(47, 87, y, shade); c.hline(41, 52, y, shade); c.hline(82, 93, y, shade) }
                c.rect(67, 185, 1, 43, shade)
                c.rect(84, 182, 4, 46, shade)
            }
        }
    }

    private fun accessory(slug: String) {
        when (slug) {
            "scarf" -> {
                val red = 0xFFC23B3B.toInt(); val dark = 0xFF8E2A35.toInt()
                c.rect(54, 179, 27, 7, red)
                c.hline(54, 80, 182, dark)
                c.rect(70, 185, 7, 22, red)
                c.rect(70, 190, 7, 1, dark); c.rect(70, 196, 7, 1, dark)
                for (x in 70..76 step 2) c.rect(x, 207, 1, 2, red)
            }
            "gloves" -> {
                c.ellipse(49, 217, 5, 5, 0xFF3E7CB1.toInt())
                c.ellipse(85, 217, 5, 5, 0xFF3E7CB1.toInt())
            }
            "sunglasses" -> {
                // Heart-shaped shades.
                for (x0 in listOf(50, 74)) {
                    c.hline(x0, x0 + 3, 161, 0xFF1A1A22.toInt()); c.hline(x0 + 6, x0 + 9, 161, 0xFF1A1A22.toInt())
                    c.rect(x0, 162, 11, 3, 0xFF1A1A22.toInt())
                    c.hline(x0 + 1, x0 + 9, 165, 0xFF1A1A22.toInt())
                    c.hline(x0 + 3, x0 + 7, 166, 0xFF1A1A22.toInt())
                    c[x0 + 5, 167] = 0xFF1A1A22.toInt()
                    c[x0 + 2, 162] = 0xFFFF7FAA.toInt()
                }
                c.hline(61, 73, 162, 0xFF1A1A22.toInt())
            }
            "beanie" -> {
                val blue = 0xFF3E7CB1.toInt(); val dark = 0xFF2E5E88.toInt()
                for (y in 132..152) for (x in 38..96) if (inEllipse(x, y, CX, 154, 27, 21)) c[x, y] = blue
                c.rect(39, 148, 57, 6, dark)
                for (x in 40..94 step 3) c.rect(x, 148, 1, 6, blue)
                c.ellipse(CX, 131, 5, 5, 0xFFF2F0EA.toInt())
                heart(78, 138, CLIP)
            }
            "sun-hat" -> {
                val straw = 0xFFE8C77A.toInt(); val shade = 0xFFC9A55A.toInt()
                for (y in 124..145) for (x in 46..89) if (inEllipse(x, y, CX, 146, 20, 21)) c[x, y] = straw
                c.ellipse(CX, 146, 36, 6, straw)
                c.hline(31, 103, 149, shade)
                c.rect(48, 139, 39, 4, 0xFFFF7FAA.toInt())
                c.ellipse(83, 140, 3, 3, 0xFFFFFFFF.toInt())
                c[83, 140] = 0xFFFFE27A.toInt()
            }
            "umbrella-closed" -> {
                val red = 0xFFC0453F.toInt(); val dark = 0xFF8E2F33.toInt()
                c.line(87, 214, 96, 261, 0xFF3A2A2A.toInt())
                for (k in 0..26) {
                    val y = 224 + k
                    val x = 88 + (y - 214) * 9 / 47
                    val half = if (k < 20) k / 6 + 1 else (26 - k) / 3
                    c.hline(x - half, x + half, y, if (k % 7 == 0) dark else red)
                }
                c.rect(85, 210, 2, 5, 0xFF3A2A2A.toInt()); c.rect(83, 209, 3, 2, 0xFF3A2A2A.toInt())
            }
            "umbrella-open" -> {
                val red = 0xFFC0453F.toInt(); val dark = 0xFF8E2F33.toInt(); val light = 0xFFDD6A5E.toInt()
                // Held beside the head, so the shaft doesn't cross the face.
                val apexX = 94
                val left = 54
                val right = 134
                val base = 132
                val panels = 4
                val panel = (right - left) / panels
                for (y in 100..base) for (x in left..right) {
                    if (!inEllipse(x, y, apexX, base, (right - left) / 2, base - 102)) continue
                    val t = (x - left) % panel
                    val scallop = (3 * sin(PI * t / panel)).roundToInt()
                    if (y > base - scallop) continue
                    val p = (x - left) / panel
                    c[x, y] = if (p % 2 == 0) red else light
                }
                for (i in 0..panels) {
                    c.line(apexX, 103, left + i * panel, base, dark)
                }
                c.rect(apexX - 1, 96, 2, 6, 0xFF3A2A2A.toInt())
                c.rect(apexX, base, 1, 67, 0xFF3A2A2A.toInt())
                c.ellipse(apexX, 198, 3, 3, SKIN)
                c.rect(apexX, 202, 1, 4, 0xFF3A2A2A.toInt())
                c.hline(apexX, apexX + 3, 206, 0xFF3A2A2A.toInt())
                c[apexX + 3, 205] = 0xFF3A2A2A.toInt()
            }
            else -> error("no placeholder for accessory $slug")
        }
    }

    // ---- effects ----------------------------------------------------------

    private fun fx(slug: String) {
        val random = Random(slug.hashCode().toLong())
        val rain = 0xFFAFBEE6.toInt()
        val rainFaint = 0xAAAFBEE6.toInt()
        fun drops(count: Int, length: Int, splashes: Int) {
            repeat(count) {
                val x = random.nextInt(SCENE)
                val y = random.nextInt(SCENE)
                c.rect(x, y, 1, length, if (random.nextBoolean()) rain else rainFaint)
            }
            repeat(splashes) {
                val x = random.nextInt(SCENE)
                val y = QUAY_TOP + 6 + random.nextInt(SCENE - QUAY_TOP - 8)
                c[x - 1, y] = rain; c[x + 1, y] = rain; c[x - 2, y - 1] = rainFaint; c[x + 2, y - 1] = rainFaint
            }
        }
        when (slug) {
            "drizzle" -> drops(140, 2, 0)
            "rain" -> drops(240, 4, 14)
            "heavy-rain" -> drops(480, 7, 30)
            "snow" -> repeat(170) {
                val x = random.nextInt(SCENE)
                val y = random.nextInt(SCENE)
                if (random.nextInt(3) == 0) {
                    c[x, y] = 0xFFFFFFFF.toInt(); c[x - 1, y] = 0xCCFFFFFF.toInt(); c[x + 1, y] = 0xCCFFFFFF.toInt()
                    c[x, y - 1] = 0xCCFFFFFF.toInt(); c[x, y + 1] = 0xCCFFFFFF.toInt()
                } else {
                    c.rect(x, y, 2, 2, 0xFFF4F6FF.toInt())
                }
            }
            "hail" -> repeat(130) {
                val x = random.nextInt(SCENE)
                val y = random.nextInt(SCENE)
                c.rect(x, y, 2, 2, 0xFFFFFFFF.toInt())
                c[x + 1, y + 1] = 0xFFB8C4D8.toInt()
            }
            "wind-breezy", "wind-stormy" -> {
                val stormy = slug == "wind-stormy"
                repeat(if (stormy) 10 else 5) {
                    val x = random.nextInt(SCENE - 60)
                    val y = 30 + random.nextInt(220)
                    val length = 24 + random.nextInt(30)
                    gust(x, y, length, if (stormy) 0xDDFFFFFF.toInt() else 0x99FFFFFF.toInt())
                }
                val leaves = intArrayOf(0xFF7BA84A.toInt(), 0xFFD98A3A.toInt(), 0xFFB5523A.toInt())
                repeat(if (stormy) 12 else 4) {
                    val x = random.nextInt(SCENE)
                    val y = 40 + random.nextInt(220)
                    val leaf = leaves[random.nextInt(leaves.size)]
                    c.rect(x, y, 2, 1, leaf); c[x + 1, y + 1] = leaf
                }
            }
            else -> error("no placeholder for fx $slug")
        }
    }

    /** A streak of wind ending in a small curl. */
    private fun gust(x: Int, y: Int, length: Int, colour: Int) {
        c.hline(x, x + length, y, colour)
        c[x + length + 1, y - 1] = colour
        c[x + length + 2, y - 2] = colour
        c[x + length + 2, y - 3] = colour
        c[x + length + 1, y - 4] = colour
        c[x + length, y - 4] = colour
        c[x + length - 1, y - 3] = colour
    }

    private companion object {
        const val SCENE = 300
        const val FRAME_W = 135
        const val CX = 67
        const val WATER_TOP = 232
        const val QUAY_TOP = 256

        val OUTLINE = 0xFF2A1F2D.toInt()
        val SKIN = 0xFFF6D3BC.toInt()
        val SKIN_SHADE = 0xFFE3AE98.toInt()
        val BLUSH = 0xFFF7A1B0.toInt()
        val HAIR = 0xFF7A3B2E.toInt()
        val HAIR_DARK = 0xFF5A2620.toInt()
        val HAIR_LIGHT = 0xFFA0533C.toInt()
        val EYE = 0xFF3A2430.toInt()
        val IRIS = 0xFF8A3E4E.toInt()
        val IRIS_LIGHT = 0xFFD9708A.toInt()
        val MOUTH = 0xFFE0707E.toInt()
        val CLIP = 0xFFFF7FAA.toInt()
        val FRINGE = intArrayOf(0, 2, 4, 2, 0, 3, 5, 3, 1, 4, 2)
        const val HEAD_Y = 163

        fun inEllipse(x: Int, y: Int, cx: Int, cy: Int, rx: Int, ry: Int): Boolean {
            val dx = (x - cx).toDouble() / rx.coerceAtLeast(1)
            val dy = (y - cy).toDouble() / ry.coerceAtLeast(1)
            return dx * dx + dy * dy <= 1.0
        }

        /** Mixes two ARGB colours; the result takes [a]'s alpha. */
        fun mix(a: Int, b: Int, t: Double): Int {
            fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t).roundToInt()
            return (a and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}

/** A tiny ARGB pixel canvas. Writes outside the image are ignored. */
private class Pix(val w: Int, val h: Int) {
    val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)

    operator fun get(x: Int, y: Int): Int = if (x in 0 until w && y in 0 until h) image.getRGB(x, y) else 0

    operator fun set(x: Int, y: Int, argb: Int) {
        if (x !in 0 until w || y !in 0 until h) return
        val alpha = argb ushr 24
        image.setRGB(x, y, if (alpha == 0xFF || alpha == 0) argb else blend(image.getRGB(x, y), argb))
    }

    fun rect(x: Int, y: Int, width: Int, height: Int, argb: Int) {
        for (yy in y until y + height) for (xx in x until x + width) this[xx, yy] = argb
    }

    fun hline(x1: Int, x2: Int, y: Int, argb: Int) {
        for (x in minOf(x1, x2)..maxOf(x1, x2)) this[x, y] = argb
    }

    fun ellipse(cx: Int, cy: Int, rx: Int, ry: Int, argb: Int) {
        for (y in cy - ry..cy + ry) for (x in cx - rx..cx + rx) {
            val dx = (x - cx).toDouble() / rx.coerceAtLeast(1)
            val dy = (y - cy).toDouble() / ry.coerceAtLeast(1)
            if (dx * dx + dy * dy <= 1.0) this[x, y] = argb
        }
    }

    /** Bresenham line. */
    fun line(x1: Int, y1: Int, x2: Int, y2: Int, argb: Int) {
        var x = x1
        var y = y1
        val dx = abs(x2 - x1)
        val dy = -abs(y2 - y1)
        val sx = if (x1 < x2) 1 else -1
        val sy = if (y1 < y2) 1 else -1
        var err = dx + dy
        while (true) {
            this[x, y] = argb
            if (x == x2 && y == y2) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    /** Draws [argb] on every empty pixel that touches a filled one. */
    fun outline(argb: Int) {
        val filled = Array(h) { y -> BooleanArray(w) { x -> image.getRGB(x, y) ushr 24 != 0 } }
        for (y in 0 until h) for (x in 0 until w) {
            if (filled[y][x]) continue
            val touches = (x > 0 && filled[y][x - 1]) || (x < w - 1 && filled[y][x + 1]) ||
                (y > 0 && filled[y - 1][x]) || (y < h - 1 && filled[y + 1][x])
            if (touches) image.setRGB(x, y, argb)
        }
    }

    private fun blend(under: Int, over: Int): Int {
        val a = (over ushr 24) / 255.0
        val ua = (under ushr 24) / 255.0
        val outA = a + ua * (1 - a)
        if (outA == 0.0) return 0
        fun ch(shift: Int): Int {
            val o = (over shr shift) and 0xFF
            val u = (under shr shift) and 0xFF
            return ((o * a + u * ua * (1 - a)) / outA).roundToInt().coerceIn(0, 255)
        }
        return ((outA * 255).roundToInt() shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
