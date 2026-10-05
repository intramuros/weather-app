package io.github.intramuros.weatherbuddy.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.intramuros.weatherbuddy.R
import io.github.intramuros.weatherbuddy.core.Condition
import io.github.intramuros.weatherbuddy.core.HourForecast
import io.github.intramuros.weatherbuddy.core.Scene
import io.github.intramuros.weatherbuddy.labelRes
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** A shade behind the forecast keeps it readable over every art style. */
@Composable
internal fun HourlyStrip(hours: List<HourForecast>, timeZone: String?, modifier: Modifier = Modifier) {
    val start = rememberForecastTime(remember(timeZone) { zoneOrDefault(timeZone) }, ChronoUnit.HOURS).toLocalDateTime()
    val coming = remember(hours, start) { upcomingHours(hours, start) }
    if (coming.size < 2) return
    val locale = LocalConfiguration.current.locales[0]
    val use24Hours = DateFormat.is24HourFormat(LocalContext.current)
    val formatter = remember(locale, use24Hours) {
        DateTimeFormatter.ofPattern(if (use24Hours) "HH:mm" else "h a", locale)
    }
    val shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 1f), 2f)
    ProvideTextStyle(TextStyle(color = Color.White, shadow = shadow, textAlign = TextAlign.Center)) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 34.dp),
        ) {
            coming.forEachIndexed { index, (hour, time) ->
                val name = if (index == 0) stringResource(R.string.now) else time.format(formatter)
                HourColumn(hour, name, Modifier.weight(1f))
            }
            // Keep short forecasts in the same six-column layout.
            repeat(6 - coming.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun HourColumn(hour: HourForecast, name: String, modifier: Modifier) {
    val scene = Scene.from(hour)
    val condition = stringResource(Condition.of(scene).labelRes(scene.timeOfDay))
    val temperature = hour.temperatureC.roundToInt()
    val probability = hour.precipitationProbabilityPercent
    val rain = when {
        probability != null && probability >= 20 -> stringResource(R.string.percent, probability.roundToInt())
        probability == null && hour.precipitationMm >= 0.1 -> stringResource(R.string.precipitation_mm, hour.precipitationMm)
        else -> ""
    }
    val description = listOfNotNull(
        stringResource(R.string.hour_description, name, condition, temperature),
        when {
            probability != null -> stringResource(R.string.hour_rain_description, probability.roundToInt())
            hour.precipitationMm >= 0.1 -> stringResource(R.string.day_rain_description, hour.precipitationMm)
            else -> null
        },
    ).joinToString(", ")
    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(name, style = LocalTextStyle.current.merge(MaterialTheme.typography.labelMedium), maxLines = 1)
        Text(
            stringResource(R.string.degrees, temperature),
            style = LocalTextStyle.current.merge(MaterialTheme.typography.titleMedium),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            rain,
            style = LocalTextStyle.current.merge(MaterialTheme.typography.labelMedium),
            color = RAIN_COLOUR,
            minLines = 1,
            maxLines = 1,
        )
    }
}

/** Keep the current local hour, but leave out expired or malformed entries. */
internal fun upcomingHours(hours: List<HourForecast>, start: LocalDateTime): List<Pair<HourForecast, LocalDateTime>> =
    hours.mapNotNull { hour ->
        val time = try {
            LocalDateTime.parse(hour.time)
        } catch (_: DateTimeParseException) {
            return@mapNotNull null
        }
        (hour to time).takeIf { !time.isBefore(start) }
    }.take(6)

/** Light blue keeps rain amounts and chances distinct on the dark scrim. */
private val RAIN_COLOUR = Color(0xFFADD8FF)
