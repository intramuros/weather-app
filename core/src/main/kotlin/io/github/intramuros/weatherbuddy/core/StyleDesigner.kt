package io.github.intramuros.weatherbuddy.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.util.Base64
import java.util.Locale

/**
 * Designs a [CustomStyle] with the user: a short conversation with OpenAI's chat
 * model that ends in a proposed style, whose pictures OpenAI's image model then
 * draws from [ScenePrompts], each from its pixel-art scene. Both run on the
 * user's own API key. This is what is sent and how the answers are read; the
 * app does the sending.
 */
object StyleDesigner {
    const val CHAT_URL = "https://api.openai.com/v1/chat/completions"
    const val IMAGE_EDIT_URL = "https://api.openai.com/v1/images/edits"

    /** Lists the models: free, and only answers to a valid key. */
    const val KEY_CHECK_URL = "https://api.openai.com/v1/models"

    /** Answers in a few seconds; larger models take twice as long and write no better descriptions. */
    const val CHAT_MODEL = "gpt-5.4-mini"
    const val IMAGE_MODEL = "gpt-image-2"

    /** The form field each picture is sent in, the pixel-art scene first, then the style's reference. */
    const val IMAGE_FIELD = "image[]"

    /** A two-word name still fits under its card in the style picker. */
    const val MAX_NAME_LENGTH = 24

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Message(val role: Role, val content: String)

    @Serializable
    enum class Role {
        @SerialName("user")
        USER,

        @SerialName("assistant")
        ASSISTANT,
    }

    /** One answer: what the designer says and, once it has one, a style. */
    data class Turn(
        val reply: String,
        val proposal: Proposal?,
        /** The answer as it goes back into the conversation, so the designer sees its earlier proposals. */
        val message: Message,
    )

    /** A style as the designer proposes it. */
    @Serializable
    data class Proposal(
        val name: String,
        val summary: String,
        val description: String,
        val particles: ProposedParticles,
    ) {
        /** [name], short enough for the style picker: the name the style gets. */
        val displayName: String get() = shortName(name)

        /**
         * The style, made safe to use: a short name, and particles that the app can
         * draw, falling back to [ScenePrompts.EXAMPLE_STYLE]'s for any it can't read.
         */
        fun toStyle(slug: String, quality: ImageQuality, createdAtMillis: Long): CustomStyle {
            val fallback = CustomStyle.Particles.of(ScenePrompts.EXAMPLE_STYLE)
            return CustomStyle(
                slug = slug,
                displayName = displayName,
                summary = summary.trim(),
                description = description.trim(),
                particles = CustomStyle.Particles(
                    drop = particles.drop.toLook(fallback.drop),
                    flake = particles.flake.toLook(fallback.flake),
                    hailstone = particles.hailstone.toLook(fallback.hailstone),
                    streak = particles.streak.toLook(fallback.streak),
                    leaf = particles.leaf.toLook(fallback.leaf),
                ),
                quality = quality,
                createdAtMillis = createdAtMillis,
            )
        }
    }

    @Serializable
    data class ProposedParticles(
        val drop: ProposedLook,
        val flake: ProposedLook,
        val hailstone: ProposedLook,
        val streak: ProposedLook,
        val leaf: ProposedLook,
    )

    /** [color] is "#AARRGGBB"; [width] in [ArtCanvas] units, like [ParticleLook]. */
    @Serializable
    data class ProposedLook(val color: String, val width: Double) {
        fun toLook(fallback: ParticleLook) = ParticleLook(
            argb = parseColour(color) ?: fallback.argb,
            width = width.takeIf { it.isFinite() }?.coerceIn(MIN_WIDTH, MAX_WIDTH) ?: fallback.width,
        )
    }

    /** Thinner disappears on a phone; thicker covers the picture. */
    private const val MIN_WIDTH = 0.25
    private const val MAX_WIDTH = 1.0

    /** "#AARRGGBB", or "#RRGGBB" for opaque, as ARGB; `null` if it's neither. */
    internal fun parseColour(text: String): Int? {
        val hex = text.trim().removePrefix("#")
        if (hex.length != 6 && hex.length != 8 || !hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
        val value = hex.toLong(16)
        return (if (hex.length == 6) value or 0xFF000000 else value).toInt()
    }

    /** At most [MAX_NAME_LENGTH] characters, cut between words where possible. */
    internal fun shortName(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val fitting = words.runningReduce { acc, word -> "$acc $word" }.lastOrNull { it.length <= MAX_NAME_LENGTH }
        return (fitting ?: words.firstOrNull()?.take(MAX_NAME_LENGTH)).orEmpty()
    }

    /** The designer's opening line, as said by it, so it knows what it asked. */
    fun greeting(text: String) = Message(Role.ASSISTANT, json.encodeToString(Answer.serializer(), Answer(text)))

    /** The request body for the designer's answer to the last message in [conversation]. */
    fun chatRequest(conversation: List<Message>): String = buildJsonObject {
        put("model", CHAT_MODEL)
        put("reasoning_effort", "low")
        putJsonArray("messages") {
            addJsonObject {
                put("role", "developer")
                put("content", instructions)
            }
            for (message in conversation) add(json.encodeToJsonElement(Message.serializer(), message))
        }
        putJsonObject("response_format") {
            put("type", "json_schema")
            putJsonObject("json_schema") {
                put("name", "style_designer_answer")
                put("strict", true)
                put("schema", answerSchema)
            }
        }
    }.toString()

    /** @throws OpenAiException if the answer isn't one. */
    fun parseChat(body: String): Turn {
        val choice = decode<ChatResponse>(body).choices.firstOrNull() ?: throw unexpected("no answer")
        choice.message.refusal?.takeIf { it.isNotBlank() }?.let { return Turn(it, null, greeting(it)) }
        val content = choice.message.content ?: throw unexpected("an empty answer")
        val answer = decode<Answer>(content)
        return Turn(answer.reply.trim(), answer.proposal, Message(Role.ASSISTANT, content))
    }

    /** The form fields of an image edit; the pictures go with them as [IMAGE_FIELD] files. */
    fun imageFields(prompt: String, quality: ImageQuality): List<Pair<String, String>> = listOf(
        "model" to IMAGE_MODEL,
        "prompt" to prompt,
        "size" to "1024x1024",
        "quality" to quality.apiValue,
        "output_format" to "webp",
        // About the size of the built-in pictures, which look the same.
        "output_compression" to "85",
        "n" to "1",
    )

    /**
     * The drawn picture, as a WebP file.
     * @throws OpenAiException if there's none.
     */
    fun parseImage(body: String): ByteArray {
        val encoded = decode<ImageResponse>(body).data.firstOrNull()?.b64Json ?: throw unexpected("no picture")
        return try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw unexpected("a broken picture", e)
        }
    }

    /** What went wrong, from an error response's [status], body and `Retry-After` header. */
    fun error(status: Int, body: String?, retryAfter: String?): OpenAiException {
        val error = body?.let {
            try {
                json.decodeFromString<ErrorResponse>(it).error
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        val code = (error?.code as? JsonPrimitive)?.contentOrNull
        val problem = when {
            status == 401 -> OpenAiException.Problem.KEY
            code == "insufficient_quota" || code == "billing_hard_limit_reached" -> OpenAiException.Problem.QUOTA
            status == 429 -> OpenAiException.Problem.BUSY
            code == "moderation_blocked" || code == "content_policy_violation" -> OpenAiException.Problem.REJECTED
            status >= 500 -> OpenAiException.Problem.SERVER
            else -> OpenAiException.Problem.OTHER
        }
        val message = error?.message?.takeIf { it.isNotBlank() } ?: "OpenAI answered HTTP $status"
        return OpenAiException(problem, message, retryAfter?.trim()?.toLongOrNull(), status = status)
    }

    private inline fun <reified T> decode(body: String): T =
        try {
            json.decodeFromString<T>(body)
        } catch (e: SerializationException) {
            throw unexpected("an answer it couldn't read", e)
        } catch (e: IllegalArgumentException) {
            throw unexpected("an answer it couldn't read", e)
        }

    private fun unexpected(what: String, cause: Throwable? = null) =
        OpenAiException(OpenAiException.Problem.SERVER, "OpenAI sent $what", cause = cause)

    @Serializable
    private data class Answer(val reply: String, val proposal: Proposal? = null)

    @Serializable
    private data class ChatResponse(val choices: List<Choice>)

    @Serializable
    private data class Choice(val message: ChatMessage)

    @Serializable
    private data class ChatMessage(val content: String? = null, val refusal: String? = null)

    @Serializable
    private data class ImageResponse(val data: List<ImageData>)

    @Serializable
    private data class ImageData(@SerialName("b64_json") val b64Json: String? = null)

    @Serializable
    private data class ErrorResponse(val error: ErrorBody)

    @Serializable
    private data class ErrorBody(val message: String? = null, val code: JsonElement? = null)

    /** Strict, so every answer has this shape. */
    private val answerSchema: JsonObject by lazy {
        fun type(name: String) = buildJsonObject { put("type", name) }
        fun obj(vararg properties: Pair<String, JsonElement>) = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonArray("required") { properties.forEach { add(it.first) } }
            putJsonObject("properties") { properties.forEach { (name, schema) -> put(name, schema) } }
        }
        val look = obj("color" to type("string"), "width" to type("number"))
        val proposal = obj(
            "name" to type("string"),
            "summary" to type("string"),
            "description" to type("string"),
            "particles" to obj("drop" to look, "flake" to look, "hailstone" to look, "streak" to look, "leaf" to look),
        )
        obj(
            "reply" to type("string"),
            "proposal" to buildJsonObject {
                putJsonArray("anyOf") {
                    add(type("null"))
                    add(proposal)
                }
            },
        )
    }

    private val instructions: String by lazy {
        val example = ScenePrompts.EXAMPLE_STYLE
        val exampleParticles = ParticleKind.entries.joinToString(", ") { kind ->
            val look = example.particleLook(kind)
            String.format(Locale.ROOT, "%s #%08X %s", kind.name.lowercase(Locale.ROOT), look.argb, look.width)
        }
        INSTRUCTIONS.trimIndent()
            .replace("{common}", ScenePrompts.common)
            .replace("{scene}", ScenePrompts.scene(ScenePicture.RAIN_COLD))
            .replace("{maxName}", MAX_NAME_LENGTH.toString())
            .replace("{exampleName}", example.displayName)
            .replace("{example}", ScenePrompts.exampleStyle)
            .replace("{exampleParticles}", exampleParticles)
    }

    private const val INSTRUCTIONS = """
        You are the style designer of Weather Buddy, an Android app that shows a young woman dressed for the weather outside, as a home-screen widget and as wallpaper. The user wants a new art style for her pictures, and you design it with them in a short, friendly chat.

        How a style becomes pictures: an image model redraws 30 pixel-art scenes, one for each kind of weather (sun, clouds, fog, wind, rain, storm and snow, by day and by night, from hot to freezing), in the new style. Each picture's prompt has three parts: a fixed part that every style shares, then the style's description, which you write, then the scene's weather and outfit. The app then animates rain, snow, hail, wind and blown leaves over the finished pictures.

        The fixed part. The description must not contradict it and needn't repeat it:
        <<<
        {common}
        >>>

        A scene's part, for example: "{scene}"

        The chat:
        - Find out what they'd like: a medium, an artist, a period or tradition, a place, a mood, a palette; how the style draws her; what is behind her; how the weather looks. Ask at most two short questions at a time, each with a few concrete ideas to choose from. Keep every reply under 60 words, in the language they write in.
        - Propose a style as soon as you know enough: after three questions at most, or straight away if they want you to choose. Afterwards they may ask for changes or tell you how a preview looks: then propose the whole style again, changed.
        - She is always the same person (see the fixed part): a style changes how she is drawn, not who she is. If they want another character, say so kindly and offer what the style can do instead. Any artist, period or tradition is fine, but no copyrighted characters, logos or text, and everything suitable for all ages.
        - "proposal" is null until you propose. With a proposal, the reply says in a sentence or two what you made, and that they can draw a preview of it with the button below it, or ask for changes. You don't write prompts or draw pictures yourself: the app does.

        A proposal:
        - name: one or two words, at most {maxName} characters, in their language, like "{exampleName}" or "Stained glass".
        - summary: one or two sentences for them, in their language.
        - description: in English, for the image model, as detailed as this example from the {exampleName} style:
        <<<
        {example}
        >>>
          In that order, it says: the medium, so that it is unmistakable, and what the pictures must not look like; how the style draws her face, figure and hair, in her modern clothes from the scene (who she is, how big and where, comes from the fixed part: don't repeat it); how it draws each kind of weather (sun, clouds, rain, snow, wind, fog, night); how the top third stays calm, plain sky in this style; and a setting and composition from the style's own world: simple, pale and low behind her, never the pixel art's Amsterdam canal, with nothing tall or busy.
        - particles: how the app paints the moving weather to match the style's palette and texture: rain drops, snow flakes, hailstones, wind streaks, and leaves blown in a storm. Colours are "#AARRGGBB": rain is usually see-through (alpha 80 to B3), snow and hail nearly opaque, streaks faint. Widths go from 0.3 for fine lines to 0.8 for thick strokes of paint. The {exampleName} style, for example: {exampleParticles}.
    """
}

/** OpenAI turned a request down, or answered with something unexpected. */
class OpenAiException(
    val problem: Problem,
    message: String,
    /** How long OpenAI asked to wait before trying again, if it said. */
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null,
    /** The HTTP status OpenAI answered with, if it answered. */
    val status: Int? = null,
) : IOException(message, cause) {
    enum class Problem {
        /** The key is wrong or has been revoked. */
        KEY,

        /** The account has run out of credit. */
        QUOTA,

        /** Too many requests at once; try again shortly. */
        BUSY,

        /** The image model's safety system turned a picture down; another try may pass. */
        REJECTED,

        /** OpenAI had trouble; trying again later may work. */
        SERVER,

        /** Anything else, such as a model the account may not use yet; the message says why. */
        OTHER,
    }
}
