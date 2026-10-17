package io.github.intramuros.weatherbuddy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaceHintTest {
    private val home = PlaceHint("Europe/Amsterdam", "nl")

    @Test
    fun theSamePlaceIsntElsewhere() {
        assertFalse(home.isElsewhereFrom(home))
    }

    @Test
    fun anotherTimeZoneIsElsewhere() {
        assertTrue(PlaceHint("Europe/London", "nl").isElsewhereFrom(home))
    }

    @Test
    fun anotherNetworkCountryIsElsewhereInTheSameTimeZone() {
        assertTrue(PlaceHint("Europe/Amsterdam", "be").isElsewhereFrom(home))
    }

    @Test
    fun noNetworkIsntElsewhere() {
        assertFalse(PlaceHint("Europe/Amsterdam", null).isElsewhereFrom(home))
        assertFalse(home.isElsewhereFrom(PlaceHint("Europe/Amsterdam", null)))
    }

    @Test
    fun losingTheSignalAtHomeKeepsTheCountry() {
        val noSignal = PlaceHint("Europe/Amsterdam", null).keepingCountryOf(home)
        assertEquals(home, noSignal)
        // Driving into Belgium meanwhile still counts once the signal comes back.
        assertTrue(PlaceHint("Europe/Amsterdam", "be").isElsewhereFrom(noSignal))
    }

    @Test
    fun arrivingInAnotherTimeZoneWithNoSignalDropsTheCountry() {
        val landed = PlaceHint("Europe/London", null).keepingCountryOf(home)
        assertEquals(PlaceHint("Europe/London", null), landed)
        // The signal coming back there isn't somewhere new again.
        assertFalse(PlaceHint("Europe/London", "gb").isElsewhereFrom(landed))
    }
}
