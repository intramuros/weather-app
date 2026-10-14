package io.github.intramuros.weatherbuddy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LookupsTest {
    private val gap = 1_000L

    @Test
    fun nothingIsRecentBeforeTheFirstLookup() {
        val lookups = Lookups()
        assertFalse(lookups.recent(0, gap, onlyFound = false))
        assertFalse(lookups.recent(0, gap, onlyFound = true))
    }

    @Test
    fun aLookupThatFoundThePhoneHoldsBackEveryOne() {
        val lookups = Lookups()
        lookups.ended(100, found = true)
        assertTrue(lookups.recent(100 + gap - 1, gap, onlyFound = false))
        assertTrue(lookups.recent(100 + gap - 1, gap, onlyFound = true))
    }

    @Test
    fun aLookupThatFoundNothingDoesntHoldBackTheApp() {
        val lookups = Lookups()
        lookups.ended(100, found = true)
        // The widget's, from the background, a while later.
        lookups.ended(100 + gap, found = false)
        assertTrue(lookups.recent(100 + gap + 1, gap, onlyFound = false))
        assertFalse(lookups.recent(100 + gap + 1, gap, onlyFound = true))
    }

    @Test
    fun aLookupIsNoLongerRecentAfterTheGap() {
        val lookups = Lookups()
        lookups.ended(100, found = true)
        assertFalse(lookups.recent(100 + gap, gap, onlyFound = false))
        assertFalse(lookups.recent(100 + gap, gap, onlyFound = true))
    }
}
