package io.github.intramuros.weatherbuddy.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.intramuros.weatherbuddy.R
import io.github.intramuros.weatherbuddy.RefreshResult
import io.github.intramuros.weatherbuddy.Refresher
import io.github.intramuros.weatherbuddy.core.RenderPlan
import io.github.intramuros.weatherbuddy.core.ScenePicture
import io.github.intramuros.weatherbuddy.core.Style
import io.github.intramuros.weatherbuddy.data.LocationProvider
import io.github.intramuros.weatherbuddy.data.Settings
import io.github.intramuros.weatherbuddy.data.SettingsRepository
import io.github.intramuros.weatherbuddy.data.WeatherSnapshot
import io.github.intramuros.weatherbuddy.data.WeatherStore
import io.github.intramuros.weatherbuddy.render.Compositor
import io.github.intramuros.weatherbuddy.render.LiveRenderer
import io.github.intramuros.weatherbuddy.wallpaper.BuddyWallpaperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val settings: Settings? = null,
    val snapshot: WeatherSnapshot? = null,
    val preview: Bitmap? = null,
    /** The styles that can be picked; the picker only shows when there's a choice. */
    val styles: List<Style> = emptyList(),
    /** The current weather drawn in every available style, for the style picker. */
    val thumbnails: Map<Style, Bitmap> = emptyMap(),
    /** Draws the animated preview. */
    val live: LiveRenderer? = null,
    /** Whether our animated wallpaper is the current home screen wallpaper. */
    val liveWallpaperActive: Boolean = false,
    val busy: Boolean = false,
    @param:StringRes val message: Int? = null,
)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val settingsRepo = SettingsRepository(app)
    private val store = WeatherStore(app)

    private val _state = MutableStateFlow(UiState())

    /** The picture the style thumbnails show. */
    private var thumbnailPicture: ScenePicture? = null
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { settingsRepo.settings.collect { s -> _state.update { it.copy(settings = s) } } }
        viewModelScope.launch {
            loadFromDisk()
            val snapshot = state.value.snapshot
            val age = snapshot?.let { System.currentTimeMillis() - it.fetchedAtMillis }
            // Older snapshots need hourly forecasts even when their weather is still fresh.
            refreshNow(fetch = age == null || age > STALE_AFTER_MS || snapshot?.conditions?.hourly?.isEmpty() == true)
            // Finding the phone can take many seconds, so the saved place's weather comes first.
            if (LocationProvider.hasPermission(app)) followLocation()
        }
    }

    fun selectStyle(style: Style) = viewModelScope.launch {
        settingsRepo.setStyle(style)
        refreshNow(fetch = false)
    }

    fun setWallpaperHome(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setWallpaperHome(enabled)
        if (enabled) refreshNow(fetch = false)
    }

    fun setWallpaperLock(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setWallpaperLock(enabled)
        if (enabled) refreshNow(fetch = false)
    }

    /** Call after the location permission has been granted. */
    fun useMyLocation() = viewModelScope.launch { updateLocation() }

    fun refresh() = viewModelScope.launch { refreshNow(fetch = true) }

    /** Call when returning to the app, e.g. from the system wallpaper screen. */
    fun checkLiveWallpaper() = _state.update { it.copy(liveWallpaperActive = BuddyWallpaperService.isActive(app)) }

    private suspend fun updateLocation() {
        _state.update { it.copy(busy = true) }
        val location = LocationProvider.current(app)
        if (location == null) {
            _state.update { it.copy(busy = false, message = R.string.error_location) }
            return
        }
        settingsRepo.setLocation(location, LocationProvider.placeName(app, location))
        refreshNow(fetch = true)
    }

    /** Follows the phone to a new place, quietly: if it can't be found, the saved place stays. */
    private suspend fun followLocation() {
        val location = LocationProvider.current(app) ?: return
        val settings = settingsRepo.current()
        val moved = location != settings.location
        if (!moved && settings.placeName != null) return
        val placeName = LocationProvider.placeName(app, location)
        settingsRepo.setLocation(location, placeName)
        // The weather for an unchanged place is already up; a newly found name only needs the widget redrawn.
        if (moved) refreshNow(fetch = true) else if (placeName != null) refreshNow(fetch = false)
    }

    private suspend fun refreshNow(fetch: Boolean) {
        _state.update { it.copy(busy = true, message = null) }
        val message = when (val result = Refresher.run(app, fetch)) {
            is RefreshResult.Ok -> if (result.stale) R.string.error_offline else null
            RefreshResult.NoData -> R.string.no_data_yet
        }
        loadFromDisk()
        _state.update { it.copy(busy = false, message = message) }
    }

    private suspend fun loadFromDisk() {
        val (snapshot, preview) = withContext(Dispatchers.IO) { store.loadSnapshot() to store.loadPreview() }
        val styles = settingsRepo.availableStyles
        // The last weather shows straight away; the moving picture and the thumbnails follow.
        _state.update { it.copy(snapshot = snapshot, preview = preview, styles = styles) }
        if (snapshot == null) {
            _state.update { it.copy(thumbnails = emptyMap(), live = null) }
            return
        }
        val plan = RenderPlan.plan(snapshot.conditions)
        val style = settingsRepo.current().style
        val live = withContext(Dispatchers.Default) { LiveRenderer(app.assets, plan, style) }
        _state.update { it.copy(live = live) }
        // A thumbnail shows only the picture, so it stays as long as the picture does.
        if (styles.size > 1 && plan.picture != thumbnailPicture) {
            val compositor = Compositor(app.assets)
            val thumbnails = withContext(Dispatchers.Default) {
                styles.associateWith { compositor.render(plan, it, THUMB_WIDTH, THUMB_HEIGHT) }
            }
            thumbnailPicture = plan.picture
            _state.update { it.copy(thumbnails = thumbnails) }
        }
    }

    private companion object {
        const val STALE_AFTER_MS = 15 * 60 * 1000L
        const val THUMB_WIDTH = 216
        const val THUMB_HEIGHT = 480
    }
}
