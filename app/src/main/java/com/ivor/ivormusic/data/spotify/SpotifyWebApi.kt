package com.ivor.ivormusic.data.spotify

import android.content.Context
import com.ivor.ivormusic.util.KLog
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The Web API, for what App Remote cannot do: searching the catalog and
 * reading the user's saved songs and playlists.
 *
 * Every call runs with the session [SpotifyAuth] already holds, refreshing it
 * when it is old. Nothing here stores or logs a token.
 *
 * ## 429 is expected, not exceptional
 *
 * Spotify answers 429 both for the rolling 30-second rate limit and for the
 * development-mode quota, and the two need different words in the UI. The
 * response's `Retry-After` (seconds) is honoured; when it is missing, this
 * class waits 1s, 2s, 4s and then gives up, so a burst of scrolling cannot
 * turn into a request storm. `YouTubeRateLimit` is deliberately not involved:
 * it belongs to YouTube's extraction path and knows nothing about this.
 *
 * ## Development mode
 *
 * After February 2026 a development-mode app is limited to five users, needs
 * the owner on Premium, caps `GET /search` at ten results per type, and can
 * only read the contents of playlists the user owns or collaborates on. The
 * callers get the 403 for the last one and draw the playlist as a whole.
 */
class SpotifyWebApi private constructor(context: Context) {

    private val auth = SpotifyAuth.get(context.applicationContext)
    private val client = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * Search tracks, albums and playlists in one request.
     *
     * The types travel together because the page has three tabs and switching
     * between them should not cost a second call. `limit` is 10 because that
     * is the maximum a development-mode app is allowed, and `offset` is how
     * the section pages past it.
     */
    suspend fun search(query: String, offset: Int = 0): SpotifySearchResults {
        val path = "/v1/search?q=${encode(query)}&type=track,album,playlist" +
            "&limit=$SEARCH_PAGE&offset=$offset"
        return SpotifyCatalog.parseSearch(json(request(path)))
    }

    /** `GET /me/tracks`, one page. */
    suspend fun savedTracks(offset: Int = 0): List<com.ivor.ivormusic.data.Song> {
        val path = "/v1/me/tracks?limit=$PAGE&offset=$offset"
        return SpotifyCatalog.parseSavedTracks(json(request(path)))
    }

    /** `GET /me/playlists`, one page. Ownership is resolved against `/me`. */
    suspend fun playlists(offset: Int = 0): List<SpotifyPlaylistItem> {
        val meId = meId()
        val path = "/v1/me/playlists?limit=$PAGE&offset=$offset"
        return SpotifyCatalog.parsePlaylists(json(request(path)), meId)
    }

    /**
     * `GET /playlists/{id}/items`, only for a playlist the user owns or
     * collaborates on. Spotify answers 403 for anything else, which arrives
     * here as [SpotifyApiFailure.FORBIDDEN] rather than an empty list.
     */
    suspend fun playlistItems(playlistId: String, offset: Int = 0): List<com.ivor.ivormusic.data.Song> {
        val path = "/v1/playlists/${encodePath(playlistId)}/items?limit=$PAGE&offset=$offset"
        return SpotifyCatalog.parsePlaylistItems(json(request(path)))
    }

    /** `GET /me`, for the account name the settings page shows. */
    suspend fun me(): SpotifyProfile = SpotifyCatalog.parseProfile(json(request("/v1/me")))

    private suspend fun meId(): String? = cachedMeId ?: run {
        val id = me().id
        cachedMeId = id
        id
    }

    // --- Diagnostics ---------------------------------------------------------

    /**
     * Ask Spotify for each endpoint this app uses and report the answer.
     *
     * This exists because the endpoints cannot be proven from a build machine:
     * a development-mode app answers differently from an extended-quota one,
     * and the allowlist decides whether the session works at all. The report
     * carries the status, the HTTP code and the time, never the token.
     */
    suspend fun probeEndpoints(): List<SpotifyProbe> {
        val probes = mutableListOf<SpotifyProbe>()
        var meId: String? = null

        val me = probe(SpotifyProbeId.ME, "/v1/me")
        probes += me.report
        if (me.report.status == SpotifyProbeStatus.OK) {
            meId = me.body?.let { runCatching { SpotifyCatalog.parseProfile(JSONObject(it)).id }.getOrNull() }
        }

        probes += probe(
            SpotifyProbeId.SEARCH,
            "/v1/search?q=koda&type=track&limit=1"
        ).report

        probes += probe(
            SpotifyProbeId.SAVED_TRACKS,
            "/v1/me/tracks?limit=1"
        ).report

        val playlists = probe(SpotifyProbeId.PLAYLISTS, "/v1/me/playlists?limit=50")
        probes += playlists.report

        // The items endpoint needs a playlist the session is allowed to read;
        // without one there is nothing to test, and pretending otherwise would
        // report a 403 that means "wrong playlist", not "endpoint missing".
        val ownedId = playlists.body
            ?.takeIf { playlists.report.status == SpotifyProbeStatus.OK }
            ?.let { body ->
                SpotifyCatalog.parsePlaylists(JSONObject(body), meId)
                    .firstOrNull { it.owned }
                    ?.id
            }
        probes += if (ownedId == null) {
            SpotifyProbe(
                id = SpotifyProbeId.PLAYLIST_ITEMS,
                path = "/v1/playlists/{id}/items",
                status = SpotifyProbeStatus.SKIPPED,
                code = null,
                at = System.currentTimeMillis(),
            )
        } else {
            probe(
                SpotifyProbeId.PLAYLIST_ITEMS,
                "/v1/playlists/${encodePath(ownedId)}/items?limit=1"
            ).report
        }

        return probes
    }

    private data class ProbeAnswer(val report: SpotifyProbe, val body: String?)

    private suspend fun probe(id: SpotifyProbeId, path: String): ProbeAnswer {
        return try {
            val response = execute(path, token())
            ProbeAnswer(
                report = SpotifyProbe(
                    id = id,
                    path = path,
                    status = if (response.code in 200..299) {
                        SpotifyProbeStatus.OK
                    } else {
                        SpotifyProbeStatus.HTTP
                    },
                    code = response.code,
                    at = System.currentTimeMillis(),
                ),
                body = response.body,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KLog.w(TAG, "Spotify probe $path failed", e)
            ProbeAnswer(
                report = SpotifyProbe(
                    id = id,
                    path = path,
                    status = SpotifyProbeStatus.ERROR,
                    code = (e as? SpotifyApiException)?.code,
                    at = System.currentTimeMillis(),
                ),
                body = null,
            )
        }
    }

    // --- Transport -----------------------------------------------------------

    private suspend fun json(response: SpotifyResponse): JSONObject {
        if (response.code !in 200..299) throw failure(response)
        return JSONObject(response.body)
    }

    private fun failure(response: SpotifyResponse): SpotifyApiException {
        val error = runCatching { JSONObject(response.body).optJSONObject("error") }.getOrNull()
        val reason = error?.optString("reason").orEmpty()
        return SpotifyApiException(
            code = response.code,
            failure = when {
                response.code == 401 -> SpotifyApiFailure.NO_SESSION
                response.code == 403 -> SpotifyApiFailure.FORBIDDEN
                response.code == 404 -> SpotifyApiFailure.NOT_FOUND
                response.code == 429 && reason == "QUOTA_EXCEEDED" -> SpotifyApiFailure.QUOTA
                response.code == 429 -> SpotifyApiFailure.RATE_LIMITED
                response.code >= 500 -> SpotifyApiFailure.SERVER
                else -> SpotifyApiFailure.UNKNOWN
            },
            reason = reason,
            retryAfterMs = response.retryAfterMs,
        )
    }

    /**
     * One GET, retried on 429.
     *
     * `Retry-After` is in seconds and "normally" present, so the fallback has
     * to exist. Three attempts keep a screen responsive while still riding out
     * a short burst; the caller gets the 429 after that and shows its message.
     */
    private suspend fun request(path: String): SpotifyResponse {
        val token = token()
        var attempt = 0
        while (true) {
            val response = execute(path, token)
            if (response.code != 429 || attempt >= MAX_ATTEMPTS) return response
            attempt += 1
            delay(response.retryAfterMs ?: backoffMs(attempt))
        }
    }

    private suspend fun token(): String {
        return try {
            auth.validAccessToken() ?: throw SpotifyApiException(401, SpotifyApiFailure.NO_SESSION)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SpotifyApiException) {
            throw e
        } catch (e: Exception) {
            // A rejected refresh token is a session problem, whatever the auth
            // layer called it; the UI has one sentence for both.
            throw SpotifyApiException(401, SpotifyApiFailure.NO_SESSION)
        }
    }

    private suspend fun execute(path: String, token: String): SpotifyResponse {
        val request = Request.Builder()
            .url("$API$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (continuation.isActive) {
                            continuation.resume(
                                SpotifyResponse(
                                    code = it.code,
                                    body = it.body?.string().orEmpty(),
                                    retryAfterMs = it.header("Retry-After")
                                        ?.trim()
                                        ?.toLongOrNull()
                                        ?.times(1_000L),
                                )
                            )
                        }
                    }
                }
            })
        }
    }

    /** 1s, 2s, 4s... capped, used only when `Retry-After` is missing. */
    private fun backoffMs(attempt: Int): Long =
        (1_000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(30_000L)

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun encodePath(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    companion object {
        private const val TAG = "SpotifyWebApi"
        private const val API = "https://api.spotify.com"
        private const val PAGE = 50
        private const val SEARCH_PAGE = 10
        private const val MAX_ATTEMPTS = 3

        @Volatile private var instance: SpotifyWebApi? = null
        @Volatile private var cachedMeId: String? = null

        fun get(context: Context): SpotifyWebApi = instance ?: synchronized(this) {
            instance ?: SpotifyWebApi(context).also { instance = it }
        }
    }
}

/** A Web API answer with everything the caller needs to report on it. */
data class SpotifyResponse(val code: Int, val body: String, val retryAfterMs: Long?)

/** A failed Web API call, already classified for the UI. */
class SpotifyApiException(
    val code: Int,
    val failure: SpotifyApiFailure,
    val reason: String = "",
    val retryAfterMs: Long? = null,
) : Exception("Spotify HTTP $code${if (reason.isNotBlank()) " ($reason)" else ""}")

/** The endpoints the settings page can test, named so the UI owns the words. */
enum class SpotifyProbeId { ME, SEARCH, SAVED_TRACKS, PLAYLISTS, PLAYLIST_ITEMS }

enum class SpotifyProbeStatus { OK, HTTP, ERROR, SKIPPED }

/**
 * One endpoint's answer. [at] is when the answer arrived, in epoch millis; the
 * path is kept so the report can be copied without guessing which call it was.
 */
data class SpotifyProbe(
    val id: SpotifyProbeId,
    val path: String,
    val status: SpotifyProbeStatus,
    val code: Int?,
    val at: Long,
)
