package io.github.intramuros.weatherbuddy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoopbackCallbackTest {
    /** What the browser gets for [path] on the callback's port. */
    private suspend fun browse(port: Int, path: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        try {
            val code = connection.responseCode
            code to ((if (code == 200) connection.inputStream else connection.errorStream)?.bufferedReader()?.readText().orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun answersTheBrowserAndReturnsTheCallbacksQuery() = runBlocking<Unit> {
        LoopbackCallback.open().use { callback ->
            val query = async { callback.await(timeoutMs = 10_000, page = "<p>Back to the app</p>") }
            // Browsers ask for a favicon too.
            assertEquals(404, browse(callback.port, "/favicon.ico").first)
            val (code, page) = browse(callback.port, "/auth/callback?code=abc&state=xyz")
            assertEquals(200, code)
            assertEquals("<p>Back to the app</p>", page)
            assertEquals("code=abc&state=xyz", query.await())
        }
    }

    @Test
    fun givesUpAfterTheTimeout() = runBlocking<Unit> {
        LoopbackCallback.open().use { callback ->
            assertFailsWith<SocketTimeoutException> { callback.await(timeoutMs = 1500, page = "") }
        }
    }

    @Test
    fun thePageEscapesItsText() {
        val page = LoopbackCallback.page("A <b>title</b>", "Tom & Jerry", "weatherbuddy://studio", "Back \"home\"")
        assertTrue("A &lt;b&gt;title&lt;/b&gt;" in page)
        assertTrue("Tom &amp; Jerry" in page)
        assertTrue("href=\"weatherbuddy://studio\"" in page)
        assertTrue("Back &quot;home&quot;" in page)
        assertFalse("<b>" in page)
    }
}
