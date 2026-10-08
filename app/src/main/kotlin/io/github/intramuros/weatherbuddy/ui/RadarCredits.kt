package io.github.intramuros.weatherbuddy.ui

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString

/** Keep provider credits visible on the map and link to their attribution pages. */
internal fun radarCreditText(credit: String) = buildAnnotatedString {
    append(credit)
    for ((name, url) in listOf(
        "Buienradar" to "https://www.buienradar.nl",
        "OpenStreetMap" to "https://www.openstreetmap.org/copyright",
    )) {
        val start = credit.indexOf(name)
        if (start >= 0) addLink(LinkAnnotation.Url(url), start, start + name.length)
    }
}
