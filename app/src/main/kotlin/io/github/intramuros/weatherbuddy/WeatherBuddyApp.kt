package io.github.intramuros.weatherbuddy

import android.app.Application
import android.net.http.HttpResponseCache
import android.util.Log
import io.github.intramuros.weatherbuddy.work.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

class WeatherBuddyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Honour tile Cache-Control/Expires headers and revalidate stale tiles with ETag/Last-Modified.
        try {
            HttpResponseCache.install(File(cacheDir, "http"), 50L * 1024 * 1024)
        } catch (e: IOException) {
            Log.w("WeatherBuddyApp", "HTTP cache unavailable", e)
        }
        RefreshWorker.schedule(this)
        // Android forgets what the app was watching for when the phone restarts.
        CoroutineScope(Dispatchers.Default).launch { LocationWatch.sync(this@WeatherBuddyApp) }
    }
}
