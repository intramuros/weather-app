package io.github.intramuros.weatherbuddy.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Space request starts using a monotonic clock; waiting is cancellable and idle time earns no burst. */
internal class RequestPacer(
    private val intervalMs: Long,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutex = Mutex()
    private var nextAllowedAt: Long? = null

    init {
        require(intervalMs > 0)
    }

    suspend fun awaitTurn() = mutex.withLock {
        nextAllowedAt?.let { next ->
            val waitMs = next - nowMs()
            if (waitMs > 0) delay(waitMs)
        }
        nextAllowedAt = nowMs() + intervalMs
    }
}
