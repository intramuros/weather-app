package io.github.intramuros.weatherbuddy.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Sign in with ChatGPT, so the style designer's chat runs on the user's ChatGPT plan
 * instead of an API key (https://developers.openai.com/siwc/token-sharing-open-source).
 *
 * OAuth 2.0 with PKCE for a public client. The first sign-in registers the app with the
 * user's account ([DYNAMIC_CLIENT]) and returns the client ID to use from then on. The
 * browser comes back to a listener on 127.0.0.1 ([redirectUri]), the only redirect
 * OpenAI accepts for it.
 *
 * Plan usage covers Responses API requests, but not image generation, so drawing a
 * style's pictures still needs an API key.
 */
object ChatGptSignIn {
    const val AUTHORIZE_URL = "https://auth.openai.com/api/accounts/authorize"
    const val TOKEN_URL = "https://auth.openai.com/api/accounts/oauth/token"
    const val REVOKE_URL = "https://auth.openai.com/api/accounts/oauth/revoke"
    const val MODELS_URL = "https://api.openai.com/v1/models"
    const val RESPONSES_URL = "https://api.openai.com/v1/responses"

    /** Where the user sees and limits what each app may use of their plan. */
    const val USAGE_SETTINGS_URL = "https://chatgpt.com/settings/usage"

    /** Registers the app with the user's account; OpenAI then issues it a client ID of its own. */
    const val DYNAMIC_CLIENT = "dynamic_agent_client"

    /** Granted when the user lets the app use their plan. */
    const val PLAN_SCOPE = "chatgpt.tokens.use.direct"

    const val CALLBACK_PATH = "/auth/callback"

    private const val ISSUER = "https://auth.openai.com"
    private const val RESOURCE = "https://api.openai.com/v1"
    private const val SCOPES = "openid profile email offline_access resource.invoke $PLAN_SCOPE"
    private const val APP_NAME = "Weather Buddy"

    /** Refresh a little before the access token runs out, not after a request fails. */
    private const val REFRESH_MARGIN_SECONDS = 60L

    private val json = Json { ignoreUnknownKeys = true }

    fun redirectUri(port: Int) = "http://127.0.0.1:$port$CALLBACK_PATH"

    /** Identifies this phone to OpenAI: opaque, made once and kept across sign-ins. */
    fun newHostId(): String = "urn:uuid:${UUID.randomUUID()}"

    /** What a sign-in must remember until the browser comes back. */
    data class Attempt(
        val state: String,
        val nonce: String,
        val verifier: String,
        val redirectUri: String,
        /** The saved client ID, or `null` to register the app first. */
        val clientId: String?,
    )

    fun newAttempt(port: Int, clientId: String?, random: SecureRandom = SecureRandom()): Attempt {
        fun token() = ByteArray(32).also(random::nextBytes).base64Url()
        return Attempt(state = token(), nonce = token(), verifier = token(), redirectUri = redirectUri(port), clientId = clientId)
    }

    /** PKCE's S256 challenge for [verifier]. */
    fun codeChallenge(verifier: String): String =
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)).base64Url()

    /**
     * The page to open in the browser. [consent] asks again for every permission, to let
     * a user who declined plan usage allow it after all.
     */
    fun authorizeUrl(attempt: Attempt, hostId: String, idTokenHint: String? = null, consent: Boolean = false): String {
        val params = buildList {
            add("client_id" to (attempt.clientId ?: DYNAMIC_CLIENT))
            if (attempt.clientId == null) add("agent_name_hint" to APP_NAME)
            add("ext_agent_host_id" to hostId)
            if (idTokenHint != null) add("id_token_hint" to idTokenHint)
            add("response_type" to "code")
            add("redirect_uri" to attempt.redirectUri)
            add("scope" to SCOPES)
            add("resource" to RESOURCE)
            add("state" to attempt.state)
            add("nonce" to attempt.nonce)
            add("code_challenge_method" to "S256")
            add("code_challenge" to codeChallenge(attempt.verifier))
            if (consent) add("prompt" to "consent")
        }
        return "$AUTHORIZE_URL?${form(params)}"
    }

    /** How the browser came back. */
    sealed interface Callback {
        /** [clientId] is the one to use from now on. */
        data class Code(val code: String, val clientId: String) : Callback

        /** The user said no. */
        data object Denied : Callback
    }

    /**
     * Reads the callback's query.
     * @throws SignInException if it isn't the answer to [attempt].
     */
    fun parseCallback(query: String, attempt: Attempt): Callback {
        val params = query.split('&').filter { it.isNotEmpty() }.associate {
            val (name, value) = (it.split('=', limit = 2) + "").take(2)
            URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }
        if (params["state"] != attempt.state) throw SignInException("the sign-in came back for another attempt")
        if (params["error"] != null) {
            if (params["error"] == "access_denied") return Callback.Denied
            throw SignInException("OpenAI said: ${params["error_description"] ?: params["error"]}")
        }
        val code = params["code"] ?: throw SignInException("the sign-in came back without a code")
        val issued = params["client_id"]
        val clientId = when {
            attempt.clientId == null -> issued ?: throw SignInException("the sign-in came back without a client ID")
            issued != null && issued != attempt.clientId -> throw SignInException("the sign-in came back for another client")
            else -> attempt.clientId
        }
        return Callback.Code(code, clientId)
    }

    /** The token endpoint's form for exchanging the callback's code. */
    fun tokenRequest(attempt: Attempt, code: String, clientId: String): String = form(
        listOf(
            "grant_type" to "authorization_code",
            "client_id" to clientId,
            "code" to code,
            "code_verifier" to attempt.verifier,
            "redirect_uri" to attempt.redirectUri,
            "resource" to RESOURCE,
        ),
    )

    /** The token endpoint's form for a new access token; the refresh token is replaced too. */
    fun refreshRequest(clientId: String, refreshToken: String): String = form(
        listOf(
            "grant_type" to "refresh_token",
            "client_id" to clientId,
            "refresh_token" to refreshToken,
            "resource" to RESOURCE,
        ),
    )

    fun revokeRequest(clientId: String, refreshToken: String): String = form(
        listOf("client_id" to clientId, "token" to refreshToken, "token_type_hint" to "refresh_token"),
    )

    data class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        val idToken: String?,
        /** When to refresh the access token, in epoch seconds. */
        val refreshAtSeconds: Long,
        val scopes: Set<String>,
    ) {
        /** Whether the user let the app use their plan. Without it they're signed in, but the chat needs an API key. */
        val planUsage: Boolean get() = PLAN_SCOPE in scopes
    }

    /** @throws SignInException if the token endpoint's answer isn't one. */
    fun parseTokens(body: String, nowSeconds: Long): Tokens {
        val response = try {
            json.decodeFromString<TokenResponse>(body)
        } catch (e: SerializationException) {
            throw SignInException("OpenAI sent tokens it couldn't read", e)
        } catch (e: IllegalArgumentException) {
            throw SignInException("OpenAI sent tokens it couldn't read", e)
        }
        val expiresAt = nowSeconds + response.expiresIn
        // Not before OpenAI allows it, but never after the access token has run out.
        val earliest = response.earliestRefreshAt?.takeIf { it <= expiresAt } ?: 0
        return Tokens(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken,
            idToken = response.idToken,
            refreshAtSeconds = maxOf(expiresAt - REFRESH_MARGIN_SECONDS, earliest),
            scopes = response.scope?.split(' ')?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
        )
    }

    /** Who signed in. */
    data class Identity(val subject: String, val email: String?)

    /**
     * Checks an ID token for [clientId] and the sign-in's [nonce] (none for a refresh),
     * and reads who it is. Its signature isn't checked: it came straight from the token
     * endpoint over TLS, which OpenID Connect allows instead (Core 1.0, 3.1.3.7).
     * @throws SignInException if it isn't for this app and sign-in.
     */
    fun identity(idToken: String, clientId: String, nonce: String?, nowSeconds: Long): Identity {
        val claims = try {
            val payload = idToken.split('.').getOrNull(1) ?: throw IllegalArgumentException("not a JWT")
            json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)).jsonObject
        } catch (e: IllegalArgumentException) {
            throw SignInException("OpenAI sent an ID token it couldn't read", e)
        } catch (e: SerializationException) {
            throw SignInException("OpenAI sent an ID token it couldn't read", e)
        }
        fun string(name: String) = (claims[name] as? JsonPrimitive)?.contentOrNull
        val audience = claims["aud"].let { aud ->
            (aud as? JsonPrimitive)?.contentOrNull?.let(::listOf)
                ?: (aud as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: emptyList()
        }
        val expires = (claims["exp"] as? JsonPrimitive)?.longOrNull ?: 0
        when {
            string("iss") != ISSUER -> throw SignInException("the ID token isn't from OpenAI")
            clientId !in audience -> throw SignInException("the ID token is for another app")
            expires < nowSeconds -> throw SignInException("the ID token has expired")
            nonce != null && string("nonce") != nonce -> throw SignInException("the ID token is for another sign-in")
        }
        val subject = string("sub") ?: throw SignInException("the ID token doesn't say who signed in")
        return Identity(subject, string("email"))
    }

    /** The token endpoint's error, e.g. `invalid_grant`, from an error response's body. */
    fun tokenError(body: String?): String? = body?.let {
        try {
            (json.parseToJsonElement(it).jsonObject["error"] as? JsonPrimitive)?.contentOrNull
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Whether a refresh failed for good, so only signing in again helps. */
    fun isFinalRefreshError(error: String?): Boolean = error in FINAL_REFRESH_ERRORS

    private val FINAL_REFRESH_ERRORS = setOf(
        "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
        "refresh_token_invalidated", "refresh_token_reused", "invalid_client",
    )

    /** The chat models the signed-in plan offers, best first, as the models list orders them. */
    fun parseModels(body: String): List<String> = try {
        json.decodeFromString<ModelList>(body).models.filter { it.visibility == "list" }.map { it.slug }
    } catch (e: SerializationException) {
        throw SignInException("OpenAI sent a models list it couldn't read", e)
    } catch (e: IllegalArgumentException) {
        throw SignInException("OpenAI sent a models list it couldn't read", e)
    }

    /** The designer's model if the plan offers it, otherwise the plan's first. */
    fun chooseModel(models: List<String>): String? =
        StyleDesigner.CHAT_MODEL.takeIf { it in models } ?: models.firstOrNull()

    private fun form(params: List<Pair<String, String>>) =
        params.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun ByteArray.base64Url() = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("id_token") val idToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long = 3600,
        val scope: String? = null,
        @SerialName("earliest_refresh_at") val earliestRefreshAt: Long? = null,
    )

    @Serializable
    private data class ModelList(val models: List<Model> = emptyList())

    @Serializable
    private data class Model(val slug: String, val visibility: String? = null)
}

/** Signing in with ChatGPT went wrong. */
class SignInException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)
