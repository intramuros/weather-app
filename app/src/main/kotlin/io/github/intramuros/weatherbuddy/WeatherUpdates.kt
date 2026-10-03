package io.github.intramuros.weatherbuddy

import android.content.Context
import io.github.intramuros.weatherbuddy.core.RenderPlan
import io.github.intramuros.weatherbuddy.data.WeatherStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Tells the live wallpaper and the UI that a new picture is ready. */
object WeatherUpdates {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun notifyChanged() = _version.update { it + 1 }

    /** The plan for the last known weather, or `null` before the first fetch. */
    suspend fun currentPlan(context: Context): RenderPlan? {
        val snapshot = withContext(Dispatchers.IO) { WeatherStore(context).loadSnapshot() } ?: return null
        return RenderPlan.plan(snapshot.conditions)
    }
}
