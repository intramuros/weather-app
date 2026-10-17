package io.github.intramuros.weatherbuddy

import kotlin.test.Test
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
}
