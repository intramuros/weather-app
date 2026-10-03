package io.github.intramuros.weatherbuddy.render

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import io.github.intramuros.weatherbuddy.core.ArtCanvas
import io.github.intramuros.weatherbuddy.core.Layer
import io.github.intramuros.weatherbuddy.core.ParticleField
import io.github.intramuros.weatherbuddy.core.ParticleSpec
import io.github.intramuros.weatherbuddy.core.RenderPlan
import io.github.intramuros.weatherbuddy.core.Style
import io.github.intramuros.weatherbuddy.render.Compositor.Companion.buddyBounds
import io.github.intramuros.weatherbuddy.render.Compositor.Companion.coverBounds
import io.github.intramuros.weatherbuddy.render.Compositor.Companion.mirroredIf
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Draws a [RenderPlan] at any moment in time: the live wallpaper and the
 * in-app preview.
 *
 * Runs of still layers are flattened into one bitmap up front (one for the
 * square scenery, one for the buddy's frame), so a frame is only a handful of
 * bitmap draws plus the particles. Animated frames are trimmed to their
 * visible pixels to keep memory down. For pixel art, bitmaps are scaled
 * without smoothing and particles snap to the scene's pixel grid, so they
 * look like part of the art.
 *
 * Use from one thread only.
 */
class LiveRenderer(assets: AssetManager, private val plan: RenderPlan, private val artStyle: Style) {
    private sealed interface Step

    private class Still(val bitmap: Bitmap, val scenery: Boolean) : Step

    private class Animated(val sprite: Layer.Sprite, val frames: Map<String, Trimmed>, val scenery: Boolean) : Step

    private class Particles(val fields: List<Pair<ParticleSpec, ParticleField>>) : Step

    private class Trimmed(val bitmap: Bitmap, val left: Int, val top: Int)

    /** Source size of each space, in the art's own pixels. */
    private class Space(val width: Int, val height: Int)

    private val scene: Space
    private val buddy: Space
    private val steps: List<Step>
    private val bitmapPaint = Paint().apply { isFilterBitmap = !artStyle.pixelated }
    private val particlePaint = Paint().apply { isAntiAlias = !artStyle.pixelated }
    private val dst = RectF()
    private var points = FloatArray(1024)

    init {
        val compositor = Compositor(assets)
        val spaces = HashMap<Boolean, Space>()
        fun load(path: String): Bitmap? = compositor.load(path)?.also {
            spaces.getOrPut(Style.isScenery(path)) { Space(it.width, it.height) }
        }

        val built = mutableListOf<Step>()
        var run: Bitmap? = null
        var runIsScenery = false
        fun flush() {
            run?.let { built += Still(it, runIsScenery) }
            run = null
        }
        var seed = 1L
        for (layer in plan.layers) {
            when {
                layer is Layer.Sprite && !layer.isAnimated -> {
                    val scenery = Style.isScenery(layer.still)
                    val bitmap = load(layer.still) ?: continue
                    if (run != null && runIsScenery != scenery) flush()
                    val target = run ?: createBitmap(bitmap.width, bitmap.height).also {
                        run = it
                        runIsScenery = scenery
                    }
                    Canvas(target).drawBitmap(bitmap, null, RectF(0f, 0f, target.width.toFloat(), target.height.toFloat()), bitmapPaint)
                    bitmap.recycle()
                }
                layer is Layer.Sprite -> {
                    flush()
                    val frames = layer.assets.distinct().mapNotNull { path -> load(path)?.let { path to trim(it) } }.toMap()
                    if (frames.isNotEmpty()) built += Animated(layer, frames, Style.isScenery(layer.still))
                }
                layer is Layer.Particles -> {
                    flush()
                    built += Particles(layer.specs.map { it to ParticleField(it, seed++) })
                }
            }
        }
        flush()
        steps = built
        scene = spaces[true] ?: Space(ArtCanvas.SCENE_SIZE.toInt(), ArtCanvas.SCENE_SIZE.toInt())
        buddy = spaces[false] ?: Space(ArtCanvas.BUDDY_WIDTH.toInt(), ArtCanvas.SCENE_SIZE.toInt())
    }

    /** Rounds time down to this style's frame rate, so pixel art moves in crisp steps. */
    fun quantize(seconds: Double): Double = floor(seconds * artStyle.fps) / artStyle.fps

    /**
     * Draws the frame at [seconds], covering a [width] × [height] area at the canvas origin,
     * with the buddy's middle at [buddyX] of the width.
     */
    fun draw(canvas: Canvas, width: Int, height: Int, seconds: Double, buddyX: Float = 0.5f) {
        val t = quantize(seconds)
        val sceneBounds = coverBounds(scene.width, scene.height, width, height)
        val buddyBounds = buddyBounds(buddy.width, buddy.height, width, height, buddyX)
        canvas.withClip(0, 0, width, height) {
            for (step in steps) {
                when (step) {
                    is Still -> {
                        val b = if (step.scenery) sceneBounds else buddyBounds
                        mirroredIf(plan.mirrored, b) {
                            dst.set(b.left, b.top, b.left + b.width, b.top + b.height)
                            drawBitmap(step.bitmap, null, dst, bitmapPaint)
                        }
                    }
                    is Animated -> {
                        val frame = step.frames[step.sprite.frameAt(t)] ?: continue
                        val (b, space) = if (step.scenery) sceneBounds to scene else buddyBounds to buddy
                        val k = b.width / space.width
                        mirroredIf(plan.mirrored, b) {
                            val left = b.left + frame.left * k
                            val top = b.top + frame.top * k
                            dst.set(left, top, left + frame.bitmap.width * k, top + frame.bitmap.height * k)
                            drawBitmap(frame.bitmap, null, dst, bitmapPaint)
                        }
                    }
                    is Particles -> mirroredIf(plan.particlesMirrored, sceneBounds) {
                        val k = (sceneBounds.width / ArtCanvas.SCENE_SIZE).toFloat()
                        withTranslation(sceneBounds.left, sceneBounds.top) {
                            scale(k, k)
                            for ((spec, field) in step.fields) drawParticles(this, spec, field, t)
                        }
                    }
                }
            }
        }
    }

    private fun drawParticles(canvas: Canvas, spec: ParticleSpec, field: ParticleField, t: Double) {
        val look = artStyle.particleLook(spec.kind)
        val paint = particlePaint
        paint.color = look.argb
        if (artStyle.pixelated) {
            drawPixelParticles(canvas, spec, field, t)
            return
        }
        paint.strokeWidth = look.width.toFloat()
        paint.strokeCap = Paint.Cap.ROUND
        val r = spec.size.toFloat() / 2
        field.forEachAt(t) { x, y, dx, dy ->
            val fx = x.toFloat()
            val fy = y.toFloat()
            if (spec.isDot) {
                canvas.drawCircle(fx + r, fy + r, r, paint)
            } else {
                canvas.drawLine(fx, fy, fx + dx.toFloat(), fy + dy.toFloat(), paint)
            }
        }
    }

    /** Lines become runs of whole scene pixels, dots become pixel squares: all in one draw call. */
    private fun drawPixelParticles(canvas: Canvas, spec: ParticleSpec, field: ParticleField, t: Double) {
        var n = 0
        fun point(x: Double, y: Double) {
            if (n + 2 > points.size) points = points.copyOf(points.size * 2)
            points[n++] = floor(x).toFloat() + 0.5f
            points[n++] = floor(y).toFloat() + 0.5f
        }
        val dot = spec.size.roundToInt().coerceAtLeast(1)
        field.forEachAt(t) { x, y, dx, dy ->
            if (spec.isDot) {
                for (i in 0 until dot) for (j in 0 until dot) point(x + i, y + j)
            } else {
                val steps = maxOf(abs(dx), abs(dy)).roundToInt().coerceAtLeast(1)
                for (i in 0..steps) point(x + dx * i / steps, y + dy * i / steps)
            }
        }
        particlePaint.strokeWidth = 1f
        particlePaint.strokeCap = Paint.Cap.SQUARE
        canvas.drawPoints(points, 0, n, particlePaint)
    }

    private companion object {
        /** Crops a full-frame image to its visible pixels. */
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
            if (right < 0) return Trimmed(createBitmap(1, 1), 0, 0)
            val cropped = Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
            if (cropped !== bitmap) bitmap.recycle()
            return Trimmed(cropped, left, top)
        }
    }
}
