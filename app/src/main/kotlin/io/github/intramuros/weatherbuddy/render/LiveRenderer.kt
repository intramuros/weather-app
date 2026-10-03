package io.github.intramuros.weatherbuddy.render

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withScale
import io.github.intramuros.weatherbuddy.core.ArtCanvas
import io.github.intramuros.weatherbuddy.core.Layer
import io.github.intramuros.weatherbuddy.core.ParticleField
import io.github.intramuros.weatherbuddy.core.ParticleSpec
import io.github.intramuros.weatherbuddy.core.RenderPlan
import io.github.intramuros.weatherbuddy.core.Style
import kotlin.math.floor

/**
 * Draws a [RenderPlan] at any moment in time: the live wallpaper and the
 * in-app preview.
 *
 * Runs of still layers are flattened into one bitmap up front, so a frame is
 * only a handful of bitmap draws plus the particles. Animated frames are
 * trimmed to their visible pixels to keep memory down. Pixel art is drawn
 * into a buffer at its native 135 × 300 and scaled up without smoothing, so
 * the raindrops snap to the same pixel grid as the art.
 *
 * Use from one thread only.
 */
class LiveRenderer(assets: AssetManager, private val plan: RenderPlan, private val artStyle: Style) {
    private sealed interface Step

    private class Still(val bitmap: Bitmap) : Step

    private class Animated(val sprite: Layer.Sprite, val frames: Map<String, Trimmed>) : Step

    private class Particles(val fields: List<Pair<ParticleSpec, ParticleField>>) : Step

    private class Trimmed(val bitmap: Bitmap, val left: Float, val top: Float)

    private val artWidth: Int
    private val artHeight: Int
    private val steps: List<Step>
    private val bitmapPaint = Paint().apply { isFilterBitmap = !artStyle.pixelated }
    private val particlePaint = Paint().apply {
        isAntiAlias = !artStyle.pixelated
        strokeCap = Paint.Cap.BUTT
    }
    private val buffer: Bitmap?
    private val bufferCanvas: Canvas?
    private val dst = RectF()

    init {
        val compositor = Compositor(assets)
        val first = compositor.load(plan.stillLayers.first()) ?: createBitmap(135, 300)
        artWidth = first.width
        artHeight = first.height
        first.recycle()

        val built = mutableListOf<Step>()
        var stillRun: Bitmap? = null
        fun flushStill() {
            stillRun?.let { built += Still(it) }
            stillRun = null
        }
        var seed = 1L
        for (layer in plan.layers) {
            when {
                layer is Layer.Sprite && !layer.isAnimated -> {
                    val bitmap = compositor.load(layer.still) ?: continue
                    val run = stillRun ?: createBitmap(artWidth, artHeight).also { stillRun = it }
                    Canvas(run).drawBitmap(bitmap, null, RectF(0f, 0f, artWidth.toFloat(), artHeight.toFloat()), bitmapPaint)
                    bitmap.recycle()
                }
                layer is Layer.Sprite -> {
                    flushStill()
                    val frames = layer.assets.distinct().mapNotNull { path ->
                        compositor.load(path)?.let { path to trim(it) }
                    }.toMap()
                    if (frames.isNotEmpty()) built += Animated(layer, frames)
                }
                layer is Layer.Particles -> {
                    flushStill()
                    built += Particles(layer.specs.map { it to ParticleField(it, seed++) })
                }
            }
        }
        flushStill()
        steps = built

        if (artStyle.pixelated) {
            buffer = createBitmap(artWidth, artHeight)
            bufferCanvas = Canvas(buffer)
        } else {
            buffer = null
            bufferCanvas = null
        }
    }

    /** Rounds time down to this style's frame rate, so pixel art moves in crisp steps. */
    fun quantize(seconds: Double): Double = floor(seconds * artStyle.fps) / artStyle.fps

    /** Draws the frame at [seconds], covering a [width] × [height] area at the canvas origin. */
    fun draw(canvas: Canvas, width: Int, height: Int, seconds: Double) {
        val t = quantize(seconds)
        val b = Compositor.coverBounds(artWidth, artHeight, width, height)
        canvas.withClip(0, 0, width, height) {
            if (plan.mirrored) scale(-1f, 1f, width / 2f, 0f)
            if (buffer != null && bufferCanvas != null) {
                buffer.eraseColor(Color.TRANSPARENT)
                drawArt(bufferCanvas, t)
                dst.set(b.left, b.top, b.left + b.width, b.top + b.height)
                drawBitmap(buffer, null, dst, bitmapPaint)
            } else {
                translate(b.left, b.top)
                scale(b.width / artWidth, b.height / artHeight)
                drawArt(this, t)
            }
        }
    }

    private fun drawArt(canvas: Canvas, t: Double) {
        for (step in steps) {
            when (step) {
                is Still -> canvas.drawBitmap(step.bitmap, 0f, 0f, bitmapPaint)
                is Animated -> step.frames[step.sprite.frameAt(t)]?.let {
                    canvas.drawBitmap(it.bitmap, it.left, it.top, bitmapPaint)
                }
                is Particles -> canvas.withScale(
                    (artWidth / ArtCanvas.WIDTH).toFloat(),
                    (artHeight / ArtCanvas.HEIGHT).toFloat(),
                ) {
                    for ((spec, field) in step.fields) drawParticles(this, spec, field, t)
                }
            }
        }
    }

    private fun drawParticles(canvas: Canvas, spec: ParticleSpec, field: ParticleField, t: Double) {
        val look = artStyle.particleLook(spec.kind)
        val paint = particlePaint
        paint.color = look.argb
        paint.strokeWidth = look.width.toFloat()
        val size = spec.size.toFloat()
        field.forEachAt(t) { x, y, dx, dy ->
            val fx = x.toFloat()
            val fy = y.toFloat()
            when {
                !spec.isDot -> canvas.drawLine(fx, fy, fx + dx.toFloat(), fy + dy.toFloat(), paint)
                artStyle.pixelated -> canvas.drawRect(fx, fy, fx + size, fy + size, paint)
                else -> canvas.drawCircle(fx + size / 2, fy + size / 2, size / 2, paint)
            }
        }
    }

    private companion object {
        /** Crops a full-canvas frame to its visible pixels. */
        fun trim(bitmap: Bitmap): Trimmed {
            val w = bitmap.width
            val h = bitmap.height
            val row = IntArray(w)
            var left = w
            var right = -1
            var top = h
            var bottom = -1
            for (y in 0 until h) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    if (row[x] ushr 24 != 0) {
                        if (x < left) left = x
                        if (x > right) right = x
                        if (y < top) top = y
                        bottom = y
                    }
                }
            }
            if (right < 0) return Trimmed(createBitmap(1, 1), 0f, 0f)
            val cropped = Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
            if (cropped !== bitmap) bitmap.recycle()
            return Trimmed(cropped, left.toFloat(), top.toFloat())
        }
    }
}
