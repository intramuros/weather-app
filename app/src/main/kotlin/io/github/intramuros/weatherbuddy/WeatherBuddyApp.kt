package io.github.intramuros.weatherbuddy

import android.app.Application
import io.github.intramuros.weatherbuddy.work.RefreshWorker

class WeatherBuddyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RefreshWorker.schedule(this)
    }
}
