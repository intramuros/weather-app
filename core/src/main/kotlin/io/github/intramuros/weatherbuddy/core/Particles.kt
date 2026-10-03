package io.github.intramuros.weatherbuddy.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

enum class ParticleKind {
    /** A falling line segment. */
    DROP,

    /** A dot drifting down and swaying. */
    FLAKE,

    /** A dot falling fast. */
    HAILSTONE,

    /** A horizontal line blowing across: visible wind. */
    STREAK,

    /** A dot tumbling sideways. */
    LEAF,
}

/**
 * How one kind of particle moves, in [ArtCanvas] units. Motion is always
 * towards the right of the art; [RenderPlan.mirrored] flips the whole picture
 * when the real wind blows the other way.
 */
data class ParticleSpec(
    val kind: ParticleKind,
    val count: Int,
    /** Downward speed, units per second. */
    val fallSpeed: Double,
    /** Sideways speed from the wind, units per second. */
    val drift: Double,
    /** 0..0.6: how strongly the wind comes and goes in gusts. */
    val gustiness: Double,
    /** Side-to-side sway amplitude, units. */
    val sway: Double,
    /** Length of drops and streaks; diameter of dots. */
    val size: Double,
) {
    val isDot: Boolean get() = kind == ParticleKind.FLAKE || kind == ParticleKind.HAILSTONE || kind == ParticleKind.LEAF

    companion object {
        /** Falling weather, or `null` when it's dry. */
        fun precipitation(scene: Scene, c: Conditions): ParticleSpec? {
            val wind = c.windSpeedKmh
            val gusts = gustiness(c)
            return when (scene.precipitation) {
                Precipitation.NONE -> null
                Precipitation.DRIZZLE -> ParticleSpec(ParticleKind.DROP, 80, 150.0, wind * 1.5, gusts, 0.0, 3.0)
                Precipitation.RAIN -> {
                    val mmPerHour = c.rainNowMmH ?: 1.5
                    val count = (110 + 45 * mmPerHour).toInt().coerceIn(110, 310)
                    ParticleSpec(ParticleKind.DROP, count, 260.0, wind * 2, gusts, 0.0, 8.0)
                }
                Precipitation.HEAVY_RAIN -> ParticleSpec(ParticleKind.DROP, 400, 340.0, wind * 2, gusts, 0.0, 13.0)
                Precipitation.SNOW -> ParticleSpec(ParticleKind.FLAKE, 155, 22.0, wind * 0.8, gusts, 5.0, 2.5)
                Precipitation.HAIL -> ParticleSpec(ParticleKind.HAILSTONE, 100, 280.0, wind * 1.5, gusts, 0.0, 3.0)
            }
        }

        /** Visible wind: streaks, plus leaves in a storm. Empty when calm. */
        fun wind(scene: Scene, c: Conditions): List<ParticleSpec> {
            val wind = maxOf(c.windSpeedKmh, 15.0)
            val gusts = gustiness(c)
            return when (scene.wind) {
                Wind.CALM -> emptyList()
                Wind.BREEZY -> listOf(ParticleSpec(ParticleKind.STREAK, 11, 0.0, wind * 3, gusts, 3.0, 26.0))
                Wind.STORMY -> listOf(
                    ParticleSpec(ParticleKind.STREAK, 22, 0.0, wind * 3.5, gusts, 4.0, 36.0),
                    ParticleSpec(ParticleKind.LEAF, 18, 30.0, wind * 2.5, gusts, 8.0, 3.0),
                )
            }
        }

        private fun gustiness(c: Conditions): Double =
            ((c.windGustsKmh - c.windSpeedKmh) / maxOf(c.windSpeedKmh, 1.0)).coerceIn(0.0, 0.6)
    }
}

/**
 * A deterministic, stateless particle simulation: positions are a pure
 * function of time, so any frame can be drawn without stepping through the
 * ones before it, and nothing drifts or leaks over hours of running.
 */
class ParticleField(private val spec: ParticleSpec, seed: Long = 1) {
    private val falls = spec.fallSpeed > 0
    private val ySpan = ArtCanvas.GROUND + spec.size
    private val x0 = DoubleArray(spec.count)
    private val y0 = DoubleArray(spec.count)
    private val phase = DoubleArray(spec.count)
    private val speed = DoubleArray(spec.count)
    private val scratch = DoubleArray(4)

    init {
        val random = Random(seed)
        for (i in 0 until spec.count) {
            x0[i] = random.nextDouble() * X_SPAN
            // Falling particles start anywhere above the ground; streaks and leaves stay in the air.
            y0[i] = if (falls) random.nextDouble() * ySpan else 20 + random.nextDouble() * (ArtCanvas.GROUND - 40)
            phase[i] = random.nextDouble() * 2 * PI
            speed[i] = 0.8 + random.nextDouble() * 0.4
        }
    }

    val count: Int get() = spec.count

    /**
     * Calls [draw] for every particle at time [seconds]. Lines go from (x, y) to
     * (x + dx, y + dy); dots have dx = dy = 0 and a diameter of [ParticleSpec.size].
     * Not thread-safe: use one field per drawing thread.
     */
    fun forEachAt(seconds: Double, draw: (x: Double, y: Double, dx: Double, dy: Double) -> Unit) {
        for (i in 0 until spec.count) {
            compute(i, seconds, scratch)
            draw(scratch[0], scratch[1], scratch[2], scratch[3])
        }
    }

    private fun compute(i: Int, t: Double, out: DoubleArray) {
        val s = speed[i]
        // Wind that comes and goes: the offset is the integral of drift × (1 + gust(t)).
        val g = spec.gustiness
        val windOffset = spec.drift * (t + g * (sin(0.6 * t) / 0.6 + 0.5 * sin(1.7 * t + 1) / 1.7))
        val windNow = spec.drift * (1 + g * (cos(0.6 * t) + 0.5 * cos(1.7 * t + 1)))
        val swayAngle = phase[i] + 1.3 * t * s
        out[0] = (x0[i] + s * windOffset + spec.sway * sin(swayAngle)).mod(X_SPAN) - MARGIN
        out[1] = if (falls) (y0[i] + s * spec.fallSpeed * t).mod(ySpan) - spec.size else y0[i] + spec.sway * sin(swayAngle)
        when (spec.kind) {
            ParticleKind.DROP -> {
                val vx = s * windNow
                val vy = s * spec.fallSpeed
                val k = spec.size / hypot(vx, vy).coerceAtLeast(1e-6)
                out[2] = vx * k
                out[3] = vy * k
            }
            ParticleKind.STREAK -> { out[2] = spec.size; out[3] = 0.0 }
            else -> { out[2] = 0.0; out[3] = 0.0 }
        }
    }

    private companion object {
        /** Particles wrap around off-screen so they don't pop in at the edges. */
        const val MARGIN = 40.0
        const val X_SPAN = ArtCanvas.SCENE_SIZE + 2 * MARGIN
    }
}
