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
import io.github.intramuros.weatherbuddy.RefreshResult
import io.github.intramuros.weatherbuddy.Refresher
import java.util.concurrent.TimeUnit

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when (val result = Refresher.run(applicationContext, fetch = true)) {
            is RefreshResult.Ok -> if (result.stale) Result.retry() else Result.success()
            RefreshResult.NoData -> Result.retry()
        }

    companion object {
        private const val PERIODIC = "refresh"
        private const val ONCE = "refresh-now"

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
    }
}
