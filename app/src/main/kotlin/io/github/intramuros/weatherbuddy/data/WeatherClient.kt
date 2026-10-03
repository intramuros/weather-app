package io.github.intramuros.weatherbuddy.data

import android.util.Log
import io.github.intramuros.weatherbuddy.core.Buienradar
import io.github.intramuros.weatherbuddy.core.Conditions
import io.github.intramuros.weatherbuddy.core.OpenMeteo
import io.github.intramuros.weatherbuddy.core.WeatherParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Fetches current conditions and the coming days from Open-Meteo, plus the Buienradar rain nowcast. */
object WeatherClient {
    private const val TAG = "WeatherClient"
    private const val TIMEOUT_MS = 10_000

    /**
     * @throws IOException if Open-Meteo can't be reached.
     * @throws WeatherParseException if Open-Meteo answers with something unexpected.
     */
    suspend fun fetch(location: Location): Conditions = withContext(Dispatchers.IO) {
        coroutineScope {
            val nowcast = async {
                // The nowcast is a nice-to-have; the model alone still gives a sensible picture.
                try {
                    Buienradar.parseRaintext(get(Buienradar.raintextUrl(location.latitude, location.longitude)))
                } catch (e: IOException) {
                    Log.w(TAG, "Buienradar unavailable", e)
                    emptyList()
                } catch (e: WeatherParseException) {
                    Log.w(TAG, "Buienradar response not understood", e)
                    emptyList()
                }
            }
            val conditions = OpenMeteo.parse(get(OpenMeteo.forecastUrl(location.latitude, location.longitude)))
            conditions.copy(rainNowcast = nowcast.await())
        }
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "WeatherBuddy/0.1 (Android)")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
