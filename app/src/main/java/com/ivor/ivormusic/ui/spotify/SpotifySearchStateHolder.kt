package com.ivor.ivormusic.ui.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import com.ivor.ivormusic.data.spotify.SpotifyAuth
import com.ivor.ivormusic.data.spotify.SpotifyBackend
import com.ivor.ivormusic.data.spotify.SpotifyCommand
import com.ivor.ivormusic.data.spotify.SpotifySearchKind
import com.ivor.ivormusic.data.spotify.SpotifySearchResults
import com.ivor.ivormusic.data.spotify.SpotifySearchState
import com.ivor.ivormusic.data.spotify.SpotifyWebApi

/**
 * Holds the Spotify search state and effects so the search screen only
 * emits the section block. One line of hook in SearchScreen.kt.
 *
 * [state] is owned by the caller so the section can be drawn from it inside
 * the LazyColumn.
 */
@Composable
fun SpotifySearchStateHolder(
    query: String,
    kind: SpotifySearchKind,
    state: androidx.compose.runtime.MutableState<SpotifySearchState>,
    onPlay: (String) -> Unit,
    onOpen: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    val context: Context = androidx.compose.ui.platform.LocalContext.current
    val spotifyAuth = remember { SpotifyAuth.get(context) }
    val spotifyState by spotifyAuth.state.collectAsState()
    val spotifyApi = remember { SpotifyWebApi.get(context) }
    val spotifyBackend = remember { SpotifyBackend.get(context) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query, spotifyState.connected) {
        if (query.isBlank() || !spotifyState.connected) {
            state.value = SpotifySearchState()
            return@LaunchedEffect
        }
        delay(350)
        state.value = state.value.copy(
            query = query,
            loading = true,
            failure = null,
        )
        try {
            val results: SpotifySearchResults = spotifyApi.search(query)
            state.value = SpotifySearchState(
                query = query,
                tracks = results.tracks,
                albums = results.albums,
                playlists = results.playlists,
            )
        } catch (e: com.ivor.ivormusic.data.spotify.SpotifyApiException) {
            state.value = state.value.copy(
                query = query,
                loading = false,
                failure = e.failure,
            )
        } catch (_: Exception) {
            state.value = state.value.copy(
                query = query,
                loading = false,
                failure = com.ivor.ivormusic.data.spotify.SpotifyApiFailure.NETWORK,
            )
        }
    }

    // No UI is drawn here: the caller draws spotifySearchSection from [state].
}
