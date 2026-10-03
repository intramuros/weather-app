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
 * All layers share one aspect ratio (9:20). They're scaled to cover the target
 * and cropped around the buddy, so the same art works for a tall wallpaper and
 * a square widget.
 */
class Compositor(private val assets: AssetManager) {
    fun render(plan: RenderPlan, style: Style, width: Int, height: Int): Bitmap {
        val out = createBitmap(width, height)
        val canvas = Canvas(out)
        val paint = Paint().apply { isFilterBitmap = !style.pixelated }
        for (path in plan.layers) {
            val layer = load(path) ?: continue
            val b = coverBounds(layer.width, layer.height, width, height)
            canvas.drawBitmap(layer, null, RectF(b.left, b.top, b.left + b.width, b.top + b.height), paint)
            layer.recycle()
        }
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

        /** Vertical position of the buddy's middle in the source art, as a fraction of its height. */
        const val FOCUS_Y = 0.67f

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
