package com.ivor.ivormusic.ui.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.ivor.ivormusic.data.spotify.SpotifyApiException
import com.ivor.ivormusic.data.spotify.SpotifyApiFailure
import com.ivor.ivormusic.data.spotify.SpotifyAuth
import com.ivor.ivormusic.data.spotify.SpotifyBackend
import com.ivor.ivormusic.data.spotify.SpotifyCommand
import com.ivor.ivormusic.data.spotify.SpotifyLibraryState
import com.ivor.ivormusic.data.spotify.SpotifyPlaylistItem
import com.ivor.ivormusic.data.spotify.SpotifyPlaybackMode
import com.ivor.ivormusic.data.spotify.SpotifyPlaybackModeStore
import com.ivor.ivormusic.data.spotify.SpotifyWebApi

/**
 * Bloque de biblioteca de Spotify para una pantalla de Koda.
 *
 * Lee el [SpotifyPlaybackMode] internamente y solo dibuja cuando el [slot]
 * coincide con el modo activo: TOP para spotify_preferred, BOTTOM para
 * youtube_music. Encapsula estado, efectos y dibujo; la pantalla solo pasa
 * el slot, sin lambdas.
 *
 * Cuando el usuario toca "Liked songs", el bloque gestiona internamente la
 * pantalla de canciones guardadas paginada, sin que la pantalla de Koda
 * necesite conocer su estado.
 */
@Composable
fun LazyListScope.SpotifyLibraryBlock(
    slot: SpotifyBlockSlot,
) {
    val context: Context = androidx.compose.ui.platform.LocalContext.current
    val playbackModeStore = SpotifyPlaybackModeStore(context)
    val playbackMode by playbackModeStore.mode.collectAsState()

    val shouldDraw = when {
        slot == SpotifyBlockSlot.TOP && playbackMode == SpotifyPlaybackMode.SPOTIFY_PREFERRED -> true
        slot == SpotifyBlockSlot.BOTTOM && playbackMode == SpotifyPlaybackMode.YOUTUBE_MUSIC -> true
        else -> false
    }
    if (!shouldDraw) return

    val spotifyAuth = remember { SpotifyAuth.get(context) }
    val spotifyState by spotifyAuth.state.collectAsState()
    val spotifyApi = remember { SpotifyWebApi.get(context) }
    var spotifyLibraryState by remember { mutableStateOf(SpotifyLibraryState()) }
    var spotifyLibraryLoaded by remember { mutableStateOf(false) }
    var spotifyLibraryRetry by mutableIntStateOf(0)
    var showLikedSongs by remember { mutableStateOf(false) }
    val spotifyScope = rememberCoroutineScope()

    LaunchedEffect(spotifyState.connected, spotifyLibraryRetry) {
        if (!spotifyState.connected) {
            spotifyLibraryState = SpotifyLibraryState()
            spotifyLibraryLoaded = false
            return@LaunchedEffect
        }
        if (spotifyLibraryLoaded) return@LaunchedEffect
        spotifyLibraryState = spotifyLibraryState.copy(loading = true, failure = null)
        try {
            val tracks = spotifyApi.savedTracks()
            val playlists = spotifyApi.playlists()
            spotifyLibraryState = SpotifyLibraryState(tracks = tracks, playlists = playlists)
            spotifyLibraryLoaded = true
        } catch (e: SpotifyApiException) {
            spotifyLibraryState = spotifyLibraryState.copy(loading = false, failure = e.failure)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            spotifyLibraryState = spotifyLibraryState.copy(
                loading = false,
                failure = SpotifyApiFailure.NETWORK,
            )
        }
    }

    if (showLikedSongs) {
        SpotifyLikedSongsScreen(
            onClose = { showLikedSongs = false },
        )
        return
    }

    spotifyLibrarySection(
        state = spotifyLibraryState,
        onPlay = { uri ->
            spotifyScope.launch {
                SpotifyBackend.get(context).connect()
                SpotifyBackend.get(context).execute(SpotifyCommand.Play(uri))
            }
        },
        onOpen = { uri ->
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            )
        },
        onOpenPlaylist = { playlist ->
            val uri = playlist.uri
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            )
        },
        onOpenLikedSongs = { showLikedSongs = true },
        onRetry = {
            spotifyLibraryLoaded = false
            spotifyLibraryState = SpotifyLibraryState(loading = true)
            spotifyLibraryRetry++
        },
    )
}
