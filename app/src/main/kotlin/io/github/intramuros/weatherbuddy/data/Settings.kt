package io.github.intramuros.weatherbuddy.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.intramuros.weatherbuddy.PlaceHint
import io.github.intramuros.weatherbuddy.core.ArtStyle
import io.github.intramuros.weatherbuddy.core.ScenePicture
import io.github.intramuros.weatherbuddy.core.Style
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
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
    /** Always one of [SettingsRepository.availableStyles]. */
    val style: ArtStyle,
    val wallpaperHome: Boolean,
    val wallpaperLock: Boolean,
    /** `null` until the user shares their location; [Location.DEFAULT] is used meanwhile. */
    val location: Location?,
    /** Town or city of [location], if the platform could name it. */
    val placeName: String?,
    /**
     * When the phone was last found at [location] (wall clock), so that a move Android reported
     * earlier, still waiting for the network, doesn't take its place; `null` before this was kept.
     */
    val locatedAt: Long?,
    /**
     * Whether the scheduled refresh looks for the phone too, not only the app while in use.
     * Needs location allowed all the time ([LocationProvider.hasBackgroundPermission]).
     */
    val followInBackground: Boolean,
    /** Whether the app has asked for location on its first opening, so it asks only then. */
    val askedForLocation: Boolean,
    /**
     * Whether to remind the user to turn location on: it's off, and the phone seems to have moved
     * ([io.github.intramuros.weatherbuddy.LocationReminder]).
     */
    val locationReminder: Boolean,
    /** Identifies the picture last set as wallpaper, to avoid re-setting an identical one. */
    val wallpaperKey: String?,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val app = context.applicationContext
    private val store = app.dataStore
    private val customStyles = CustomStyleStore(app)

    /**
     * The styles that can be picked: the app's own whose pictures are all there,
     * Pixel art first, then the complete ones the user made, oldest first. Reads
     * files; call off the main thread.
     */
    fun availableStyles(): List<ArtStyle> = shippedStyles(app) + customStyles.complete()

    val settings: Flow<Settings> = store.data.map { prefs ->
        val lat = prefs[LATITUDE]
        val lon = prefs[LONGITUDE]
        Settings(
            style = style(prefs[STYLE]),
            wallpaperHome = prefs[WALLPAPER_HOME] ?: false,
            wallpaperLock = prefs[WALLPAPER_LOCK] ?: false,
            location = if (lat != null && lon != null) Location(lat, lon) else null,
            placeName = prefs[PLACE_NAME],
            locatedAt = prefs.locatedAt(),
            followInBackground = prefs[FOLLOW_IN_BACKGROUND] ?: false,
            askedForLocation = prefs[ASKED_FOR_LOCATION] ?: false,
            locationReminder = prefs[LOCATION_REMINDER] ?: false,
            wallpaperKey = prefs[WALLPAPER_KEY],
        )
    }.flowOn(Dispatchers.IO) // A made style is read from its file.

    /** The style saved as [slug] if it can still be shown, Pixel art otherwise. */
    private fun style(slug: String?): ArtStyle =
        Style.fromSlug(slug)?.takeIf { it in shippedStyles(app) }
            ?: customStyles.load(slug)?.takeIf { customStyles.isComplete(it) }
            ?: Style.PIXEL_ART

    suspend fun current(): Settings = settings.first()

    suspend fun setStyle(style: ArtStyle) = store.edit { it[STYLE] = style.slug }

    suspend fun setWallpaperHome(enabled: Boolean) = store.edit { it[WALLPAPER_HOME] = enabled }

    suspend fun setWallpaperLock(enabled: Boolean) = store.edit { it[WALLPAPER_LOCK] = enabled }

    /**
     * Saves where the phone was found at [locatedAt] (wall clock), unless it has been found since: a
     * move Android reported can wait for the network, a lookup can fall back on an older fix, and
     * naming a place takes a while. Checked in the same edit, so no other save can come in between.
     * Returns whether it saved.
     */
    suspend fun setLocation(location: Location, placeName: String?, locatedAt: Long): Boolean {
        var saved = false
        store.edit {
            if ((it.locatedAt() ?: Long.MIN_VALUE) > locatedAt) return@edit
            it[LATITUDE] = location.latitude
            it[LONGITUDE] = location.longitude
            if (placeName != null) it[PLACE_NAME] = placeName else it.remove(PLACE_NAME)
            it[LOCATED_AT] = locatedAt
            saved = true
        }
        if (saved) foundHere()
        return saved
    }

    /**
     * If [location] is still the saved one, notes that the phone was found there at [locatedAt]
     * (wall clock), if that's later than the time already saved, so a move from before then can't
     * replace it. Returns whether it is.
     */
    suspend fun stillAt(location: Location, locatedAt: Long): Boolean {
        var still = false
        store.edit {
            val latitude = it[LATITUDE]
            val longitude = it[LONGITUDE]
            if (latitude == null || longitude == null || Location(latitude, longitude) != location) return@edit
            still = true
            if ((it.locatedAt() ?: Long.MIN_VALUE) < locatedAt) it[LOCATED_AT] = locatedAt
        }
        if (still) foundHere()
        return still
    }

    suspend fun setFollowInBackground(enabled: Boolean) = store.edit { it[FOLLOW_IN_BACKGROUND] = enabled }

    suspend fun setAskedForLocation() = store.edit { it[ASKED_FOR_LOCATION] = true }

    suspend fun setLocationReminder(enabled: Boolean) = store.edit { it[LOCATION_REMINDER] = enabled }

    /** Where the phone last seemed to be, without location; `null` before the first look. */
    suspend fun placeHint(): PlaceHint? {
        val prefs = store.data.first()
        return prefs[HINT_TIME_ZONE]?.let { PlaceHint(it, prefs[HINT_NETWORK_COUNTRY]) }
    }

    /**
     * The phone has just been found: the next trip, for reminding to turn location on
     * ([io.github.intramuros.weatherbuddy.LocationReminder]), is measured from here.
     */
    private suspend fun foundHere() = setPlaceHint(PlaceHint.current(app).keepingCountryOf(placeHint()))

    suspend fun setPlaceHint(hint: PlaceHint) = store.edit {
        it[HINT_TIME_ZONE] = hint.timeZone
        if (hint.networkCountry != null) it[HINT_NETWORK_COUNTRY] = hint.networkCountry else it.remove(HINT_NETWORK_COUNTRY)
    }

    suspend fun setWallpaperKey(key: String) = store.edit { it[WALLPAPER_KEY] = key }

    private companion object {
        /**
         * When the saved place was found, unless that's ahead of the clock: the clock was set back
         * since, and such a time would turn away every place found until it came round again.
         */
        fun Preferences.locatedAt(): Long? = this[LOCATED_AT]?.takeIf { it <= System.currentTimeMillis() }

        /** The app's assets don't change while it runs, so they're listed once. */
        @Volatile
        private var shipped: List<Style>? = null

        /** The app's own styles whose pictures are all in its assets, Pixel art first. */
        fun shippedStyles(context: Context): List<Style> = shipped ?: Style.entries.filter { style ->
            val files = context.assets.list("scenes/${style.slug}").orEmpty().toSet()
            ScenePicture.entries.all { style.scene(it).substringAfterLast('/') in files }
        }.also { shipped = it }

        val STYLE = stringPreferencesKey("style")
        val WALLPAPER_HOME = booleanPreferencesKey("wallpaper_home")
        val WALLPAPER_LOCK = booleanPreferencesKey("wallpaper_lock")
        val LATITUDE = doublePreferencesKey("latitude")
        val LONGITUDE = doublePreferencesKey("longitude")
        val WALLPAPER_KEY = stringPreferencesKey("wallpaper_key")
        val PLACE_NAME = stringPreferencesKey("place_name")
        val LOCATED_AT = longPreferencesKey("located_at")
        val FOLLOW_IN_BACKGROUND = booleanPreferencesKey("follow_in_background")
        val ASKED_FOR_LOCATION = booleanPreferencesKey("asked_for_location")
        val LOCATION_REMINDER = booleanPreferencesKey("location_reminder")
        val HINT_TIME_ZONE = stringPreferencesKey("hint_time_zone")
        val HINT_NETWORK_COUNTRY = stringPreferencesKey("hint_network_country")
    }
}
