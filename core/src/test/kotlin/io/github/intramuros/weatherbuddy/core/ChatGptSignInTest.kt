package io.github.intramuros.weatherbuddy.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatGptSignInTest {
    private val attempt = ChatGptSignIn.Attempt(
        state = "the-state",
        nonce = "the-nonce",
        verifier = "a-verifier_that-is.long~enough0123456789abcdefg",
        redirectUri = ChatGptSignIn.redirectUri(43123),
        clientId = null,
    )

    private fun query(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate {
        val (name, value) = it.split('=', limit = 2)
        name to URLDecoder.decode(value, "UTF-8")
    }

    @Test
    fun pkceChallengeIsTheVerifiersSha256() {
        // Worked out separately, with Python's hashlib.
        assertEquals("R6ciw3-4UtUAg0eLGhAKf697i8hjhRUGuoMldUCsNOk", ChatGptSignIn.codeChallenge(attempt.verifier))
    }

    @Test
    fun aNewAttemptHasItsOwnSecrets() {
        val random = SecureRandom()
        val a = ChatGptSignIn.newAttempt(1455, clientId = null, random)
        val b = ChatGptSignIn.newAttempt(1456, clientId = "oaiapp_1", random)
        assertNotEquals(a.state, b.state)
        assertNotEquals(a.verifier, b.verifier)
        assertTrue(a.verifier.length in 43..128, "PKCE verifiers are 43 to 128 characters")
        assertEquals("http://127.0.0.1:1456/auth/callback", b.redirectUri)
        assertTrue(ChatGptSignIn.newHostId().matches(Regex("urn:uuid:[0-9a-f-]{36}")))
    }

    @Test
    fun theFirstSignInRegistersTheApp() {
        val params = query(ChatGptSignIn.authorizeUrl(attempt, hostId = "urn:uuid:host"))
        assertEquals(ChatGptSignIn.DYNAMIC_CLIENT, params["client_id"])
        assertEquals("Weather Buddy", params["agent_name_hint"])
        assertEquals("urn:uuid:host", params["ext_agent_host_id"])
        assertEquals("code", params["response_type"])
        assertEquals("http://127.0.0.1:43123/auth/callback", params["redirect_uri"])
        assertEquals("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct", params["scope"])
        assertEquals("https://api.openai.com/v1", params["resource"])
        assertEquals("the-state", params["state"])
        assertEquals("the-nonce", params["nonce"])
        assertEquals("S256", params["code_challenge_method"])
        assertEquals(ChatGptSignIn.codeChallenge(attempt.verifier), params["code_challenge"])
        assertNull(params["prompt"])
    }

    @Test
    fun laterSignInsReuseTheClient() {
        val url = ChatGptSignIn.authorizeUrl(attempt.copy(clientId = "oaiapp_123"), "urn:uuid:host", idTokenHint = "id.token.here", consent = true)
        val params = query(url)
        assertEquals("oaiapp_123", params["client_id"])
        assertNull(params["agent_name_hint"], "only sent when registering")
        assertEquals("id.token.here", params["id_token_hint"])
        assertEquals("consent", params["prompt"])
        assertTrue(url.startsWith("https://auth.openai.com/api/accounts/authorize?"))
        assertFalse('+' in URI(url).rawQuery, "spaces are %20")
    }

    @Test
    fun readsTheCallback() {
        val registered = ChatGptSignIn.parseCallback("code=abc%2Fd&state=the-state&scope=openid&client_id=oaiapp_9", attempt)
        assertEquals(ChatGptSignIn.Callback.Code("abc/d", "oaiapp_9"), registered)

        val again = attempt.copy(clientId = "oaiapp_9")
        assertEquals(ChatGptSignIn.Callback.Code("xyz", "oaiapp_9"), ChatGptSignIn.parseCallback("code=xyz&state=the-state", again))
        assertEquals(ChatGptSignIn.Callback.Denied, ChatGptSignIn.parseCallback("error=access_denied&state=the-state", attempt))
    }

    @Test
    fun refusesACallbackForAnotherAttemptOrClient() {
        val bad = listOf(
            "code=abc&state=another-state&client_id=oaiapp_9" to attempt,
            "error=access_denied&state=another-state" to attempt,
            "code=abc&state=the-state" to attempt, // registering needs the issued client ID
            "state=the-state&client_id=oaiapp_9" to attempt,
            "code=abc&state=the-state&client_id=oaiapp_other" to attempt.copy(clientId = "oaiapp_9"),
            "error=server_error&state=the-state" to attempt,
        )
        for ((query, attempt) in bad) assertFailsWith<SignInException>(query) { ChatGptSignIn.parseCallback(query, attempt) }
    }

    @Test
    fun exchangesAndRefreshesWithTheSameClient() {
        assertEquals(
            "grant_type=authorization_code&client_id=oaiapp_9&code=abc%2Fd&code_verifier=a-verifier_that-is.long%7Eenough0123456789abcdefg" +
                "&redirect_uri=http%3A%2F%2F127.0.0.1%3A43123%2Fauth%2Fcallback&resource=https%3A%2F%2Fapi.openai.com%2Fv1",
            ChatGptSignIn.tokenRequest(attempt, "abc/d", "oaiapp_9"),
        )
        assertEquals(
            "grant_type=refresh_token&client_id=oaiapp_9&refresh_token=r1&resource=https%3A%2F%2Fapi.openai.com%2Fv1",
            ChatGptSignIn.refreshRequest("oaiapp_9", "r1"),
        )
    }

    @Test
    fun readsTokensAndWhetherThePlanMayBeUsed() {
        val body = """{"access_token":"a","refresh_token":"r","id_token":"i","token_type":"Bearer","expires_in":3600,
            "scope":"openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"}"""
        val tokens = ChatGptSignIn.parseTokens(body, nowSeconds = 1000)
        assertEquals("a", tokens.accessToken)
        assertEquals("r", tokens.refreshToken)
        assertEquals(1000 + 3600 - 60, tokens.refreshAtSeconds)
        assertTrue(tokens.planUsage)

        val declined = ChatGptSignIn.parseTokens("""{"access_token":"a","expires_in":3600,"scope":"openid email"}""", 0)
        assertFalse(declined.planUsage, "signed in, but the user didn't share their plan")
        assertNull(declined.refreshToken)

        // Not before OpenAI allows a refresh; a value past the token's life is ignored.
        assertEquals(3590, ChatGptSignIn.parseTokens("""{"access_token":"a","expires_in":3600,"earliest_refresh_at":3590}""", 0).refreshAtSeconds)
        assertEquals(3540, ChatGptSignIn.parseTokens("""{"access_token":"a","expires_in":3600,"earliest_refresh_at":99999999}""", 0).refreshAtSeconds)
        assertFailsWith<SignInException> { ChatGptSignIn.parseTokens("""{"error":"invalid_grant"}""", 0) }
    }

    private fun idToken(claims: JsonObject): String {
        fun part(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
        return "${part("""{"alg":"RS256"}""")}.${part(claims.toString())}.signature"
    }

    private val claims = buildJsonObject {
        put("iss", "https://auth.openai.com")
        put("aud", "oaiapp_9")
        put("sub", "user-1")
        put("email", "her@example.com")
        put("nonce", "the-nonce")
        put("exp", 2000)
    }

    @Test
    fun readsWhoSignedIn() {
        assertEquals(
            ChatGptSignIn.Identity("user-1", "her@example.com"),
            ChatGptSignIn.identity(idToken(claims), "oaiapp_9", "the-nonce", nowSeconds = 1000),
        )
        // A refreshed ID token needn't carry the sign-in's nonce, and the audience may be a list.
        val refreshed = JsonObject(claims - "nonce" + ("aud" to JsonArray(listOf(JsonPrimitive("oaiapp_9")))))
        assertEquals("user-1", ChatGptSignIn.identity(idToken(refreshed), "oaiapp_9", nonce = null, nowSeconds = 1000).subject)
    }

    @Test
    fun refusesAnIdTokenForAnotherAppOrSignIn() {
        fun with(name: String, value: String) = JsonObject(claims + (name to JsonPrimitive(value)))
        val bad = listOf(
            idToken(with("iss", "https://evil.example")),
            idToken(with("aud", "oaiapp_other")),
            idToken(with("nonce", "another-nonce")),
            idToken(JsonObject(claims + ("exp" to JsonPrimitive(999)))),
            idToken(JsonObject(claims - "sub")),
            "not-a-jwt",
        )
        for (token in bad) assertFailsWith<SignInException> { ChatGptSignIn.identity(token, "oaiapp_9", "the-nonce", nowSeconds = 1000) }
    }

    @Test
    fun knowsWhenOnlySigningInAgainHelps() {
        assertEquals("invalid_grant", ChatGptSignIn.tokenError("""{"error":"invalid_grant","error_description":"gone"}"""))
        assertNull(ChatGptSignIn.tokenError("<html>"))
        assertTrue(ChatGptSignIn.isFinalRefreshError("refresh_token_reused"))
        assertFalse(ChatGptSignIn.isFinalRefreshError("temporarily_unavailable"))
        assertFalse(ChatGptSignIn.isFinalRefreshError(null))
    }

    @Test
    fun choosesAModelThePlanOffers() {
        val body = """{"models":[
            {"slug":"gpt-6.1-sol","display_name":"GPT-6.1","visibility":"list"},
            {"slug":"internal-thing","visibility":"hide"},
            {"slug":"${StyleDesigner.CHAT_MODEL}","display_name":"Mini","visibility":"list"}]}"""
        val models = ChatGptSignIn.parseModels(body)
        assertEquals(listOf("gpt-6.1-sol", StyleDesigner.CHAT_MODEL), models)
        assertEquals(StyleDesigner.CHAT_MODEL, ChatGptSignIn.chooseModel(models))
        assertEquals("gpt-6.1-sol", ChatGptSignIn.chooseModel(listOf("gpt-6.1-sol", "other")))
        assertNull(ChatGptSignIn.chooseModel(emptyList()))
    }
}
