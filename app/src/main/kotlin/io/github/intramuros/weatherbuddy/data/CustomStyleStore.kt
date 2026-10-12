package io.github.intramuros.weatherbuddy.data

import android.content.Context
import android.util.Log
import io.github.intramuros.weatherbuddy.core.CustomStyle
import io.github.intramuros.weatherbuddy.core.ScenePicture
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * The styles the user made, one folder each in [root]:
 *
 * ```
 * <slug>/style.json        the CustomStyle
 * <slug>/<picture>.webp    each picture, once drawn
 * <slug>/problem           why drawing the rest stopped, if it did
 * ```
 *
 * Pictures are written whole or not at all, so a style is complete when every
 * picture's file is there. Reads and writes files; call off the main thread.
 */
class CustomStyleStore(private val root: File) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "styles"))

    /** Every made style, finished or not, oldest first. */
    fun all(): List<CustomStyle> =
        root.listFiles().orEmpty().mapNotNull { load(it.name) }.sortedBy { it.createdAtMillis }

    /** The made styles whose pictures are all there, oldest first. */
    fun complete(): List<CustomStyle> = all().filter { isComplete(it) }

    fun load(slug: String?): CustomStyle? {
        if (!CustomStyle.isSlug(slug)) return null
        val file = File(folder(slug!!), STYLE_FILE)
        return try {
            file.takeIf { it.exists() }?.readText()?.let { json.decodeFromString<CustomStyle>(it) }
        } catch (e: SerializationException) {
            Log.w(TAG, "unreadable $file", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "unreadable $file", e)
            null
        } catch (e: IOException) {
            Log.w(TAG, "unreadable $file", e)
            null
        }
    }

    /** Starts a style's folder, or updates its description. */
    fun save(style: CustomStyle) {
        val folder = folder(style.slug).apply { mkdirs() }
        File(folder, STYLE_FILE).writeAtomically { it.writeText(json.encodeToString(style)) }
    }

    fun file(style: CustomStyle, picture: ScenePicture) = File(folder(style.slug), "${picture.slug}.webp")

    /**
     * Saves a drawn picture. Fails if the style was deleted meanwhile, rather than
     * bringing it back.
     * @throws IOException if it can't be written.
     */
    fun savePicture(style: CustomStyle, picture: ScenePicture, webp: ByteArray) {
        if (!folder(style.slug).isDirectory) throw IOException("${style.slug} was deleted")
        try {
            file(style, picture).writeAtomically { it.writeBytes(webp) }
        } catch (e: IllegalStateException) {
            throw IOException(e.message, e)
        }
    }

    fun missing(style: CustomStyle): List<ScenePicture> {
        val files = folder(style.slug).list().orEmpty().toSet()
        return ScenePicture.entries.filter { "${it.slug}.webp" !in files }
    }

    fun isComplete(style: CustomStyle) = missing(style).isEmpty()

    /** Why drawing stopped last time, as the [StyleProblem] and OpenAI's own words; `null` if it didn't. */
    fun problem(style: CustomStyle): Pair<StyleProblem, String>? {
        val lines = File(folder(style.slug), PROBLEM_FILE).takeIf { it.exists() }?.readLines() ?: return null
        val problem = StyleProblem.entries.firstOrNull { it.name == lines.firstOrNull() } ?: return null
        return problem to lines.drop(1).joinToString("\n")
    }

    fun setProblem(style: CustomStyle, problem: StyleProblem?, message: String = "") {
        val folder = folder(style.slug)
        if (!folder.isDirectory) return // Deleted.
        val file = File(folder, PROBLEM_FILE)
        if (problem == null) file.delete() else file.writeAtomically { it.writeText("${problem.name}\n$message") }
    }

    fun delete(slug: String) {
        if (CustomStyle.isSlug(slug)) folder(slug).deleteRecursively()
    }

    private fun folder(slug: String) = File(root, slug)

    private companion object {
        const val TAG = "CustomStyleStore"
        const val STYLE_FILE = "style.json"
        const val PROBLEM_FILE = "problem"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Why a made style's pictures stopped being drawn. */
enum class StyleProblem {
    /** OpenAI doesn't accept the key (any more). */
    KEY,

    /** The OpenAI account has run out of credit. */
    QUOTA,

    /** OpenAI's safety system kept turning a picture down. */
    REJECTED,

    /** OpenAI couldn't be reached, or kept having trouble. */
    NETWORK,

    /** Anything else; OpenAI's message says what. */
    OTHER,
}
