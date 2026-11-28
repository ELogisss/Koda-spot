package com.ivor.ivormusic.data.spotify

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's preferred music source: YouTube Music or Spotify.
 *
 * Own SharedPreferences file, not ThemePreferences: the playback mode is a
 * playback decision, not a theme decision, and it must survive theme-store
 * changes. Every instance reads the same file, so a write on one instance is
 * visible on another holding its own instance.
 */
class SpotifyPlaybackModeStore(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "spotify_playback_mode", Context.MODE_PRIVATE
    )

    private val _mode = MutableStateFlow(readMode())
    val mode: StateFlow<SpotifyPlaybackMode> = _mode.asStateFlow()

    fun readMode(): SpotifyPlaybackMode =
        SpotifyPlaybackMode.fromId(prefs.getString(KEY_MODE, null))

    /** Direct read of the stored value, without the flow cache. For playback decisions. */
    fun current(): SpotifyPlaybackMode = readMode()

    fun setMode(value: SpotifyPlaybackMode) {
        prefs.edit().putString(KEY_MODE, value.id).apply()
        _mode.value = value
    }

    companion object {
        private const val KEY_MODE = "spotify_playback_mode"
    }
}
