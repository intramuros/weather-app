package io.github.intramuros.weatherbuddy.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import io.github.intramuros.weatherbuddy.RefreshResult
import io.github.intramuros.weatherbuddy.Refresher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val result = Refresher.run(applicationContext, fetch = true)
        // A tap doesn't keep retrying in the background; tapping again is the retry.
        if (inputData.getBoolean(KEY_TAPPED, false)) return Result.success()
        return when (result) {
            is RefreshResult.Ok -> if (result.stale) Result.retry() else Result.success()
            RefreshResult.NoData -> Result.retry()
        }
    }

    companion object {
        private const val PERIODIC = "refresh"
        private const val ONCE = "refresh-now"
        private const val TAPPED = "refresh-tapped"
        private const val KEY_TAPPED = "tapped"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Refreshes every 30 minutes while online. Safe to call repeatedly. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(online)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
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
