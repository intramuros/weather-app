package io.github.intramuros.weatherbuddy.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.intramuros.weatherbuddy.R
import io.github.intramuros.weatherbuddy.data.WeatherStore
import io.github.intramuros.weatherbuddy.ui.MainActivity
import io.github.intramuros.weatherbuddy.work.RefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Shows the last rendered picture; [io.github.intramuros.weatherbuddy.Refresher] keeps it current. */
class WeatherWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (image, temperature) = withContext(Dispatchers.IO) {
            val store = WeatherStore(context)
            store.loadWidgetImage() to store.loadSnapshot()?.conditions?.temperatureC
        }
        provideContent { Content(image, temperature) }
    }

    @Composable
    private fun Content(image: Bitmap?, temperatureC: Double?) {
        val context = LocalContext.current
        val white = ColorProvider(day = Color.White, night = Color.White)
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(16.dp)
                .background(ColorProvider(day = Color(0xFFBDE7F5), night = Color(0xFF222034)))
                .clickable(actionStartActivity<MainActivity>()),
            contentAlignment = Alignment.BottomEnd,
        ) {
            if (image != null) {
                Image(
                    provider = ImageProvider(image),
                    contentDescription = context.getString(R.string.buddy_description),
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier.fillMaxSize(),
                )
            } else {
                Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(context.getString(R.string.widget_empty))
                }
            }
            if (temperatureC != null) {
                Text(
                    text = "${temperatureC.roundToInt()}°",
                    style = TextStyle(color = white, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    modifier = GlanceModifier
                        .padding(8.dp)
                        .cornerRadius(8.dp)
                        .background(ColorProvider(day = Color(0x66000000), night = Color(0x66000000)))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
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
}
