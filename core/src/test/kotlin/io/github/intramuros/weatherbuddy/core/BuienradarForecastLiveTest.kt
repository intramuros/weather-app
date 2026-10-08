package io.github.intramuros.weatherbuddy.core

import java.net.HttpURLConnection
import java.net.URL
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Run separately in CI: provider metadata and actual PNG images must agree with the adapter. */
class BuienradarForecastLiveTest {
    @Test
    fun providerReturnsFutureTimestampsAndADecodableGeographicOverlay() {
        val metadata = get(Radar.INDEX_URL).toString(Charsets.UTF_8)
        println("Provider metadata sample: ${metadata.take(1500)}")
        val index = Radar.parseIndex(metadata, System.currentTimeMillis() / 1000)
        val future = index.frames.filter { it.isForecast }
        assertTrue(future.isNotEmpty())
        val image = ImageIO.read(get(future.first().url).inputStream())
        assertNotNull(image)
        assertEquals(Radar.FRAME_WIDTH, image.width)
        assertEquals(Radar.FRAME_HEIGHT, image.height)
        println("Buienradar returned ${index.frames.size} usable frames; last forecast: ${java.time.Instant.ofEpochSecond(future.last().timeSeconds)}")
    }

    private fun get(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "WeatherBuddy/0.2 (https://github.com/intramuros/weather-app; forecast contract check)")
            assertEquals(200, connection.responseCode, "Provider request failed: ${URL(url).host}")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
