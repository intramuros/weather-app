package io.github.intramuros.weatherbuddy.ui

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
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
import io.github.intramuros.weatherbuddy.core.Radar
import io.github.intramuros.weatherbuddy.core.RadarFrame
import io.github.intramuros.weatherbuddy.core.RadarIndex
import io.github.intramuros.weatherbuddy.core.TileId
import io.github.intramuros.weatherbuddy.core.WeatherParseException
import io.github.intramuros.weatherbuddy.data.Location
import io.github.intramuros.weatherbuddy.data.RadarClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.Date
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.floor
import kotlin.math.roundToInt

private const val ZOOM = 6
private const val TILE_DP = 200
private const val REFRESH_MS = 5 * 60 * 1000L
private const val STEP_MS = 450L
private const val HOLD_MS = 1500L
private const val PARALLEL_FETCHES = 6

/**
 * Buienradar's available future rain pictures over a map centred on [location]. Tap to pause.
 */
@Composable
internal fun RadarMap(location: Location, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var size by remember { mutableStateOf(IntSize.Zero) }
    var index by remember { mutableStateOf<RadarIndex?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    val tiles = remember { mutableStateMapOf<String, ImageBitmap>() }
    val images = remember { mutableStateMapOf<String, ImageBitmap>() }
    // Only successfully decoded forecast images can enter playback.
    val ready = remember { mutableStateListOf<RadarFrame>() }
    var shown by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }

    val density = LocalDensity.current.density
    val tilePx = TILE_DP * density
    val center = remember(location) { Radar.project(location.latitude, location.longitude, ZOOM) }
    val covered = Radar.covers(location.latitude, location.longitude)
    val window = remember(center, size, tilePx) {
        if (size == IntSize.Zero) emptyList()
        else Radar.tilesCovering(center, ZOOM, size.width / tilePx.toDouble(), size.height / tilePx.toDouble())
    }

    LaunchedEffect(lifecycle, covered) {
        if (!covered) return@LaunchedEffect
        refreshRadarWhileStarted(lifecycle, REFRESH_MS) {
            try {
                index = RadarClient.index()
                // Even an unchanged index should retry previously missing images.
                refresh++
            } catch (_: IOException) {
                failed = ready.none { it.timeSeconds > System.currentTimeMillis() / 1000 }
            } catch (_: WeatherParseException) {
                failed = ready.none { it.timeSeconds > System.currentTimeMillis() / 1000 }
            }
        }
    }

    // A forecast outage must not prevent the underlying map from loading.
    LaunchedEffect(lifecycle, window) {
        if (window.isEmpty()) return@LaunchedEffect
        refreshRadarWhileStarted(lifecycle, REFRESH_MS) {
            val wanted = window.map { Radar.baseTileUrl(it) }
            tiles.keys.retainAll(wanted.toSet())
            val permits = Semaphore(PARALLEL_FETCHES)
            loadMapTiles(wanted, tiles) { url -> permits.withPermit { RadarClient.tile(url)?.asImageBitmap() } }
        }
    }

    LaunchedEffect(lifecycle, index, refresh, covered) {
        val current = index ?: return@LaunchedEffect
        if (!covered) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val frames = current.frames
            images.keys.retainAll(frames.map { it.url }.toSet())
            ready.removeAll { frame ->
                frame !in frames || frame.url !in images
            }
            if (ready.isEmpty()) failed = false
            val permits = Semaphore(PARALLEL_FETCHES)
            suspend fun load(urls: List<String>): Boolean = loadMapTiles(urls, images) { url ->
                permits.withPermit { RadarClient.tile(url) }?.asImageBitmap()
            }
            // Start near the current time, then fill in the rest of the forecast horizon.
            frames.forEach { frame ->
                if (load(listOf(frame.url))) {
                    if (frame !in ready) {
                        ready.add(frame)
                        ready.sortBy { it.timeSeconds }
                    }
                    failed = false
                } else {
                    ready.remove(frame)
                }
            }
            failed = ready.none { it.isForecast }
        }
    }

    LaunchedEffect(lifecycle, ready.size, playing, covered, failed) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (covered && !failed && playing && ready.size > 1) {
                delay(if (shown >= ready.size - 1) HOLD_MS else STEP_MS)
                shown = if (shown >= ready.size - 1) 0 else shown + 1
            }
            if (ready.isNotEmpty() && shown > ready.size - 1) shown = ready.size - 1
        }
    }

    val frame = ready.getOrNull(shown.coerceAtMost(ready.lastIndex.coerceAtLeast(0)))
    val currentIndex = index
    Box(
        modifier
            .onSizeChanged { size = it }
            .clickable { playing = !playing },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawTiles(window, center.x, center.y, tilePx) { tile -> tiles[Radar.baseTileUrl(tile)] }
            if (covered && !failed && frame != null && currentIndex != null) {
                images[frame.url]?.let { drawForecast(it, center.x, center.y, tilePx) }
            }
            val middle = Offset(this.size.width / 2, this.size.height / 2)
            drawCircle(Color.White, radius = 7.dp.toPx(), center = middle)
            drawCircle(Color(0xFFE53935), radius = 5.dp.toPx(), center = middle)
            drawCircle(Color.Black.copy(alpha = 0.4f), radius = 7.dp.toPx(), center = middle, style = Stroke(1.dp.toPx()))
        }
        val label = when {
            !covered -> stringResource(R.string.radar_outside_coverage)
            failed -> stringResource(R.string.radar_unavailable)
            frame != null && currentIndex != null -> {
                val time = DateFormat.getTimeFormat(context).format(Date(frame.timeSeconds * 1000))
                if (frame.isForecast) {
                    stringResource(R.string.radar_forecast, time)
                } else {
                    time
                }
            }
            else -> stringResource(R.string.radar_loading)
        }
        val shadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 1f), 3f)
        Text(
            label,
            style = TextStyle(color = Color.White, shadow = shadow, fontWeight = FontWeight.Bold, fontSize = 16.sp),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 22.dp),
        )
        Text(
            radarCreditText(stringResource(R.string.radar_credit)),
            style = TextStyle(color = Color.White, shadow = shadow, fontSize = 10.sp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 4.dp),
        )
        if (covered && !failed && ready.size > 1) {
            Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(16.dp).padding(horizontal = 12.dp, vertical = 6.dp)) {
                val gap = 3.dp.toPx()
                val width = (this.size.width - gap * (ready.size - 1)) / ready.size
                val height = 4.dp.toPx()
                ready.forEachIndexed { i, _ ->
                    drawRect(
                        Color.White.copy(alpha = if (i == shown) 0.95f else 0.4f),
                        topLeft = Offset(i * (width + gap), 0f),
                        size = Size(width, height),
                    )
                }
            }
        }
    }
}

/** Buienradar's WebmercatorNL image is one geographic overlay, not a grid of radar tiles. */
private fun DrawScope.drawForecast(image: ImageBitmap, cx: Double, cy: Double, tilePx: Float) {
    val area = Radar.forecastArea(ZOOM)
    drawImage(
        image,
        dstOffset = IntOffset(
            (size.width / 2 + (area.x - cx) * tilePx).roundToInt(),
            (size.height / 2 + (area.y - cy) * tilePx).roundToInt(),
        ),
        dstSize = IntSize((area.width * tilePx).roundToInt(), (area.height * tilePx).roundToInt()),
        alpha = 0.8f,
        filterQuality = FilterQuality.Medium,
    )
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
