package io.github.intramuros.weatherbuddy.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.intramuros.weatherbuddy.core.Radar
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

    /**
     * @throws IOException if RainViewer can't be reached.
     * @throws WeatherParseException if it answers with something unexpected.
     */
    suspend fun index(): RadarIndex = withContext(Dispatchers.IO) {
        Radar.parseIndex(get(Radar.INDEX_URL).toString(Charsets.UTF_8))
    }

    /** The tile at [url], or `null` if it can't be fetched or decoded; a missing tile just leaves a gap. */
    suspend fun tile(url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            get(url).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } catch (_: IOException) {
            null
        }
    }

    private fun get(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "WeatherBuddy/0.1 (Android)")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
