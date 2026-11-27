package com.ivor.ivormusic.ui.spotify

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ivor.ivormusic.R
import com.ivor.ivormusic.data.Song
import com.ivor.ivormusic.data.spotify.SpotifyAlbumItem
import com.ivor.ivormusic.data.spotify.SpotifyApiFailure
import com.ivor.ivormusic.data.spotify.SpotifyPlaylistItem
import com.ivor.ivormusic.data.spotify.SpotifySearchKind
import com.ivor.ivormusic.data.spotify.SpotifySearchState
import com.ivor.ivormusic.data.spotify.spotifyTrackId

/**
 * The Spotify block inside search results.
 *
 * A block of its own, never mixed with YouTube rows: the two play in different
 * apps, and a list that mixes them would have to explain, row by row, which
 * player a tap reaches. The header says where the tap goes, and every row
 * carries the Spotify mark as the attribution the design guidelines require -
 * the mark is a link to `open.spotify.com`, and it sits beside the artwork,
 * never on top of it.
 *
 * The block is emitted into the caller's `LazyListScope` so it scrolls with
 * the results it belongs to.
 */
fun LazyListScope.spotifySearchSection(
    state: SpotifySearchState,
    kind: SpotifySearchKind,
    onPlay: (String) -> Unit,
    onOpen: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    val items = when (kind) {
        SpotifySearchKind.TRACKS -> state.tracks.size
        SpotifySearchKind.ALBUMS -> state.albums.size
        SpotifySearchKind.PLAYLISTS -> state.playlists.size
    }

    item(key = "spotify-header-$kind") {
        SpotifySectionHeader(
            title = stringResource(R.string.spotify_source_title),
            count = items.takeIf { it > 0 },
            showQueueNote = true,
        )
    }

    when {
        state.loading && items == 0 -> item(key = "spotify-loading-$kind") {
            SpotifyLoadingRow()
        }

        state.failure != null && items == 0 -> item(key = "spotify-error-$kind") {
            SpotifyFailureRow(failure = state.failure)
        }

        items == 0 && state.query.isNotBlank() -> item(key = "spotify-empty-$kind") {
            Text(
                stringResource(R.string.spotify_search_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }

    when (kind) {
        SpotifySearchKind.TRACKS -> itemsIndexedStable("track", state.tracks) { song ->
            SpotifyTrackRow(
                song = song,
                onPlay = { onPlay(song.id) },
                onOpen = { onOpen("https://open.spotify.com/track/${song.id.spotifyTrackId()}") },
            )
        }

        SpotifySearchKind.ALBUMS -> itemsIndexedStable("album", state.albums) { album ->
            SpotifyAlbumRow(
                album = album,
                onPlay = { onPlay(album.uri) },
                onOpen = { onOpen(album.webUrl) },
            )
        }

        SpotifySearchKind.PLAYLISTS -> itemsIndexedStable("playlist", state.playlists) { playlist ->
            SpotifyPlaylistRow(
                playlist = playlist,
                onPlay = { onPlay(playlist.uri) },
                onOpen = { onOpen(playlist.webUrl) },
            )
        }
    }

    if (items > 0 && !state.exhausted) {
        item(key = "spotify-more-$kind") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (state.loadingMore) {
                    LoadingIndicator(Modifier.padding(16.dp).size(24.dp))
                } else {
                    TextButton(onClick = onLoadMore) {
                        Text(stringResource(R.string.spotify_show_more))
                    }
                }
            }
        }
    }
}

/** A `forEachIndexed` that emits lazy items without the generic shadowing trap. */
private inline fun <T> LazyListScope.itemsIndexedStable(
    keyPrefix: String,
    items: List<T>,
    crossinline content: @Composable (T) -> Unit,
) {
    items.forEachIndexed { index, item ->
        item(key = "spotify-$keyPrefix-$index-${item.hashCode()}") { content(item) }
    }
}

@Composable
internal fun SpotifySectionHeader(
    title: String,
    count: Int?,
    showQueueNote: Boolean,
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpotifyMark(Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (count != null) {
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.spotify_results_count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // Attribution: the guidelines require a link back and the mark, and
        // the sentence is the link's visible label.
        Text(
            stringResource(R.string.spotify_attribution),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showQueueNote) {
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.spotify_queue_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SpotifyTrackRow(song: Song, onPlay: () -> Unit, onOpen: () -> Unit) {
    SpotifyRow(
        artwork = song.thumbnailUrl,
        title = song.title,
        subtitle = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" \u00b7 "),
        trailing = song.duration.takeIf { it > 0 }?.let(::durationText),
        onPlay = onPlay,
        onOpen = onOpen,
        openLabel = stringResource(R.string.spotify_open_named, song.title),
    )
}

@Composable
private fun SpotifyAlbumRow(album: SpotifyAlbumItem, onPlay: () -> Unit, onOpen: () -> Unit) {
    SpotifyRow(
        artwork = album.thumbnailUrl,
        title = album.name,
        subtitle = listOfNotNull(
            album.artist.takeIf { it.isNotBlank() },
            album.releaseYear?.toString(),
        ).joinToString(" \u00b7 "),
        trailing = album.totalTracks.takeIf { it >= 0 }?.let {
            stringResource(R.string.spotify_track_count, it)
        },
        onPlay = onPlay,
        onOpen = onOpen,
        openLabel = stringResource(R.string.spotify_open_named, album.name),
    )
}

@Composable
internal fun SpotifyPlaylistRow(
    playlist: SpotifyPlaylistItem,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
) {
    SpotifyRow(
        artwork = playlist.thumbnailUrl,
        title = playlist.name,
        subtitle = listOfNotNull(
            playlist.owner.takeIf { it.isNotBlank() },
            playlist.itemCount.takeIf { it >= 0 }?.let {
                stringResource(R.string.spotify_track_count, it)
            },
        ).joinToString(" \u00b7 "),
        trailing = null,
        onPlay = onPlay,
        onOpen = onOpen,
        openLabel = stringResource(R.string.spotify_open_named, playlist.name),
    )
}

/**
 * One row: artwork, two lines, an optional fact, and the mark as the link.
 *
 * The artwork is drawn with `ContentScale.Fit` in a fixed square and nothing is
 * layered over it, because the guidelines forbid cropping or covering Spotify
 * covers. The mark lives in the trailing slot, clear of the image.
 */
@Composable
private fun SpotifyRow(
    artwork: String?,
    title: String,
    subtitle: String,
    trailing: String?,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    openLabel: String,
) {
    Surface(
        onClick = onPlay,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 3.dp)
    ) {
        Row(
            Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (!artwork.isNullOrBlank()) {
                    AsyncImage(
                        model = artwork,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Text(
                    trailing,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpen) {
                SpotifyMark(
                    Modifier.size(24.dp),
                    contentDescription = openLabel,
                )
            }
        }
    }
}

/**
 * The Spotify mark, at the guidelines' minimum size or above and tinted by the
 * scheme: `onSurface` is the dark mark on light surfaces and the light mark on
 * dark ones, which is the monochrome variant the guidelines ask for on any
 * background that is not black or white.
 */
@Composable
internal fun SpotifyMark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Icon(
        painter = painterResource(R.drawable.ic_spotify_logo),
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

@Composable
internal fun SpotifyLoadingRow() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        LoadingIndicator(Modifier.size(28.dp))
    }
}

@Composable
internal fun SpotifyFailureRow(failure: SpotifyApiFailure?) {
    Text(
        stringResource(failure.messageRes()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

/** The one sentence each failure gets, shared by search and the library block. */
@StringRes
internal fun SpotifyApiFailure?.messageRes(): Int = when (this) {
    SpotifyApiFailure.NO_SESSION -> R.string.spotify_api_no_session
    SpotifyApiFailure.RATE_LIMITED -> R.string.spotify_api_rate_limited
    SpotifyApiFailure.QUOTA -> R.string.spotify_api_quota
    SpotifyApiFailure.FORBIDDEN -> R.string.spotify_api_forbidden
    SpotifyApiFailure.NOT_FOUND -> R.string.spotify_api_not_found
    SpotifyApiFailure.SERVER -> R.string.spotify_api_server
    SpotifyApiFailure.NETWORK -> R.string.spotify_api_network
    null, SpotifyApiFailure.UNKNOWN -> R.string.spotify_api_unknown
}

private fun durationText(ms: Long): String {
    val totalSeconds = (ms / 1_000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
