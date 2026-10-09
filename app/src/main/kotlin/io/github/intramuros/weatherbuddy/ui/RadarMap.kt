package io.github.intramuros.weatherbuddy.ui

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.intramuros.weatherbuddy.R
import io.github.intramuros.weatherbuddy.core.ForecastRadar
import io.github.intramuros.weatherbuddy.core.ForecastRadarFrame
import io.github.intramuros.weatherbuddy.core.Radar
import io.github.intramuros.weatherbuddy.core.TileId
import io.github.intramuros.weatherbuddy.core.WeatherParseException
import io.github.intramuros.weatherbuddy.data.Location
import io.github.intramuros.weatherbuddy.data.RadarClient
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.floor
import kotlin.math.roundToInt

private const val ZOOM = 6
private const val TILE_DP = 200
private const val REFRESH_MS = 5 * 60 * 1000L

/** A forecast map driven by the histogram's shared timestamp. Tap either view to pause both. */
@Composable
internal fun RadarMap(location: Location, playback: RainPlayback, modifier: Modifier = Modifier, timeZone: String? = null) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val use24Hours = DateFormat.is24HourFormat(context)
    val formatter = remember(locale, use24Hours, timeZone) {
        DateTimeFormatter.ofPattern(if (use24Hours) "HH:mm" else "h:mm a", locale).withZone(zoneOrDefault(timeZone))
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var size by remember { mutableStateOf(IntSize.Zero) }
    var frames by remember(location) { mutableStateOf<List<ForecastRadarFrame>>(emptyList()) }
    var refresh by remember(location) { mutableIntStateOf(0) }
    var failed by remember(location) { mutableStateOf(false) }
    var loaded by remember(location) { mutableStateOf(false) }
    val tiles = remember { mutableStateMapOf<String, ImageBitmap>() }
    val publishFrames by rememberUpdatedState<(List<ForecastRadarFrame>) -> Unit>({ playback.frames = it })
    val covered = ForecastRadar.covers(location.latitude, location.longitude)
    val tilePx = TILE_DP * LocalDensity.current.density
    val center = remember(location) { Radar.project(location.latitude, location.longitude, ZOOM) }
    val window = remember(center, size, tilePx) {
        if (size == IntSize.Zero) emptyList() else Radar.tilesCovering(
            center, ZOOM, size.width / tilePx.toDouble(), size.height / tilePx.toDouble(),
        )
    }

    LaunchedEffect(lifecycle, location) {
        if (!covered) return@LaunchedEffect
        refreshRadarWhileStarted(lifecycle, REFRESH_MS) {
            try {
                // Keep older loaded pictures until the replacement set is ready.
                frames = RadarClient.forecastIndex()
                refresh++
                failed = false
            } catch (_: IOException) {
                failed = frames.isEmpty()
            } catch (_: WeatherParseException) {
                failed = frames.isEmpty()
            }
        }
    }
    LaunchedEffect(lifecycle, frames, refresh, window) {
        if (window.isEmpty()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val urls = window.map { Radar.baseTileUrl(it) }
            val permits = Semaphore(4)
            loadMapTiles(urls, tiles) { url -> permits.withPermit { RadarClient.tile(url) }?.asImageBitmap() }
            // Forecast images load in time order, letting synchronized playback start with two frames.
            loaded = false
            frames.forEach { frame ->
                loadMapTiles(listOf(frame.url), tiles) { RadarClient.tile(it)?.asImageBitmap() }
            }
            loaded = true
            tiles.keys.retainAll((urls + frames.map { it.url }).toSet())
            failed = covered && frames.isNotEmpty() && frames.none { it.url in tiles }
        }
    }
    val ready = frames.filter { it.url in tiles }
    LaunchedEffect(ready) { publishFrames(ready) }
    val time = playback.time
    val frame = time?.let { ForecastRadar.frameAt(ready, it) }
    val northwest = remember { Radar.project(ForecastRadar.NORTH, ForecastRadar.WEST, ZOOM) }
    val southeast = remember { Radar.project(ForecastRadar.SOUTH, ForecastRadar.EAST, ZOOM) }
    Box(modifier.onSizeChanged { size = it }.clickable { playback.toggle() }) {
        Canvas(Modifier.fillMaxSize()) {
            drawTiles(window, center.x, center.y, tilePx) { tiles[Radar.baseTileUrl(it)] }
            frame?.let { f -> tiles[f.url]?.let { image ->
                val left = (size.width / 2 + (northwest.x - center.x) * tilePx).roundToInt()
                val top = (size.height / 2 + (northwest.y - center.y) * tilePx).roundToInt()
                drawImage(
                    image,
                    dstOffset = IntOffset(left, top),
                    dstSize = IntSize(((southeast.x - northwest.x) * tilePx).roundToInt(), ((southeast.y - northwest.y) * tilePx).roundToInt()),
                    alpha = 0.85f, filterQuality = FilterQuality.Medium,
                )
            } }
            val middle = Offset(this.size.width / 2, this.size.height / 2)
            drawCircle(Color.White, 7.dp.toPx(), middle)
            drawCircle(Color(0xFFE53935), 5.dp.toPx(), middle)
            drawCircle(Color.Black.copy(alpha = 0.4f), 7.dp.toPx(), middle, style = Stroke(1.dp.toPx()))
        }
        val clock = time?.let(formatter::format)
        val label = when {
            !covered -> stringResource(R.string.radar_outside_forecast)
            frame != null -> stringResource(R.string.radar_forecast, clock.orEmpty())
            failed -> stringResource(R.string.radar_unavailable)
            clock != null && loaded -> stringResource(R.string.radar_time_unavailable, clock)
            loaded && time == null -> stringResource(R.string.radar_unavailable)
            else -> stringResource(R.string.radar_loading)
        }
        val shadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 1f), 3f)
        Text(label, style = TextStyle(color = Color.White, shadow = shadow, fontWeight = FontWeight.Bold, fontSize = 16.sp),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 30.dp))
        Text(radarCreditText(stringResource(R.string.forecast_radar_credit)),
            style = TextStyle(color = Color.White, shadow = shadow, fontSize = 12.sp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** Draws [tile] images laid out on the grid so that the world point ([cx], [cy]) is at the middle. */
private fun DrawScope.drawTiles(
    window: List<TileId>,
    cx: Double,
    cy: Double,
    tilePx: Float,
    alpha: Float = 1f,
    image: (TileId) -> ImageBitmap?,
) {
    val worldTiles = 1 shl ZOOM
    for (tile in window) {
        val bitmap = image(tile) ?: continue
        // Columns wrap around the world, so measure the shortest way from the centre.
        val dx = Math.floorMod(tile.x - floor(cx).toInt() + worldTiles / 2, worldTiles) - worldTiles / 2 - (cx - floor(cx))
        val dy = tile.y - cy
        val left = (size.width / 2 + dx * tilePx).roundToInt()
        val top = (size.height / 2 + dy * tilePx).roundToInt()
        val side = tilePx.roundToInt() + 1 // A pixel of overlap hides seams.
        drawImage(
            bitmap,
            dstOffset = IntOffset(left, top),
            dstSize = IntSize(side, side),
            alpha = alpha,
            filterQuality = FilterQuality.Medium,
        )
    }
}
