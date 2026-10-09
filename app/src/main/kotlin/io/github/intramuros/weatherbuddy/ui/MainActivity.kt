package io.github.intramuros.weatherbuddy.ui

import android.Manifest
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.intramuros.weatherbuddy.R
import io.github.intramuros.weatherbuddy.core.Condition
import io.github.intramuros.weatherbuddy.core.DayForecast
import io.github.intramuros.weatherbuddy.core.Scene
import io.github.intramuros.weatherbuddy.core.Style
import io.github.intramuros.weatherbuddy.core.TimeOfDay
import io.github.intramuros.weatherbuddy.data.Location
import io.github.intramuros.weatherbuddy.data.LocationProvider
import io.github.intramuros.weatherbuddy.labelRes
import io.github.intramuros.weatherbuddy.wallpaper.BuddyWallpaperService
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Date
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    /** Whether the user went to a screen opened from here: the gallery, the wallpaper picker or a link. */
    private var openedScreen = false

    /** Whether the app is being left, rather than turned or covered by a screen of its own. */
    private val leaving get() = !openedScreen && !isChangingConfigurations

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WeatherBuddyTheme {
                // Leaving the app opens it at the top next time, even if the phone has to restore it
                // from saved state; coming back from a screen opened here doesn't.
                val scroll = rememberSaveable(saver = Saver(save = { if (leaving) 0 else it.value }, restore = { ScrollState(it) })) {
                    ScrollState(0)
                }
                val scope = rememberCoroutineScope()
                LifecycleStartEffect(Unit) {
                    // scrollTo also stops a fling still under way, which would carry it back down.
                    onStopOrDispose { if (leaving) scope.launch { scroll.scrollTo(0) } }
                }
                SettingsScreen(scroll)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        openedScreen = false
    }

    override fun startActivity(intent: Intent, options: Bundle?) {
        openedScreen = true
        try {
            super.startActivity(intent, options)
        } catch (e: ActivityNotFoundException) {
            openedScreen = false
            throw e
        }
    }
}

@Composable
internal fun WeatherBuddyTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(scroll: ScrollState, vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requestLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.useMyLocation()
    }
    val settings = state.settings
    LifecycleResumeEffect(Unit) {
        vm.checkLiveWallpaper()
        onPauseOrDispose {}
    }

    Scaffold { padding ->
        // Pulling down refreshes; its indicator also shows the refreshes started any other way.
        PullToRefreshBox(
            isRefreshing = state.busy,
            onRefresh = vm::refresh,
            modifier = Modifier.padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .clip(RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    RadarMap(settings?.location ?: Location.DEFAULT, Modifier.fillMaxSize())
                    state.snapshot?.let {
                        HourlyStrip(
                            it.conditions.hourly,
                            it.conditions.timeZone,
                            Modifier.align(Alignment.TopCenter),
                        )
                    }
                }

                val snapshot = state.snapshot
                if (snapshot != null) {
                    Text(
                        stringResource(
                            R.string.status,
                            DateFormat.getTimeFormat(context).format(Date(snapshot.fetchedAtMillis)),
                            snapshot.conditions.temperatureC,
                            snapshot.conditions.apparentTemperatureC,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.message?.let {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                snapshot?.let { Forecast(it.conditions.forecast, it.conditions.timeZone) }

                if (settings == null) return@Column

                if (state.styles.size > 1) {
                    Text(stringResource(R.string.style), style = MaterialTheme.typography.titleMedium)
                    // More styles than fit across a phone, so the row scrolls, starting at the chosen one.
                    val styleScroll = rememberScrollState()
                    val cardStep = with(LocalDensity.current) { (STYLE_CARD_WIDTH + STYLE_CARD_GAP).roundToPx() }
                    LaunchedEffect(Unit) { styleScroll.scrollTo(state.styles.indexOf(settings.style).coerceAtLeast(0) * cardStep) }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(STYLE_CARD_GAP),
                        modifier = Modifier.horizontalScroll(styleScroll),
                    ) {
                        state.styles.forEach { style ->
                            StyleCard(
                                style = style,
                                thumbnail = state.thumbnails[style],
                                selected = style == settings.style,
                                onClick = { vm.selectStyle(style) },
                                modifier = Modifier.width(STYLE_CARD_WIDTH),
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = { context.startActivity(GalleryActivity.intent(context, settings.style)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.browse_pictures)) }

                Text(stringResource(R.string.show_on), style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.live_wallpaper))
                        Text(stringResource(R.string.live_wallpaper_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (state.liveWallpaperActive) {
                        Text(stringResource(R.string.live_wallpaper_active), color = MaterialTheme.colorScheme.primary)
                    } else {
                        Button(onClick = { setLiveWallpaper(context) }) { Text(stringResource(R.string.set_live_wallpaper)) }
                    }
                }
                SwitchRow(
                    label = stringResource(R.string.home_screen_wallpaper),
                    checked = settings.wallpaperHome && !state.liveWallpaperActive,
                    onChange = vm::setWallpaperHome,
                    enabled = !state.liveWallpaperActive,
                    supporting = if (state.liveWallpaperActive) stringResource(R.string.home_screen_live_active) else null,
                )
                SwitchRow(stringResource(R.string.lock_screen), settings.wallpaperLock, vm::setWallpaperLock)
                Text(stringResource(R.string.widget_hint), style = MaterialTheme.typography.bodySmall)

                Text(stringResource(R.string.location), style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = settings.location
                            ?.let { settings.placeName ?: stringResource(R.string.location_coordinates, it.latitude, it.longitude) }
                            ?: stringResource(R.string.location_default),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = {
                            if (LocationProvider.hasPermission(context)) {
                                vm.useMyLocation()
                            } else {
                                requestLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                            }
                        },
                        enabled = !state.busy,
                    ) { Text(stringResource(R.string.use_my_location)) }
                }

                Button(onClick = { vm.refresh() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.refresh))
                }

                Credits()
            }
        }
    }
}

/**
 * Today and the coming days, one row each; days already past are left out.
 * "Today" is today at the forecast's location, whose dates the days are.
 */
@Composable
private fun Forecast(days: List<DayForecast>, timeZone: String?) {
    val today = rememberForecastTime(remember(timeZone) { zoneOrDefault(timeZone) }, ChronoUnit.DAYS).toLocalDate()
    val coming = days.mapNotNull { day ->
        val date = try {
            LocalDate.parse(day.date)
        } catch (_: DateTimeParseException) {
            return@mapNotNull null
        }
        (day to date).takeIf { !date.isBefore(today) }
    }
    if (coming.isEmpty()) return
    Column {
        Text(stringResource(R.string.coming_days), style = MaterialTheme.typography.titleMedium)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                coming.forEach { (day, date) -> DayRow(day, dayName(date, today)) }
            }
        }
    }
}

@Composable
private fun DayRow(day: DayForecast, name: String) {
    val condition = stringResource(Condition.of(Scene.from(day)).labelRes(TimeOfDay.DAY))
    val max = day.maxC.roundToInt()
    val min = day.minC.roundToInt()
    val wet = day.precipitationMm >= RAIN_THRESHOLD_MM
    val description = listOfNotNull(
        stringResource(R.string.day_description, name, condition, min, max),
        if (wet) stringResource(R.string.day_rain_description, day.precipitationMm) else null,
    ).joinToString(", ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            condition,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (wet) stringResource(R.string.precipitation_mm, day.precipitationMm) else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(64.dp),
        )
        Text(
            stringResource(R.string.degrees, max),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp),
        )
        Text(
            stringResource(R.string.degrees, min),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp),
        )
    }
}

@Composable
private fun dayName(date: LocalDate, today: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (date) {
        today -> stringResource(R.string.today)
        today.plusDays(1) -> stringResource(R.string.tomorrow)
        // Some languages, Dutch among them, write day names in lower case.
        else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }
    }
}

/** Wide enough for the longest style name ("Watercolour") on one line. */
private val STYLE_CARD_WIDTH = 88.dp
private val STYLE_CARD_GAP = 12.dp

/** Less than this over a day (mm) isn't worth showing. */
private const val RAIN_THRESHOLD_MM = 0.1

@Composable
private fun StyleCard(
    style: Style,
    thumbnail: android.graphics.Bitmap?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 14f),
        ) {
            thumbnail?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = if (style.pixelated) FilterQuality.None else FilterQuality.Low,
                )
            }
        }
        // A two-word name may wrap under its card.
        Text(
            style.displayName,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    supporting: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) },
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private fun setLiveWallpaper(context: Context) {
    try {
        context.startActivity(BuddyWallpaperService.pickerIntent(context))
    } catch (_: ActivityNotFoundException) {
        // Some launchers only offer the general live wallpaper list.
        context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
    }
}

/** Buienradar's terms require a credit with a link. */
@Composable
private fun Credits() {
    val text = buildAnnotatedString {
        append("Weather: ")
        withLink(LinkAnnotation.Url("https://open-meteo.com")) { append("Open-Meteo") }
        append(" (KNMI model). Rain radar: ")
        withLink(LinkAnnotation.Url("https://www.buienradar.nl")) { append("Buienradar") }
        append(". Fonts: ")
        withLink(LinkAnnotation.Url("https://github.com/scfried/soft-type-jersey")) { append("Jersey 10") }
        append(" and ")
        withLink(LinkAnnotation.Url("https://github.com/fontworks-fonts/DotGothic16")) { append("DotGothic16") }
        append(" (SIL Open Font License).")
    }
    Text(text, style = MaterialTheme.typography.bodySmall)
}
