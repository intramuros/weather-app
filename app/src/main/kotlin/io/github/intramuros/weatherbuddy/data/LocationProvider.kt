package io.github.intramuros.weatherbuddy.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import android.location.Location as AndroidLocation

/**
 * Coarse location from the platform [LocationManager], without Google Play
 * services. Only called while the app is in the foreground; the background
 * refresh reuses the last saved location, so no background-location permission
 * is needed.
 */
object LocationProvider {
    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // Checked by hasPermission().
    suspend fun current(context: Context): Location? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }.filter { manager.isProviderEnabled(it) }

        val fresh = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            providers.firstOrNull()?.let { provider ->
                withTimeoutOrNull(15_000) {
                    suspendCancellableCoroutine<AndroidLocation?> { cont ->
                        manager.getCurrentLocation(provider, null, context.mainExecutor) { cont.resume(it) }
                    }
                }
            }
        } else {
            null
        }
        val location = fresh ?: providers
            .mapNotNull { manager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
        return location?.let { Location.rounded(it.latitude, it.longitude) }
    }

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
