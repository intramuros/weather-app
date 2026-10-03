package io.github.intramuros.weatherbuddy.render

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import io.github.intramuros.weatherbuddy.R
import io.github.intramuros.weatherbuddy.core.CompassPoint
import io.github.intramuros.weatherbuddy.core.Condition
import io.github.intramuros.weatherbuddy.core.Conditions
import io.github.intramuros.weatherbuddy.core.Scene
import io.github.intramuros.weatherbuddy.core.Style
import io.github.intramuros.weatherbuddy.core.TimeOfDay
import kotlin.math.floor
import kotlin.math.roundToInt

/** The words drawn next to the buddy on the widget, already formatted. */
data class WidgetInfo(
    val temperature: String,
    val condition: String,
    val wind: String,
    val windDirection: String?,
) {
    /** For screen readers, since the words are part of the picture. */
    fun describe(context: Context): String =
        context.getString(R.string.widget_info_description, temperature, condition, listOfNotNull(wind, windDirection).joinToString(" "))

    companion object {
        fun from(context: Context, conditions: Conditions, scene: Scene) = WidgetInfo(
            temperature = "${conditions.temperatureC.roundToInt()}°",
            condition = context.getString(label(Condition.of(scene))),
            wind = context.getString(R.string.wind_speed, conditions.windSpeedKmh.roundToInt()),
            windDirection = conditions.windDirectionDeg?.let { CompassPoint.fromDegrees(it).name },
        )

        private fun label(condition: Condition) = when (condition) {
            Condition.CLEAR -> R.string.condition_clear
            Condition.PARTLY_CLOUDY -> R.string.condition_partly_cloudy
            Condition.CLOUDY -> R.string.condition_cloudy
            Condition.FOG -> R.string.condition_fog
            Condition.DRIZZLE -> R.string.condition_drizzle
            Condition.RAIN -> R.string.condition_rain
            Condition.HEAVY_RAIN -> R.string.condition_heavy_rain
            Condition.SNOW -> R.string.condition_snow
            Condition.HAIL -> R.string.condition_hail
            Condition.THUNDERSTORM -> R.string.condition_thunderstorm
        }
    }
}

/**
 * Draws [WidgetInfo] in the right-hand part of the widget picture: a big
 * temperature, the condition, and the wind with a little wind icon. Sizes follow
 * the picture's height, and every line shrinks to fit the width.
 */
internal class InfoOverlay(private val assets: AssetManager) {
    private val pixelRegular by lazy { Typeface.createFromAsset(assets, PIXEL_FONT) }
    private val pixelBold by lazy {
        Typeface.Builder(assets, PIXEL_FONT).setFontVariationSettings("'wght' 700").build() ?: pixelRegular
    }

    fun draw(canvas: Canvas, info: WidgetInfo, style: Style, time: TimeOfDay, width: Int, height: Int) {
        val h = height.toFloat()
        // One pixel of the square scene, which is what the shadow is offset by.
        val unit = maxOf(width, height) / SCENE_PIXELS
        val ink = Ink.of(style, time)
        val pixelated = style.pixelated
        val paint = Paint().apply { isAntiAlias = !pixelated }
        val left = width * TEXT_LEFT
        val maxWidth = width * TEXT_RIGHT - left

        fun text(value: String, x: Float, baseline: Float, size: Float, bold: Boolean) {
            paint.typeface = when {
                pixelated && bold -> pixelBold
                pixelated -> pixelRegular
                bold -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
                else -> Typeface.SERIF
            }
            paint.fitText(value, size, width * TEXT_RIGHT - x, pixelated)
            ink.shadow?.let {
                paint.color = it
                canvas.drawText(value, x + unit, baseline + unit, paint)
            }
            paint.color = ink.fill
            canvas.drawText(value, x, baseline, paint)
        }

        val tempBaseline = h * 0.27f
        text(info.temperature, left, tempBaseline, h * 0.22f, bold = true)
        val conditionBaseline = tempBaseline + h * 0.10f
        text(info.condition, left, conditionBaseline, h * 0.075f, bold = false)

        val k = (h * 0.0066f).roundToInt().coerceAtLeast(1).toFloat()
        val iconTop = conditionBaseline + h * 0.07f
        if (maxWidth > WIND_ICON[0].length * k * 2) {
            ink.shadow?.let { windIcon(canvas, left + unit, iconTop + unit, k, paint.apply { color = it }) }
            windIcon(canvas, left, iconTop, k, paint.apply { color = ink.fill })
        }
        val windX = left + (WIND_ICON[0].length + 2) * k
        val windBaseline = iconTop + 7 * k
        text(info.wind, windX, windBaseline, h * 0.065f, bold = false)
        info.windDirection?.let { text(it, windX, windBaseline + h * 0.08f, h * 0.065f, bold = false) }
    }

    private fun windIcon(canvas: Canvas, x: Float, y: Float, k: Float, paint: Paint) {
        for ((row, line) in WIND_ICON.withIndex()) for ((col, ch) in line.withIndex()) {
            if (ch == '#') canvas.drawRect(x + col * k, y + row * k, x + (col + 1) * k, y + (row + 1) * k, paint)
        }
    }

    /** Light text with a dark drop shadow, or dark ink with none on pale daytime paper. */
    private class Ink(val fill: Int, val shadow: Int?) {
        companion object {
            fun of(style: Style, time: TimeOfDay): Ink = when {
                time == TimeOfDay.NIGHT || style == Style.PIXEL_ART -> Ink(0xFFF6F2FF.toInt(), 0xC01A162C.toInt())
                style == Style.DELFTS_BLAUW -> Ink(0xFF1F3C88.toInt(), null)
                else -> Ink(0xFF1C1C1C.toInt(), null)
            }
        }
    }

    private companion object {
        const val PIXEL_FONT = "fonts/PixelifySans.ttf"
        const val SCENE_PIXELS = 300f
        const val TEXT_LEFT = 0.56f
        const val TEXT_RIGHT = 0.96f

        val WIND_ICON = listOf(
            ".......##...",
            "......#..#..",
            ".........#..",
            "#########...",
            "............",
            "###########.",
            "...........#",
            "........#..#",
            ".........##.",
        )

        /**
         * Sets the text size, shrinking it until [text] fits [maxWidth]. The pixel font
         * is drawn on a grid of 1/10 em, so its size stays a multiple of 10 to keep
         * every font pixel the same size.
         */
        fun Paint.fitText(text: String, size: Float, maxWidth: Float, pixelated: Boolean) {
            fun snap(s: Float, round: (Float) -> Float) = if (pixelated && s >= 10f) round(s / 10f) * 10f else s
            textSize = snap(size) { it.roundToInt().toFloat() }
            val measured = measureText(text)
            if (measured > maxWidth && measured > 0f) {
                textSize = snap(textSize * maxWidth / measured) { floor(it) }.coerceAtLeast(1f)
            }
        }
    }
}
