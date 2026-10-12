package io.github.intramuros.weatherbuddy.data

import io.github.intramuros.weatherbuddy.core.CustomStyle
import io.github.intramuros.weatherbuddy.core.ImageQuality
import io.github.intramuros.weatherbuddy.core.ScenePicture
import io.github.intramuros.weatherbuddy.core.Style
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomStyleStoreTest {
    private val root: File = Files.createTempDirectory("styles").toFile()
    private val store = CustomStyleStore(root)

    private fun style(slug: String, createdAt: Long) = CustomStyle(
        slug = slug,
        displayName = "Stained glass",
        summary = "",
        description = "Medium: a stained-glass window.",
        particles = CustomStyle.Particles.of(Style.WATERCOLOUR),
        quality = ImageQuality.MEDIUM,
        createdAtMillis = createdAt,
    )

    private val older = style("made-00000001", createdAt = 1)
    private val newer = style("made-00000002", createdAt = 2)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun aStyleIsCompleteOnceEveryPictureIsDrawn() {
        store.save(older)
        assertEquals(older, store.load(older.slug))
        assertEquals(ScenePicture.entries, store.missing(older))

        ScenePicture.entries.drop(1).forEach { store.savePicture(older, it, byteArrayOf(1)) }
        assertEquals(listOf(ScenePicture.entries.first()), store.missing(older))
        assertEquals(emptyList(), store.complete())

        store.savePicture(older, ScenePicture.entries.first(), byteArrayOf(1, 2))
        assertTrue(store.isComplete(older))
        assertEquals(listOf(older), store.complete())
        assertContentEquals(byteArrayOf(1, 2), store.file(older, ScenePicture.entries.first()).readBytes())
    }

    @Test
    fun listsTheOldestFirst() {
        store.save(newer)
        store.save(older)
        assertEquals(listOf(older, newer), store.all())
    }

    @Test
    fun aDeletedStyleStaysDeleted() {
        store.save(older)
        store.delete(older.slug)
        assertNull(store.load(older.slug))
        // A picture finishing after the style was deleted doesn't bring it back.
        assertFailsWith<IOException> { store.savePicture(older, ScenePicture.RAIN_COLD, byteArrayOf(1)) }
        store.setProblem(older, StyleProblem.NETWORK)
        assertFalse(File(root, older.slug).exists())
    }

    @Test
    fun remembersWhyDrawingStopped() {
        store.save(older)
        assertNull(store.problem(older))
        store.setProblem(older, StyleProblem.OTHER, "Your organization must be verified.\nSee the settings.")
        assertEquals(StyleProblem.OTHER to "Your organization must be verified.\nSee the settings.", store.problem(older))
        store.setProblem(older, null)
        assertNull(store.problem(older))
    }

    @Test
    fun onlyMadeStylesSlugsAreFolders() {
        File(root, "pixel-art").mkdirs()
        assertNull(store.load("pixel-art"))
        assertNull(store.load("../styles"))
        store.delete("..")
        assertTrue(root.exists())
        assertEquals(emptyList(), store.all())
    }
}
