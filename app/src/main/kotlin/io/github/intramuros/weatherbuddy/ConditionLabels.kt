package io.github.intramuros.weatherbuddy

import androidx.annotation.StringRes
import io.github.intramuros.weatherbuddy.core.Condition
import io.github.intramuros.weatherbuddy.core.TimeOfDay

/** The one-word label for a condition, as on the widget and in the forecast. */
@StringRes
fun Condition.labelRes(time: TimeOfDay): Int = when (this) {
    Condition.CLEAR -> if (time == TimeOfDay.DAY) R.string.condition_sunny else R.string.condition_clear
    Condition.PARTLY_CLOUDY -> R.string.condition_partly_cloudy
    Condition.CLOUDY -> R.string.condition_cloudy
    Condition.FOG -> R.string.condition_fog
    Condition.DRIZZLE -> R.string.condition_drizzle
    Condition.RAIN -> R.string.condition_rain
    Condition.HEAVY_RAIN -> R.string.condition_heavy_rain
    Condition.SNOW -> R.string.condition_snow
    Condition.HAIL -> R.string.condition_hail
    Condition.THUNDERSTORM -> R.string.condition_thunderstorm
    Condition.WINDY -> R.string.condition_windy
}
