package io.github.intramuros.weatherbuddy.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.text.Text
import io.github.intramuros.weatherbuddy.R
import io.github.intramuros.weatherbuddy.core.Scene
import io.github.intramuros.weatherbuddy.data.WeatherStore
import io.github.intramuros.weatherbuddy.render.WidgetInfo
import io.github.intramuros.weatherbuddy.ui.MainActivity
import io.github.intramuros.weatherbuddy.work.RefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shows the last rendered picture, temperature and wind included;
 * [io.github.intramuros.weatherbuddy.Refresher] keeps it current.
 */
class WeatherWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (image, description) = withContext(Dispatchers.IO) {
            val store = WeatherStore(context)
            val conditions = store.loadSnapshot()?.conditions
            store.loadWidgetImage() to conditions?.let { WidgetInfo.from(context, it, Scene.from(it)).describe(context) }
        }
        provideContent { Content(image, description) }
    }

    @Composable
    private fun Content(image: Bitmap?, description: String?) {
        val context = LocalContext.current
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(16.dp)
                .background(ColorProvider(day = Color(0xFFBDE7F5), night = Color(0xFF222034)))
                .clickable(actionStartActivity<MainActivity>()),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(
                    provider = ImageProvider(image),
                    contentDescription = description ?: context.getString(R.string.buddy_description),
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier.fillMaxSize(),
                )
            } else {
                Text(context.getString(R.string.widget_empty))
            }
        }
    }
}

class WeatherWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeatherWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshWorker.runOnce(context)
    }

    /** The picture is drawn for the widget's shape, so redraw it after a resize. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        RefreshWorker.redraw(context)
    }
}
