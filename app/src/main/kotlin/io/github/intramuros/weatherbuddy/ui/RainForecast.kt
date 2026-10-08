package io.github.intramuros.weatherbuddy.ui

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import io.github.intramuros.weatherbuddy.core.RainHistogram
import io.github.intramuros.weatherbuddy.core.RainInterval
import io.github.intramuros.weatherbuddy.data.Location
import io.github.intramuros.weatherbuddy.data.WeatherSnapshot
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** The next two hours, with real five-minute intensities rather than rain probabilities. */
@Composable
internal fun RainForecast(snapshot: WeatherSnapshot?, location: Location, modifier: Modifier = Modifier) {
    val clock = rememberForecastTime(RainHistogram.zone, ChronoUnit.MINUTES).toInstant()
    val windowStart = clock.minusSeconds((clock.atZone(RainHistogram.zone).minute % 5) * 60L)
    val intervals = remember(snapshot, location, clock) {
        // A failed location refresh can leave weather saved for the previous place.
        if (snapshot == null || snapshot.location != location) emptyList() else RainHistogram.intervals(
            snapshot.conditions.rainNowcast,
            Instant.ofEpochMilli(snapshot.fetchedAtMillis),
            clock,
        )
    }
    val locale = LocalConfiguration.current.locales[0]
    val use24Hours = DateFormat.is24HourFormat(LocalContext.current)
    val zone = remember(snapshot?.conditions?.timeZone) { zoneOrDefault(snapshot?.conditions?.timeZone) }
    val formatter = remember(locale, use24Hours, zone) {
        DateTimeFormatter.ofPattern(if (use24Hours) "HH:mm" else "h:mm a", locale).withZone(zone)
    }
    val numbers = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 } }
    var selected by remember(intervals) { mutableIntStateOf(0) }
    val point = intervals.getOrNull(selected)
    val selectedLabel = point?.let {
        stringResource(R.string.rain_interval, formatter.format(it.start), formatter.format(it.end), it.mmPerHour)
    }
    val blue = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF82C4FF) else Color(0xFF1565C0)
    val source = stringResource(R.string.rain_source)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.rain_forecast), style = MaterialTheme.typography.titleMedium)
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
                    RainPlot(intervals, windowStart, selected, numbers, formatter, blue) { selected = it }
                    if (intervals.size > 1) {
                        val description = stringResource(R.string.rain_inspect)
                        Slider(
                            value = selected.toFloat(),
                            onValueChange = { selected = it.roundToInt().coerceIn(intervals.indices) },
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
    numbers: NumberFormat,
    formatter: DateTimeFormatter,
    blue: Color,
    onSelect: (Int) -> Unit,
) {
    val maximum = remember(intervals) { RainHistogram.axisMaximum(intervals) }
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    Column {
        Text(stringResource(R.string.rain_unit), style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Column(
                Modifier.width(46.dp).height(160.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                (4 downTo 0).forEach { tick ->
                    Text(numbers.format(maximum * tick / 4), style = MaterialTheme.typography.labelMedium)
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
                // Grid and labels share an inset so the top and bottom labels remain inside the plot.
                val inset = 8.dp.toPx()
                val baseline = size.height - inset
                val plotHeight = size.height - 2 * inset
                repeat(5) { tick ->
                    val y = baseline - plotHeight * tick / 4
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                intervals.forEachIndexed { index, interval ->
                    val left = (Duration.between(windowStart, interval.start).seconds / 7200f * size.width).coerceAtLeast(0f)
                    val right = (Duration.between(windowStart, interval.end).seconds / 7200f * size.width).coerceAtMost(size.width)
                    if (right <= left) return@forEachIndexed
                    val height = (interval.mmPerHour / maximum * plotHeight).toFloat()
                    val color = if (index == selected) blue else blue.copy(alpha = 0.65f)
                    // A short baseline mark identifies dry data; missing intervals stay blank.
                    drawRect(
                        color,
                        Offset(left + 1.dp.toPx(), baseline - height.coerceAtLeast(2.dp.toPx())),
                        Size((right - left - 2.dp.toPx()).coerceAtLeast(1f), height.coerceAtLeast(2.dp.toPx())),
                    )
                    if (index == selected) {
                        val middle = (left + right) / 2
                        drawLine(blue, Offset(middle, inset), Offset(middle, baseline), 1.dp.toPx())
                        drawCircle(blue, 3.dp.toPx(), Offset(middle, baseline - height))
                    }
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

