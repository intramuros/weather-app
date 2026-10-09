package io.github.intramuros.weatherbuddy.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import io.github.intramuros.weatherbuddy.RefreshResult
import io.github.intramuros.weatherbuddy.Refresher
import io.github.intramuros.weatherbuddy.core.RefreshSchedule
import io.github.intramuros.weatherbuddy.data.Location
import io.github.intramuros.weatherbuddy.data.SettingsRepository
import io.github.intramuros.weatherbuddy.data.WeatherStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val result = Refresher.run(applicationContext, fetch = !freshEnough())
        if (inputData.getBoolean(KEY_CHAIN, false)) {
            // The chain is its own retry: the next run follows whatever this one found.
            val minutes = when (result) {
                is RefreshResult.Ok -> if (result.stale) RefreshSchedule.RETRY_MINUTES else RefreshSchedule.delayMinutes(result.snapshot.conditions)
                RefreshResult.NoData -> RefreshSchedule.RETRY_MINUTES
            }
            enqueueNext(applicationContext, minutes)
            return Result.success()
        }
        // A tap doesn't keep retrying in the background; tapping again is the retry.
        if (inputData.getBoolean(KEY_TAPPED, false)) return Result.success()
        return when (result) {
            is RefreshResult.Ok -> if (result.stale) Result.retry() else Result.success()
            RefreshResult.NoData -> Result.retry()
        }
    }

    /** Taps and the chain can land close together; weather fetched moments ago for this place is still current. */
    private suspend fun freshEnough(): Boolean {
        val snapshot = WeatherStore(applicationContext).loadSnapshot() ?: return false
        val place = SettingsRepository(applicationContext).current().location ?: Location.DEFAULT
        return snapshot.location == place && System.currentTimeMillis() - snapshot.fetchedAtMillis < MIN_FETCH_GAP_MS
    }

    companion object {
        private const val LEGACY_PERIODIC = "refresh"
        private const val CHAIN = "refresh-adaptive"
        private const val ONCE = "refresh-now"
        private const val TAPPED = "refresh-tapped"
        private const val KEY_TAPPED = "tapped"
        private const val KEY_CHAIN = "chain"
        private const val MIN_FETCH_GAP_MS = 2 * 60 * 1000L

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
         * For the widget's refresh button. Runs even offline, so the tap always
         * ends, showing the last known weather; taps while it runs are ignored.
         * Returns once the work is enqueued, so [tappedInProgress] already says so.
         */
        suspend fun runTapped(context: Context) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setInputData(workDataOf(KEY_TAPPED to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(TAPPED, ExistingWorkPolicy.KEEP, request).await()
        }

        /** Whether a [runTapped] refresh is waiting or running. */
        fun tappedInProgress(context: Context): Flow<Boolean> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(TAPPED)
                .map { infos -> infos.any { !it.state.isFinished } }
    }
}
