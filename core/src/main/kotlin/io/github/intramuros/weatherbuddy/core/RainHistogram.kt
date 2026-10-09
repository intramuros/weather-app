package io.github.intramuros.weatherbuddy.core

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** A five-minute rain interval, anchored to the snapshot rather than today's date. */
data class RainInterval(val start: Instant, val mmPerHour: Double) {
    val end: Instant get() = start.plusSeconds(300)
}

object RainHistogram {
    /** Buienradar raintext clocks are Dutch local time, regardless of the phone's zone. */
    val zone: ZoneId = ZoneId.of("Europe/Amsterdam")

    fun intervals(steps: List<RainStep>, fetchedAt: Instant, now: Instant): List<RainInterval> {
        var expected = fetchedAt
        var previous: Instant? = null
        return steps.take(25).mapNotNull { step ->
            if (step.hour !in 0..23 || step.minute !in 0..59 || !step.mmPerHour.isFinite() || step.mmPerHour < 0) {
                return@mapNotNull null
            }
            val date = expected.atZone(zone).toLocalDate()
            val clock = LocalTime.of(step.hour, step.minute)
            // Consider both offsets in the repeated autumn hour, and both sides of midnight.
            val start = (-1L..1L).flatMap { day ->
                val local = date.plusDays(day).atTime(clock)
                zone.rules.getValidOffsets(local).map { local.toInstant(it) }
            }.filter { candidate ->
                candidate >= fetchedAt.minusSeconds(300) && candidate <= fetchedAt.plusSeconds(7200) &&
                    (previous?.let { candidate > it } ?: true)
            }
                .minByOrNull { abs(Duration.between(expected, it).seconds) } ?: return@mapNotNull null
            previous = start
            expected = start.plusSeconds(300)
            RainInterval(start, step.mmPerHour).takeIf {
                // Old cached clocks must never roll forward and masquerade as fresh rain.
                it.end > now && it.start < now.plusSeconds(7200) &&
                    it.start >= fetchedAt.minusSeconds(300) && it.start <= fetchedAt.plusSeconds(7200)
            }
        }
    }

    /** Linear scale with four readable grid divisions; dry forecasts retain a 1 mm/h axis. */
    fun axisMaximum(intervals: List<RainInterval>): Double {
        val peak = (intervals.maxOfOrNull { it.mmPerHour } ?: 0.0).coerceAtLeast(1.0)
        val magnitude = 10.0.pow(floor(log10(peak)))
        val step = magnitude / 2.0
        return ceil(peak / step) * step
    }
}
