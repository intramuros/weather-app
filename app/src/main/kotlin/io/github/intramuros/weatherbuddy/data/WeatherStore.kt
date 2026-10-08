package io.github.intramuros.weatherbuddy.data

import android.content.Context
import io.github.intramuros.weatherbuddy.core.Conditions
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class WeatherSnapshot(
    val conditions: Conditions,
    val fetchedAtMillis: Long,
    /** Where the weather is for; `null` in snapshots saved before this was kept. */
    val location: Location? = null,
)

/**
 * The last fetched weather, kept as a file so the widget, worker and UI all see the same thing.
 */
class WeatherStore(context: Context) {
    private val dir = context.applicationContext.filesDir
    private val weatherFile = File(dir, "weather.json")

    fun loadSnapshot(): WeatherSnapshot? =
        try {
            weatherFile.takeIf { it.exists() }?.readText()?.let { Json.decodeFromString<WeatherSnapshot>(it) }
        } catch (_: SerializationException) {
            null // Written by an older, incompatible version; refetch.
        } catch (_: IllegalArgumentException) {
            null
        }

    fun saveSnapshot(snapshot: WeatherSnapshot) = weatherFile.writeAtomically { it.writeText(Json.encodeToString(snapshot)) }

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
