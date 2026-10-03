package io.github.intramuros.weatherbuddy.render

import android.content.Context
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import io.github.intramuros.weatherbuddy.R
import io.github.intramuros.weatherbuddy.core.CompassPoint
import io.github.intramuros.weatherbuddy.core.Condition
import io.github.intramuros.weatherbuddy.core.Conditions
import io.github.intramuros.weatherbuddy.core.RenderPlan
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
            condition = context.getString(label(Condition.of(scene), scene.timeOfDay)),
            humidity = conditions.humidityPercent?.let { context.getString(R.string.humidity, it.roundToInt()) },
            wind = context.getString(R.string.wind_speed, conditions.windSpeedKmh.roundToInt()),
            windDirection = conditions.windDirectionDeg?.let { CompassPoint.fromDegrees(it).name },
        )

        private fun label(condition: Condition, time: TimeOfDay) = when (condition) {
            Condition.CLEAR -> if (time == TimeOfDay.DAY) R.string.condition_sunny else R.string.condition_clear
            Condition.PARTLY_CLOUDY -> R.string.condition_partly_cloudy
            Condition.CLOUDY -> R.string.condition_cloudy
            Condition.FOG -> R.string.condition_fog
            Condition.DRIZZLE -> R.string.condition_drizzle
            Condition.RAIN -> R.string.condition_rain
            Condition.HEAVY_RAIN -> R.string.condition_heavy_rain
            Condition.SNOW -> R.string.condition_snow
            Condition.HAIL -> R.string.condition_hail
            Condition.THUNDERSTORM -> R.string.condition_thunderstorm
            Condition.WINDY -> R.string.condition_windy
        }
    }
}

/**
 * Draws [WidgetInfo] onto the widget picture.
 *
 * Whole-scene styles get the picture's own icons (weather, humidity drop, wind)
 * and the text in the spots the artwork left for it ([SceneLayout]). Layered
 * styles get the text in the right-hand part of the picture, next to the buddy.
 * Every line shrinks to fit the space it has.
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

    fun draw(canvas: Canvas, info: WidgetInfo, plan: RenderPlan, style: Style, width: Int, height: Int) {
        val picture = plan.picture
        if (picture != null) {
            drawOnScene(canvas, info, picture, style, width, height)
        } else {
            drawBesideBuddy(canvas, info, style, plan.scene.timeOfDay, width, height)
        }
    }

    private fun drawOnScene(canvas: Canvas, info: WidgetInfo, picture: ScenePicture, style: Style, width: Int, height: Int) {
        val icons = try {
            assets.open("styles/${style.sceneIcons(picture)}").use { BitmapFactory.decodeStream(it) }
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

    private fun drawBesideBuddy(canvas: Canvas, info: WidgetInfo, style: Style, time: TimeOfDay, width: Int, height: Int) {
        val h = height.toFloat()
        // One pixel of the 300-pixel scene, which is what the shadow is offset by.
        val unit = maxOf(width, height) / 300f
        val ink = Ink.of(style, time)
        val paint = Paint().apply { isAntiAlias = true }
        val left = width * TEXT_LEFT

        fun text(value: String, x: Float, baseline: Float, size: Float, big: Boolean) {
            paint.typeface = if (big) Typeface.create(Typeface.SERIF, Typeface.BOLD) else Typeface.SERIF
            paint.fitText(value, size, width * TEXT_RIGHT - x)
            ink.shadow?.let {
                paint.color = it
                canvas.drawText(value, x + unit, baseline + unit, paint)
            }
            paint.color = ink.fill
            canvas.drawText(value, x, baseline, paint)
        }

        var baseline = h * 0.16f
        info.place?.let {
            text(it, left, baseline, h * 0.06f, big = false)
            baseline += h * 0.04f
        }
        baseline += h * 0.13f
        text(info.temperature, left, baseline, h * 0.18f, big = true)
        baseline += h * 0.09f
        text(info.condition, left, baseline, h * 0.07f, big = false)
        info.humidity?.let {
            baseline += h * 0.09f
            text(it, left, baseline, h * 0.06f, big = false)
        }
        baseline += h * 0.08f
        text(listOfNotNull(info.wind, info.windDirection).joinToString(" "), left, baseline, h * 0.06f, big = false)
    }

    /** Light text with a dark drop shadow, or dark ink with none on pale daytime paper. */
    private class Ink(val fill: Int, val shadow: Int?) {
        companion object {
            fun of(style: Style, time: TimeOfDay): Ink = when {
                time == TimeOfDay.NIGHT -> Ink(0xFFF6F2FF.toInt(), 0xC01A162C.toInt())
                style == Style.DELFTS_BLAUW -> Ink(0xFF1F3C88.toInt(), null)
                else -> Ink(0xFF1C1C1C.toInt(), null)
            }
        }
    }

    private companion object {
        const val TEXT_LEFT = 0.56f
        const val TEXT_RIGHT = 0.96f
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
