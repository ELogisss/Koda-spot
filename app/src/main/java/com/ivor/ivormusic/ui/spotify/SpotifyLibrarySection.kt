package com.ivor.ivormusic.ui.spotify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ivor.ivormusic.R
import com.ivor.ivormusic.data.spotify.SpotifyApiFailure
import com.ivor.ivormusic.data.spotify.SpotifyLibraryState
import com.ivor.ivormusic.data.spotify.SpotifyPlaylistItem
import com.ivor.ivormusic.data.spotify.spotifyTrackId
import com.ivor.ivormusic.ui.components.AdaptiveModalSheet

/**
 * The Spotify block inside the library.
 *
 * It shows what lives in the Spotify account - saved songs and playlists -
 * beside Koda's own library rather than inside it. Nothing here is copied or
 * downloaded: a tap sends `play(uri)` to the Spotify app, and the block says
 * so, because the Koda queue does not take over until a later phase.
 *
 * Saved songs are shown as a single entry card ("Liked songs") that opens a
 * dedicated screen. The list itself is not drawn here: it lives on
 * [SpotifyLikedSongsScreen], paginated in pages of 50.
 */
fun LazyListScope.spotifyLibrarySection(
    state: SpotifyLibraryState,
    onPlay: (String) -> Unit,
    onOpen: (String) -> Unit,
    onOpenPlaylist: (SpotifyPlaylistItem) -> Unit,
    onOpenLikedSongs: () -> Unit,
    onRetry: () -> Unit,
) {
    item(key = "spotify-library-header") {
        SpotifySectionHeader(
            title = stringResource(R.string.spotify_library_title),
            count = (state.tracks.size + state.playlists.size).takeIf { it > 0 },
            showQueueNote = true,
        )
    }

    when {
        state.loading && state.tracks.isEmpty() && state.playlists.isEmpty() ->
            item(key = "spotify-library-loading") { SpotifyLoadingRow() }

        state.failure != null && state.tracks.isEmpty() && state.playlists.isEmpty() ->
            item(key = "spotify-library-error") {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    SpotifyFailureRow(state.failure)
                    OutlinedButton(onClick = onRetry) {
                        Text(stringResource(R.string.spotify_retry))
                    }
                }
            }

        state.tracks.isEmpty() && state.playlists.isEmpty() ->
            item(key = "spotify-library-empty") {
                Text(
                    stringResource(R.string.spotify_library_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
    }

    // Saved songs: one entry card, not the full list. Tapping opens the
    // dedicated Liked songs screen (paginated, offset-based).
    if (state.tracks.isNotEmpty()) {
        item(key = "spotify-library-liked-songs") {
            SpotifyLikedSongsCard(
                trackCount = state.tracks.size,
                onClick = onOpenLikedSongs,
            )
        }
    }

    if (state.playlists.isNotEmpty()) {
        item(key = "spotify-library-playlists-header") {
            SpotifySubHeader(stringResource(R.string.spotify_playlists))
            Text(
                stringResource(R.string.spotify_playlists_followed_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }
        state.playlists.forEachIndexed { index, playlist ->
            item(key = "spotify-library-playlist-$index-${playlist.id}") {
                SpotifyPlaylistRow(
                    playlist = playlist,
                    onPlay = {
                        if (playlist.owned) onOpenPlaylist(playlist) else onPlay(playlist.uri)
                    },
                    onOpen = { onOpen(playlist.webUrl) },
                )
            }
        }
    }
}

@Composable
private fun SpotifySubHeader(title: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SpotifyMark(Modifier.size(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * A playlist the user owns or collaborates on, opened to its songs.
 *
 * The songs are fetched by the caller; this draws what it has and says so when
 * Spotify refused. Every row plays through the Spotify app, like the block.
 */
@Composable
fun SpotifyPlaylistSheet(
    playlist: SpotifyPlaylistItem,
    songs: List<com.ivor.ivormusic.data.Song>,
    loading: Boolean,
    failure: SpotifyApiFailure?,
    onDismiss: () -> Unit,
    onPlay: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    AdaptiveModalSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SpotifyMark(Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        playlist.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.spotify_attribution),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onOpen(playlist.webUrl) }) {
                    Text(stringResource(R.string.spotify_open))
                }
            }

            when {
                loading -> SpotifyLoadingRow()
                failure != null -> SpotifyFailureRow(failure)
                songs.isEmpty() -> Text(
                    stringResource(R.string.spotify_playlist_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    songs.forEachIndexed { index, song ->
                        item(key = "spotify-sheet-$index-${song.id}") {
                            SpotifyTrackRow(
                                song = song,
                                onPlay = { onPlay(song.id) },
                                onOpen = {
                                    onOpen("https://open.spotify.com/track/${song.id.spotifyTrackId()}")
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
