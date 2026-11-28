package com.ivor.ivormusic.ui.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.ivor.ivormusic.data.spotify.SpotifyAuth
import com.ivor.ivormusic.data.spotify.SpotifyBackend
import com.ivor.ivormusic.data.spotify.SpotifyCommand
import com.ivor.ivormusic.data.spotify.SpotifyLibraryState
import com.ivor.ivormusic.data.spotify.SpotifyPlaylistItem
import com.ivor.ivormusic.data.spotify.SpotifyWebApi

/**
 * Holds the Spotify library state and effects so the library screen only
 * emits the section block. One line of hook in LibraryScreen.kt.
 *
 * [state] is owned by the caller so the section can be drawn from it inside
 * the LazyColumn.
 */
@Composable
fun SpotifyLibraryStateHolder(
    state: androidx.compose.runtime.MutableState<SpotifyLibraryState>,
    onOpenLikedSongs: () -> Unit,
    onRetry: () -> Unit,
) {
    val context: Context = androidx.compose.ui.platform.LocalContext.current
    val spotifyAuth = remember { SpotifyAuth.get(context) }
    val spotifyState by spotifyAuth.state.collectAsState()
    val spotifyApi = remember { SpotifyWebApi.get(context) }
    val spotifyScope = rememberCoroutineScope()

    LaunchedEffect(spotifyState.connected, state.value) {
        if (!spotifyState.connected) {
            state.value = SpotifyLibraryState()
            return@LaunchedEffect
        }
        try {
            val tracks = spotifyApi.savedTracks()
            val playlists = spotifyApi.playlists()
            state.value = SpotifyLibraryState(tracks = tracks, playlists = playlists)
        } catch (e: com.ivor.ivormusic.data.spotify.SpotifyApiException) {
            state.value = SpotifyLibraryState(failure = e.failure)
        } catch (_: Exception) {
            state.value = SpotifyLibraryState(
                failure = com.ivor.ivormusic.data.spotify.SpotifyApiFailure.NETWORK
            )
        }
    }

    // No UI is drawn here: the caller draws spotifyLibrarySection from [state].
}
