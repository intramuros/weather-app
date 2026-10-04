package io.github.intramuros.weatherbuddy.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import io.github.intramuros.weatherbuddy.R
import io.github.intramuros.weatherbuddy.Refresher
import io.github.intramuros.weatherbuddy.WeatherUpdates
import io.github.intramuros.weatherbuddy.core.RenderPlan
import io.github.intramuros.weatherbuddy.core.Style
import io.github.intramuros.weatherbuddy.data.SettingsRepository
import io.github.intramuros.weatherbuddy.data.WeatherStore
import io.github.intramuros.weatherbuddy.render.Compositor
import io.github.intramuros.weatherbuddy.render.WidgetInfo
import io.github.intramuros.weatherbuddy.ui.MainActivity
import io.github.intramuros.weatherbuddy.work.RefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Shows the picture for the last known weather, temperature and wind included,
 * drawn in the widget's own shape so it fills it edge to edge;
 * [io.github.intramuros.weatherbuddy.Refresher] keeps it current. The button
 * in the corner refreshes it now.
 */
class WeatherWidget : GlanceAppWidget() {
    // Composed once per size the widget is shown at, so each gets a picture its shape.
    override val sizeMode: SizeMode = SizeMode.Exact

    /**
     * What the widget shows, and the [WeatherUpdates.version] it was loaded at.
     * Its pictures are drawn per widget size, when first needed.
     */
    private class Shown(val picture: Picture?, val description: String?, val version: Int) {
        /** By pixel size, so a smaller memory budget gets a smaller picture. */
        private val drawn = ConcurrentHashMap<Pair<Int, Int>, Bitmap>()

        fun drawnAt(size: DpSize, maxPixels: Int): Bitmap? = drawn[pictureSize(size.width.value, size.height.value, maxPixels)]

        suspend fun drawAt(context: Context, size: DpSize, maxPixels: Int): Bitmap? {
            val picture = picture ?: return null
            val pixels = pictureSize(size.width.value, size.height.value, maxPixels)
            drawn[pixels]?.let { return it }
            return withContext(Dispatchers.Default) {
                Compositor(context.assets).render(picture.plan, picture.style, pixels.first, pixels.second, picture.info)
            }.also { drawn[pixels] = it }
        }
    }

    private class Picture(val plan: RenderPlan, val style: Style, val info: WidgetInfo)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = load(context)
        // Draw the sizes it's on screen at now, so it never shows up without its picture.
        val sizes = GlanceAppWidgetManager(context).getAppWidgetSizes(id)
        val initialMaxPixels = maxPicturePixels(context, sizes.size)
        sizes.forEach { initial.drawAt(context, it, initialMaxPixels) }
        val initialRefreshing = RefreshWorker.tappedInProgress(context).first()
        provideContent {
            // While the widget is on screen it isn't rebuilt, so pick up new pictures here.
            val version by WeatherUpdates.version.collectAsState()
            val shown by produceState(initial, version) {
                if (version != initial.version) value = load(context)
            }
            // Keeps the last picture until the one for a new size or new weather is drawn.
            val size = LocalSize.current
            val image by produceState(shown.drawnAt(size, initialMaxPixels), shown, size) {
                val maxPixels = maxPicturePixels(context, GlanceAppWidgetManager(context).getAppWidgetSizes(id).size)
                value = shown.drawAt(context, size, maxPixels)
            }
            val refreshing by RefreshWorker.tappedInProgress(context).collectAsState(initialRefreshing)
            Content(image, shown.description, refreshing)
        }
    }

    private suspend fun load(context: Context): Shown = withContext(Dispatchers.IO) {
        val version = WeatherUpdates.version.value
        val conditions = WeatherStore(context).loadSnapshot()?.conditions ?: return@withContext Shown(null, null, version)
        val settings = SettingsRepository(context).current()
        val plan = RenderPlan.plan(conditions)
        val info = WidgetInfo.from(context, conditions, plan.scene, Refresher.placeName(context, settings))
        Shown(Picture(plan, settings.style, info), info.describe(context), version)
    }

    @Composable
    private fun Content(image: Bitmap?, description: String?, refreshing: Boolean) {
        val context = LocalContext.current
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(16.dp)
                .background(ColorProvider(day = Color(0xFF34436E), night = Color(0xFF222034)))
                .clickable(actionStartActivity<MainActivity>()),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                // Already the widget's shape; cropping only hides rounding.
                Image(
                    provider = ImageProvider(image),
                    contentDescription = description ?: context.getString(R.string.buddy_description),
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier.fillMaxSize(),
                )
            } else {
                Text(context.getString(R.string.widget_empty))
            }
            // Bottom right, where the scenes have ground rather than text.
            Box(
                modifier = GlanceModifier.fillMaxSize().padding(6.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                RefreshButton(refreshing)
            }
        }
    }

    @Composable
    private fun RefreshButton(refreshing: Boolean) {
        val context = LocalContext.current
        // Taps while refreshing are ignored by the worker, not passed on to open the app.
        Box(
            modifier = GlanceModifier
                .size(32.dp)
                .background(ImageProvider(R.drawable.widget_button_background))
                .clickable(actionRunCallback<RefreshAction>()),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(if (refreshing) R.drawable.ic_widget_refreshing else R.drawable.ic_widget_refresh),
                contentDescription = context.getString(if (refreshing) R.string.refreshing else R.string.refresh),
                modifier = GlanceModifier.size(20.dp),
            )
        }
    }
}

/** The widget's refresh button. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        RefreshWorker.runTapped(context)
        // An idle widget has nothing watching the work, so redraw it to show the dots.
        WeatherWidget().update(context, glanceId)
    }
}

/** The longer side of the widget's picture, in pixels, when memory allows. */
private const val PICTURE_SIDE = 800

/**
 * The most pixels each of a widget's [sizeCount] pictures may have. Android
 * rejects a widget update whose bitmaps take more memory than 1.5 screens
 * ([android.appwidget.AppWidgetManager.updateAppWidget]), and every size goes
 * in the same update; some room is kept for the rest of it.
 */
private fun maxPicturePixels(context: Context, sizeCount: Int): Int {
    val metrics = context.resources.displayMetrics
    val screenPixels = metrics.widthPixels.toLong() * metrics.heightPixels
    return (screenPixels * 1.5 * 0.9 / sizeCount.coerceAtLeast(1)).toInt()
}

/**
 * The widget picture's size in pixels for a widget [widthDp] × [heightDp]:
 * the same shape, [PICTURE_SIDE] pixels along its longer side, or smaller to
 * stay within [maxPixels].
 */
internal fun pictureSize(widthDp: Float, heightDp: Float, maxPixels: Int): Pair<Int, Int> {
    val (w, h) = if (widthDp <= 0f || heightDp <= 0f) 1f to 1f else widthDp to heightDp
    var scale = PICTURE_SIDE / maxOf(w, h)
    if (w * scale * h * scale > maxPixels) scale = sqrt(maxPixels / (w * h))
    return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
}

class WeatherWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeatherWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshWorker.runOnce(context)
    }
}
