package io.github.intramuros.weatherbuddy.ui

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.intramuros.weatherbuddy.R
import io.github.intramuros.weatherbuddy.core.Radar
import io.github.intramuros.weatherbuddy.core.RadarFrame
import io.github.intramuros.weatherbuddy.core.RadarIndex
import io.github.intramuros.weatherbuddy.core.TileId
import io.github.intramuros.weatherbuddy.core.WeatherParseException
import io.github.intramuros.weatherbuddy.data.Location
import io.github.intramuros.weatherbuddy.data.RadarClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
private const val FRAMES_SHOWN = 9
private const val REFRESH_MS = 5 * 60 * 1000L
private const val STEP_MS = 450L
private const val HOLD_MS = 1500L
private const val PARALLEL_FETCHES = 6

/**
 * A Buienradar-style rain radar: the last hour or two of precipitation (and, when RainViewer offers
 * it, the coming half hour) looping over a map centred on [location]. Tap to pause.
 */
@Composable
internal fun RadarMap(location: Location, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var index by remember { mutableStateOf<RadarIndex?>(null) }
    var failed by remember { mutableStateOf(false) }
    val tiles = remember { mutableStateMapOf<String, ImageBitmap>() }
    // The frames whose tiles have all arrived (or failed), by time, and the one on show.
    val ready = remember { mutableStateListOf<RadarFrame>() }
    var shown by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }

    val density = context.resources.displayMetrics.density
    val tilePx = TILE_DP * density
    val center = remember(location) { Radar.project(location.latitude, location.longitude, ZOOM) }
    val window = remember(center, size, tilePx) {
        if (size == IntSize.Zero) emptyList()
        else Radar.tilesCovering(center, ZOOM, size.width / tilePx.toDouble(), size.height / tilePx.toDouble())
    }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                index = RadarClient.index()
                failed = false
            } catch (_: IOException) {
                failed = index == null
            } catch (_: WeatherParseException) {
                failed = index == null
            }
            delay(REFRESH_MS)
        }
    }

    LaunchedEffect(index, window, dark) {
        val current = index ?: return@LaunchedEffect
        if (window.isEmpty()) return@LaunchedEffect
        val frames = current.frames.takeLast(FRAMES_SHOWN)
        ready.removeAll { it !in frames }
        val wanted = window.map { Radar.baseTileUrl(it, dark) } +
            frames.flatMap { frame -> window.map { Radar.radarTileUrl(current, frame, it) } }
        tiles.keys.retainAll(wanted.toSet())
        val permits = Semaphore(PARALLEL_FETCHES)
        suspend fun load(url: String) {
            if (url in tiles) return
            permits.withPermit { RadarClient.tile(url) }?.let { tiles[url] = it.asImageBitmap() }
        }
        coroutineScope {
            // The map and the newest picture first, so something shows early; older frames fill in the loop.
            window.map { async { load(Radar.baseTileUrl(it, dark)) } }.awaitAll()
            frames.asReversed().forEach { frame ->
                window.map { async { load(Radar.radarTileUrl(current, frame, it)) } }.awaitAll()
                if (frame !in ready) {
                    ready.add(frame)
                    ready.sortBy { it.timeSeconds }
                }
            }
        }
    }

    LaunchedEffect(ready.size, playing) {
        while (playing && ready.size > 1) {
            delay(if (shown >= ready.size - 1) HOLD_MS else STEP_MS)
            shown = if (shown >= ready.size - 1) 0 else shown + 1
        }
        if (ready.isNotEmpty() && shown > ready.size - 1) shown = ready.size - 1
    }

    val frame = ready.getOrNull(shown.coerceAtMost(ready.lastIndex.coerceAtLeast(0)))
    val currentIndex = index
    Box(
        modifier
            .onSizeChanged { size = it }
            .clickable { playing = !playing },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawTiles(window, center.x, center.y, tilePx) { tile -> tiles[Radar.baseTileUrl(tile, dark)] }
            if (frame != null && currentIndex != null) {
                drawTiles(window, center.x, center.y, tilePx, alpha = 0.85f) { tile ->
                    tiles[Radar.radarTileUrl(currentIndex, frame, tile)]
                }
            }
            val middle = Offset(this.size.width / 2, this.size.height / 2)
            drawCircle(Color.White, radius = 7.dp.toPx(), center = middle)
            drawCircle(Color(0xFFE53935), radius = 5.dp.toPx(), center = middle)
            drawCircle(Color.Black.copy(alpha = 0.4f), radius = 7.dp.toPx(), center = middle, style = Stroke(1.dp.toPx()))
        }
        val label = when {
            frame != null && currentIndex != null -> {
                val time = DateFormat.getTimeFormat(context).format(Date(frame.timeSeconds * 1000))
                if (frame in currentIndex.frames.takeLast(currentIndex.forecastFrames)) {
                    stringResource(R.string.radar_forecast, time)
                } else {
                    time
                }
            }
            failed -> stringResource(R.string.radar_unavailable)
            else -> stringResource(R.string.radar_loading)
        }
        val shadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 1f), 3f)
        Text(
            label,
            style = TextStyle(color = Color.White, shadow = shadow, fontWeight = FontWeight.Bold, fontSize = 16.sp),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 22.dp),
        )
        Text(
            stringResource(R.string.radar_credit),
            style = TextStyle(color = Color.White, shadow = shadow, fontSize = 10.sp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 4.dp),
        )
        if (ready.size > 1) {
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
