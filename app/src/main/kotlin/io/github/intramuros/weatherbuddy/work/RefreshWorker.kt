package io.github.intramuros.weatherbuddy.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.intramuros.weatherbuddy.LocationFollower
import io.github.intramuros.weatherbuddy.RefreshResult
import io.github.intramuros.weatherbuddy.Refresher
import io.github.intramuros.weatherbuddy.core.RefreshSchedule
import io.github.intramuros.weatherbuddy.data.Location
import io.github.intramuros.weatherbuddy.data.LocationProvider
import io.github.intramuros.weatherbuddy.data.SettingsRepository
import java.util.concurrent.TimeUnit

/**
 * The scheduled refresh, and refreshes nobody waits for. The widget's button has
 * [io.github.intramuros.weatherbuddy.widget.RefreshService].
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Only if the user opted in; it may have been turned off since Android said the phone moved.
        if (SettingsRepository(applicationContext).current().followInBackground) {
            val moved = movedTo()
            // A move can wait for the network until it's too old to save, and Android won't say again while the
            // phone stays there; a lookup of its own finds it then.
            val saved = moved != null && LocationFollower.arrived(applicationContext, moved.first, timeMillis = moved.second)
            // With location allowed all the time, Android says where the phone is for any refresh.
            // Where it doesn't, asking would only keep the refresh waiting.
            if (!saved && LocationProvider.canLocateNow(applicationContext)) {
                LocationFollower.follow(applicationContext, LocationFollower.AUTO_GAP_MS)
            }
        }
        val result = Refresher.run(applicationContext, fetch = !Refresher.fetchedJustNow(applicationContext))
        if (inputData.getBoolean(KEY_CHAIN, false)) {
            // The chain is its own retry: the next run follows whatever this one found.
            val minutes = when (result) {
                is RefreshResult.Ok -> if (result.stale) RefreshSchedule.RETRY_MINUTES else RefreshSchedule.delayMinutes(result.snapshot.conditions)
                RefreshResult.NoData -> RefreshSchedule.RETRY_MINUTES
            }
            enqueueNext(applicationContext, minutes)
            return Result.success()
        }
        return when (result) {
            is RefreshResult.Ok -> if (result.stale) Result.retry() else Result.success()
            RefreshResult.NoData -> Result.retry()
        }
    }

    /** Where Android said the phone has moved to, and when it was there, for a refresh [runMoved] queued. */
    private fun movedTo(): Pair<Location, Long>? {
        val latitude = inputData.getDouble(KEY_LATITUDE, Double.NaN)
        val longitude = inputData.getDouble(KEY_LONGITUDE, Double.NaN)
        if (latitude.isNaN() || longitude.isNaN()) return null
        return Location(latitude, longitude) to inputData.getLong(KEY_TIME, 0L)
    }

    companion object {
        private const val LEGACY_PERIODIC = "refresh"
        private const val CHAIN = "refresh-adaptive"
        private const val ONCE = "refresh-now"
        private const val MOVED = "refresh-moved"
        private const val KEY_CHAIN = "chain"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_TIME = "time"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        private val onlineAndCharged = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()

        /**
         * Starts the refresh chain unless one is already waiting: each run decides when the next
         * one comes, from how near the rain is ([RefreshSchedule]). Safe to call repeatedly.
         */
        fun schedule(context: Context) {
            val work = WorkManager.getInstance(context)
            // Earlier versions refreshed on a fixed 30-minute period.
            work.cancelUniqueWork(LEGACY_PERIODIC)
            work.enqueueUniqueWork(CHAIN, ExistingWorkPolicy.KEEP, chainRequest(RefreshSchedule.DEFAULT_MINUTES))
        }

        private fun chainRequest(minutes: Long) = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(onlineAndCharged)
            .setInitialDelay(minutes, TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_CHAIN to true))
            .build()

        /** Appended, so the run that is enqueueing it isn't cancelled by its own successor. */
        private fun enqueueNext(context: Context, minutes: Long) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(CHAIN, ExistingWorkPolicy.APPEND_OR_REPLACE, chainRequest(minutes))
        }

        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * For [io.github.intramuros.weatherbuddy.LocationWatch]: saves the place the phone was in at
         * [timeMillis], with its name, then fetches its weather. A newer place replaces one still waiting.
         */
        fun runMoved(context: Context, location: Location, timeMillis: Long) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(online)
                .setInputData(
                    workDataOf(KEY_LATITUDE to location.latitude, KEY_LONGITUDE to location.longitude, KEY_TIME to timeMillis),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(MOVED, ExistingWorkPolicy.REPLACE, request)
        }

        /** Drops a [runMoved] refresh still waiting: the phone is back in the saved place. */
        fun cancelMoved(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(MOVED)
        }
    }
}
