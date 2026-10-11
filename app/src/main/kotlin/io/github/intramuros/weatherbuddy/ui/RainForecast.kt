package io.github.intramuros.weatherbuddy.ui

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import io.github.intramuros.weatherbuddy.R
import io.github.intramuros.weatherbuddy.core.Conditions
import io.github.intramuros.weatherbuddy.core.ForecastRadar
import io.github.intramuros.weatherbuddy.core.RainHistogram
import io.github.intramuros.weatherbuddy.core.RainInterval
import io.github.intramuros.weatherbuddy.data.WeatherSnapshot
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** The next two hours, with real five-minute intensities rather than rain probabilities. */
@Composable
internal fun RainForecast(snapshot: WeatherSnapshot?, playback: RainPlayback, modifier: Modifier = Modifier) {
    val clock = rememberForecastTime(RainHistogram.zone, ChronoUnit.MINUTES).toInstant()
    val windowStart = clock.minusSeconds((clock.atZone(RainHistogram.zone).minute % 5) * 60L)
    val intervals = playback.intervals
    val locale = LocalConfiguration.current.locales[0]
    val use24Hours = DateFormat.is24HourFormat(LocalContext.current)
    val zone = remember(snapshot?.conditions?.timeZone) { zoneOrDefault(snapshot?.conditions?.timeZone) }
    val formatter = remember(locale, use24Hours, zone) {
        DateTimeFormatter.ofPattern(if (use24Hours) "HH:mm" else "h:mm a", locale).withZone(zone)
    }
    val selected = playback.time?.let { ForecastRadar.intervalAt(intervals, it) } ?: -1
    val point = intervals.getOrNull(selected)
    val selectedLabel = point?.let {
        stringResource(R.string.rain_interval, formatter.format(it.start), formatter.format(it.end), it.mmPerHour)
    }
    val blue = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF82C4FF) else Color(0xFF1565C0)
    val source = stringResource(R.string.rain_source)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.rain_forecast), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = playback::toggle, enabled = playback.canPlay) {
                Text(stringResource(if (playback.playing) R.string.rain_pause else R.string.rain_play))
            }
        }
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (intervals.isEmpty()) {
                    Text(stringResource(R.string.rain_unavailable), style = MaterialTheme.typography.bodyMedium)
                } else {
                    val firstWet = intervals.firstOrNull { it.mmPerHour >= Conditions.RAIN_THRESHOLD_MM_H }
                    val summary = when {
                        firstWet == null -> stringResource(R.string.rain_dry)
                        firstWet.start <= clock -> stringResource(R.string.rain_now)
                        else -> stringResource(R.string.rain_starts, formatter.format(firstWet.start))
                    }
                    Text(summary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(selectedLabel.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = blue)
                    RainPlot(intervals, windowStart, selected, formatter, blue) { playback.select(intervals[it].start) }
                    if (intervals.size > 1) {
                        val description = stringResource(R.string.rain_inspect)
                        Slider(
                            value = selected.coerceAtLeast(0).toFloat(),
                            onValueChange = { playback.select(intervals[it.roundToInt().coerceIn(intervals.indices)].start) },
                            valueRange = 0f..intervals.lastIndex.toFloat(),
                            steps = (intervals.size - 2).coerceAtLeast(0),
                            modifier = Modifier.fillMaxWidth().semantics {
                                contentDescription = description
                                stateDescription = selectedLabel.orEmpty()
                            },
                        )
                    }
                    Text(
                        stringResource(R.string.rain_available_until, formatter.format(intervals.last().end)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    buildAnnotatedString {
                        withLink(LinkAnnotation.Url("https://www.buienradar.nl")) { append(source) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun RainPlot(
    intervals: List<RainInterval>,
    windowStart: Instant,
    selected: Int,
    formatter: DateTimeFormatter,
    blue: Color,
    onSelect: (Int) -> Unit,
) {
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    Column {
        Row(Modifier.fillMaxWidth()) {
            // Labels name the bands between grid lines, sharing the canvas inset.
            Column(Modifier.width(46.dp).height(160.dp).padding(vertical = 8.dp)) {
                listOf(R.string.rain_high, R.string.rain_medium, R.string.rain_low).forEach { label ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Canvas(
                Modifier.weight(1f).height(160.dp).clearAndSetSemantics {}.pointerInput(intervals, windowStart) {
                    detectTapGestures { position ->
                        val seconds = position.x.coerceIn(0f, size.width.toFloat()) / size.width * 7200
                        onSelect(intervals.indices.minBy { index ->
                            abs(Duration.between(windowStart, intervals[index].start).seconds + 150 - seconds)
                        })
                    }
                },
            ) {
                val inset = 8.dp.toPx()
                val baseline = size.height - inset
                val plotHeight = size.height - 2 * inset
                repeat(4) { tick ->
                    val y = baseline - plotHeight * tick / 3
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                fun x(time: Instant) = (Duration.between(windowStart, time).seconds / 7200f * size.width).coerceIn(0f, size.width)
                fun y(interval: RainInterval) = baseline - (RainHistogram.height(interval.mmPerHour) * plotHeight).toFloat()
                // Missing intervals break the curve, so gaps stay distinct from dry data.
                contiguousRuns(intervals).forEach { run ->
                    val points = listOf(Offset(x(run.first().start), y(run.first()))) +
                        run.map { Offset(x(it.start.plusSeconds(150)), y(it)) } +
                        Offset(x(run.last().end), y(run.last()))
                    val curve = smoothPath(points)
                    val area = Path().apply {
                        addPath(curve)
                        lineTo(points.last().x, baseline)
                        lineTo(points.first().x, baseline)
                        close()
                    }
                    drawPath(area, blue.copy(alpha = 0.35f))
                    drawPath(curve, blue, style = Stroke(2.dp.toPx()))
                }
                intervals.getOrNull(selected)?.let { interval ->
                    val middle = x(interval.start.plusSeconds(150))
                    drawLine(blue, Offset(middle, inset), Offset(middle, baseline), 1.dp.toPx())
                    drawCircle(blue, 4.dp.toPx(), Offset(middle, y(interval)))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 46.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf(0L, 60L, 120L).forEach { minutes ->
                Text(formatter.format(windowStart.plusSeconds(minutes * 60)), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            }
        }
    }
}

private fun contiguousRuns(intervals: List<RainInterval>): List<List<RainInterval>> =
    intervals.fold(mutableListOf<MutableList<RainInterval>>()) { runs, interval ->
        if (runs.lastOrNull()?.last()?.end == interval.start) runs.last() += interval else runs += mutableListOf(interval)
        runs
    }

/** A monotone cubic through the points, so the curve never dips below dry or overshoots a peak. */
private fun smoothPath(points: List<Offset>): Path {
    val slopes = points.zipWithNext { a, b -> if (b.x > a.x) (b.y - a.y) / (b.x - a.x) else 0f }
    val tangents = points.indices.map { i ->
        when (i) {
            0 -> slopes.firstOrNull() ?: 0f
            points.lastIndex -> slopes.last()
            else -> if (slopes[i - 1] * slopes[i] <= 0f) 0f else {
                // Fritsch–Carlson harmonic mean keeps each segment monotone.
                2f / (1f / slopes[i - 1] + 1f / slopes[i])
            }
        }
    }
    return Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1..points.lastIndex) {
            val a = points[i - 1]
            val b = points[i]
            val third = (b.x - a.x) / 3
            cubicTo(a.x + third, a.y + tangents[i - 1] * third, b.x - third, b.y - tangents[i] * third, b.x, b.y)
        }
    }
}
