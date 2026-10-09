package io.github.intramuros.weatherbuddy.core

import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Run separately in CI: provider metadata and actual PNG images must agree with the adapter. */
class BuienradarForecastLiveTest {
    @Test
    fun providerReturnsFutureTimestampsAndADecodableGeographicOverlay() {
        val metadata = get(ForecastRadar.INDEX_URL).toString(Charsets.UTF_8)
        println("Provider metadata sample: ${metadata.take(1500)}")
        val frames = ForecastRadar.parse(metadata)
        val now = Instant.now()
        val future = frames.filter { it.time > now }
        assertTrue(future.size > 1, "Production feed must provide multiple future playback steps")
        val image = ImageIO.read(get(future.first().url).inputStream())
        assertNotNull(image)
        assertEquals(ForecastRadar.FRAME_WIDTH, image.width)
        assertEquals(ForecastRadar.FRAME_HEIGHT, image.height)
        println("Buienradar returned ${frames.size} usable frames; last forecast: ${future.last().time}")
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
