package io.github.intramuros.weatherbuddy.work

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.intramuros.weatherbuddy.Refresher
import io.github.intramuros.weatherbuddy.core.CustomStyle
import io.github.intramuros.weatherbuddy.core.OpenAiException
import io.github.intramuros.weatherbuddy.core.ScenePicture
import io.github.intramuros.weatherbuddy.core.ScenePrompts
import io.github.intramuros.weatherbuddy.core.Style
import io.github.intramuros.weatherbuddy.data.ApiKeyStore
import io.github.intramuros.weatherbuddy.data.CustomStyleStore
import io.github.intramuros.weatherbuddy.data.OpenAiClient
import io.github.intramuros.weatherbuddy.data.SettingsRepository
import io.github.intramuros.weatherbuddy.data.StyleProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Draws a made style's missing pictures with the user's OpenAI key, a few at a
 * time. Each is drawn from its pixel-art scene and the style's reference
 * picture, so she looks the same in all of them. Every picture is saved as soon
 * as it's drawn, so a run that is stopped picks up where it left off. Once all
 * are there, the app switches to the style.
 */
class StyleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val store = CustomStyleStore(applicationContext)

    /** Why a picture couldn't be drawn; [fatal] if no other picture will fare better. */
    private class Failure(val problem: StyleProblem, val message: String) {
        val fatal get() = problem == StyleProblem.KEY || problem == StyleProblem.QUOTA || problem == StyleProblem.OTHER
    }

    override suspend fun doWork(): Result {
        val style = withContext(Dispatchers.IO) { store.load(inputData.getString(KEY_SLUG)) } ?: return Result.failure()
        val key = withContext(Dispatchers.IO) { ApiKeyStore(applicationContext).get() }
            ?: return stop(style, Failure(StyleProblem.KEY, ""))
        val missing = withContext(Dispatchers.IO) {
            store.setProblem(style, null)
            store.missing(style)
        }
        val done = AtomicInteger(ScenePicture.entries.size - missing.size)
        setProgress(workDataOf(KEY_DONE to done.get()))

        // She has to look the same in every picture, so the one the others copy comes first.
        if (CustomStyle.REFERENCE in missing) {
            draw(key, style, CustomStyle.REFERENCE, reference = null)?.let { return endWith(style, it) }
            setProgress(workDataOf(KEY_DONE to done.incrementAndGet()))
        }
        val reference = withContext(Dispatchers.IO) { store.file(style, CustomStyle.REFERENCE).readBytes() }

        val failure = AtomicReference<Failure?>(null)
        val permits = Semaphore(PARALLEL)
        coroutineScope {
            for (picture in missing - CustomStyle.REFERENCE) {
                launch {
                    permits.withPermit {
                        if (failure.get()?.fatal == true) return@withPermit
                        val problem = draw(key, style, picture, reference)
                        if (problem == null) {
                            setProgress(workDataOf(KEY_DONE to done.incrementAndGet()))
                        } else {
                            // A problem that ends the run outranks one that only cost a picture.
                            failure.updateAndGet { if (it?.fatal == true) it else problem }
                        }
                    }
                }
            }
        }
        failure.get()?.let { return endWith(style, it) }

        SettingsRepository(applicationContext).setStyle(style)
        Refresher.run(applicationContext, fetch = false)
        return Result.success()
    }

    /** Waits and tries again if only the network failed; otherwise stops and says why. */
    private suspend fun endWith(style: CustomStyle, failure: Failure): Result =
        if (failure.problem == StyleProblem.NETWORK && runAttemptCount < MAX_RUNS) Result.retry() else stop(style, failure)

    private suspend fun stop(style: CustomStyle, failure: Failure): Result {
        withContext(Dispatchers.IO) { store.setProblem(style, failure.problem, failure.message) }
        return Result.failure(workDataOf(KEY_PROBLEM to failure.problem.name))
    }

    /** Draws and saves one picture, trying again after a hiccup; returns why it couldn't, or `null`. */
    private suspend fun draw(key: String, style: CustomStyle, picture: ScenePicture, reference: ByteArray?): Failure? {
        val prompt = ScenePrompts.forPicture(style.description, picture)
        val scene = withContext(Dispatchers.IO) {
            applicationContext.assets.open(Style.PIXEL_ART.scene(picture)).use { it.readBytes() }
        }
        val images = listOfNotNull(scene, reference)
        var last = Failure(StyleProblem.NETWORK, "")
        var waitSeconds = 0L
        for (attempt in 0 until ATTEMPTS) {
            delay(TimeUnit.SECONDS.toMillis(minOf(waitSeconds * attempt, MAX_WAIT_SECONDS)))
            val webp = try {
                OpenAiClient.draw(key, prompt, images, style.quality)
            } catch (e: OpenAiException) {
                Log.w(TAG, "${picture.slug}: ${e.problem}")
                last = Failure(problemOf(e), e.message.orEmpty())
                waitSeconds = when (e.problem) {
                    OpenAiException.Problem.KEY, OpenAiException.Problem.QUOTA, OpenAiException.Problem.OTHER -> return last
                    OpenAiException.Problem.BUSY -> e.retryAfterSeconds ?: RETRY_SECONDS
                    OpenAiException.Problem.REJECTED, OpenAiException.Problem.SERVER -> RETRY_SECONDS
                }
                continue
            } catch (e: IOException) {
                Log.w(TAG, "${picture.slug}: ${e.javaClass.simpleName}")
                last = Failure(StyleProblem.NETWORK, "")
                waitSeconds = RETRY_SECONDS
                continue
            }
            if (!isPicture(webp)) {
                last = Failure(StyleProblem.NETWORK, "")
                waitSeconds = RETRY_SECONDS
                continue
            }
            return try {
                withContext(Dispatchers.IO) { store.savePicture(style, picture, webp) }
                null
            } catch (e: IOException) {
                // The style was deleted while it was being drawn.
                Failure(StyleProblem.OTHER, e.message.orEmpty())
            }
        }
        return last
    }

    private fun isPicture(bytes: ByteArray): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    private fun problemOf(e: OpenAiException) = when (e.problem) {
        OpenAiException.Problem.KEY -> StyleProblem.KEY
        OpenAiException.Problem.QUOTA -> StyleProblem.QUOTA
        OpenAiException.Problem.REJECTED -> StyleProblem.REJECTED
        OpenAiException.Problem.BUSY, OpenAiException.Problem.SERVER -> StyleProblem.NETWORK
        OpenAiException.Problem.OTHER -> StyleProblem.OTHER
    }

    companion object {
        private const val TAG = "StyleWorker"
        private const val WORK_TAG = "make-style"
        private const val KEY_SLUG = "slug"
        private const val KEY_DONE = "done"
        private const val KEY_PROBLEM = "problem"

        /** OpenAI's image limits are low for new accounts; three at once still takes only minutes. */
        private const val PARALLEL = 3
        private const val ATTEMPTS = 4
        private const val RETRY_SECONDS = 15L
        private const val MAX_WAIT_SECONDS = 120L

        /** Runs that only failed for the network, before giving up. */
        private const val MAX_RUNS = 5

        /** Draws [style]'s missing pictures, unless that's already under way. */
        fun start(context: Context, style: CustomStyle) {
            val request = OneTimeWorkRequestBuilder<StyleWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_SLUG to style.slug))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .addTag(WORK_TAG)
                .addTag(slugTag(style.slug))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(style.slug), ExistingWorkPolicy.KEEP, request)
        }

        fun stop(context: Context, slug: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(slug))
        }

        /**
         * The made styles being drawn or waiting to be, by slug, with how many of their
         * pictures are done once the run has said. Changes whenever a run starts or ends.
         */
        fun drawing(context: Context): Flow<Map<String, Int?>> =
            WorkManager.getInstance(context).getWorkInfosByTagFlow(WORK_TAG).map { infos ->
                infos.filter { !it.state.isFinished }.mapNotNull { info ->
                    val slug = info.tags.firstNotNullOfOrNull { it.removePrefix("$WORK_TAG:").takeIf(CustomStyle::isSlug) }
                    slug?.let { it to info.progress.getInt(KEY_DONE, -1).takeIf { done -> done >= 0 } }
                }.toMap()
            }

        private fun workName(slug: String) = "$WORK_TAG-$slug"

        private fun slugTag(slug: String) = "$WORK_TAG:$slug"
    }
}
