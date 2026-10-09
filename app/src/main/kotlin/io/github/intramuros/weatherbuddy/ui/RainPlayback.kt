package io.github.intramuros.weatherbuddy.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.intramuros.weatherbuddy.core.ForecastRadar
import io.github.intramuros.weatherbuddy.core.ForecastRadarFrame
import io.github.intramuros.weatherbuddy.core.RainHistogram
import io.github.intramuros.weatherbuddy.core.RainInterval
import io.github.intramuros.weatherbuddy.data.Location
import io.github.intramuros.weatherbuddy.data.WeatherSnapshot
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay

/** One timestamp and one play/pause state for both forecast views. */
internal class RainPlayback {
    var time by mutableStateOf<Instant?>(null)
    var playing by mutableStateOf(true)
    var frames by mutableStateOf<List<ForecastRadarFrame>>(emptyList())
    var intervals by mutableStateOf<List<RainInterval>>(emptyList())
        private set
    var times by mutableStateOf<List<Instant>>(emptyList())
        private set
    val canPlay: Boolean get() = times.size > 1

    fun updateForecast(intervals: List<RainInterval>, now: Instant) {
        this.intervals = intervals
        val currentFrames = frames.filter { it.time.plusSeconds(300) > now && it.time < now.plusSeconds(7200) }
        times = ForecastRadar.playbackTimes(intervals, currentFrames)
        val selected = time
        // Keep a manually inspected histogram bar, even when its map is missing.
        val valid = selected != null && (selected in times || ForecastRadar.intervalAt(intervals, selected) != null)
        if (!valid) time = times.firstOrNull()
    }

    fun select(time: Instant) {
        playing = false
        this.time = time
    }

    fun toggle() { playing = !playing }
}

@Composable
internal fun rememberRainPlayback(snapshot: WeatherSnapshot?, location: Location): RainPlayback {
    val state = remember(location) { RainPlayback() }
    val now = rememberForecastTime(RainHistogram.zone, ChronoUnit.MINUTES).toInstant()
    val intervals = remember(snapshot, location, now) {
        if (snapshot == null || snapshot.location != location) emptyList() else RainHistogram.intervals(
            snapshot.conditions.rainNowcast, Instant.ofEpochMilli(snapshot.fetchedAtMillis), now,
        )
    }
    LaunchedEffect(state, intervals, state.frames, now) { state.updateForecast(intervals, now) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val times = state.times
    LaunchedEffect(lifecycle, state, times, state.playing) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (state.playing && times.size > 1) {
                if (state.time !in times) state.time = ForecastRadar.nextTime(times, state.time)
                delay(if (state.time == times.last()) 2000 else 750)
                state.time = ForecastRadar.nextTime(times, state.time)
            }
        }
    }
    return state
}
