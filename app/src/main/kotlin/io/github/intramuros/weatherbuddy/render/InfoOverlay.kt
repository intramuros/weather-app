package io.github.intramuros.weatherbuddy.render

import android.content.Context
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import io.github.intramuros.weatherbuddy.R
import io.github.intramuros.weatherbuddy.labelRes
import io.github.intramuros.weatherbuddy.core.CompassPoint
import io.github.intramuros.weatherbuddy.core.Condition
import io.github.intramuros.weatherbuddy.core.Conditions
import io.github.intramuros.weatherbuddy.core.Scene
import io.github.intramuros.weatherbuddy.core.ScenePicture
import io.github.intramuros.weatherbuddy.core.Style
import io.github.intramuros.weatherbuddy.core.TimeOfDay
import java.io.FileNotFoundException
import kotlin.math.roundToInt

/** The words drawn on the widget, already formatted. */
data class WidgetInfo(
    val place: String?,
    val temperature: String,
    val condition: String,
    val humidity: String?,
    /** [humidity] with a label, for layouts without a drop icon. */
    val humidityLabelled: String?,
    val wind: String,
    val windDirection: String?,
) {
    /** For screen readers, since the words are part of the picture. */
    fun describe(context: Context): String = listOfNotNull(
        place,
        temperature,
        condition,
        humidity?.let { context.getString(R.string.humidity_description, it) },
        context.getString(R.string.wind_description, listOfNotNull(wind, windDirection).joinToString(" ")),
    ).joinToString(", ")

    companion object {
        fun from(context: Context, conditions: Conditions, scene: Scene, place: String?) = WidgetInfo(
            place = place,
            temperature = "${conditions.temperatureC.roundToInt()}°",
            condition = context.getString(Condition.of(scene).labelRes(scene.timeOfDay)),
            humidity = conditions.humidityPercent?.let { context.getString(R.string.humidity, it.roundToInt()) },
            humidityLabelled = conditions.humidityPercent?.let { context.getString(R.string.humidity_labelled, it.roundToInt()) },
            wind = context.getString(R.string.wind_speed, conditions.windSpeedKmh.roundToInt()),
            windDirection = conditions.windDirectionDeg?.let { CompassPoint.fromDegrees(it).name },
        )
    }
}

/**
 * Draws [WidgetInfo] onto the widget picture.
 *
 * Styles with icons ([Style.hasWidgetIcons]) get the picture's own icons
 * (weather, humidity drop, wind), then the text in the spots the artwork left
 * for it ([SceneLayout]). Other styles get the text in the sky, which their
 * pictures keep clear: temperature and condition on the left, place, humidity
 * and wind on the right. Every line shrinks to fit the space it has.
 *
 * Every line but the temperature is drawn larger than the mock-ups had it, to
 * be readable on a phone ([VALUES_SCALE]). Over the icons, the temperature
 * keeps its size, since it already fills the room under the weather icon, and
 * the condition below it grows by [LABEL_SCALE], down towards the ground.
 */
internal class InfoOverlay(private val assets: AssetManager) {
    /**
     * A pixel font with where its capitals sit, how far down and right its hard
     * shadow falls (none if 0) and the extra space before a `%`, all as fractions
     * of the font size.
     */
    private class PixelFont(
        val typeface: Typeface,
        val capHeight: Float,
        val belowBaseline: Float,
        val fakeBold: Boolean = false,
        val shadowOffset: Float = 0f,
        val percentGap: Float = 0f,
    )

    /** Chunky, for the temperature. */
    private val bigFont by lazy { PixelFont(Typeface.createFromAsset(assets, "fonts/Jersey10.ttf"), 0.535f, 0f) }

    /**
     * For everything else; its glyphs sit one font pixel below the baseline. Thickened,
     * since it's thinner than the lettering in the artwork, and shadowed one stroke
     * width away, to stand out from a pale daytime sky. Its `%` fills its whole cell,
     * so it would touch the digit before it.
     */
    private val smallFont by lazy {
        PixelFont(
            Typeface.createFromAsset(assets, "fonts/DotGothic16.ttf"),
            capHeight = 0.815f,
            belowBaseline = 0.0275f,
            fakeBold = true,
            shadowOffset = 0.0725f,
            percentGap = 0.08f,
        )
    }

    fun draw(canvas: Canvas, info: WidgetInfo, picture: ScenePicture, style: Style, width: Int, height: Int) {
        if (style.hasWidgetIcons) drawOnIcons(canvas, info, picture, style, width, height) else drawInSky(canvas, info, width, height)
    }

    private fun drawOnIcons(canvas: Canvas, info: WidgetInfo, picture: ScenePicture, style: Style, width: Int, height: Int) {
        val icons = try {
            assets.open(style.sceneIcons(picture)).use { BitmapFactory.decodeStream(it) }
        } catch (_: FileNotFoundException) {
            null
        }
        // The picture covers the widget, cropping its sides or sky on a widget of another
        // shape, so the icons and text are placed on their own to stay whole ([OverlayPlacement]).
        val place = OverlayPlacement(width, height)
        icons?.let {
            val split = (SceneLayout.LEFT_COLUMN_END / SceneLayout.UNITS * it.width).roundToInt()
            val bottom = place.y(SceneLayout.UNITS)
            val iconPaint = Paint().apply { isFilterBitmap = true }
            canvas.drawBitmap(it, Rect(0, 0, split, it.height), RectF(0f, 0f, place.leftColumnEnd, bottom), iconPaint)
            canvas.drawBitmap(it, Rect(split, 0, it.width, it.height), RectF(place.rightColumnStart, 0f, width.toFloat(), bottom), iconPaint)
            it.recycle()
        }

        val layout = SceneLayout.of(picture)
        fun x(units: Float) = place.x(units)
        fun y(units: Float) = place.y(units)
        val paint = Paint().apply { isAntiAlias = true }
        val leftEdge = place.leftColumnEnd
        val rightEdge = x(SceneLayout.UNITS * 0.97f)

        // Lines are placed by the bottom of their capitals, as measured in the artwork.
        fun text(value: String, xUnits: Float, bottomUnits: Float, capUnits: Float, maxX: Float, big: Boolean, colour: Int) {
            val font = if (big) bigFont else smallFont
            paint.typeface = font.typeface
            paint.isFakeBoldText = font.fakeBold
            val left = x(xUnits)
            val capPixels = capUnits * place.scale
            val gaps = value.count { it == '%' } * font.percentGap
            paint.fitText(value, capPixels / (font.capHeight + font.belowBaseline), maxX - left, gaps)
            val baseline = y(bottomUnits) - font.belowBaseline * paint.textSize
            val percentGap = font.percentGap * paint.textSize
            if (font.shadowOffset > 0f) {
                val offset = font.shadowOffset * paint.textSize
                paint.color = SCENE_SHADOW
                canvas.drawSpaced(value, left + offset, baseline + offset, percentGap, paint)
            }
            paint.color = colour
            canvas.drawSpaced(value, left, baseline, percentGap, paint)
        }

        with(layout) {
            val labelSize = labelCap * LABEL_SCALE
            val smallSize = smallCap * VALUES_SCALE
            // The condition grows down, keeping the artwork's gap below the temperature.
            // Humidity and wind grow both ways, to stay centred on their icons; the
            // direction moves down with the wind line above it.
            val growth = smallSize - smallCap
            text(info.temperature, tempX, tempBottom, tempCap, leftEdge, big = true, colour = SCENE_TEXT)
            text(info.condition, labelX, labelBottom + labelSize - labelCap, labelSize, leftEdge, big = false, colour = SCENE_LABEL)
            info.place?.let { text(it, placeX, placeBottom, smallSize, rightEdge, big = false, colour = SCENE_TEXT) }
            info.humidity?.let { text(it, valuesX, humidityBottom + growth / 2, smallSize, rightEdge, big = false, colour = SCENE_TEXT) }
            text(info.wind, valuesX, windBottom + growth / 2, smallSize, rightEdge, big = false, colour = SCENE_TEXT)
            info.windDirection?.let {
                text(it, valuesX, directionBottom + growth * 1.5f, smallSize, rightEdge, big = false, colour = SCENE_TEXT)
            }
        }
    }

    private fun drawInSky(canvas: Canvas, info: WidgetInfo, width: Int, height: Int) {
        val w = width.toFloat()
        val h = height.toFloat()
        // A soft shade behind the text, fading out before the buddy.
        val shade = Paint().apply { shader = LinearGradient(0f, 0f, 0f, h * 0.45f, SKY_SHADE, 0, Shader.TileMode.CLAMP) }
        canvas.drawRect(0f, 0f, w, h * 0.45f, shade)

        val paint = Paint().apply {
            isAntiAlias = true
            color = SKY_TEXT
            setShadowLayer(h * 0.008f, 0f, h * 0.003f, SKY_TEXT_SHADOW)
        }
        val margin = w * 0.05f
        val middle = w * 0.5f

        fun text(value: String, x: Float, baseline: Float, size: Float, bold: Boolean, alignRight: Boolean = false) {
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            paint.textAlign = if (alignRight) Paint.Align.RIGHT else Paint.Align.LEFT
            paint.fitText(value, size, middle - margin * 1.5f)
            canvas.drawText(value, x, baseline, paint)
        }

        text(info.temperature, margin, h * 0.19f, h * 0.17f, bold = true)
        text(info.condition, margin, h * 0.31f, h * 0.055f * VALUES_SCALE, bold = false)

        val right = w - margin
        var baseline = h * 0.12f
        info.place?.let {
            text(it, right, baseline, h * 0.05f * VALUES_SCALE, bold = true, alignRight = true)
            baseline += h * 0.065f * VALUES_SCALE
        }
        info.humidityLabelled?.let {
            text(it, right, baseline, h * 0.045f * VALUES_SCALE, bold = false, alignRight = true)
            baseline += h * 0.06f * VALUES_SCALE
        }
        val wind = listOfNotNull(info.wind, info.windDirection).joinToString(" ")
        text(wind, right, baseline, h * 0.045f * VALUES_SCALE, bold = false, alignRight = true)
    }

    private companion object {
        val SKY_TEXT = 0xFFFFFFFF.toInt()
        val SKY_TEXT_SHADOW = 0x99000000.toInt()
        val SKY_SHADE = 0x59000000
        val SCENE_TEXT = 0xFFF0F2F8.toInt()
        val SCENE_LABEL = 0xFFBCCAEA.toInt()
        val SCENE_SHADOW = 0xAA141A38.toInt()

        /** How much larger than in the mock-ups the condition under the temperature is, over the icons. */
        const val LABEL_SCALE = 1.25f

        /** How much larger than in the mock-ups the place, humidity and wind are, and in the sky the condition. */
        const val VALUES_SCALE = 1.5f

        /**
         * Sets the text size, shrinking it until [text] fits [maxWidth], with
         * [extraSpace] (a fraction of the text size) added to its width.
         */
        fun Paint.fitText(text: String, size: Float, maxWidth: Float, extraSpace: Float = 0f) {
            textSize = size
            val measured = measureText(text) + extraSpace * size
            if (measured > maxWidth && measured > 0f) textSize = (size * maxWidth / measured).coerceAtLeast(1f)
        }

        /** Draws [text] with [percentGap] pixels of extra space before each `%`. */
        fun Canvas.drawSpaced(text: String, x: Float, y: Float, percentGap: Float, paint: Paint) {
            if (percentGap <= 0f) return drawText(text, x, y, paint)
            var cursor = x
            text.split('%').forEachIndexed { i, part ->
                if (i > 0) {
                    cursor += percentGap
                    drawText("%", cursor, y, paint)
                    cursor += paint.measureText("%")
                }
                drawText(part, cursor, y, paint)
                cursor += paint.measureText(part)
            }
        }
    }
}

/**
 * Where the widget's icons and text go on a [width] × [height] picture, from
 * [SceneLayout] units: scaled to fit and at the top, with the left column (up to
 * [SceneLayout.LEFT_COLUMN_END]) against the left edge and the right column
 * against the right. On a square picture that's exactly where the artwork has them.
 */
internal class OverlayPlacement(width: Int, height: Int) {
    /** Pixels per unit. */
    val scale = minOf(width, height) / SceneLayout.UNITS

    /** How far right of its place in the artwork the right column moves. */
    private val shift = width - SceneLayout.UNITS * scale

    val leftColumnEnd = SceneLayout.LEFT_COLUMN_END * scale
    val rightColumnStart = leftColumnEnd + shift

    fun x(units: Float) = units * scale + if (units >= SceneLayout.LEFT_COLUMN_END) shift else 0f

    fun y(units: Float) = units * scale
}

/**
 * Where each [ScenePicture] leaves room for text, in units of a 1200 × 1200
 * picture: the temperature and condition under the weather icon on the left,
 * and the place, humidity and wind (next to the drop and wind icons) on the
 * right. Measured from the artwork: `x` is a line's left edge, `bottom` the
 * bottom of its capitals, and `cap` their height.
 */
internal class SceneLayout(
    val tempX: Float,
    val tempBottom: Float,
    val tempCap: Float,
    val labelX: Float,
    val labelBottom: Float,
    val labelCap: Float,
    val placeX: Float,
    val placeBottom: Float,
    val valuesX: Float,
    val humidityBottom: Float,
    val windBottom: Float,
    val directionBottom: Float,
    val smallCap: Float,
) {
    companion object {
        const val UNITS = 1200f

        /** The left column stays clear of the buddy's umbrella. */
        const val LEFT_COLUMN_END = 720f

        fun of(picture: ScenePicture): SceneLayout = when (picture) {
            ScenePicture.CLEAR_HOT -> SceneLayout(226f, 403f, 134f, 228f, 466f, 42f, 761f, 135f, 905f, 257f, 363f, 423f, 46f)
            ScenePicture.CLEAR_WARM -> SceneLayout(228f, 403f, 127f, 232f, 469f, 43f, 769f, 146f, 915f, 264f, 360f, 420f, 44f)
            ScenePicture.CLEAR_FREEZING -> SceneLayout(165f, 418f, 144f, 170f, 479f, 43f, 740f, 136f, 905f, 258f, 368f, 431f, 46f)
            ScenePicture.CLEAR_NIGHT_HOT -> SceneLayout(167f, 413f, 142f, 168f, 476f, 43f, 737f, 137f, 908f, 258f, 366f, 432f, 47f)
            ScenePicture.CLEAR_NIGHT_WARM -> SceneLayout(165f, 411f, 142f, 165f, 475f, 42f, 736f, 135f, 906f, 256f, 364f, 432f, 47f)
            ScenePicture.CLEAR_NIGHT_COLD -> SceneLayout(179f, 413f, 145f, 133f, 484f, 48f, 735f, 134f, 908f, 256f, 365f, 431f, 49f)
            ScenePicture.PARTLY_CLOUDY_MILD -> SceneLayout(168f, 412f, 142f, 169f, 472f, 47f, 738f, 134f, 908f, 256f, 364f, 432f, 47f)
            ScenePicture.PARTLY_CLOUDY_COLD -> SceneLayout(130f, 396f, 139f, 130f, 459f, 45f, 780f, 133f, 915f, 255f, 358f, 422f, 46f)
            ScenePicture.CLOUDY_COOL -> SceneLayout(175f, 414f, 140f, 184f, 476f, 42f, 764f, 144f, 913f, 261f, 363f, 427f, 45f)
            ScenePicture.CLOUDY_COLD -> SceneLayout(217f, 409f, 147f, 217f, 476f, 44f, 752f, 159f, 886f, 274f, 381f, 444f, 45f)
            ScenePicture.CLOUDY_FREEZING -> SceneLayout(170f, 417f, 142f, 176f, 478f, 42f, 737f, 137f, 906f, 259f, 367f, 434f, 47f)
            ScenePicture.FOG_COOL -> SceneLayout(154f, 429f, 158f, 164f, 494f, 46f, 736f, 135f, 909f, 255f, 364f, 432f, 47f)
            ScenePicture.FOG_FREEZING -> SceneLayout(171f, 413f, 139f, 173f, 473f, 40f, 738f, 138f, 907f, 258f, 367f, 432f, 46f)
            ScenePicture.WINDY_MILD -> SceneLayout(160f, 420f, 149f, 169f, 478f, 41f, 738f, 137f, 907f, 259f, 367f, 435f, 47f)
            ScenePicture.WINDY_COOL -> SceneLayout(174f, 419f, 146f, 178f, 483f, 47f, 735f, 133f, 910f, 255f, 364f, 432f, 49f)
            ScenePicture.WINDY_NIGHT_COOL -> SceneLayout(167f, 427f, 156f, 168f, 478f, 44f, 738f, 137f, 908f, 258f, 366f, 433f, 47f)
            ScenePicture.WINDY_FREEZING -> SceneLayout(217f, 419f, 134f, 183f, 478f, 41f, 738f, 138f, 908f, 258f, 366f, 433f, 46f)
            ScenePicture.WINDY_NIGHT_FREEZING -> SceneLayout(188f, 412f, 140f, 178f, 475f, 41f, 735f, 136f, 904f, 257f, 366f, 433f, 47f)
            ScenePicture.RAIN_HOT -> SceneLayout(148f, 429f, 150f, 180f, 485f, 43f, 743f, 137f, 907f, 258f, 366f, 433f, 47f)
            ScenePicture.RAIN_WARM -> SceneLayout(162f, 412f, 126f, 166f, 476f, 42f, 747f, 135f, 909f, 257f, 365f, 432f, 46f)
            ScenePicture.RAIN_MILD -> SceneLayout(134f, 425f, 156f, 134f, 493f, 51f, 759f, 142f, 904f, 259f, 365f, 433f, 48f)
            ScenePicture.RAIN_NIGHT_COOL -> SceneLayout(153f, 419f, 145f, 165f, 475f, 41f, 738f, 137f, 909f, 259f, 366f, 434f, 47f)
            ScenePicture.RAIN_FREEZING -> SceneLayout(173f, 413f, 148f, 177f, 474f, 39f, 743f, 142f, 907f, 259f, 369f, 434f, 46f)
            ScenePicture.RAIN_COLD -> SceneLayout(132f, 428f, 156f, 134f, 497f, 51f, 735f, 134f, 907f, 254f, 364f, 431f, 49f)
            ScenePicture.STORM_WARM -> SceneLayout(166f, 413f, 143f, 164f, 474f, 42f, 738f, 136f, 907f, 256f, 365f, 432f, 48f)
            ScenePicture.STORM_COOL -> SceneLayout(141f, 426f, 154f, 147f, 490f, 46f, 747f, 128f, 913f, 249f, 359f, 426f, 47f)
            ScenePicture.STORM_COLD -> SceneLayout(103f, 397f, 141f, 108f, 452f, 37f, 843f, 137f, 952f, 259f, 348f, 399f, 37f)
            ScenePicture.SNOW_COLD -> SceneLayout(205f, 397f, 126f, 210f, 465f, 40f, 736f, 145f, 884f, 258f, 353f, 416f, 42f)
            ScenePicture.SNOW_FREEZING -> SceneLayout(167f, 411f, 139f, 181f, 466f, 44f, 760f, 132f, 908f, 249f, 352f, 415f, 46f)
            ScenePicture.SNOW_NIGHT_FREEZING -> SceneLayout(171f, 417f, 135f, 184f, 476f, 43f, 756f, 138f, 907f, 258f, 367f, 432f, 45f)
        }
    }
}
