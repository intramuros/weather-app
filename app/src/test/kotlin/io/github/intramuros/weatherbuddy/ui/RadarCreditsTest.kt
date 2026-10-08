package io.github.intramuros.weatherbuddy.ui

import androidx.compose.ui.text.LinkAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals

class RadarCreditsTest {
    @Test
    fun providerNamesHaveSeparateClickableAttributionLinks() {
        // Prefixes may be translated; the provider names stay the same.
        val credit = "Neerslag: Buienradar · Kaart: © OpenStreetMap contributors"
        val text = radarCreditText(credit)
        assertEquals(credit, text.text)
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(listOf("Buienradar", "OpenStreetMap"), links.map { text.text.substring(it.start, it.end) })
        assertEquals(
            listOf("https://www.buienradar.nl", "https://www.openstreetmap.org/copyright"),
            links.map { (it.item as LinkAnnotation.Url).url },
        )
    }
}
