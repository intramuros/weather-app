package io.github.intramuros.weatherbuddy.render

import android.content.Context
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
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
 */
internal class InfoOverlay(private val assets: AssetManager) {
    /** A pixel font with where its capitals sit, as fractions of the font size. */
    private class PixelFont(val typeface: Typeface, val capHeight: Float, val belowBaseline: Float, val fakeBold: Boolean = false)

    /** Chunky, for the temperature. */
    private val bigFont by lazy { PixelFont(Typeface.createFromAsset(assets, "fonts/Jersey10.ttf"), 0.535f, 0f) }

    /**
     * For everything else; its glyphs sit one font pixel below the baseline. Thickened,
     * since it's thinner than the lettering in the artwork.
     */
    private val smallFont by lazy {
        PixelFont(Typeface.createFromAsset(assets, "fonts/DotGothic16.ttf"), 0.815f, 0.0275f, fakeBold = true)
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
        // The icons match the picture pixel for pixel, so they land where the picture did.
        val b = Compositor.coverBounds(icons?.width ?: 1, icons?.height ?: 1, width, height)
        icons?.let {
            canvas.drawBitmap(it, null, RectF(b.left, b.top, b.left + b.width, b.top + b.height), Paint().apply { isFilterBitmap = true })
            it.recycle()
        }

        val layout = SceneLayout.of(picture)
        fun x(units: Float) = b.left + units / SceneLayout.UNITS * b.width
        fun y(units: Float) = b.top + units / SceneLayout.UNITS * b.height
        val paint = Paint().apply { isAntiAlias = true }
        val leftEdge = x(SceneLayout.LEFT_COLUMN_END)
        val rightEdge = x(SceneLayout.UNITS * 0.97f)

        // Lines are placed by the bottom of their capitals, as measured in the artwork.
        fun text(value: String, xUnits: Float, bottomUnits: Float, capUnits: Float, maxX: Float, big: Boolean, colour: Int) {
            val font = if (big) bigFont else smallFont
            paint.typeface = font.typeface
            paint.isFakeBoldText = font.fakeBold
            val left = x(xUnits)
            val capPixels = capUnits / SceneLayout.UNITS * b.height
            paint.fitText(value, capPixels / (font.capHeight + font.belowBaseline), maxX - left)
            paint.color = colour
            canvas.drawText(value, left, y(bottomUnits) - font.belowBaseline * paint.textSize, paint)
        }

        with(layout) {
            text(info.temperature, tempX, tempBottom, tempCap, leftEdge, big = true, colour = SCENE_TEXT)
            text(info.condition, labelX, labelBottom, labelCap, leftEdge, big = false, colour = SCENE_LABEL)
            info.place?.let { text(it, placeX, placeBottom, smallCap, rightEdge, big = false, colour = SCENE_TEXT) }
            info.humidity?.let { text(it, valuesX, humidityBottom, smallCap, rightEdge, big = false, colour = SCENE_TEXT) }
            text(info.wind, valuesX, windBottom, smallCap, rightEdge, big = false, colour = SCENE_TEXT)
            info.windDirection?.let { text(it, valuesX, directionBottom, smallCap, rightEdge, big = false, colour = SCENE_TEXT) }
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
        text(info.condition, margin, h * 0.26f, h * 0.055f, bold = false)

        val right = w - margin
        var baseline = h * 0.09f
        info.place?.let {
            text(it, right, baseline, h * 0.05f, bold = true, alignRight = true)
            baseline += h * 0.065f
        }
        info.humidityLabelled?.let {
            text(it, right, baseline, h * 0.045f, bold = false, alignRight = true)
            baseline += h * 0.06f
        }
        text(listOfNotNull(info.wind, info.windDirection).joinToString(" "), right, baseline, h * 0.045f, bold = false, alignRight = true)
    }

    private companion object {
        val SKY_TEXT = 0xFFFFFFFF.toInt()
        val SKY_TEXT_SHADOW = 0x99000000.toInt()
        val SKY_SHADE = 0x59000000
        val SCENE_TEXT = 0xFFF0F2F8.toInt()
        val SCENE_LABEL = 0xFFBCCAEA.toInt()

        /** Sets the text size, shrinking it until [text] fits [maxWidth]. */
        fun Paint.fitText(text: String, size: Float, maxWidth: Float) {
            textSize = size
            val measured = measureText(text)
            if (measured > maxWidth && measured > 0f) textSize = (size * maxWidth / measured).coerceAtLeast(1f)
        }
    }
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
