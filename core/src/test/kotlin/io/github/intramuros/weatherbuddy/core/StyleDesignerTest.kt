package io.github.intramuros.weatherbuddy.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StyleDesignerTest {
    private val proposal = """{
        "name": "Stained glass",
        "summary": "Jewel-coloured glass in lead lines.",
        "description": "Medium: a stained-glass window.",
        "particles": {
            "drop": {"color": "#994A6CC8", "width": 0.4},
            "flake": {"color": "#FFFFFF", "width": 0.6},
            "hailstone": {"color": "white", "width": 0.5},
            "streak": {"color": "#66FFFFFF", "width": 7},
            "leaf": {"color": "#FFD98A3C", "width": 0.01}
        }
    }"""

    /** A chat completion whose message is [content], as OpenAI sends it. */
    private fun completion(content: String) = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("index", 0)
                put("message", buildJsonObject {
                    put("role", "assistant")
                    put("content", content)
                    put("refusal", null as String?)
                })
                put("finish_reason", "stop")
            })
        })
    }.toString()

    @Test
    fun asksForAStrictAnswerAfterTheInstructions() {
        val conversation = listOf(
            StyleDesigner.greeting("What would you like?"),
            StyleDesigner.Message(StyleDesigner.Role.USER, "Stained glass"),
        )
        val request = Json.parseToJsonElement(StyleDesigner.chatRequest(conversation)).jsonObject
        assertEquals(StyleDesigner.CHAT_MODEL, request["model"]!!.jsonPrimitive.content)

        val messages = request["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("developer", "assistant", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        val instructions = messages[0]["content"]!!.jsonPrimitive.content
        assertTrue(ScenePrompts.common in instructions)
        assertTrue(ScenePrompts.exampleStyle in instructions)
        assertTrue("drop #8C6E8FB8 0.35" in instructions, "the example style's particles")
        assertNull(Regex("""\{[a-zA-Z]+}""").find(instructions), "every placeholder is filled")
        assertEquals("Stained glass", messages[2]["content"]!!.jsonPrimitive.content)

        val format = request["response_format"]!!.jsonObject["json_schema"]!!.jsonObject
        assertTrue(format["strict"]!!.jsonPrimitive.boolean)
        val schema = format["schema"]!!.jsonObject
        assertEquals(setOf("reply", "proposal"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test
    fun readsAQuestion() {
        val content = """{"reply": " Which colours? ", "proposal": null}"""
        val turn = StyleDesigner.parseChat(completion(content))
        assertEquals("Which colours?", turn.reply)
        assertNull(turn.proposal)
        assertEquals(StyleDesigner.Message(StyleDesigner.Role.ASSISTANT, content), turn.message)
    }

    @Test
    fun readsAProposal() {
        val turn = StyleDesigner.parseChat(completion("""{"reply": "Here it is.", "proposal": $proposal}"""))
        assertEquals("Stained glass", turn.proposal?.name)
        assertEquals("Medium: a stained-glass window.", turn.proposal?.description)
    }

    @Test
    fun aRefusalIsSaidAndKept() {
        val body = """{"choices": [{"message": {"role": "assistant", "content": null, "refusal": "I can't help with that."}}]}"""
        val turn = StyleDesigner.parseChat(body)
        assertEquals("I can't help with that.", turn.reply)
        assertNull(turn.proposal)
        assertEquals(StyleDesigner.Role.ASSISTANT, turn.message.role)
    }

    @Test
    fun anUnreadableAnswerIsOpenAisTrouble() {
        for (body in listOf("<html>", """{"choices": []}""", completion("""{"reply": "cut sh"""))) {
            val e = assertFailsWith<OpenAiException> { StyleDesigner.parseChat(body) }
            assertEquals(OpenAiException.Problem.SERVER, e.problem)
        }
    }

    @Test
    fun aProposalBecomesAStyleTheAppCanDraw() {
        val proposed = Json.decodeFromString<StyleDesigner.Proposal>(proposal)
        val style = proposed.toStyle("made-0a1b2c3d", ImageQuality.MEDIUM, createdAtMillis = 5)
        assertEquals("Stained glass", style.displayName)
        assertEquals("Medium: a stained-glass window.", style.description)
        assertEquals(ParticleLook(0x994A6CC8.toInt(), 0.4), style.particleLook(ParticleKind.DROP))
        // "#RRGGBB" is opaque.
        assertEquals(ParticleLook(0xFFFFFFFF.toInt(), 0.6), style.particleLook(ParticleKind.FLAKE))
        // Unreadable colours fall back to the example style's; widths stay drawable.
        assertEquals(Style.WATERCOLOUR.particleLook(ParticleKind.HAILSTONE).argb, style.particleLook(ParticleKind.HAILSTONE).argb)
        assertEquals(1.0, style.particleLook(ParticleKind.STREAK).width)
        assertEquals(0.25, style.particleLook(ParticleKind.LEAF).width)
    }

    @Test
    fun namesFitUnderTheirCard() {
        assertEquals("Cosy Cathedral Stained", StyleDesigner.shortName("Cosy Cathedral Stained Glass"))
        assertEquals("Stained glass", StyleDesigner.shortName("  Stained \n glass "))
        assertEquals("Supercalifragilisticexpi", StyleDesigner.shortName("Supercalifragilisticexpialidocious"))
        assertEquals("", StyleDesigner.shortName(" "))
    }

    @Test
    fun readsColours() {
        assertEquals(0x80123456.toInt(), StyleDesigner.parseColour("#80123456"))
        assertEquals(0xFFABCDEF.toInt(), StyleDesigner.parseColour("abcdef"))
        for (bad in listOf("", "#12345", "#-1234567", "#GG000000", "red")) assertNull(StyleDesigner.parseColour(bad), bad)
    }

    @Test
    fun drawsWebpAtTheStylesQuality() {
        val fields = StyleDesigner.imageFields("a prompt", ImageQuality.HIGH).toMap()
        assertEquals(StyleDesigner.IMAGE_MODEL, fields["model"])
        assertEquals("a prompt", fields["prompt"])
        assertEquals("high", fields["quality"])
        assertEquals("webp", fields["output_format"])
        assertEquals("1024x1024", fields["size"])
    }

    @Test
    fun readsThePicture() {
        val webp = byteArrayOf(0x52, 0x49, 0x46, 0x46, 1, 2, 3)
        val body = """{"created": 1, "data": [{"b64_json": "${Base64.getEncoder().encodeToString(webp)}"}]}"""
        assertContentEquals(webp, StyleDesigner.parseImage(body))
        assertFailsWith<OpenAiException> { StyleDesigner.parseImage("""{"data": []}""") }
        assertFailsWith<OpenAiException> { StyleDesigner.parseImage("""{"data": [{"b64_json": "*not base64*"}]}""") }
    }

    private fun error(code: String?, message: String = "Something happened.") = buildJsonObject {
        put("error", buildJsonObject {
            put("message", message)
            put("type", "invalid_request_error")
            put("code", code)
        })
    }.toString()

    @Test
    fun tellsWhatWentWrong() {
        fun problem(status: Int, body: String?) = StyleDesigner.error(status, body, null).problem
        assertEquals(OpenAiException.Problem.KEY, problem(401, error("invalid_api_key")))
        assertEquals(OpenAiException.Problem.QUOTA, problem(429, error("insufficient_quota")))
        assertEquals(OpenAiException.Problem.QUOTA, problem(400, error("billing_hard_limit_reached")))
        assertEquals(OpenAiException.Problem.BUSY, problem(429, error("rate_limit_exceeded")))
        assertEquals(OpenAiException.Problem.REJECTED, problem(400, error("moderation_blocked")))
        assertEquals(OpenAiException.Problem.SERVER, problem(503, "<html>Service unavailable</html>"))
        assertEquals(OpenAiException.Problem.OTHER, problem(400, """{"error": {"message": "Bad", "code": 42}}"""))

        // OpenAI's own words, e.g. that the organisation must be verified for the image model.
        val forbidden = StyleDesigner.error(403, error(null, "Your organization must be verified."), null)
        assertEquals(OpenAiException.Problem.OTHER, forbidden.problem)
        assertEquals("Your organization must be verified.", forbidden.message)
        assertEquals(403, forbidden.status)
        assertEquals("OpenAI answered HTTP 502", StyleDesigner.error(502, null, null).message)

        assertEquals(20L, StyleDesigner.error(429, error("rate_limit_exceeded"), " 20 ").retryAfterSeconds)
        assertNull(StyleDesigner.error(429, null, "Wed, 21 Oct 2026 07:28:00 GMT").retryAfterSeconds)
    }
}
