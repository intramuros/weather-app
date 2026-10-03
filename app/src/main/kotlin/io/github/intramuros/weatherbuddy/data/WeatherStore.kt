package io.github.intramuros.weatherbuddy.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.intramuros.weatherbuddy.core.Conditions
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class WeatherSnapshot(val conditions: Conditions, val fetchedAtMillis: Long)

/**
 * The last fetched weather and the pictures rendered from it, kept as files so
 * the widget, the worker and the UI all see the same thing.
 */
class WeatherStore(context: Context) {
    private val dir = context.applicationContext.filesDir
    private val weatherFile = File(dir, "weather.json")
    private val widgetFile = File(dir, "widget.png")
    private val previewFile = File(dir, "preview.png")

    fun loadSnapshot(): WeatherSnapshot? =
        try {
            weatherFile.takeIf { it.exists() }?.readText()?.let { Json.decodeFromString<WeatherSnapshot>(it) }
        } catch (_: SerializationException) {
            null // Written by an older, incompatible version; refetch.
        } catch (_: IllegalArgumentException) {
            null
        }

    fun saveSnapshot(snapshot: WeatherSnapshot) = weatherFile.writeAtomically { it.writeText(Json.encodeToString(snapshot)) }

    fun loadWidgetImage(): Bitmap? = widgetFile.decode()

    fun loadPreview(): Bitmap? = previewFile.decode()

    fun saveImages(widget: Bitmap, preview: Bitmap) {
        widgetFile.writeAtomically { file -> file.outputStream().use { widget.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        previewFile.writeAtomically { file -> file.outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun File.decode(): Bitmap? = takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Readers never see a half-written file. */
    private fun File.writeAtomically(write: (File) -> Unit) {
        val tmp = File(parentFile, "$name.tmp")
        write(tmp)
        if (!tmp.renameTo(this)) {
            tmp.delete()
            error("could not replace $this")
        }
    }
}
