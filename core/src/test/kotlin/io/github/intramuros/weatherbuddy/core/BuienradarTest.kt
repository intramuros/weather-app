package io.github.intramuros.weatherbuddy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BuienradarTest {
    @Test
    fun convertsValues() {
        assertEquals(0.0, Buienradar.valueToMmPerHour(0))
        assertEquals(1.0, Buienradar.valueToMmPerHour(109), 1e-9)
        assertEquals(10.0, Buienradar.valueToMmPerHour(141), 1e-9)
        assertTrue(Buienradar.valueToMmPerHour(77) < 0.11)
    }

    @Test
    fun parsesLinesWithCrlf() {
        val steps = Buienradar.parseRaintext("000|14:15\r\n109|14:20\r\n141|14:25\r\n")
        assertEquals(3, steps.size)
        assertEquals(RainStep(14, 15, 0.0), steps[0])
        assertEquals(10.0, steps[2].mmPerHour, 1e-9)
    }

    @Test
    fun rejectsGarbage() {
        val e = assertFailsWith<WeatherParseException> { Buienradar.parseRaintext("000|14:15\nnope\n") }
        assertTrue("line 2" in e.message!!)
        assertFailsWith<WeatherParseException> { Buienradar.parseRaintext("300|14:15") }
        assertFailsWith<WeatherParseException> { Buienradar.parseRaintext("010|25:00") }
    }

    @Test
    fun urlUsesDotDecimals() {
        assertEquals(
            "https://gpsgadget.buienradar.nl/data/raintext?lat=52.09&lon=5.12",
            Buienradar.raintextUrl(52.0907, 5.1214),
        )
    }
}
