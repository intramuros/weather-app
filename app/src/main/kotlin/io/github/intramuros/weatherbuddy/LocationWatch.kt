package io.github.intramuros.weatherbuddy

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.util.Log
import androidx.core.content.IntentCompat
import io.github.intramuros.weatherbuddy.data.Location
import io.github.intramuros.weatherbuddy.data.LocationProvider
import io.github.intramuros.weatherbuddy.data.SettingsRepository
import io.github.intramuros.weatherbuddy.work.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.location.Location as AndroidLocation

/**
 * With Follow me in the background on and location allowed all the time, has Android say when
 * the phone has moved about a kilometre, so the widget and wallpaper follow it between scheduled
 * refreshes. Android looks at most a few times an hour for an app in the background, and only in
 * ways that spare the battery, not with GPS.
 */
object LocationWatch {
    private const val TAG = "LocationWatch"
    private const val INTERVAL_MS = 15 * 60 * 1000L

    /** About where the rounded place ([Location.rounded]) can change. */
    private const val MIN_DISTANCE_M = 1000f

    /**
     * Starts or stops watching to match the setting and the permission. Safe to call repeatedly;
     * call it when the app starts, since Android forgets the request when the phone restarts, and
     * when either may have changed.
     */
    @SuppressLint("MissingPermission") // Checked by hasPermission() and hasBackgroundPermission().
    suspend fun sync(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(LocationManager::class.java) ?: return
        val intent = pendingIntent(app)
        val allowed = LocationProvider.hasPermission(app) && LocationProvider.hasBackgroundPermission(app)
        if (!allowed || !SettingsRepository(app).current().followInBackground) {
            manager.removeUpdates(intent)
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.hasProvider(LocationManager.FUSED_PROVIDER)) {
                val request = LocationRequest.Builder(INTERVAL_MS)
                    .setQuality(LocationRequest.QUALITY_LOW_POWER)
                    .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
                    .build()
                manager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, request, intent)
            } else if (LocationManager.NETWORK_PROVIDER in manager.allProviders) {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, INTERVAL_MS, MIN_DISTANCE_M, intent)
            }
        } catch (e: SecurityException) {
            // The permission went between checking and asking.
            Log.w(TAG, "not allowed to watch", e)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, MovedReceiver::class.java),
        // Android adds the location to it.
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
    )
}

/** Hears from [LocationWatch] where the phone is, and has a new place saved and its weather fetched. */
class MovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val fix = IntentCompat.getParcelableExtra(intent, LocationManager.KEY_LOCATION_CHANGED, AndroidLocation::class.java)
            ?: return
        val location = Location.rounded(fix.latitude, fix.longitude)
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Android also says where the phone is each time watching starts, which is mostly where it was.
                if (SettingsRepository(app).current().location != location) RefreshWorker.runMoved(app, location, fix.time)
            } finally {
                pending.finish()
            }
        }
    }
}
