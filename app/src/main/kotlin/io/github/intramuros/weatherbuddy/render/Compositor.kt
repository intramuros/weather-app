package io.github.intramuros.weatherbuddy.render

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withScale
import io.github.intramuros.weatherbuddy.core.RenderPlan
import io.github.intramuros.weatherbuddy.core.Style
import java.io.FileNotFoundException

/**
 * Draws a [RenderPlan]'s picture in a [Style] as a still bitmap: scaled to cover
 * the target, cropped around the buddy, with the widget's [WidgetInfo] on top if given.
 */
class Compositor(private val assets: AssetManager) {
    private val overlay by lazy { InfoOverlay(assets) }

    fun render(plan: RenderPlan, style: Style, width: Int, height: Int, info: WidgetInfo? = null): Bitmap {
        val out = createBitmap(width, height)
        val canvas = Canvas(out)
        load(style.scene(plan.picture))?.let {
            val b = coverBounds(it.width, it.height, width, height)
            canvas.drawBitmap(it, null, RectF(b.left, b.top, b.left + b.width, b.top + b.height), bitmapPaint(style))
            it.recycle()
        }
        if (info != null) overlay.draw(canvas, info, plan.picture, style, width, height)
        return out
    }

    internal fun load(path: String): Bitmap? =
        try {
            assets.open(path).use { BitmapFactory.decodeStream(it) }
        } catch (_: FileNotFoundException) {
            Log.w(TAG, "missing picture $path")
            null
        }

    /** Where a picture lands on the target, in target pixels. */
    internal data class Bounds(val left: Float, val top: Float, val width: Float, val height: Float) {
        val centerX: Float get() = left + width / 2
    }

    internal companion object {
        private const val TAG = "Compositor"

        /** Vertical position of the buddy's middle in the art, as a fraction of its height. */
        const val FOCUS_Y = 0.67f

        /** Pixel art scales with nearest-neighbour instead of smoothing, to keep pixels crisp. */
        fun bitmapPaint(style: Style) = Paint().apply { isFilterBitmap = !style.pixelated }

        /** Draws [block] flipped around the middle of [bounds] when [mirror] is set. */
        inline fun Canvas.mirroredIf(mirror: Boolean, bounds: Bounds, block: Canvas.() -> Unit) {
            if (mirror) withScale(-1f, 1f, bounds.centerX, 0f) { block() } else block()
        }

        /**
         * Scales the source to cover the target and centres it horizontally. Vertically
         * it keeps [FOCUS_Y] in the middle where possible, without exposing empty space.
         */
        fun coverBounds(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Bounds {
            val scale = maxOf(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
            val w = srcW * scale
            val h = srcH * scale
            val top = (dstH / 2f - FOCUS_Y * h).coerceIn(dstH - h, 0f)
            return Bounds((dstW - w) / 2, top, w, h)
        }
    }
}
