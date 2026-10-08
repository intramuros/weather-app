package io.github.intramuros.weatherbuddy.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** Refresh immediately on each foreground entry, then periodically until the activity stops. */
internal suspend fun refreshRadarWhileStarted(lifecycle: Lifecycle, intervalMs: Long, refresh: suspend () -> Unit) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) {
            refresh()
            delay(intervalMs)
        }
    }
}

/** A batch is usable only when at least one requested tile was decoded, including cached tiles. */
internal suspend fun <T : Any> loadMapTiles(
    urls: List<String>,
    cache: MutableMap<String, T>,
    fetch: suspend (String) -> T?,
): Boolean = coroutineScope {
    val loaded = urls.map { url -> async { url to (cache[url] ?: fetch(url)) } }.awaitAll()
    loaded.forEach { (url, tile) -> if (tile != null) cache[url] = tile }
    loaded.any { (_, tile) -> tile != null }
}
