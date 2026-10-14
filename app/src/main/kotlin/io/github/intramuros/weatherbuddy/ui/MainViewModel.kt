package io.github.intramuros.weatherbuddy.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.intramuros.weatherbuddy.LocationFollower
import io.github.intramuros.weatherbuddy.LocationWatch
import io.github.intramuros.weatherbuddy.R
import io.github.intramuros.weatherbuddy.RefreshResult
import io.github.intramuros.weatherbuddy.Refresher
import io.github.intramuros.weatherbuddy.core.ArtStyle
import io.github.intramuros.weatherbuddy.core.CustomStyle
import io.github.intramuros.weatherbuddy.core.RenderPlan
import io.github.intramuros.weatherbuddy.core.ScenePicture
import io.github.intramuros.weatherbuddy.core.Style
import io.github.intramuros.weatherbuddy.data.CustomStyleStore
import io.github.intramuros.weatherbuddy.data.Location
import io.github.intramuros.weatherbuddy.data.LocationProvider
import io.github.intramuros.weatherbuddy.data.Settings
import io.github.intramuros.weatherbuddy.data.SettingsRepository
import io.github.intramuros.weatherbuddy.data.StyleProblem
import io.github.intramuros.weatherbuddy.data.WeatherSnapshot
import io.github.intramuros.weatherbuddy.data.WeatherStore
import io.github.intramuros.weatherbuddy.render.Compositor
import io.github.intramuros.weatherbuddy.wallpaper.BuddyWallpaperService
import io.github.intramuros.weatherbuddy.work.StyleWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val settings: Settings? = null,
    val snapshot: WeatherSnapshot? = null,
    /** The styles that can be picked; the picker only shows when there's a choice. */
    val styles: List<ArtStyle> = emptyList(),
    /** The current weather drawn in every available style, by slug, for the style picker. */
    val thumbnails: Map<String, Bitmap> = emptyMap(),
    /** Made styles whose pictures aren't all drawn yet. */
    val unfinished: List<UnfinishedStyle> = emptyList(),
    /** Whether our animated wallpaper is the current home screen wallpaper. */
    val liveWallpaperActive: Boolean = false,
    /** Whether location is allowed at all, and all the time. */
    val locationAllowed: Boolean = false,
    val backgroundLocationAllowed: Boolean = false,
    val busy: Boolean = false,
    @param:StringRes val message: Int? = null,
)

/** A made style with pictures still to draw: being drawn, waiting to be, or stopped. */
data class UnfinishedStyle(
    val style: CustomStyle,
    val done: Int,
    /** Being drawn, or waiting for the network to be. */
    val drawing: Boolean,
    /** Why drawing stopped, with OpenAI's own words; `null` while drawing, or if the user stopped it. */
    val problem: StyleProblem?,
    val problemMessage: String,
)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val settingsRepo = SettingsRepository(app)
    private val store = WeatherStore(app)
    private val customStyles = CustomStyleStore(app)

    /** The made styles being drawn, by slug, with how many pictures are done. */
    private var drawing: Map<String, Int?> = emptyMap()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** The picture the style thumbnails show, and the styles they show it in. */
    private var thumbnailPicture: ScenePicture? = null
    private var thumbnailStyles: List<ArtStyle> = emptyList()
    private var thumbnailJob: Job? = null

    /** The refresh and the quiet location check each time the app comes into view. */
    private var opening: Job? = null

    init {
        viewModelScope.launch { settingsRepo.settings.collect { s -> _state.update { it.copy(settings = s) } } }
        // A made style's run starting, making progress or ending.
        viewModelScope.launch {
            StyleWorker.drawing(app).collect {
                drawing = it
                loadStyles()
            }
        }
    }

    /**
     * Call each time the app comes into view, not only when it's first opened:
     * shows what the background refresh fetched meanwhile, fetches if that's
     * old or for another place, then follows the phone if it has moved.
     */
    fun onStart() {
        if (opening?.isActive == true) {
            // Still looking for the phone since the last start, which read the weather before
            // the app was left; what the background refresh fetched since then shows anyway.
            viewModelScope.launch { loadFromDisk() }
            return
        }
        opening = viewModelScope.launch {
            loadFromDisk()
            val snapshot = state.value.snapshot
            val age = snapshot?.let { System.currentTimeMillis() - it.fetchedAtMillis }
            val place = settingsRepo.current().location
            // A new place can be saved while its weather failed to come, leaving the old place's.
            val elsewhere = snapshot?.location != (place ?: Location.DEFAULT)
            // Older snapshots need hourly forecasts even when their weather is still fresh.
            val incomplete = snapshot?.conditions?.hourly?.isEmpty() == true
            if (age == null || age > STALE_AFTER_MS || elsewhere || incomplete) refreshNow(fetch = true)
            // Finding the phone can take many seconds, so the saved place's weather comes first.
            val changed = LocationFollower.follow(app, LocationFollower.MIN_GAP_MS, inApp = true)
            when {
                // Moved by this lookup, or by the wallpaper's while this one waited for it.
                settingsRepo.current().location != place -> refreshNow(fetch = true)
                // The weather for an unchanged place is already up; a newly found name only needs the widget redrawn.
                changed -> refreshNow(fetch = false)
            }
        }
    }

    fun selectStyle(style: ArtStyle) = viewModelScope.launch {
        settingsRepo.setStyle(style)
        refreshNow(fetch = false)
    }

    /** Carries on drawing a made style's pictures. */
    fun continueStyle(style: CustomStyle) = StyleWorker.start(app, style)

    fun stopStyle(style: CustomStyle) = StyleWorker.stop(app, style.slug)

    fun deleteStyle(style: CustomStyle) = viewModelScope.launch {
        StyleWorker.stop(app, style.slug)
        if (state.value.settings?.style?.slug == style.slug) settingsRepo.setStyle(Style.PIXEL_ART)
        withContext(Dispatchers.IO) { customStyles.delete(style.slug) }
        loadStyles()
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
    fun useMyLocation() = viewModelScope.launch {
        // The tap's own lookup takes over from the opening's, so the two can't both save a place.
        opening?.cancelAndJoin()
        updateLocation()
    }

    fun refresh() = viewModelScope.launch { refreshNow(fetch = true) }

    fun setFollowInBackground(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setFollowInBackground(enabled)
        LocationWatch.sync(app)
    }

    /** Call when returning to the app, e.g. from the system wallpaper screen, a permission prompt or the app's settings. */
    fun checkSystemSettings() {
        _state.update {
            it.copy(
                liveWallpaperActive = BuddyWallpaperService.isActive(app),
                locationAllowed = LocationProvider.hasPermission(app),
                backgroundLocationAllowed = LocationProvider.hasBackgroundPermission(app),
            )
        }
        // Location may have been allowed all the time, or no longer.
        viewModelScope.launch { LocationWatch.sync(app) }
    }

    private suspend fun updateLocation() {
        _state.update { it.copy(busy = true) }
        val location = LocationProvider.current(app)
        if (location == null) {
            _state.update { it.copy(busy = false, message = R.string.error_location) }
            return
        }
        settingsRepo.setLocation(location, LocationProvider.placeName(app, location), System.currentTimeMillis())
        refreshNow(fetch = true)
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

    /** The made styles still being drawn, and the ones that can be picked. */
    private suspend fun loadStyles() {
        val unfinished = withContext(Dispatchers.IO) {
            customStyles.all().filterNot(customStyles::isComplete).map { style ->
                val running = style.slug in drawing
                val problem = if (running) null else customStyles.problem(style)
                UnfinishedStyle(
                    style = style,
                    done = drawing[style.slug] ?: (ScenePicture.entries.size - customStyles.missing(style).size),
                    drawing = running,
                    problem = problem?.first,
                    problemMessage = problem?.second.orEmpty(),
                )
            }
        }
        _state.update { it.copy(unfinished = unfinished) }
        loadFromDisk()
    }

    private suspend fun loadFromDisk() {
        val (snapshot, styles) = withContext(Dispatchers.IO) { store.loadSnapshot() to settingsRepo.availableStyles() }
        // The last weather shows straight away; the thumbnails follow.
        _state.update { it.copy(snapshot = snapshot, styles = styles) }
        if (snapshot == null) {
            _state.update { it.copy(thumbnails = emptyMap()) }
            return
        }
        val plan = RenderPlan.plan(snapshot.conditions)
        // A thumbnail shows only the picture, so it stays as long as the picture and the styles do.
        // Drawing a dozen of them takes a moment, so it runs alongside and a refresh doesn't wait for it.
        if (styles.size > 1 && (plan.picture != thumbnailPicture || styles != thumbnailStyles)) {
            thumbnailPicture = plan.picture
            thumbnailStyles = styles
            thumbnailJob?.cancel()
            thumbnailJob = viewModelScope.launch {
                val compositor = Compositor(app)
                val thumbnails = withContext(Dispatchers.Default) {
                    styles.associate {
                        ensureActive() // Stops between styles once a newer picture has taken over.
                        it.slug to compositor.render(plan, it, THUMB_WIDTH, THUMB_HEIGHT)
                    }
                }
                _state.update { it.copy(thumbnails = thumbnails) }
            }
        }
    }

    private companion object {
        const val STALE_AFTER_MS = 10 * 60 * 1000L
        const val THUMB_WIDTH = 216
        const val THUMB_HEIGHT = 480
    }
}
