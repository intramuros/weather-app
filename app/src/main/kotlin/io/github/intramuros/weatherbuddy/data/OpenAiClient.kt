package io.github.intramuros.weatherbuddy.data

import io.github.intramuros.weatherbuddy.core.ImageQuality
import io.github.intramuros.weatherbuddy.core.OpenAiException
import io.github.intramuros.weatherbuddy.core.StyleDesigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Talks to OpenAI for [StyleDesigner] with the user's own key ([ChatGptClient] does the
 * chat on a ChatGPT plan instead). Network errors are [IOException]s; OpenAI turning a
 * request down is an [OpenAiException].
 */
object OpenAiClient {
    private const val CONNECT_TIMEOUT_MS = 15_000
    const val CHAT_TIMEOUT_MS = 90_000

    /** A carefully drawn picture can take a few minutes; StyleWorker counts on it taking at most this. */
    const val IMAGE_TIMEOUT_MS = 5 * 60_000

    private const val HTTP_FORBIDDEN = 403

    /**
     * Whether OpenAI accepts [key]; throws if it couldn't be asked. A key that may not list
     * the models still counts: a key restricted to the chat and image models is refused
     * that (403), but works here.
     */
    suspend fun accepts(key: String): Boolean =
        try {
            request(key, StyleDesigner.KEY_CHECK_URL, CHAT_TIMEOUT_MS, body = null)
            true
        } catch (e: OpenAiException) {
            when {
                e.problem == OpenAiException.Problem.KEY -> false
                e.status == HTTP_FORBIDDEN -> true
                else -> throw e
            }
        }

    /** The designer's answer to the last message of [conversation]. */
    suspend fun chat(key: String, conversation: List<StyleDesigner.Message>): StyleDesigner.Turn {
        val body = StyleDesigner.chatRequest(conversation).toByteArray()
        return StyleDesigner.parseChat(request(key, StyleDesigner.CHAT_URL, CHAT_TIMEOUT_MS, body to "application/json"))
    }

    /** Draws a picture from [prompt] and [images] (WebP or PNG files), and returns it as WebP. */
    suspend fun draw(key: String, prompt: String, images: List<ByteArray>, quality: ImageQuality): ByteArray {
        val form = Multipart()
        StyleDesigner.imageFields(prompt, quality).forEach { (name, value) -> form.field(name, value) }
        images.forEachIndexed { i, image -> form.file(StyleDesigner.IMAGE_FIELD, "image-${i + 1}", image) }
        return StyleDesigner.parseImage(request(key, StyleDesigner.IMAGE_EDIT_URL, IMAGE_TIMEOUT_MS, form.bytes() to form.contentType))
    }

    private suspend fun request(key: String, url: String, timeoutMs: Int, body: Pair<ByteArray, String>?): String =
        send(url, "Bearer $key", timeoutMs, body) { it.bufferedReader().readText() }

    /**
     * Sends a request to OpenAI and reads the answer with [read]. [authorization] is the
     * whole header (a key or a token, never logged); an error answer becomes what
     * [error] makes of it.
     */
    internal suspend fun <T> send(
        url: String,
        authorization: String?,
        timeoutMs: Int,
        body: Pair<ByteArray, String>?,
        error: (status: Int, body: String?, retryAfter: String?) -> IOException = StyleDesigner::error,
        read: (InputStream) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = timeoutMs
                // Past the app's HTTP cache: a key check must ask OpenAI.
                connection.useCaches = false
                if (authorization != null) connection.setRequestProperty("Authorization", authorization)
                if (body != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", body.second)
                    connection.setFixedLengthStreamingMode(body.first.size)
                    connection.outputStream.use { it.write(body.first) }
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val answer = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    throw error(code, answer, connection.getHeaderField("Retry-After"))
                }
                connection.inputStream.use(read)
            } finally {
                connection.disconnect()
            }
        }
}

/** A `multipart/form-data` body: text fields, then files. */
internal class Multipart(private val boundary: String = "WeatherBuddy-${UUID.randomUUID()}") {
    private val out = ByteArrayOutputStream()

    val contentType get() = "multipart/form-data; boundary=$boundary"

    fun field(name: String, value: String) {
        part("Content-Disposition: form-data; name=\"$name\"")
        out.write(value.toByteArray())
        out.write(CRLF)
    }

    /** A file; its type is read from its first bytes, since the API checks it. */
    fun file(name: String, filename: String, bytes: ByteArray) {
        val (type, extension) = if (isWebp(bytes)) "image/webp" to "webp" else "image/png" to "png"
        part("Content-Disposition: form-data; name=\"$name\"; filename=\"$filename.$extension\"", "Content-Type: $type")
        out.write(bytes)
        out.write(CRLF)
    }

    fun bytes(): ByteArray = out.toByteArray() + "--$boundary--\r\n".toByteArray()

    private fun part(vararg headers: String) {
        out.write("--$boundary\r\n".toByteArray())
        for (header in headers) out.write("$header\r\n".toByteArray())
        out.write(CRLF)
    }

    private companion object {
        val CRLF = "\r\n".toByteArray()

        fun isWebp(bytes: ByteArray) = bytes.size > 12 &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
    }
}
