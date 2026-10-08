package io.github.intramuros.weatherbuddy.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RequestPacerTest {
    @Test
    fun largeViewportAndIndexStayBelowTheProviderLimitInEveryMinute() = runTest {
        val pacer = RequestPacer(750) { testScheduler.currentTime }
        // Nine frames with twenty tiles each, plus the shared index request.
        val starts = List(181) {
            async {
                pacer.awaitTurn()
                testScheduler.currentTime
            }
        }.awaitAll().sorted()
        assertEquals(0L, starts.first())
        assertTrue(starts.windowed(101).all { it.last() - it.first() >= 60_000 })
    }

    @Test
    fun returningAfterAnIdlePeriodDoesNotAllowABurst() = runTest {
        val pacer = RequestPacer(750) { testScheduler.currentTime }
        pacer.awaitTurn()
        advanceTimeBy(120_000)
        val starts = List(6) {
            async {
                pacer.awaitTurn()
                testScheduler.currentTime
            }
        }.awaitAll().sorted()
        assertEquals(120_000L, starts.first())
        assertTrue(starts.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun cancellingAWaiterDoesNotConsumeAFutureSlot() = runTest {
        val pacer = RequestPacer(750) { testScheduler.currentTime }
        pacer.awaitTurn()
        val cancelled = launch { pacer.awaitTurn() }
        runCurrent()
        advanceTimeBy(200)
        cancelled.cancel()
        cancelled.join()
        pacer.awaitTurn()
        assertEquals(750L, testScheduler.currentTime)
    }
}
