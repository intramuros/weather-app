package io.github.intramuros.weatherbuddy.data

import io.github.intramuros.weatherbuddy.core.ChatGptSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Waits on 127.0.0.1 for the browser to come back from signing in with ChatGPT, the
 * only redirect OpenAI allows for it, on a port the system picks.
 */
class LoopbackCallback private constructor(private val server: ServerSocket) : Closeable {
    val port: Int get() = server.localPort

    /**
     * The query of the browser's request for [ChatGptSignIn.CALLBACK_PATH], which is answered
     * with [page]. Anything else it asks for, such as a favicon, gets a 404.
     * @throws SocketTimeoutException if the browser doesn't come back within [timeoutMs].
     */
    suspend fun await(timeoutMs: Long, page: String): String = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        // Accepting in short turns lets the wait be cancelled.
        server.soTimeout = POLL_MS
        while (true) {
            ensureActive()
            val socket = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                if (System.currentTimeMillis() > deadline) throw e
                continue
            }
            socket.use {
                it.soTimeout = READ_TIMEOUT_MS
                val request = it.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine().orEmpty().split(' ')
                val target = request.getOrNull(1).orEmpty()
                if (request.first() == "GET" && target.substringBefore('?') == ChatGptSignIn.CALLBACK_PATH) {
                    respond(it, "200 OK", page)
                    return@withContext target.substringAfter('?', "")
                }
                respond(it, "404 Not Found", "")
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    override fun close() = server.close()

    private fun respond(socket: Socket, status: String, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().run {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(body)
            flush()
        }
    }

    companion object {
        private const val POLL_MS = 1000
        private const val READ_TIMEOUT_MS = 5000

        /** Listens on a free port of 127.0.0.1, never on the network. */
        fun open() = LoopbackCallback(ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))))

        /** A plain page saying [text], with a link back to the app at [appLink]. */
        fun page(title: String, text: String, appLink: String, linkText: String): String {
            fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                |<title>${escape(title)}</title><style>body{font-family:sans-serif;margin:48px 24px;text-align:center}
                |a{display:inline-block;margin-top:24px;padding:12px 24px;border-radius:24px;background:#34436E;color:#fff;text-decoration:none}</style>
                |</head><body><h1>${escape(title)}</h1><p>${escape(text)}</p><a href="${escape(appLink)}">${escape(linkText)}</a></body></html>
            """.trimMargin()
        }
    }
}
