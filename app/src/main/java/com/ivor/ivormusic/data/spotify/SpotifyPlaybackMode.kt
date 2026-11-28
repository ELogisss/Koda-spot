package com.ivor.ivormusic.data.spotify

/**
 * Where music playback comes from: YouTube Music as always, or Spotify preferred.
 *
 * Persisted id is frozen: renaming resets every user's choice.
 */
enum class SpotifyPlaybackMode(val id: String) {
    YOUTUBE_MUSIC("youtube_music"),
    SPOTIFY_PREFERRED("spotify_preferred");

    companion object {
        fun fromId(id: String?): SpotifyPlaybackMode =
            entries.firstOrNull { it.id == id } ?: YOUTUBE_MUSIC
    }
}
