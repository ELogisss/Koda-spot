package com.ivor.ivormusic.data.spotify

import com.ivor.ivormusic.data.Song
import com.ivor.ivormusic.data.SongSource
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the Web API returns, turned into Koda's own shapes.
 *
 * Nothing here touches Android or OkHttp, so the mapping can be read and
 * corrected without a phone. Two rules shape all of it, both from the February
 * 2026 dev-mode changes:
 *
 * - **Missing text stays blank.** Spotify omits fields freely (a playlist
 *   description, an image, an artist), and Koda's lists test `isNotBlank()`.
 *   Writing "Unknown" would draw a made-up artist as if Spotify had sent one.
 * - **A missing object is not an error.** A saved track can point at a track
 *   that no longer exists (`track: null`), an album can have no image, and a
 *   playlist can have no description. Every accessor here tolerates that.
 *
 * The field names come from the reference pages: `TrackObject`, the simplified
 * album inside it, `SavedTrackObject`, and the playlist object whose `tracks`
 * was renamed to `items` in February 2026.
 */

/** An album from search or the library. */
data class SpotifyAlbumItem(
    val id: String,
    val name: String,
    val artist: String,
    val thumbnailUrl: String?,
    val releaseYear: Int?,
    /** -1 when Spotify did not say, never zero. */
    val totalTracks: Int,
    val uri: String,
) {
    /** The page the attribution link opens, built from the id. */
    val webUrl: String get() = "https://open.spotify.com/album/$id"
}

/** A playlist from search or the library. */
data class SpotifyPlaylistItem(
    val id: String,
    val name: String,
    val owner: String,
    val thumbnailUrl: String?,
    /** -1 when Spotify did not say, never zero. */
    val itemCount: Int,
    val uri: String,
    /**
     * True when the signed-in user owns or collaborates on it. Only then can
     * `GET /playlists/{id}/items` be read; for anything else Spotify answers
     * 403 and the playlist can only be played as a whole.
     */
    val owned: Boolean,
) {
    val webUrl: String get() = "https://open.spotify.com/playlist/$id"
}

/** Everything one `GET /search` asked for, split by type. */
data class SpotifySearchResults(
    val tracks: List<Song> = emptyList(),
    val albums: List<SpotifyAlbumItem> = emptyList(),
    val playlists: List<SpotifyPlaylistItem> = emptyList(),
)

/** Which half of a search the Spotify section is drawing. */
enum class SpotifySearchKind { TRACKS, ALBUMS, PLAYLISTS }

/** Why a Web API call failed, in the few kinds the UI has a sentence for. */
enum class SpotifyApiFailure {
    /** Nobody is signed in, or the refresh token was rejected. */
    NO_SESSION,

    /** 429 with `Retry-After`: too many calls in the rolling window. */
    RATE_LIMITED,

    /** 429 with `reason: QUOTA_EXCEEDED`: the dev-mode allowance is spent. */
    QUOTA,

    /** 403: the session is not allowlisted, or the playlist is not the user's. */
    FORBIDDEN,

    /** 404: the item is gone. */
    NOT_FOUND,

    /** Any other answer Spotify gave. */
    SERVER,

    /** No answer at all. */
    NETWORK,

    UNKNOWN,
}

/** The search section's state, kept in the ViewModel so a tab switch keeps it. */
data class SpotifySearchState(
    val query: String = "",
    val tracks: List<Song> = emptyList(),
    val albums: List<SpotifyAlbumItem> = emptyList(),
    val playlists: List<SpotifyPlaylistItem> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val failure: SpotifyApiFailure? = null,
    /** The offset the next page asks for; the three types page together. */
    val offset: Int = 0,
    /** True once a page came back short: there is nothing after it. */
    val exhausted: Boolean = false,
) {
    /** Whether there is anything to draw for [kind]. */
    fun has(kind: SpotifySearchKind): Boolean = when (kind) {
        SpotifySearchKind.TRACKS -> tracks.isNotEmpty()
        SpotifySearchKind.ALBUMS -> albums.isNotEmpty()
        SpotifySearchKind.PLAYLISTS -> playlists.isNotEmpty()
    }
}

/** The library block's state. */
data class SpotifyLibraryState(
    val tracks: List<Song> = emptyList(),
    val playlists: List<SpotifyPlaylistItem> = emptyList(),
    val loading: Boolean = false,
    val failure: SpotifyApiFailure? = null,
)

/** The catalog parse, separated from the transport so it can be read alone. */
object SpotifyCatalog {

    /** `GET /search` with `type=track,album,playlist`. */
    fun parseSearch(json: JSONObject): SpotifySearchResults = SpotifySearchResults(
        tracks = json.objectOrNull("tracks")?.arrayOrNull("items").trackObjects(),
        albums = json.objectOrNull("albums")?.arrayOrNull("items").albumObjects(),
        // Search results carry no ownership, and a playlist found here is not
        // the user's own unless the library says so.
        playlists = json.objectOrNull("playlists")?.arrayOrNull("items").playlistObjects(meId = null),
    )

    /** `GET /me/tracks`: items carry `added_at` and the track itself. */
    fun parseSavedTracks(json: JSONObject): List<Song> {
        val items = json.arrayOrNull("items") ?: return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                val saved = items.optJSONObject(index) ?: continue
                val track = saved.objectOrNull("track") ?: continue
                track.toSong(addedAt = saved.dateTime("added_at"))?.let(::add)
            }
        }
    }

    /** `GET /me/playlists`: `items` is a link object with a total, not the tracks. */
    fun parsePlaylists(json: JSONObject, meId: String?): List<SpotifyPlaylistItem> =
        json.arrayOrNull("items").playlistObjects(meId)

    /** `GET /playlists/{id}/items`: each entry holds the track under `item`. */
    fun parsePlaylistItems(json: JSONObject): List<Song> {
        val items = json.arrayOrNull("items") ?: return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                val entry = items.optJSONObject(index) ?: continue
                // February 2026 renamed the entry from `track` to `item`; both
                // are read so a cached or older answer still maps.
                val track = entry.objectOrNull("item") ?: entry.objectOrNull("track") ?: continue
                track.toSong(addedAt = entry.dateTime("added_at"))?.let(::add)
            }
        }
    }

    /** `GET /me`: only the id and name are used, and both may be blank. */
    fun parseProfile(json: JSONObject): SpotifyProfile = SpotifyProfile(
        id = json.text("id"),
        displayName = json.text("display_name"),
    )
}

/** The signed-in account, as `GET /me` still returns it (no `product` since February 2026). */
data class SpotifyProfile(val id: String, val displayName: String)

/**
 * One track, flattened into a [Song].
 *
 * The Spotify URI is the song's id, not its `uri` field: `Song.uri` is a local
 * content URI, and putting a Spotify URI there would make every local-file
 * check in the app lie. The id is what phase F routes on.
 */
private fun JSONObject.toSong(addedAt: Long?): Song? {
    val trackId = text("id")
    if (trackId.isEmpty()) return null
    val album = objectOrNull("album")
    return Song(
        id = "$SPOTIFY_TRACK_PREFIX$trackId",
        title = text("name"),
        artist = arrayOrNull("artists").names(),
        album = album?.text("name").orEmpty(),
        duration = optLong("duration_ms", 0L),
        thumbnailUrl = album?.arrayOrNull("images").firstImageUrl(),
        source = SongSource.SPOTIFY,
        dateAdded = addedAt,
        trackNumber = optIntOrNull("track_number"),
        discNumber = optIntOrNull("disc_number"),
        albumId = album?.text("id")?.takeIf { it.isNotEmpty() },
        releaseYear = album?.releaseYear(),
        isUpload = false,
    )
}

/** The prefix a Spotify track's [Song.id] carries; persisted, so it is frozen. */
const val SPOTIFY_TRACK_PREFIX = "spotify:track:"

/** The id inside a Spotify track [Song.id], or the string itself when it has no prefix. */
fun String.spotifyTrackId(): String = removePrefix(SPOTIFY_TRACK_PREFIX)

private fun JSONArray?.trackObjects(): List<Song> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.toSong(addedAt = null)?.let(::add)
        }
    }
}

private fun JSONArray?.albumObjects(): List<SpotifyAlbumItem> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val album = optJSONObject(index) ?: continue
            val albumId = album.text("id")
            if (albumId.isEmpty()) continue
            add(
                SpotifyAlbumItem(
                    id = albumId,
                    name = album.text("name"),
                    artist = album.arrayOrNull("artists").names(),
                    thumbnailUrl = album.arrayOrNull("images").firstImageUrl(),
                    releaseYear = album.releaseYear(),
                    totalTracks = album.optIntOrNull("total_tracks") ?: -1,
                    uri = album.text("uri").ifEmpty { "spotify:album:$albumId" },
                )
            )
        }
    }
}

private fun JSONArray?.playlistObjects(meId: String?): List<SpotifyPlaylistItem> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val playlist = optJSONObject(index) ?: continue
            val playlistId = playlist.text("id")
            if (playlistId.isEmpty()) continue
            val owner = playlist.objectOrNull("owner")
            add(
                SpotifyPlaylistItem(
                    id = playlistId,
                    name = playlist.text("name"),
                    owner = owner?.text("display_name").orEmpty()
                        .ifEmpty { owner?.text("id").orEmpty() },
                    thumbnailUrl = playlist.arrayOrNull("images").firstImageUrl(),
                    itemCount = playlist.objectOrNull("items")?.optIntOrNull("total")
                        // A playlist from before the rename still says `tracks`.
                        ?: playlist.objectOrNull("tracks")?.optIntOrNull("total")
                        ?: -1,
                    uri = playlist.text("uri").ifEmpty { "spotify:playlist:$playlistId" },
                    owned = meId != null && owner?.text("id") == meId,
                )
            )
        }
    }
}

/**
 * The first image URL, which is the largest Spotify offers.
 *
 * The array is "empty or up to three images", so this answers null rather than
 * making something up. The URL is temporary (it expires in less than a day),
 * which is fine because it is fetched for the list that is on screen.
 */
private fun JSONArray?.firstImageUrl(): String? {
    if (this == null) return null
    for (index in 0 until length()) {
        val url = optJSONObject(index)?.text("url").orEmpty()
        if (url.isNotEmpty()) return url
    }
    return null
}

/** Artist names joined the way the rest of Koda joins a credit line. */
private fun JSONArray?.names(): String {
    if (this == null) return ""
    val names = buildList {
        for (index in 0 until length()) {
            val name = optJSONObject(index)?.text("name").orEmpty()
            if (name.isNotEmpty()) add(name)
        }
    }
    return names.joinToString(", ")
}

/** `release_date` is "2006", "2006-03" or "2006-03-24"; only the year is kept. */
private fun JSONObject.releaseYear(): Int? =
    text("release_date").take(4).toIntOrNull()

/** An ISO 8601 instant to epoch millis, or null when absent or unparseable. */
private fun JSONObject.dateTime(key: String): Long? {
    val raw = text(key)
    if (raw.isEmpty()) return null
    return try {
        Instant.parse(raw).toEpochMilli()
    } catch (e: java.time.format.DateTimeParseException) {
        null
    }
}

/** A string field, blank when the key is missing or explicitly null. */
private fun JSONObject.text(key: String): String =
    if (isNull(key)) "" else optString(key).orEmpty()

private fun JSONObject.objectOrNull(key: String): JSONObject? =
    if (isNull(key)) null else optJSONObject(key)

private fun JSONObject.arrayOrNull(key: String): JSONArray? =
    if (isNull(key)) null else optJSONArray(key)

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key) || !has(key)) null else optInt(key)
