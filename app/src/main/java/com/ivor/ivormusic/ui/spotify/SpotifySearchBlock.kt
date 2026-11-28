package com.ivor.ivormusic.ui.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import com.ivor.ivormusic.data.spotify.SpotifyApiException
import com.ivor.ivormusic.data.spotify.SpotifyApiFailure
import com.ivor.ivormusic.data.spotify.SpotifyAuth
import com.ivor.ivormusic.data.spotify.SpotifyBackend
import com.ivor.ivormusic.data.spotify.SpotifyCommand
import com.ivor.ivormusic.data.spotify.SpotifyPlaybackMode
import com.ivor.ivormusic.data.spotify.SpotifyPlaybackModeStore
import com.ivor.ivormusic.data.spotify.SpotifySearchKind
import com.ivor.ivormusic.data.spotify.SpotifySearchState
import com.ivor.ivormusic.data.spotify.SpotifyWebApi

/**
 * Bloque de búsqueda de Spotify para una pantalla de Koda.
 *
 * Lee el [SpotifyPlaybackMode] internamente y solo dibuja cuando el [slot]
 * coincide con el modo activo: TOP para spotify_preferred, BOTTOM para
 * youtube_music. Encapsula estado, efectos y dibujo; la pantalla solo llama
 * una vez arriba y otra abajo.
 */
@Composable
fun LazyListScope.SpotifySearchBlock(
    slot: SpotifyBlockSlot,
    query: String,
    kind: SpotifySearchKind,
    onPlay: (String) -> Unit,
    onOpen: (String) -> Unit,
    onLoadMore: () -> Unit,
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
    val spotifyBackend = remember { SpotifyBackend.get(context) }
    var spotifySearchState by remember { mutableStateOf(SpotifySearchState()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query, spotifyState.connected) {
        if (query.isBlank() || !spotifyState.connected) {
            spotifySearchState = SpotifySearchState()
            return@LaunchedEffect
        }
        delay(350)
        spotifySearchState = spotifySearchState.copy(
            query = query,
            loading = true,
            failure = null,
        )
        try {
            val results = spotifyApi.search(query)
            spotifySearchState = SpotifySearchState(
                query = query,
                tracks = results.tracks,
                albums = results.albums,
                playlists = results.playlists,
            )
        } catch (e: SpotifyApiException) {
            spotifySearchState = spotifySearchState.copy(
                query = query,
                loading = false,
                failure = e.failure,
            )
        } catch (_: Exception) {
            spotifySearchState = spotifySearchState.copy(
                query = query,
                loading = false,
                failure = SpotifyApiFailure.NETWORK,
            )
        }
    }

    spotifySearchSection(
        state = spotifySearchState,
        kind = kind,
        onPlay = onPlay,
        onOpen = onOpen,
        onLoadMore = onLoadMore,
    )
}
