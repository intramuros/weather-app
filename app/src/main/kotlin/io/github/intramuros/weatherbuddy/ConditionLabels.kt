package io.github.intramuros.weatherbuddy

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.intramuros.weatherbuddy.core.Condition
import io.github.intramuros.weatherbuddy.core.SceneKind
import io.github.intramuros.weatherbuddy.core.TimeOfDay
import io.github.intramuros.weatherbuddy.core.Warmth

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

/** The icon for a condition, as in the hourly forecast; it stands for [Condition.labelRes]. */
@DrawableRes
fun Condition.iconRes(time: TimeOfDay): Int = when (this) {
    Condition.CLEAR -> if (time == TimeOfDay.DAY) R.drawable.ic_weather_clear_day else R.drawable.ic_weather_clear_night
    Condition.PARTLY_CLOUDY -> if (time == TimeOfDay.DAY) R.drawable.ic_weather_partly_cloudy_day else R.drawable.ic_weather_partly_cloudy_night
    Condition.CLOUDY -> R.drawable.ic_weather_cloudy
    Condition.FOG -> R.drawable.ic_weather_fog
    Condition.DRIZZLE -> R.drawable.ic_weather_drizzle
    Condition.RAIN -> R.drawable.ic_weather_rain
    Condition.HEAVY_RAIN -> R.drawable.ic_weather_heavy_rain
    Condition.SNOW -> R.drawable.ic_weather_snow
    Condition.HAIL -> R.drawable.ic_weather_hail
    Condition.THUNDERSTORM -> R.drawable.ic_weather_thunderstorm
    Condition.WINDY -> R.drawable.ic_weather_windy
}

/** The weather a picture shows, in the same words as [Condition.labelRes]. */
@StringRes
fun SceneKind.labelRes(time: TimeOfDay?): Int = when (this) {
    SceneKind.CLEAR -> if (time == TimeOfDay.NIGHT) R.string.condition_clear else R.string.condition_sunny
    SceneKind.PARTLY_CLOUDY -> R.string.condition_partly_cloudy
    SceneKind.CLOUDY -> R.string.condition_cloudy
    SceneKind.FOG -> R.string.condition_fog
    SceneKind.WINDY -> R.string.condition_windy
    SceneKind.RAIN -> R.string.condition_rain
    SceneKind.STORM -> R.string.condition_thunderstorm
    SceneKind.SNOW -> R.string.condition_snow
}

/** How warm it is in a picture, going by her outfit. */
@StringRes
fun Warmth.labelRes(): Int = when (this) {
    Warmth.FREEZING -> R.string.warmth_freezing
    Warmth.COLD -> R.string.warmth_cold
    Warmth.COOL -> R.string.warmth_cool
    Warmth.MILD -> R.string.warmth_mild
    Warmth.WARM -> R.string.warmth_warm
    Warmth.HOT -> R.string.warmth_hot
}
