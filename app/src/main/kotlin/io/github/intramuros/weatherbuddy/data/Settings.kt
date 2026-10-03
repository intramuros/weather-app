package io.github.intramuros.weatherbuddy.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

data class Location(val latitude: Double, val longitude: Double) {
    companion object {
        /** KNMI's home, roughly the middle of the Netherlands. */
        val DEFAULT = Location(52.10, 5.18)

        /** Rounds to ~1 km: plenty for weather, and kinder to privacy. */
        fun rounded(latitude: Double, longitude: Double) =
            Location((latitude * 100).roundToInt() / 100.0, (longitude * 100).roundToInt() / 100.0)
    }
}

data class Settings(
    val wallpaperHome: Boolean,
    val wallpaperLock: Boolean,
    /** `null` until the user shares their location; [Location.DEFAULT] is used meanwhile. */
    val location: Location?,
    /** Town or city of [location], if the platform could name it. */
    val placeName: String?,
    /** Identifies the picture last set as wallpaper, to avoid re-setting an identical one. */
    val wallpaperKey: String?,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    val settings: Flow<Settings> = store.data.map { prefs ->
        val lat = prefs[LATITUDE]
        val lon = prefs[LONGITUDE]
        Settings(
            wallpaperHome = prefs[WALLPAPER_HOME] ?: false,
            wallpaperLock = prefs[WALLPAPER_LOCK] ?: false,
            location = if (lat != null && lon != null) Location(lat, lon) else null,
            placeName = prefs[PLACE_NAME],
            wallpaperKey = prefs[WALLPAPER_KEY],
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setWallpaperHome(enabled: Boolean) = store.edit { it[WALLPAPER_HOME] = enabled }

    suspend fun setWallpaperLock(enabled: Boolean) = store.edit { it[WALLPAPER_LOCK] = enabled }

    suspend fun setLocation(location: Location, placeName: String?) = store.edit {
        it[LATITUDE] = location.latitude
        it[LONGITUDE] = location.longitude
        if (placeName != null) it[PLACE_NAME] = placeName else it.remove(PLACE_NAME)
    }

    suspend fun setWallpaperKey(key: String) = store.edit { it[WALLPAPER_KEY] = key }

    private companion object {
        val WALLPAPER_HOME = booleanPreferencesKey("wallpaper_home")
        val WALLPAPER_LOCK = booleanPreferencesKey("wallpaper_lock")
        val LATITUDE = doublePreferencesKey("latitude")
        val LONGITUDE = doublePreferencesKey("longitude")
        val WALLPAPER_KEY = stringPreferencesKey("wallpaper_key")
        val PLACE_NAME = stringPreferencesKey("place_name")
    }
}
