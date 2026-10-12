package io.github.intramuros.weatherbuddy.data

import android.content.Context
import io.github.intramuros.weatherbuddy.core.ChatGptSignIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The user's Sign in with ChatGPT, for chatting about a style on their ChatGPT plan. */
@Serializable
data class ChatGptAccount(
    /** Identifies this phone to OpenAI, the same across sign-ins. */
    val hostId: String,
    /** The client ID OpenAI issued the app at the first sign-in, used for every later one. */
    val clientId: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    /** When to get a new access token, in epoch seconds. */
    val refreshAtSeconds: Long = 0,
    /** The last ID token, which makes signing in again quicker. */
    val idToken: String? = null,
    val email: String? = null,
    /** Whether the user let the app use their plan. Signed in without it, the chat needs an API key. */
    val planUsage: Boolean = false,
) {
    val signedIn: Boolean get() = accessToken != null

    /** Whether the chat can run on the user's plan. */
    val chatsOnPlan: Boolean get() = signedIn && planUsage

    /** Signed out, keeping what identifies the app and phone to OpenAI for the next sign-in. */
    fun signedOut() = ChatGptAccount(hostId = hostId, clientId = clientId)
}

/**
 * Keeps the [ChatGptAccount] on this phone like the API key ([SecretFile]); its tokens
 * are only ever sent to OpenAI. Reads and writes files; call off the main thread.
 */
class ChatGptAccountStore(context: Context) {
    private val file = SecretFile(context, "chatgpt-account")

    /** The account, signed out if there's none yet; a new one gets this phone's host ID. */
    fun load(): ChatGptAccount {
        val saved = file.read()?.let {
            try {
                json.decodeFromString<ChatGptAccount>(it)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        return saved ?: ChatGptAccount(hostId = ChatGptSignIn.newHostId()).also(::save)
    }

    fun save(account: ChatGptAccount) = file.write(json.encodeToString(account))

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
