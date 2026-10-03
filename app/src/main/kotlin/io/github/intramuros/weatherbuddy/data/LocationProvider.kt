package io.github.intramuros.weatherbuddy.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
}
