package io.github.intramuros.weatherbuddy.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.time.DateTimeException
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The start of the current hour or day in [zone], according to [unit]. Rechecked
 * whenever the app comes back to the front, and at each hour or midnight while
 * it stays open, so forecast labels don't become stale.
 */
@Composable
internal fun rememberForecastTime(zone: ZoneId, unit: ChronoUnit): ZonedDateTime {
    var start by remember(zone, unit) { mutableStateOf(ZonedDateTime.now(zone).truncatedTo(unit)) }
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(zone, unit) {
        val job = scope.launch {
            while (true) {
                val now = ZonedDateTime.now(zone)
                start = now.truncatedTo(unit)
                delay(Duration.between(now, start.plus(1, unit)).toMillis() + 1)
            }
        }
        onPauseOrDispose { job.cancel() }
    }
    return start
}

internal fun zoneOrDefault(id: String?): ZoneId = try {
    id?.let(ZoneId::of) ?: ZoneId.systemDefault()
} catch (_: DateTimeException) {
    ZoneId.systemDefault()
}
