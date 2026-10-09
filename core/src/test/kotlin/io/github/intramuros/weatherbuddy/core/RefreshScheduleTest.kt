package io.github.intramuros.weatherbuddy.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RefreshScheduleTest {
    private fun base() = conditions(3, 12.0, 10.0)
    private fun day(mm: Double) = DayForecast("2026-10-09", 3, 8.0, 14.0, mm, 20.0)

    /** A 2-hour nowcast that is dry except for [mm] at the given 5-minute [step]. */
    private fun rainAt(step: Int, mm: Double = 0.5) = nowcast(*DoubleArray(24) { if (it == step) mm else 0.0 })

    @Test
    fun rainWithinHalfAnHourIsWatchedClosely() {
        val raining = base().copy(rainNowcast = nowcast(*DoubleArray(24) { 1.0 }))
        assertEquals(RefreshSchedule.RAIN_NOW_MINUTES, RefreshSchedule.delayMinutes(raining))

        val startsSoon = base().copy(rainNowcast = rainAt(6))
        assertEquals(RefreshSchedule.RAIN_NOW_MINUTES, RefreshSchedule.delayMinutes(startsSoon))
    }

    @Test
    fun rainWithinTwoHoursIsWatchedModerately() {
        val later = base().copy(rainNowcast = rainAt(18))
        assertEquals(RefreshSchedule.RAIN_SOON_MINUTES, RefreshSchedule.delayMinutes(later))
    }

    @Test
    fun drizzleBelowTheThresholdIsDry() {
        val drizzle = base().copy(rainNowcast = rainAt(2, mm = 0.05), forecast = listOf(day(0.0)))
        assertEquals(RefreshSchedule.DRY_DAY_MINUTES, RefreshSchedule.delayMinutes(drizzle))
    }

    @Test
    fun dryNowcastRelaxesByDayAndMoreByNight() {
        val dry = base().copy(rainNowcast = nowcast(*DoubleArray(24)), forecast = listOf(day(0.0)))
        assertEquals(RefreshSchedule.DRY_DAY_MINUTES, RefreshSchedule.delayMinutes(dry))
        assertEquals(RefreshSchedule.DRY_NIGHT_MINUTES, RefreshSchedule.delayMinutes(dry.copy(isDay = false)))
    }

    @Test
    fun aWetDayKeepsTheDefaultPaceEvenWhenTheNextTwoHoursAreDry() {
        val wetDay = base().copy(rainNowcast = nowcast(*DoubleArray(24)), forecast = listOf(day(4.0)))
        assertEquals(RefreshSchedule.DEFAULT_MINUTES, RefreshSchedule.delayMinutes(wetDay))
    }

    @Test
    fun withoutANowcastTheDefaultPaceApplies() {
        assertEquals(RefreshSchedule.DEFAULT_MINUTES, RefreshSchedule.delayMinutes(base()))
    }
}
