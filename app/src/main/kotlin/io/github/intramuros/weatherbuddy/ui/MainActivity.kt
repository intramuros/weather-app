package io.github.intramuros.weatherbuddy.ui

import android.Manifest
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
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.intramuros.weatherbuddy.R
import io.github.intramuros.weatherbuddy.core.Style
import io.github.intramuros.weatherbuddy.data.LocationProvider
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WeatherBuddyTheme {
                SettingsScreen()
            }
        }
    }
}

@Composable
private fun WeatherBuddyTheme(content: @Composable () -> Unit) {
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

@Composable
private fun SettingsScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requestLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.useMyLocation()
    }
    val settings = state.settings

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
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
                state.preview?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = stringResource(R.string.buddy_description),
                        contentScale = ContentScale.Crop,
                        filterQuality = if (settings?.style?.pixelated == true) FilterQuality.None else FilterQuality.Low,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (state.busy) CircularProgressIndicator()
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

            if (settings == null) return@Column

            Text(stringResource(R.string.style), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Style.entries.forEach { style ->
                    StyleCard(
                        style = style,
                        thumbnail = state.thumbnails[style],
                        selected = style == settings.style,
                        onClick = { vm.selectStyle(style) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Text(stringResource(R.string.show_on), style = MaterialTheme.typography.titleMedium)
            SwitchRow(stringResource(R.string.home_screen_wallpaper), settings.wallpaperHome, vm::setWallpaperHome)
            SwitchRow(stringResource(R.string.lock_screen), settings.wallpaperLock, vm::setWallpaperLock)
            Text(stringResource(R.string.widget_hint), style = MaterialTheme.typography.bodySmall)

            Text(stringResource(R.string.location), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = settings.location
                        ?.let { stringResource(R.string.location_coordinates, it.latitude, it.longitude) }
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
        Text(style.displayName, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onChange(!checked) },
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null)
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
        append(".")
    }
    Text(text, style = MaterialTheme.typography.bodySmall)
}
