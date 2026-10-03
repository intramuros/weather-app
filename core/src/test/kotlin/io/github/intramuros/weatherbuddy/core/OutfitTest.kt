package io.github.intramuros.weatherbuddy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutfitTest {
    private fun outfit(c: Conditions) = Outfit.dress(c, Scene.from(c))

    @Test
    fun hotSunnyDay() {
        val o = outfit(conditions(0, 29.0, 10.0).copy(uvIndex = 7.0))
        assertEquals(Top.TANK_TOP, o.top)
        assertEquals(Bottom.SHORTS, o.bottom)
        assertEquals(Footwear.SANDALS, o.footwear)
        assertNull(o.outerwear)
        assertEquals(listOf(Accessory.SUNGLASSES, Accessory.SUN_HAT), o.accessories)
        assertEquals(Expression.SWEATY, o.expression)
    }

    @Test
    fun freezingSnow() {
        val o = outfit(conditions(73, -4.0, 20.0))
        assertEquals(Outerwear.PUFFER_COAT, o.outerwear)
        assertEquals(Footwear.BOOTS, o.footwear)
        assertEquals(listOf(Accessory.SCARF, Accessory.GLOVES, Accessory.BEANIE), o.accessories)
        assertEquals(Expression.SHIVERING, o.expression)
    }

    @Test
    fun calmRainGetsAnUmbrella() {
        val o = outfit(conditions(63, 12.0, 20.0))
        assertEquals(Outerwear.LIGHT_JACKET, o.outerwear)
        assertTrue(o.has(Accessory.UMBRELLA))
        assertEquals(Expression.HAPPY, o.expression)
    }

    @Test
    fun windyRainGetsARaincoatInstead() {
        val o = outfit(conditions(63, 12.0, 50.0))
        assertEquals(Outerwear.RAINCOAT, o.outerwear)
        assertEquals(Footwear.RAIN_BOOTS, o.footwear)
        assertFalse(o.has(Accessory.UMBRELLA))
        assertEquals(Expression.SOGGY, o.expression)
    }

    @Test
    fun rainComingSoonMeansClosedUmbrella() {
        val dryFor30MinutesThenRain = DoubleArray(12) { if (it >= 6) 1.0 else 0.0 }
        val o = outfit(conditions(2, 17.0, 10.0).copy(rainNowcast = nowcast(*dryFor30MinutesThenRain)))
        assertTrue(o.has(Accessory.CLOSED_UMBRELLA))
        assertEquals(Footwear.SNEAKERS, o.footwear)
    }

    @Test
    fun nightIsSleepyAndHasNoSunglasses() {
        val o = outfit(conditions(0, 18.0, 10.0).copy(isDay = false))
        assertFalse(o.has(Accessory.SUNGLASSES))
        assertEquals(Expression.SLEEPY, o.expression)
    }
}
