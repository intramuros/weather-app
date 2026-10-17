package io.github.intramuros.weatherbuddy

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import io.github.intramuros.weatherbuddy.data.LocationProvider
import io.github.intramuros.weatherbuddy.data.SettingsRepository
import io.github.intramuros.weatherbuddy.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * Reminds the user to turn location on when it's off, not allowed or switched off for the whole
 * phone, and the phone seems to be somewhere else. With location off the app can't tell where the
 * phone is, so it goes by what Android says without it: the time zone and the mobile network's
 * country ([PlaceHint]). That catches a trip abroad, not a move within a country.
 */
object LocationReminder {
    private const val CHANNEL = "location-reminder"
    private const val NOTIFICATION_ID = 2

    /** Whether the app can't look for the phone: location isn't allowed, or is off for the whole phone. */
    fun isOff(context: Context): Boolean {
        if (!LocationProvider.hasPermission(context)) return true
        val manager = context.getSystemService(LocationManager::class.java) ?: return true
        return !LocationManagerCompat.isLocationEnabled(manager)
    }

    /**
     * Notes where the phone seems to be and, if location is off and that's somewhere new, has the app
     * show a reminder until it's dismissed or location is turned on; with [notify], also as a
     * notification, for when the app isn't open. Safe to call repeatedly.
     */
    suspend fun check(context: Context, notify: Boolean) {
        val app = context.applicationContext
        val settingsRepo = SettingsRepository(app)
        val last = settingsRepo.placeHint()
        val here = PlaceHint.current(app).keepingCountryOf(last)
        if (!isOff(app)) {
            // The app follows the phone by itself; the next trip is measured from here.
            settingsRepo.setPlaceHint(here)
            if (settingsRepo.current().locationReminder) settingsRepo.setLocationReminder(false)
            cancel(app)
            return
        }
        // Once per new place: the reminder already shown, or dismissed, doesn't come back until the next.
        settingsRepo.setPlaceHint(here)
        if (last == null || !here.isElsewhereFrom(last)) return
        settingsRepo.setLocationReminder(true)
        if (notify) notify(app)
    }

    /** The user said not now: no reminder until the phone seems to be somewhere else again. */
    suspend fun dismiss(context: Context) {
        SettingsRepository(context).setLocationReminder(false)
        cancel(context)
    }

    private fun notify(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.location_reminder_channel))
                .build(),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_weather_partly_cloudy_day)
            .setContentTitle(context.getString(R.string.location_reminder_title))
            .setContentText(context.getString(R.string.location_reminder_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.location_reminder_text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
}

/**
 * What Android says, without location, about where the phone is: its time zone, and the country of
 * the mobile network it's on, if any.
 */
data class PlaceHint(val timeZone: String, val networkCountry: String?) {
    /**
     * Whether the phone seems to be somewhere other than at [last]. A country only counts when both
     * are known: with no signal, or no SIM, there is none.
     */
    fun isElsewhereFrom(last: PlaceHint): Boolean =
        timeZone != last.timeZone ||
            (networkCountry != null && last.networkCountry != null && networkCountry != last.networkCountry)

    /**
     * This, with [last]'s country if this has none and the time zone is the same: arriving with no
     * signal isn't arriving in no country. Not across time zones, where the country may have changed
     * too, or it would count as somewhere new again once the signal came back.
     */
    fun keepingCountryOf(last: PlaceHint?): PlaceHint =
        if (networkCountry == null && last != null && last.timeZone == timeZone) copy(networkCountry = last.networkCountry) else this

    companion object {
        fun current(context: Context) = PlaceHint(TimeZone.getDefault().id, networkCountry(context))

        private fun networkCountry(context: Context): String? = try {
            context.getSystemService(TelephonyManager::class.java)?.networkCountryIso?.lowercase()?.takeIf { it.isNotEmpty() }
        } catch (_: UnsupportedOperationException) {
            // A phone without a mobile radio, such as a Wi-Fi-only tablet.
            null
        }
    }
}

/**
 * Hears when the phone's time zone changes, as it does on arriving in another one. Location being
 * turned on is noticed when the app comes into view or the scheduled refresh runs: Android doesn't
 * say so to apps that aren't running.
 */
class PlaceChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                LocationReminder.check(app, notify = true)
            } finally {
                pending.finish()
            }
        }
    }
}
