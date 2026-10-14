package io.github.intramuros.weatherbuddy.widget

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.glance.appwidget.updateAll
import androidx.work.await
import io.github.intramuros.weatherbuddy.LocationFollower
import io.github.intramuros.weatherbuddy.R
import io.github.intramuros.weatherbuddy.Refresher
import io.github.intramuros.weatherbuddy.data.LocationProvider
import io.github.intramuros.weatherbuddy.work.RefreshWorker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The widget's refresh button: follows the phone if it has moved, then fetches the weather.
 *
 * The button starts it straight from the home screen, as a foreground service, so it runs at
 * once and to the end; work queued from a tap could wait for Android, or be frozen halfway
 * once the tap was handled, leaving the dots up. Started from a widget, it also counts as the
 * app in use, so Android says where the phone is with location allowed only while using the
 * app. Taps while it runs are ignored.
 */
class RefreshService : Service() {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.w(TAG, "refresh failed", e) },
    )
    private var running: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android expects every start from the button, a tap during a refresh too, to put it in the foreground.
        if (!goForeground()) {
            // Not allowed: the refresh still comes, as background work, without the dots or a lookup.
            // The service stays until the work is queued, so the app isn't ended before.
            if (running?.isActive != true) {
                running = scope.launch {
                    try {
                        RefreshWorker.runOnce(this@RefreshService).await()
                    } finally {
                        stopSelf()
                    }
                }
            }
            return START_NOT_STICKY
        }
        if (running?.isActive != true) {
            running = scope.launch {
                try {
                    refresh()
                } finally {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun refresh() {
        _refreshing.value = true
        try {
            // A widget Glance isn't running has nothing watching for the dots.
            WeatherWidget().updateAll(this)
            withTimeoutOrNull(LIMIT_MS) {
                if (LocationProvider.canLocateNow(this@RefreshService)) {
                    LocationFollower.follow(this@RefreshService, LocationFollower.MIN_GAP_MS)
                }
                Refresher.run(this@RefreshService, fetch = !Refresher.fetchedJustNow(this@RefreshService))
            }
        } finally {
            _refreshing.value = false
            // Glance stops redrawing the widget about 45 seconds after drawing it, so a slow refresh
            // needs it drawn again to clear the dots, however the refresh ended.
            withContext(NonCancellable) { WeatherWidget().updateAll(this@RefreshService) }
        }
    }

    /**
     * Puts the service in the foreground, able to look for the phone if location is allowed.
     * Returns false if Android won't have it in the foreground at all.
     */
    @SuppressLint("InlinedApi") // ServiceCompat leaves the type out before Android 10, which has none.
    private fun goForeground(): Boolean {
        val notification = notification()
        return try {
            if (LocationProvider.hasPermission(this)) {
                try {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                    return true
                } catch (e: SecurityException) {
                    // From Android 14, unless it counts as the app in use; it refreshes without looking then.
                    Log.w(TAG, "not allowed to look for the phone", e)
                }
            }
            // From Android 14, every foreground service has a type; a short one needs no permission of its own.
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            true
        } catch (e: IllegalStateException) {
            // From Android 12, only if Android counts the start as the user's, as a tap on a widget should be.
            Log.w(TAG, "not allowed in the foreground", e)
            false
        }
    }

    /** Silent, and from Android 12 only shown if the refresh takes more than ten seconds. */
    private fun notification(): Notification {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_MIN)
                .setName(getString(R.string.refresh_channel))
                .setShowBadge(false)
                .build(),
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_widget_refresh)
            .setContentTitle(getString(R.string.refresh_notification))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    // A short service gets about three minutes, far more than the refresh is given.
    override fun onTimeout(startId: Int) = stopSelf()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RefreshService"
        private const val CHANNEL = "refresh"
        private const val NOTIFICATION_ID = 1

        /** Room for a lookup, a place name and a slow fetch; the dots never stay up longer. */
        private const val LIMIT_MS = 60_000L

        private val _refreshing = MutableStateFlow(false)

        /** Whether the button's refresh is under way; the widget shows dots meanwhile. */
        val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    }
}
