package io.github.intramuros.weatherbuddy.render

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import io.github.intramuros.weatherbuddy.core.RenderPlan
import io.github.intramuros.weatherbuddy.core.Style
import androidx.core.graphics.createBitmap
import java.io.FileNotFoundException

/**
 * Stacks a [RenderPlan]'s layers from `assets/styles/` into one bitmap.
 *
 * Scenery layers are square and scaled to cover the target, cropped around
 * the buddy. The buddy's layers are a 9:20 frame of the same height, placed
 * at [render]'s `buddyX`: centred on a wallpaper, to the left on the widget so
 * there is room for [WidgetInfo].
 */
class Compositor(private val assets: AssetManager) {
    private val overlay by lazy { InfoOverlay(assets) }

    fun render(
        plan: RenderPlan,
        style: Style,
        width: Int,
        height: Int,
        buddyX: Float = 0.5f,
        info: WidgetInfo? = null,
    ): Bitmap {
        val out = createBitmap(width, height)
        val canvas = Canvas(out)
        val paint = Paint().apply { isFilterBitmap = !style.pixelated }
        for (path in plan.layers) {
            val layer = load(path) ?: continue
            val b = if (Style.isScenery(path)) {
                coverBounds(layer.width, layer.height, width, height)
            } else {
                buddyBounds(layer.width, layer.height, width, height, buddyX)
            }
            canvas.drawBitmap(layer, null, RectF(b.left, b.top, b.left + b.width, b.top + b.height), paint)
            layer.recycle()
        }
        if (info != null) overlay.draw(canvas, info, style, plan.scene.timeOfDay, width, height)
        return out
    }

    private fun load(path: String): Bitmap? =
        try {
            assets.open("styles/$path").use { BitmapFactory.decodeStream(it) }
        } catch (_: FileNotFoundException) {
            Log.w(TAG, "missing layer $path")
            null
        }

    /** Where a layer lands on the target, in target pixels. */
    internal data class Bounds(val left: Float, val top: Float, val width: Float, val height: Float)

    internal companion object {
        private const val TAG = "Compositor"

        /** Vertical position of the buddy's middle in the art, as a fraction of its height. */
        const val FOCUS_Y = 0.67f

        /** Where the buddy stands on the widget, as a fraction of its width. */
        const val WIDGET_BUDDY_X = 0.32f

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

        /**
         * The buddy's frame at the same scale and height as the square scene it stands
         * in, with its middle at [buddyX] of the target's width.
         */
        fun buddyBounds(srcW: Int, srcH: Int, dstW: Int, dstH: Int, buddyX: Float): Bounds {
            val scene = coverBounds(srcH, srcH, dstW, dstH)
            val w = scene.height * srcW / srcH
            return Bounds(dstW * buddyX - w / 2, scene.top, w, scene.height)
        }
    }
}
