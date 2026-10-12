package io.github.intramuros.weatherbuddy

import android.content.Context
import android.os.SystemClock
import io.github.intramuros.weatherbuddy.data.LocationProvider
import io.github.intramuros.weatherbuddy.data.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Follows the phone to a new place, quietly: if it can't be found, the saved
 * place stays. Android only says where the phone is while the app is in use,
 * so it's looked for when the app comes into view, when the animated
 * wallpaper does, and when the widget's refresh button is tapped
 * ([LocationProvider.canLocateNow]); the scheduled refresh in between reuses
 * the saved place.
 */
object LocationFollower {
    /** For opening the app or tapping the widget: a lookup this recent is still current. */
    const val MIN_GAP_MS = 2 * 60 * 1000L

    /** For the wallpaper, which comes into view every time the phone is unlocked. */
    const val WALLPAPER_GAP_MS = 15 * 60 * 1000L

    private val mutex = Mutex()

    /** When the last lookup ended, by [SystemClock.elapsedRealtime]; `null` before the first. */
    private var lastLookup: Long? = null

    /**
     * Looks for the phone, unless a lookup ended less than [minGapMs] ago, and
     * saves the place it's in. Returns whether it saved a new place, or a name
     * for the saved one. One lookup at a time, so the wallpaper, the app and
     * the widget can't each look and save at once; one that waited for
     * another usually needn't look itself.
     */
    suspend fun follow(context: Context, minGapMs: Long): Boolean = mutex.withLock {
        val app = context.applicationContext
        if (!LocationProvider.hasPermission(app)) return@withLock false
        lastLookup?.let { if (SystemClock.elapsedRealtime() - it < minGapMs) return@withLock false }
        val location = LocationProvider.current(app)
        lastLookup = SystemClock.elapsedRealtime()
        if (location == null) return@withLock false

        val settingsRepo = SettingsRepository(app)
        val settings = settingsRepo.current()
        val moved = location != settings.location
        if (!moved && settings.placeName != null) return@withLock false
        val placeName = LocationProvider.placeName(app, location)
        settingsRepo.setLocation(location, placeName)
        moved || placeName != null
    }
}
