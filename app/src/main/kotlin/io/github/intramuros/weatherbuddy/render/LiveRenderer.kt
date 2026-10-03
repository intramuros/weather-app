package io.github.intramuros.weatherbuddy.render

import android.content.res.AssetManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import io.github.intramuros.weatherbuddy.core.Art
import io.github.intramuros.weatherbuddy.core.ArtCanvas
import io.github.intramuros.weatherbuddy.core.ParticleField
import io.github.intramuros.weatherbuddy.core.ParticleSpec
import io.github.intramuros.weatherbuddy.core.RenderPlan
import io.github.intramuros.weatherbuddy.render.Compositor.Companion.coverBounds
import io.github.intramuros.weatherbuddy.render.Compositor.Companion.mirroredIf
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Draws a [RenderPlan] at any moment in time: the live wallpaper and the
 * in-app preview.
 *
 * The picture is loaded once, so a frame is one bitmap draw plus the
 * particles. Particles snap to the scene's pixel grid, so they look like part
 * of the art.
 *
 * Use from one thread only.
 */
class LiveRenderer(assets: AssetManager, private val plan: RenderPlan) {
    private val compositor = Compositor(assets)
    private val picture = compositor.load(Art.scene(plan.picture))
    private val fields = plan.particles.mapIndexed { i, spec -> spec to ParticleField(spec, seed = i + 1L) }
    private val particlePaint = Paint().apply {
        isAntiAlias = false
        strokeWidth = 1f
        strokeCap = Paint.Cap.SQUARE
    }
    private val dst = RectF()
    private var points = FloatArray(1024)

    /** Rounds time down to the frame rate, so pixel art moves in crisp steps. */
    fun quantize(seconds: Double): Double = floor(seconds * Art.FPS) / Art.FPS

    /** Draws the frame at [seconds], covering a [width] × [height] area at the canvas origin. */
    fun draw(canvas: Canvas, width: Int, height: Int, seconds: Double) {
        val t = quantize(seconds)
        val size = ArtCanvas.SCENE_SIZE.toInt()
        val b = coverBounds(picture?.width ?: size, picture?.height ?: size, width, height)
        canvas.withClip(0, 0, width, height) {
            picture?.let {
                dst.set(b.left, b.top, b.left + b.width, b.top + b.height)
                drawBitmap(it, null, dst, compositor.pixelPaint)
            }
            mirroredIf(plan.particlesMirrored, b) {
                val k = (b.width / ArtCanvas.SCENE_SIZE).toFloat()
                withTranslation(b.left, b.top) {
                    scale(k, k)
                    for ((spec, field) in fields) drawParticles(this, spec, field, t)
                }
            }
        }
    }

    /** Lines become runs of whole scene pixels, dots become pixel squares: all in one draw call. */
    private fun drawParticles(canvas: Canvas, spec: ParticleSpec, field: ParticleField, t: Double) {
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
        particlePaint.color = Art.particleColor(spec.kind)
        canvas.drawPoints(points, 0, n, particlePaint)
    }
}
