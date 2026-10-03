package io.github.intramuros.weatherbuddy.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
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
import io.github.intramuros.weatherbuddy.core.Scene
import io.github.intramuros.weatherbuddy.data.SettingsRepository
import io.github.intramuros.weatherbuddy.data.WeatherStore
import io.github.intramuros.weatherbuddy.render.WidgetInfo
import io.github.intramuros.weatherbuddy.ui.MainActivity
import io.github.intramuros.weatherbuddy.work.RefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Shows the last rendered picture, temperature and wind included;
 * [io.github.intramuros.weatherbuddy.Refresher] keeps it current. The button
 * in the corner refreshes it now.
 */
class WeatherWidget : GlanceAppWidget() {
    /** What the widget shows, and the [WeatherUpdates.version] it was loaded at. */
    private class Shown(val image: Bitmap?, val description: String?, val version: Int)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = load(context)
        val initialRefreshing = RefreshWorker.tappedInProgress(context).first()
        provideContent {
            // While the widget is on screen it isn't rebuilt, so pick up new pictures here.
            val version by WeatherUpdates.version.collectAsState()
            val shown by produceState(initial, version) {
                if (version != initial.version) value = load(context)
            }
            val refreshing by RefreshWorker.tappedInProgress(context).collectAsState(initialRefreshing)
            Content(shown, refreshing)
        }
    }

    private suspend fun load(context: Context): Shown = withContext(Dispatchers.IO) {
        val version = WeatherUpdates.version.value
        val store = WeatherStore(context)
        val conditions = store.loadSnapshot()?.conditions
        val place = Refresher.placeName(context, SettingsRepository(context).current())
        val description = conditions?.let { WidgetInfo.from(context, it, Scene.from(it), place).describe(context) }
        Shown(store.loadWidgetImage(), description, version)
    }

    @Composable
    private fun Content(shown: Shown, refreshing: Boolean) {
        val context = LocalContext.current
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(16.dp)
                .background(ColorProvider(day = Color(0xFF34436E), night = Color(0xFF222034)))
                .clickable(actionStartActivity<MainActivity>()),
            contentAlignment = Alignment.Center,
        ) {
            if (shown.image != null) {
                Image(
                    provider = ImageProvider(shown.image),
                    contentDescription = shown.description ?: context.getString(R.string.buddy_description),
                    contentScale = ContentScale.Fit,
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

class WeatherWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeatherWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshWorker.runOnce(context)
    }
}
