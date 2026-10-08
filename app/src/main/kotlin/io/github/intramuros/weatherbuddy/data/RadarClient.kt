package io.github.intramuros.weatherbuddy.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.http.HttpResponseCache
import android.util.Log
import io.github.intramuros.weatherbuddy.core.Radar
import io.github.intramuros.weatherbuddy.core.ForecastRadar
import io.github.intramuros.weatherbuddy.core.ForecastRadarFrame
import io.github.intramuros.weatherbuddy.core.RadarIndex
import io.github.intramuros.weatherbuddy.core.WeatherParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Fetches the RainViewer radar index and the map tiles the radar view draws. */
object RadarClient {
    private const val TIMEOUT_MS = 10_000
    // One process-wide budget for the index and all radar tiles, including retries and new viewports.
    // 80 requests/minute leaves room below RainViewer's 100 requests/IP/minute limit.
    private val rainViewerRequests = RequestPacer(intervalMs = 750)

    /**
     * @throws IOException if RainViewer can't be reached.
     * @throws WeatherParseException if it answers with something unexpected.
     */
    suspend fun index(): RadarIndex = withContext(Dispatchers.IO) {
        Radar.parseIndex(get(Radar.INDEX_URL).toString(Charsets.UTF_8))
    }

    suspend fun forecastIndex(): List<ForecastRadarFrame> = withContext(Dispatchers.IO) {
        ForecastRadar.parse(get(ForecastRadar.INDEX_URL).toString(Charsets.UTF_8))
    }

    /** The tile at [url], or `null` if it can't be fetched or decoded; a missing tile just leaves a gap. */
    suspend fun tile(url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            get(url).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } catch (e: IOException) {
            Log.w("RadarClient", "Tile unavailable from ${URL(url).host}", e)
            null
        }
    }

    private suspend fun get(url: String): ByteArray {
        val target = URL(url)
        if (target.host != "tile.openstreetmap.org") rainViewerRequests.awaitTurn()
        val connection = target.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "WeatherBuddy/0.2 (Android; +https://github.com/intramuros/weather-app)")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
            // Persist completed responses even if Android kills the process after leaving the map.
            try {
                HttpResponseCache.getInstalled()?.flush()
            } catch (e: IOException) {
                Log.w("RadarClient", "HTTP cache could not be flushed", e)
            }
        }
    }
}
