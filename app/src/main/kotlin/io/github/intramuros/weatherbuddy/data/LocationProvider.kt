package io.github.intramuros.weatherbuddy.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import android.location.Location as AndroidLocation

/** Where the phone was found, rounded ([Location.rounded]), and when (wall clock). */
data class Fix(val location: Location, val timeMillis: Long)

/**
 * Coarse location from the platform [LocationManager], without Google Play
 * services. Unless location is allowed all the time, Android only answers
 * while the app is in use, which takes more than having it open (see
 * [io.github.intramuros.weatherbuddy.LocationFollower]); the scheduled
 * background refresh then reuses the last saved location.
 */
object LocationProvider {
    /** A fix another app got this recently will do; an older one may be from a place the phone has left. */
    const val MAX_LAST_KNOWN_AGE_MS = 30 * 60 * 1000L

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Whether location is allowed all the time, not only while the app is in use. Android 8 and 9
     * have no such choice: any location access covers it.
     */
    fun hasBackgroundPermission(context: Context) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            hasPermission(context)
        }

    /**
     * Whether Android would say where the phone is right now. With "only while using the app"
     * it counts the app as in use while it's open, while its animated wallpaper is on screen,
     * and, from Android 11 on, for a while after its widget is tapped; with "all the time", always.
     */
    fun canLocateNow(context: Context): Boolean {
        if (!hasPermission(context)) return false
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        // Not the raw setting ("while in use") but what it comes to at this moment.
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_COARSE_LOCATION, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Where the phone is, or was within [MAX_LAST_KNOWN_AGE_MS] when Android has nothing newer. */
    @SuppressLint("MissingPermission") // Checked by hasPermission().
    suspend fun current(context: Context): Fix? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }.filter { manager.isProviderEnabled(it) }

        // The passive provider only passes on other apps' fixes, so it isn't asked for one.
        val fresh = providers.firstOrNull { it != LocationManager.PASSIVE_PROVIDER }?.let { provider ->
            withTimeoutOrNull(15_000) {
                suspendCancellableCoroutine<AndroidLocation?> { cont ->
                    val signal = CancellationSignal()
                    // Before Android 11, the compat version asks for updates and stops at the first.
                    LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(context)) {
                        cont.resume(it)
                    }
                    // Only once asked: asking with a cancelled signal throws.
                    cont.invokeOnCancellation { signal.cancel() }
                }
            }
        }
        val location = fresh ?: providers
            .mapNotNull {
                try {
                    manager.getLastKnownLocation(it)
                } catch (_: SecurityException) {
                    // Up to Android 10, the passive provider is only for apps allowed precise location.
                    null
                }
            }
            .filter { ageMillis(it) <= MAX_LAST_KNOWN_AGE_MS }
            .minByOrNull(::ageMillis)
        return location?.let(::fix)
    }

    /** [location] rounded, found when its age says: unlike its own time, that doesn't jump when the clock is set. */
    fun fix(location: AndroidLocation) =
        Fix(Location.rounded(location.latitude, location.longitude), System.currentTimeMillis() - ageMillis(location))

    private fun ageMillis(location: AndroidLocation) =
        SystemClock.elapsedRealtime() - LocationCompat.getElapsedRealtimeMillis(location)

    /** The town or city at [location], for the widget, or `null` if the platform can't tell. */
    suspend fun placeName(context: Context, location: Location): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val address = withTimeoutOrNull(10_000) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(
                        location.latitude,
                        location.longitude,
                        1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<android.location.Address>) {
                                cont.resume(addresses.firstOrNull())
                            }

                            override fun onError(errorMessage: String?) = cont.resume(null)
                        },
                    )
                }
            } else {
                withContext(Dispatchers.IO) {
                    try {
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
                    } catch (_: IOException) {
                        null
                    }
                }
            }
        }
        return address?.let { it.locality ?: it.subAdminArea }
    }
}
