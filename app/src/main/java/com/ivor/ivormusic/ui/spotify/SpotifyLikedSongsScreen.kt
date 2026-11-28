package com.ivor.ivormusic.ui.spotify

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import com.ivor.ivormusic.data.Song
import com.ivor.ivormusic.data.spotify.SpotifyApiException
import com.ivor.ivormusic.data.spotify.SpotifyApiFailure
import com.ivor.ivormusic.data.spotify.SpotifyAuth
import com.ivor.ivormusic.data.spotify.SpotifyBackend
import com.ivor.ivormusic.data.spotify.SpotifyCommand
import com.ivor.ivormusic.data.spotify.SpotifyWebApi
import com.ivor.ivormusic.data.spotify.spotifyTrackId
import com.ivor.ivormusic.R
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp

/**
 * Dedicated screen for the user's Spotify liked songs. Paginates in pages of
 * 50 using offset, with loading, empty, error and 429 states. No YouTube
 * rows are mixed in. No Scaffold: the caller hosts it (e.g. LibraryScreen
 * swaps content when the entry card is tapped). Playback is handled inside
 * this screen through the App Remote backend.
 */
@Composable
fun SpotifyLikedSongsScreen(
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val spotifyAuth = remember { SpotifyAuth.get(context) }
    val spotifyState by spotifyAuth.state.collectAsState()
    val spotifyApi = remember { SpotifyWebApi.get(context) }
    val spotifyBackend = remember { SpotifyBackend.get(context) }
    val scope = rememberCoroutineScope()
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<SpotifyApiFailure?>(null) }
    var offset by mutableIntStateOf(0)
    var exhausted by remember { mutableStateOf(false) }

    fun loadPage(newOffset: Int) {
        scope.launch {
            if (newOffset == 0) {
                loading = true
                failure = null
                songs = emptyList()
                offset = 0
                exhausted = false
            }
            try {
                val page = spotifyApi.savedTracks(offset = newOffset)
                if (newOffset == 0) {
                    songs = page
                } else {
                    songs = songs + page
                }
                offset = newOffset + page.size
                exhausted = page.size < 50
                loading = false
            } catch (e: SpotifyApiException) {
                failure = e.failure
                loading = false
            } catch (_: Exception) {
                failure = SpotifyApiFailure.NETWORK
                loading = false
            }
        }
    }

    LaunchedEffect(spotifyState.connected) {
        if (!spotifyState.connected) return@LaunchedEffect
        loadPage(0)
    }

    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.spotify_back))
            }
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            Text(
                stringResource(com.ivor.ivormusic.R.string.spotify_saved_songs),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (loading && songs.isEmpty()) {
            SpotifyLoadingRow()
        }
        if (failure != null && songs.isEmpty()) {
            SpotifyFailureRow(failure)
        }
        if (songs.isEmpty() && !loading && failure == null) {
            Text(
                stringResource(com.ivor.ivormusic.R.string.spotify_library_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed(songs) { index, song ->
                SpotifyTrackRow(
                    song = song,
                    onPlay = {
                        scope.launch {
                            spotifyBackend.connect()
                            spotifyBackend.execute(SpotifyCommand.Play(song.id))
                        }
                    },
                    onOpen = {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(
                                    "https://open.spotify.com/track/${song.id.spotifyTrackId()}"
                                )
                            )
                        )
                    },
                )
            }
            if (!exhausted && songs.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { loadPage(offset) }) {
                            Text(stringResource(com.ivor.ivormusic.R.string.spotify_show_more))
                        }
                    }
                }
            }
        }
    }
}
