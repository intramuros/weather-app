package io.github.intramuros.weatherbuddy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParticlesTest {
    private fun scene(c: Conditions) = Scene.from(c)

    private fun positions(field: ParticleField, t: Double) = buildList {
        field.forEachAt(t) { x, y, dx, dy -> add(listOf(x, y, dx, dy)) }
    }

    @Test
    fun heavierRainMeansMoreAndLongerDrops() {
        val drizzle = conditions(51, 12.0, 10.0).let { ParticleSpec.precipitation(scene(it), it)!! }
        val heavy = conditions(65, 12.0, 10.0).let { ParticleSpec.precipitation(scene(it), it)!! }
        assertTrue(heavy.count > drizzle.count)
        assertTrue(heavy.size > drizzle.size)
        assertTrue(heavy.fallSpeed > drizzle.fallSpeed)
    }

    @Test
    fun radarIntensityScalesRain() {
        val light = conditions(63, 12.0, 10.0).copy(rainNowcast = nowcast(0.6))
        val strong = conditions(63, 12.0, 10.0).copy(rainNowcast = nowcast(3.5))
        assertTrue(
            ParticleSpec.precipitation(scene(strong), strong)!!.count >
                ParticleSpec.precipitation(scene(light), light)!!.count,
        )
    }

    @Test
    fun dryAndCalmMeansNoParticles() {
        val c = conditions(0, 20.0, 10.0)
        assertNull(ParticleSpec.precipitation(scene(c), c))
        assertEquals(emptyList(), ParticleSpec.wind(scene(c), c))
    }

    @Test
    fun stormAddsLeaves() {
        val c = conditions(3, 12.0, 80.0)
        assertEquals(listOf(ParticleKind.STREAK, ParticleKind.LEAF), ParticleSpec.wind(scene(c), c).map { it.kind })
    }

    @Test
    fun fieldIsDeterministicAndStaysOnCanvas() {
        val c = conditions(65, 12.0, 50.0)
        val spec = ParticleSpec.precipitation(scene(c), c)!!
        val a = ParticleField(spec, seed = 7)
        val b = ParticleField(spec, seed = 7)
        for (t in listOf(0.0, 0.5, 13.7, 3600.0, 86_400.0)) {
            val pa = positions(a, t)
            assertEquals(pa, positions(b, t))
            assertEquals(spec.count, pa.size)
            for ((x, y) in pa) {
                assertTrue(x >= -40 && x < ArtCanvas.SCENE_SIZE + 40, "x=$x at $t")
                assertTrue(y >= -spec.size && y < ArtCanvas.GROUND, "y=$y at $t")
            }
        }
    }

    @Test
    fun windSlantsDropsToTheRight() {
        val c = conditions(63, 12.0, 50.0)
        val field = ParticleField(ParticleSpec.precipitation(scene(c), c)!!)
        for ((_, _, dx, dy) in positions(field, 1.0)) {
            assertTrue(dx > 0 && dy > 0)
        }
    }

    @Test
    fun dropsActuallyFall() {
        val c = conditions(63, 12.0, 10.0)
        val field = ParticleField(ParticleSpec.precipitation(scene(c), c)!!)
        val before = positions(field, 0.0)
        val after = positions(field, 0.01)
        // Each drop moved down a little, or hit the ground and started again at the top.
        for ((p, q) in before.zip(after)) {
            val fell = q[1] - p[1] in 0.0..5.0
            val restarted = p[1] > ArtCanvas.GROUND - 5 && q[1] < 0
            assertTrue(fell || restarted, "${p[1]} -> ${q[1]}")
        }
        assertTrue(before.zip(after).count { (p, q) -> q[1] > p[1] } > before.size * 0.9)
    }
}
