package io.github.intramuros.weatherbuddy.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder
import io.github.intramuros.weatherbuddy.WeatherUpdates
import io.github.intramuros.weatherbuddy.core.Style
import io.github.intramuros.weatherbuddy.render.LiveRenderer
import io.github.intramuros.weatherbuddy.work.RefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The animated wallpaper. Draws only while visible, at the style's frame rate
 * (lower in battery saver), and picks up new weather as soon as the
 * background refresh has it.
 */
class BuddyWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = BuddyEngine()

    private inner class BuddyEngine : Engine(), Choreographer.FrameCallback {
        private val scope = MainScope()
        private val startNanos = System.nanoTime()
        private var renderer: LiveRenderer? = null
        private var style: Style? = null
        private var visible = false
        private var width = 0
        private var height = 0

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            scope.launch {
                WeatherUpdates.version.collect { reload() }
            }
        }

        private suspend fun reload() {
            val current = WeatherUpdates.currentPlan(this@BuddyWallpaperService)
            if (current == null) {
                RefreshWorker.runOnce(this@BuddyWallpaperService)
                return
            }
            val (plan, newStyle) = current
            renderer = withContext(Dispatchers.Default) { LiveRenderer(assets, plan, newStyle) }
            style = newStyle
            drawFrame()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            this.width = width
            this.height = height
            drawFrame()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            val choreographer = Choreographer.getInstance()
            choreographer.removeFrameCallback(this)
            if (visible) choreographer.postFrameCallback(this)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            Choreographer.getInstance().removeFrameCallback(this)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            Choreographer.getInstance().removeFrameCallback(this)
            scope.cancel()
            super.onDestroy()
        }

        override fun doFrame(frameTimeNanos: Long) {
            drawFrame()
            if (visible) Choreographer.getInstance().postFrameCallbackDelayed(this, frameDelayMs())
        }

        private fun frameDelayMs(): Long {
            val fps = style?.fps ?: 12
            val powerSave = getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
            return 1000L / (if (powerSave) minOf(fps, 6) else fps)
        }

        private fun drawFrame() {
            if (width == 0 || height == 0) return
            val holder = surfaceHolder
            val canvas = try {
                holder.lockHardwareCanvas()
            } catch (_: IllegalStateException) {
                null
            } ?: return
            try {
                canvas.drawColor(Color.BLACK)
                renderer?.draw(canvas, width, height, (System.nanoTime() - startNanos) / 1e9)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    companion object {
        fun component(context: Context) = ComponentName(context, BuddyWallpaperService::class.java)

        /** Whether our live wallpaper is the current home screen wallpaper. */
        fun isActive(context: Context): Boolean =
            try {
                WallpaperManager.getInstance(context).wallpaperInfo?.component == component(context)
            } catch (_: SecurityException) {
                false
            }

        /** Opens the system screen to apply the live wallpaper; the user has to confirm. */
        fun pickerIntent(context: Context): Intent =
            Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component(context))
    }
}
