package io.github.intramuros.weatherbuddy

import android.content.Context
import android.os.SystemClock
import io.github.intramuros.weatherbuddy.data.Location
import io.github.intramuros.weatherbuddy.data.LocationProvider
import io.github.intramuros.weatherbuddy.data.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Follows the phone to a new place, quietly: if it can't be found, the saved
 * place stays. Unless location is allowed all the time, Android only says
 * where the phone is while the app is in use, so it's looked for when the app
 * comes into view, when the animated wallpaper does, and when the widget's
 * refresh button is tapped ([LocationProvider.canLocateNow]). Only if the user
 * opted in ([io.github.intramuros.weatherbuddy.data.Settings.followInBackground])
 * does the scheduled refresh in between look too, and does Android say when the
 * phone has moved ([LocationWatch]); otherwise they reuse the saved place.
 */
object LocationFollower {
    /** For opening the app or tapping the widget: a lookup this recent is still current. */
    const val MIN_GAP_MS = 2 * 60 * 1000L

    /**
     * For lookups nobody asked for: the wallpaper coming into view, which it does every time
     * the phone is unlocked, and the scheduled refresh, which is spaced by its own schedule
     * when Android has ended the process in between.
     */
    const val AUTO_GAP_MS = 15 * 60 * 1000L

    private val mutex = Mutex()

    private val lookups = Lookups()

    /**
     * Looks for the phone, unless a lookup ended less than [minGapMs] ago, and
     * saves the place it's in. Returns whether it saved a new place, or a name
     * for the saved one. One lookup at a time, so the wallpaper, the app and
     * the widget can't each look and save at once; one that waited for
     * another usually needn't look itself.
     *
     * [inApp] is for the app on screen, where Android finds the phone best: only a lookup that
     * found it holds this one back. One that didn't, often the widget's from the background
     * while the app waited for it, would otherwise leave the old place showing.
     */
    suspend fun follow(context: Context, minGapMs: Long, inApp: Boolean = false): Boolean = mutex.withLock {
        val app = context.applicationContext
        if (!LocationProvider.hasPermission(app)) return@withLock false
        if (lookups.recent(SystemClock.elapsedRealtime(), minGapMs, onlyFound = inApp)) return@withLock false
        val location = LocationProvider.current(app)
        lookups.ended(SystemClock.elapsedRealtime(), found = location != null)
        if (location == null) return@withLock false
        save(app, location)
    }

    /**
     * Saves where Android said, without being asked ([LocationWatch]), the phone was at [timeMillis]
     * (wall clock), unless that's old by now or a lookup has found the phone since. Returns whether
     * it saved a new place, or a name for the saved one.
     */
    suspend fun arrived(context: Context, location: Location, timeMillis: Long): Boolean = mutex.withLock {
        val age = System.currentTimeMillis() - timeMillis
        if (age > LocationProvider.MAX_LAST_KNOWN_AGE_MS) return@withLock false
        if (lookups.foundSince(SystemClock.elapsedRealtime() - age)) return@withLock false
        save(context.applicationContext, location)
    }

    private suspend fun save(app: Context, location: Location): Boolean {
        val settingsRepo = SettingsRepository(app)
        val settings = settingsRepo.current()
        val moved = location != settings.location
        if (!moved && settings.placeName != null) return false
        val placeName = LocationProvider.placeName(app, location)
        settingsRepo.setLocation(location, placeName)
        return moved || placeName != null
    }
}

/**
 * When lookups ended, by [SystemClock.elapsedRealtime]; nothing before the first in this
 * process.
 */
internal class Lookups {
    private var ended: Long? = null
    private var found: Long? = null

    /**
     * Whether a lookup ended less than [gapMs] before [now], or, with [onlyFound], one that
     * found the phone.
     */
    fun recent(now: Long, gapMs: Long, onlyFound: Boolean): Boolean {
        val last = (if (onlyFound) found else ended) ?: return false
        return now - last < gapMs
    }

    /** Whether a lookup that found the phone ended after [time]. */
    fun foundSince(time: Long): Boolean = found?.let { it > time } ?: false

    fun ended(now: Long, found: Boolean) {
        ended = now
        if (found) this.found = now
    }
}
