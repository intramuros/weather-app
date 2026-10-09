package io.github.intramuros.weatherbuddy.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RadarLoadingTest {
    @Test
    fun refreshesOnlyInForegroundAndImmediatelyOnReturn() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = Owner()
        var requests = 0
        val job = backgroundScope.launch { refreshRadarWhileStarted(owner.lifecycle, 1_000) { requests++ } }
        try {
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            runCurrent()
            assertEquals(0, requests)

            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(1, requests)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, requests)

            owner.lifecycle.currentState = Lifecycle.State.CREATED
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(2, requests)

            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(3, requests)
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            runCurrent()
            assertTrue(job.isCompleted)
        } finally {
            job.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun stoppingCancelsAnInFlightRefresh() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = Owner()
        var requests = 0
        var cancellations = 0
        val job = backgroundScope.launch {
            refreshRadarWhileStarted(owner.lifecycle, 1_000) {
                requests++
                try {
                    awaitCancellation()
                } finally {
                    cancellations++
                }
            }
        }
        try {
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(1, requests)
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            runCurrent()
            assertEquals(1, cancellations)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(1, requests)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(2, requests)
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            runCurrent()
            assertEquals(2, cancellations)
            assertTrue(job.isCompleted)
        } finally {
            job.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedRadarTilesAreNotReadyEvenWithACachedBaseMap() = runTest {
        val cache = mutableMapOf("base" to "map")
        assertFalse(loadMapTiles(listOf("radar-a", "radar-b"), cache) { null })
        assertEquals(mapOf("base" to "map"), cache)
    }

    @Test
    fun partiallyLoadedFramesRemainUsableAndReuseSuccessfulTiles() = runTest {
        val cache = mutableMapOf<String, String>()
        val requested = mutableListOf<String>()
        val urls = listOf("radar-a", "radar-b")
        assertTrue(loadMapTiles(urls, cache) { url -> if (url == "radar-a") "rain" else null })
        assertEquals(mapOf("radar-a" to "rain"), cache)
        assertTrue(loadMapTiles(urls, cache) { url -> requested.add(url); null })
        assertEquals(listOf("radar-b"), requested)
    }

    @Test
    fun failedTilesCanRecoverOnTheNextLoadOfTheSameFrame() = runTest {
        val cache = mutableMapOf<String, String>()
        val urls = listOf("radar-a", "radar-b")
        assertFalse(loadMapTiles(urls, cache) { null })
        assertTrue(loadMapTiles(urls, cache) { "rain" })
        assertEquals(urls.toSet(), cache.keys)
        // A frame for another viewport cannot use these previously decoded tiles.
        assertFalse(loadMapTiles(listOf("radar-c"), cache) { null })
    }

    @Test
    fun baseTilesRecoverWhileForegroundWithoutRadarMetadataUpdates() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = Owner()
        val cache = mutableMapOf<String, String>()
        val requests = mutableListOf<String>()
        var recovered = false
        val job = backgroundScope.launch {
            refreshRadarWhileStarted(owner.lifecycle, 1_000) {
                loadMapTiles(listOf("base-a", "base-b"), cache) { url ->
                    requests.add(url)
                    if (url == "base-a" || recovered) "map" else null
                }
            }
        }
        try {
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(setOf("base-a"), cache.keys)
            requests.clear()
            recovered = true
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(listOf("base-b"), requests)
            assertEquals(setOf("base-a", "base-b"), cache.keys)

            requests.clear()
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue(requests.isEmpty())
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            runCurrent()
            assertTrue(job.isCompleted)
        } finally {
            job.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }
}
