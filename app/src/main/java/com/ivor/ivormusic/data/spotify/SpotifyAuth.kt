package com.ivor.ivormusic.data.spotify

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ivor.ivormusic.R
import com.ivor.ivormusic.data.VolatileSharedPreferences
import com.ivor.ivormusic.util.KLog
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Which allowlisted redirect URI the login sends.
 *
 * Spotify requires the value at authorize and at token exchange to match the
 * dashboard entry byte for byte, so both live here as constants and the
 * settings page shows the selected one to copy. They are not interchangeable
 * at runtime: sending the one the dashboard does not hold fails in the browser
 * before Koda ever sees a code.
 *
 * `APP_SCHEME` hands the browser straight to Koda. `HTTPS_BRIDGE` goes through
 * the page the user publishes on their tailnet, which forwards to
 * `koda://spotify-callback` keeping the query; both arrive at the same intent
 * filter, so the only difference is the string sent to Spotify.
 *
 * The stored ids are persisted and frozen.
 */
enum class SpotifyRedirect(val id: String, val uri: String) {
    HTTPS_BRIDGE("https", "https://lsv.bongo-hoki.ts.net:2998/koda/callback"),
    APP_SCHEME("scheme", "koda://spotify-callback");

    companion object {
        fun fromId(id: String?): SpotifyRedirect = entries.firstOrNull { it.id == id } ?: HTTPS_BRIDGE
    }
}

data class SpotifyState(
    val clientId: String = "",
    val redirect: SpotifyRedirect = SpotifyRedirect.HTTPS_BRIDGE,
    /** A browser round trip is outstanding: the verifier is stored, no code yet. */
    val pending: Boolean = false,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val account: String = "",
    val checkedAt: Long = 0,
    val checkOk: Boolean = false,
    val error: String? = null,
)

/**
 * The Spotify login: OAuth authorization code with PKCE, no client secret.
 *
 * Tokens and the code verifier live in an encrypted store of their own
 * (`spotify_private`). That file is not in [com.ivor.ivormusic.data.BackupRepository]'s
 * preference allowlist, so credentials never enter a backup - the same rule as
 * the YouTube session and Last.fm.
 *
 * One instance per process: the redirect arrives at `MainActivity`, which has no
 * way to reach the settings page's ViewModel, and the media service will need
 * the same token later.
 */
class SpotifyAuth private constructor(private val context: Context) {

    private val prefs = openPrivatePrefs(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
    private val mutable = MutableStateFlow(snapshot())
    val state: StateFlow<SpotifyState> = mutable.asStateFlow()
    private var operation: Job? = null

    private fun value(key: String): String = prefs.getString(key, "").orEmpty()

    private fun snapshot() = SpotifyState(
        clientId = value(KEY_CLIENT_ID),
        redirect = SpotifyRedirect.fromId(prefs.getString(KEY_REDIRECT, null)),
        pending = value(KEY_VERIFIER).isNotEmpty(),
        connected = value(KEY_ACCESS).isNotEmpty(),
        account = value(KEY_ACCOUNT),
        checkedAt = prefs.getLong(KEY_CHECKED_AT, 0),
        checkOk = prefs.getBoolean(KEY_CHECK_OK, false),
    )

    private fun report(message: String) {
        mutable.value = snapshot().copy(error = message)
    }

    /** The redirect is a dashboard fact, so changing it invalidates any pending round trip. */
    fun setRedirect(redirect: SpotifyRedirect) {
        operation?.cancel()
        prefs.edit()
            .putString(KEY_REDIRECT, redirect.id)
            .remove(KEY_VERIFIER)
            .remove(KEY_STATE)
            .apply()
        mutable.value = snapshot()
    }

    fun beginLogin(clientId: String, openBrowser: (String) -> Unit) {
        val id = clientId.trim()
        if (!id.matches(CLIENT_ID)) {
            report(context.getString(R.string.spotify_error_client_id))
            return
        }
        operation?.cancel()
        val redirect = state.value.redirect
        val verifier = randomToken(64)
        val challenge = challengeOf(verifier)
        val stateValue = randomToken(24)
        // Persisted, not held in memory: the browser can outlive this process,
        // and the code it returns cannot be exchanged without the verifier.
        prefs.edit()
            .putString(KEY_CLIENT_ID, id)
            .putString(KEY_VERIFIER, verifier)
            .putString(KEY_STATE, stateValue)
            .apply()
        mutable.value = snapshot().copy(pending = true, busy = false, error = null)
        val url = AUTHORIZE.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", id)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirect.uri)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("state", stateValue)
            .addQueryParameter("scope", SCOPES)
            .build()
            .toString()
        openBrowser(url)
    }

    fun reportBrowserFailure() {
        report(context.getString(R.string.spotify_error_browser))
    }

    fun cancelLogin() {
        operation?.cancel()
        prefs.edit().remove(KEY_VERIFIER).remove(KEY_STATE).apply()
        mutable.value = snapshot().copy(error = null)
    }

    fun disconnect() {
        operation?.cancel()
        prefs.edit()
            .remove(KEY_ACCESS)
            .remove(KEY_REFRESH)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_SCOPE)
            .remove(KEY_ACCOUNT)
            .remove(KEY_VERIFIER)
            .remove(KEY_STATE)
            .remove(KEY_CHECKED_AT)
            .remove(KEY_CHECK_OK)
            .apply()
        mutable.value = snapshot()
    }

    /**
     * Take the callback out of an intent, if it is one.
     *
     * Returns whether the intent belonged to the login, so `MainActivity` can
     * stop before the shared-link paths start looking for a YouTube URL in it.
     * A wrong `state` is refused rather than exchanged: the code is bound to
     * the request that started it, and a stale link can arrive twice.
     */
    fun handleRedirect(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if (!CALLBACK_SCHEME.equals(data.scheme, ignoreCase = true)) return false
        if (!CALLBACK_HOST.equals(data.host, ignoreCase = true)) return false
        val expected = value(KEY_STATE)
        val denied = data.getQueryParameter("error")
        if (denied != null) {
            prefs.edit().remove(KEY_VERIFIER).remove(KEY_STATE).apply()
            val description = data.getQueryParameter("error_description")
            report(
                if (denied == "access_denied") context.getString(R.string.spotify_error_denied)
                else description ?: context.getString(R.string.spotify_error_denied)
            )
            return true
        }
        val code = data.getQueryParameter("code")
        val returned = data.getQueryParameter("state")
        if (code.isNullOrBlank() || expected.isEmpty() || returned != expected) {
            prefs.edit().remove(KEY_VERIFIER).remove(KEY_STATE).apply()
            report(context.getString(R.string.spotify_error_state))
            return true
        }
        exchange(code)
        return true
    }

    private fun exchange(code: String) {
        operation?.cancel()
        mutable.value = state.value.copy(busy = true, pending = true, error = null)
        operation = scope.launch {
            try {
                val form = FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", state.value.redirect.uri)
                    .add("client_id", value(KEY_CLIENT_ID))
                    .add("code_verifier", value(KEY_VERIFIER))
                    .build()
                store(tokenRequest(form))
                prefs.edit().remove(KEY_VERIFIER).remove(KEY_STATE).apply()
                mutable.value = snapshot().copy(busy = true, pending = false)
                fetchAccount()
                mutable.value = snapshot().copy(busy = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KLog.e(TAG, "Spotify code exchange failed", e)
                prefs.edit().remove(KEY_VERIFIER).remove(KEY_STATE).apply()
                mutable.value = snapshot().copy(busy = false, error = message(e))
            }
        }
    }

    /** Read `/v1/me` and one track: the pair the settings page's test button reports. */
    fun test() {
        operation?.cancel()
        mutable.value = state.value.copy(busy = true, error = null)
        operation = scope.launch {
            try {
                val token = validAccessToken()
                    ?: throw IllegalStateException(context.getString(R.string.spotify_error_not_connected))
                val me = apiGet("/v1/me", token)
                val track = apiGet("/v1/tracks/$DEMO_TRACK_ID", token)
                val name = me.optString("display_name").ifBlank { me.optString("id") }
                prefs.edit()
                    .putString(KEY_ACCOUNT, name)
                    .putLong(KEY_CHECKED_AT, System.currentTimeMillis())
                    .putBoolean(KEY_CHECK_OK, track.has("name"))
                    .apply()
                mutable.value = snapshot().copy(busy = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KLog.e(TAG, "Spotify connection test failed", e)
                mutable.value = snapshot().copy(busy = false, error = message(e))
            }
        }
    }

    /**
     * A token good for the next minute, refreshed when it is older than that.
     *
     * Null when there is no session. The caller decides what a revoked session
     * means; [test] turns it into a message.
     */
    suspend fun validAccessToken(): String? {
        val access = value(KEY_ACCESS)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0)
        if (access.isNotEmpty() && System.currentTimeMillis() < expiresAt - 60_000) return access
        val refresh = value(KEY_REFRESH)
        if (refresh.isEmpty()) return access.ifEmpty { null }
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", value(KEY_CLIENT_ID))
            .build()
        val json = try {
            tokenRequest(form)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A rejected refresh token is a revoked session, not a network blip;
            // keeping it would make every later call fail the same way.
            if (e is SpotifyHttpException && e.code == 400) {
                prefs.edit().remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_EXPIRES_AT).apply()
                mutable.value = snapshot()
                throw IllegalStateException(context.getString(R.string.spotify_error_revoked))
            }
            throw e
        }
        store(json)
        return value(KEY_ACCESS).ifEmpty { null }
    }

    private fun store(json: JSONObject) {
        val access = json.optString("access_token")
        if (access.isEmpty()) throw IOException("Token response without access_token")
        val refresh = json.optString("refresh_token").ifEmpty { value(KEY_REFRESH) }
        val expires = json.optLong("expires_in", 3600)
        val scope = json.optString("scope").ifEmpty { value(KEY_SCOPE) }
        prefs.edit()
            .putString(KEY_ACCESS, access)
            .putString(KEY_REFRESH, refresh)
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expires * 1000)
            .putString(KEY_SCOPE, scope)
            .apply()
        mutable.value = snapshot()
    }

    private suspend fun fetchAccount() {
        val token = validAccessToken() ?: return
        val me = apiGet("/v1/me", token)
        val name = me.optString("display_name").ifBlank { me.optString("id") }
        if (name.isNotEmpty()) {
            prefs.edit().putString(KEY_ACCOUNT, name).apply()
        }
    }

    private suspend fun tokenRequest(form: FormBody): JSONObject {
        val request = Request.Builder().url(TOKEN).post(form).build()
        val (code, text) = execute(request)
        if (code !in 200..299) {
            val description = runCatching { JSONObject(text).optString("error_description") }
                .getOrDefault("")
            throw SpotifyHttpException(code, description)
        }
        return JSONObject(text)
    }

    private suspend fun apiGet(path: String, token: String): JSONObject {
        val request = Request.Builder()
            .url("$API$path")
            .header("Authorization", "Bearer $token")
            .build()
        val (code, text) = execute(request)
        if (code == 401) {
            prefs.edit().remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_EXPIRES_AT).apply()
            mutable.value = snapshot()
            throw SpotifyHttpException(code, "")
        }
        if (code !in 200..299) throw SpotifyHttpException(code, "")
        return JSONObject(text)
    }

    private suspend fun execute(request: Request): Pair<Int, String> {
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use { it.body?.string().orEmpty() }
                        if (continuation.isActive) continuation.resume(response.code to text)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    private fun message(e: Exception): String {
        val http = e as? SpotifyHttpException
        return when {
            http?.code == 401 -> context.getString(R.string.spotify_error_revoked)
            http?.code == 429 -> context.getString(R.string.spotify_error_limit)
            http != null && http.code == 400 && http.description.isNotEmpty() -> http.description
            http != null -> context.getString(R.string.spotify_error_http, http.code)
            e is IOException -> context.getString(R.string.spotify_error_network)
            e is IllegalStateException && e.message != null -> e.message!!
            else -> context.getString(R.string.spotify_error_generic)
        }
    }

    private class SpotifyHttpException(val code: Int, val description: String) : Exception("HTTP $code")

    companion object {
        private const val TAG = "SpotifyAuth"
        private const val PRIVATE_PREFS = "spotify_private"
        private const val AUTHORIZE = "https://accounts.spotify.com/authorize"
        private const val TOKEN = "https://accounts.spotify.com/api/token"
        private const val API = "https://api.spotify.com"

        /** The manifest's intent filter and [SpotifyRedirect.APP_SCHEME] name the same target. */
        private const val CALLBACK_SCHEME = "koda"
        private const val CALLBACK_HOST = "spotify-callback"

        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_REDIRECT = "redirect"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_SCOPE = "scope"
        private const val KEY_ACCOUNT = "account"
        private const val KEY_VERIFIER = "code_verifier"
        private const val KEY_STATE = "state"
        private const val KEY_CHECKED_AT = "checked_at"
        private const val KEY_CHECK_OK = "check_ok"

        /** Exactly the plan's scopes; more would need a reason and a doc check. */
        private const val SCOPES = "user-read-private user-read-playback-state " +
            "user-modify-playback-state user-read-currently-playing user-library-read " +
            "playlist-read-private user-read-recently-played"

        /** The track the connection test reads from the catalog. */
        private const val DEMO_TRACK_ID = "62ExBsM6vsV9gaIwA3tEFH"

        private val CLIENT_ID = Regex("[a-fA-F0-9]{32}")
        private val secureRandom = SecureRandom()

        @Volatile private var instance: SpotifyAuth? = null

        fun get(context: Context): SpotifyAuth = instance ?: synchronized(this) {
            instance ?: SpotifyAuth(context.applicationContext).also { instance = it }
        }

        private fun randomToken(length: Int): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
            val bytes = ByteArray(length)
            secureRandom.nextBytes(bytes)
            return buildString(length) {
                bytes.forEach { append(alphabet[(it.toInt() and 0x7f) % alphabet.length]) }
            }
        }

        private fun challengeOf(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.encodeToString(
                digest,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
        }

        @Suppress("DEPRECATION")
        private fun buildPrivatePrefs(context: Context): android.content.SharedPreferences =
            EncryptedSharedPreferences.create(
                context,
                PRIVATE_PREFS,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

        /**
         * Same recovery as the Last.fm store, and the same limit: the master key
         * is shared with the YouTube session, so only this file is deleted. A
         * store that cannot be reopened falls back to memory for this process
         * rather than taking the app down.
         */
        private fun openPrivatePrefs(context: Context): android.content.SharedPreferences = try {
            buildPrivatePrefs(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KLog.e(TAG, "Encrypted Spotify store unreadable, resetting", e)
            try {
                context.deleteSharedPreferences(PRIVATE_PREFS)
                buildPrivatePrefs(context)
            } catch (retry: CancellationException) {
                throw retry
            } catch (retry: Exception) {
                KLog.e(TAG, "Encrypted Spotify store unavailable; using volatile store", retry)
                VolatileSharedPreferences()
            }
        }
    }
}
