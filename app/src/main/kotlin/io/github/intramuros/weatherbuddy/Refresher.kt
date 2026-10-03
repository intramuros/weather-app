package io.github.intramuros.weatherbuddy

import android.app.WallpaperManager
import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import io.github.intramuros.weatherbuddy.core.RenderPlan
import io.github.intramuros.weatherbuddy.core.WeatherParseException
import io.github.intramuros.weatherbuddy.data.Location
import io.github.intramuros.weatherbuddy.data.SettingsRepository
import io.github.intramuros.weatherbuddy.data.WeatherClient
import io.github.intramuros.weatherbuddy.data.WeatherSnapshot
import io.github.intramuros.weatherbuddy.data.WeatherStore
import io.github.intramuros.weatherbuddy.render.Compositor
import io.github.intramuros.weatherbuddy.render.WidgetInfo
import io.github.intramuros.weatherbuddy.wallpaper.BuddyWallpaperService
import io.github.intramuros.weatherbuddy.widget.WeatherWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToInt

sealed interface RefreshResult {
    /** [stale] is true when fetching failed and the last known weather was used. */
    data class Ok(val snapshot: WeatherSnapshot, val stale: Boolean) : RefreshResult

    /** Nothing fetched yet and the network failed. */
    data object NoData : RefreshResult
}

/**
 * Fetch → plan → render → publish (widget and, if enabled, wallpaper).
 * Shared by the background worker and the settings screen.
 */
object Refresher {
    private const val TAG = "Refresher"

    /** Twice the scene's 300 pixels, so pixel art scales by a whole number. */
    private const val WIDGET_LONG_SIDE = 600
    const val PREVIEW_WIDTH = 540
    const val PREVIEW_HEIGHT = 1200

    private val mutex = Mutex()

    suspend fun run(context: Context, fetch: Boolean): RefreshResult = mutex.withLock {
        val app = context.applicationContext
        val settingsRepo = SettingsRepository(app)
        val settings = settingsRepo.current()
        val store = WeatherStore(app)

        var stale = false
        val snapshot = if (fetch) {
            try {
                val conditions = WeatherClient.fetch(settings.location ?: Location.DEFAULT)
                WeatherSnapshot(conditions, System.currentTimeMillis()).also { store.saveSnapshot(it) }
            } catch (e: IOException) {
                Log.w(TAG, "fetch failed", e)
                stale = true
                store.loadSnapshot()
            } catch (e: WeatherParseException) {
                Log.w(TAG, "fetch failed", e)
                stale = true
                store.loadSnapshot()
            }
        } else {
            store.loadSnapshot()
        } ?: return@withLock RefreshResult.NoData

        val plan = RenderPlan.plan(snapshot.conditions, settings.style)
        val compositor = Compositor(app.assets)
        val info = WidgetInfo.from(app, snapshot.conditions, plan.scene)
        val (widgetW, widgetH) = widgetPixels(widgetAspect(app))
        withContext(Dispatchers.Default) {
            val widget = compositor.render(plan, settings.style, widgetW, widgetH, Compositor.WIDGET_BUDDY_X, info)
            val preview = compositor.render(plan, settings.style, PREVIEW_WIDTH, PREVIEW_HEIGHT)
            withContext(Dispatchers.IO) { store.saveImages(widget, preview) }
        }
        WeatherWidget().updateAll(app)
        WeatherUpdates.notifyChanged()

        // A still home wallpaper would replace the animated one.
        val liveActive = BuddyWallpaperService.isActive(app)
        val flags = (if (settings.wallpaperHome && !liveActive) WallpaperManager.FLAG_SYSTEM else 0) or
            (if (settings.wallpaperLock) WallpaperManager.FLAG_LOCK else 0)
        if (flags != 0) {
            val metrics = app.resources.displayMetrics
            val (w, h) = metrics.widthPixels to metrics.heightPixels
            val key = "${settings.style.slug}|$flags|${w}x$h|${plan.mirrored}|${plan.stillLayers.joinToString(",")}"
            if (key != settings.wallpaperKey) {
                try {
                    val bitmap = withContext(Dispatchers.Default) { compositor.render(plan, settings.style, w, h) }
                    withContext(Dispatchers.IO) {
                        WallpaperManager.getInstance(app).setBitmap(bitmap, null, true, flags)
                    }
                    settingsRepo.setWallpaperKey(key)
                } catch (e: IOException) {
                    Log.w(TAG, "could not set wallpaper", e)
                }
            }
        }

        RefreshResult.Ok(snapshot, stale)
    }

    /**
     * Width / height of the placed widget, so the picture (and the text in it) isn't
     * cropped. With several widgets, the first one wins.
     */
    private suspend fun widgetAspect(context: Context): Float {
        val manager = GlanceAppWidgetManager(context)
        val size = manager.getGlanceIds(WeatherWidget::class.java).firstOrNull()
            ?.let { manager.getAppWidgetSizes(it).firstOrNull() }
        val aspect = size?.let { it.width.value / it.height.value }
        return if (aspect != null && aspect.isFinite() && aspect > 0f) aspect.coerceIn(0.5f, 3f) else 1f
    }

    private fun widgetPixels(aspect: Float): Pair<Int, Int> =
        if (aspect >= 1f) {
            WIDGET_LONG_SIDE to (WIDGET_LONG_SIDE / aspect).roundToInt()
        } else {
            (WIDGET_LONG_SIDE * aspect).roundToInt() to WIDGET_LONG_SIDE
        }
}
