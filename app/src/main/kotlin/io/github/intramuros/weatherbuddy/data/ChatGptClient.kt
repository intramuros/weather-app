package io.github.intramuros.weatherbuddy.data

import io.github.intramuros.weatherbuddy.core.ChatGptSignIn
import io.github.intramuros.weatherbuddy.core.OpenAiException
import io.github.intramuros.weatherbuddy.core.SignInException
import io.github.intramuros.weatherbuddy.core.StyleDesigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The style designer's chat on the user's ChatGPT plan, through Sign in with ChatGPT
 * ([ChatGptSignIn]): signing in, keeping the tokens fresh, and asking. Network errors
 * are [IOException]s; OpenAI turning a request down is an [OpenAiException], whose
 * [OpenAiException.Problem.KEY] here means signing in again.
 */
object ChatGptClient {
    /** Long enough to sign in, with a password manager or a code by email. */
    private const val SIGN_IN_TIMEOUT_MS = 10 * 60_000L
    private const val FORM = "application/x-www-form-urlencoded"

    /** One refresh at a time: each one replaces the refresh token. */
    private val refreshing = Mutex()

    /** The model the plan offers for the chat, by client ID: looked up once per sign-in. */
    @Volatile
    private var model: Pair<String, String>? = null

    /**
     * Signs in: [openBrowser] shows OpenAI's page, and the browser comes back to a
     * [LoopbackCallback], which answers it with [page]. [consent] asks again for every
     * permission, to let a user who declined plan usage allow it after all.
     * @return the signed-in account, or `null` if the user said no.
     * @throws SignInException if signing in went wrong.
     */
    suspend fun signIn(store: ChatGptAccountStore, consent: Boolean, page: String, openBrowser: (String) -> Unit): ChatGptAccount? {
        val (account, callback) = withContext(Dispatchers.IO) { store.load() to LoopbackCallback.open() }
        val attempt = ChatGptSignIn.newAttempt(callback.port, account.clientId)
        val query = callback.use {
            openBrowser(ChatGptSignIn.authorizeUrl(attempt, account.hostId, account.idToken, consent))
            it.await(SIGN_IN_TIMEOUT_MS, page)
        }
        val code = when (val callback = ChatGptSignIn.parseCallback(query, attempt)) {
            ChatGptSignIn.Callback.Denied -> return null
            is ChatGptSignIn.Callback.Code -> callback
        }
        val now = nowSeconds()
        val body = OpenAiClient.send(
            ChatGptSignIn.TOKEN_URL,
            authorization = null,
            OpenAiClient.CHAT_TIMEOUT_MS,
            ChatGptSignIn.tokenRequest(attempt, code.code, code.clientId).toByteArray() to FORM,
            error = { status, answer, _ -> SignInException("OpenAI didn't finish the sign-in (${ChatGptSignIn.tokenError(answer) ?: "HTTP $status"})") },
        ) { it.bufferedReader().readText() }
        val tokens = ChatGptSignIn.parseTokens(body, now)
        val identity = tokens.idToken?.let { ChatGptSignIn.identity(it, code.clientId, attempt.nonce, now) }
        val signedIn = account.copy(
            clientId = code.clientId,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            refreshAtSeconds = tokens.refreshAtSeconds,
            idToken = tokens.idToken ?: account.idToken,
            email = identity?.email ?: account.email,
            planUsage = tokens.planUsage,
        )
        withContext(Dispatchers.IO) { store.save(signedIn) }
        model = null
        return signedIn
    }

    /** Signs out on this phone, and asks OpenAI to forget the refresh token. */
    suspend fun signOut(store: ChatGptAccountStore) {
        val account = withContext(Dispatchers.IO) { store.load().also { store.save(it.signedOut()) } }
        model = null
        val clientId = account.clientId ?: return
        val refreshToken = account.refreshToken ?: return
        try {
            OpenAiClient.send(
                ChatGptSignIn.REVOKE_URL,
                authorization = null,
                OpenAiClient.CHAT_TIMEOUT_MS,
                ChatGptSignIn.revokeRequest(clientId, refreshToken).toByteArray() to FORM,
            ) { }
        } catch (_: IOException) {
            // Signed out here anyway; the token runs out by itself.
        }
    }

    /** The designer's answer to the last message of [conversation], on the user's plan. */
    suspend fun chat(store: ChatGptAccountStore, conversation: List<StyleDesigner.Message>): StyleDesigner.Turn {
        var token = accessToken(store, renew = false)
        var reasoning = true
        var failure: OpenAiException? = null
        repeat(ATTEMPTS) { attempt ->
            val request = StyleDesigner.responsesRequest(conversation, model(store, token), reasoning).toByteArray()
            try {
                return OpenAiClient.send(ChatGptSignIn.RESPONSES_URL, "Bearer $token", OpenAiClient.CHAT_TIMEOUT_MS, request to "application/json") {
                    StyleDesigner.parseResponsesStream(it.bufferedReader().lineSequence())
                }
            } catch (e: OpenAiException) {
                failure = e
                when {
                    // The access token may have been revoked early: renew it once.
                    e.problem == OpenAiException.Problem.KEY && attempt == 0 -> token = accessToken(store, renew = true)
                    // A model that doesn't take a reasoning effort.
                    e.code == UNSUPPORTED && e.param?.startsWith("reasoning") == true && reasoning -> reasoning = false
                    else -> throw e
                }
            }
        }
        throw failure!!
    }

    /** A current access token, renewed first if it's due or [renew] is set. */
    private suspend fun accessToken(store: ChatGptAccountStore, renew: Boolean): String = refreshing.withLock {
        val account = withContext(Dispatchers.IO) { store.load() }
        val token = account.accessToken ?: throw signInAgain()
        if (!renew && nowSeconds() < account.refreshAtSeconds) return@withLock token
        val clientId = account.clientId ?: throw signInAgain()
        val refreshToken = account.refreshToken ?: throw signInAgain()
        val now = nowSeconds()
        val body = try {
            OpenAiClient.send(
                ChatGptSignIn.TOKEN_URL,
                authorization = null,
                OpenAiClient.CHAT_TIMEOUT_MS,
                ChatGptSignIn.refreshRequest(clientId, refreshToken).toByteArray() to FORM,
                error = { _, answer, _ -> RefreshFailed(ChatGptSignIn.tokenError(answer)) },
            ) { it.bufferedReader().readText() }
        } catch (e: RefreshFailed) {
            if (!ChatGptSignIn.isFinalRefreshError(e.error)) {
                throw OpenAiException(OpenAiException.Problem.SERVER, "OpenAI couldn't renew the sign-in")
            }
            withContext(Dispatchers.IO) { store.save(account.signedOut()) }
            throw signInAgain()
        }
        val tokens = ChatGptSignIn.parseTokens(body, now)
        val email = tokens.idToken?.let {
            try {
                ChatGptSignIn.identity(it, clientId, nonce = null, now).email
            } catch (_: SignInException) {
                null
            }
        }
        val renewed = account.copy(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken ?: refreshToken,
            refreshAtSeconds = tokens.refreshAtSeconds,
            idToken = tokens.idToken ?: account.idToken,
            email = email ?: account.email,
            // A refresh that doesn't list the scopes keeps the ones granted.
            planUsage = if (tokens.scopes.isEmpty()) account.planUsage else tokens.planUsage,
        )
        withContext(Dispatchers.IO) { store.save(renewed) }
        tokens.accessToken
    }

    /** The plan's model for the chat; the designer's own if the list can't be had. */
    private suspend fun model(store: ChatGptAccountStore, token: String): String {
        val clientId = withContext(Dispatchers.IO) { store.load().clientId }.orEmpty()
        model?.takeIf { it.first == clientId }?.let { return it.second }
        val models = try {
            OpenAiClient.send(ChatGptSignIn.MODELS_URL, "Bearer $token", OpenAiClient.CHAT_TIMEOUT_MS, body = null) {
                ChatGptSignIn.parseModels(it.bufferedReader().readText())
            }
        } catch (_: IOException) {
            emptyList()
        }
        return (ChatGptSignIn.chooseModel(models) ?: StyleDesigner.CHAT_MODEL).also { model = clientId to it }
    }

    private fun signInAgain() = OpenAiException(OpenAiException.Problem.KEY, "Sign in with ChatGPT again")

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    private class RefreshFailed(val error: String?) : IOException("refresh failed: $error")

    private const val ATTEMPTS = 3
    private const val UNSUPPORTED = "subscription_sharing_unsupported_capability"
}
