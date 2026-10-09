package io.github.intramuros.weatherbuddy.core

/** How soon the weather should be fetched again: often when rain is near, rarely when nothing changes. */
object RefreshSchedule {
    const val RAIN_NOW_MINUTES = 10L
    const val RAIN_SOON_MINUTES = 15L
    const val DEFAULT_MINUTES = 30L
    const val DRY_DAY_MINUTES = 60L
    const val DRY_NIGHT_MINUTES = 90L

    /** After a failed fetch, whose old weather is no use for deciding. */
    const val RETRY_MINUTES = 15L

    /** Wet-day total (mm) above which rain later in the day keeps the default pace. */
    private const val WET_DAY_MM = 1.0

    fun delayMinutes(conditions: Conditions): Long {
        val threshold = Conditions.RAIN_THRESHOLD_MM_H
        return when {
            conditions.maxRainWithin(30) >= threshold -> RAIN_NOW_MINUTES
            conditions.maxRainWithin(120) >= threshold -> RAIN_SOON_MINUTES
            // No nowcast (outside Buienradar's area, or it failed): nothing to be adaptive about.
            conditions.rainNowcast.isEmpty() -> DEFAULT_MINUTES
            (conditions.forecast.firstOrNull()?.precipitationMm ?: 0.0) >= WET_DAY_MM -> DEFAULT_MINUTES
            conditions.isDay -> DRY_DAY_MINUTES
            else -> DRY_NIGHT_MINUTES
        }
    }
}
