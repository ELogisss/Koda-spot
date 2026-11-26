package com.ivor.ivormusic.ui.settings

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ivor.ivormusic.R
import com.ivor.ivormusic.data.spotify.SpotifyAuth
import com.ivor.ivormusic.data.spotify.SpotifyBackend
import com.ivor.ivormusic.data.spotify.SpotifyCommand
import com.ivor.ivormusic.data.spotify.SpotifyFailure
import com.ivor.ivormusic.data.spotify.SpotifyRedirect
import com.ivor.ivormusic.data.spotify.SpotifyRemoteState
import com.ivor.ivormusic.data.spotify.SpotifyRemoteStatus
import com.ivor.ivormusic.data.spotify.SpotifyRepeat
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SpotifyViewModel(application: Application) : AndroidViewModel(application) {
    val auth = SpotifyAuth.get(application)
    val state = auth.state
    val backend = SpotifyBackend.get(application)
}

@Composable
internal fun SpotifyHubRow(
    onClick: () -> Unit,
    selected: Boolean = false,
    model: SpotifyViewModel = viewModel()
) {
    val state by model.state.collectAsStateWithLifecycle()
    SettingsHubRow(
        icon = Icons.Rounded.Album,
        title = stringResource(R.string.spotify_title),
        value = if (state.connected) state.account else stringResource(R.string.spotify_not_connected),
        onClick = onClick,
        tint = MaterialTheme.colorScheme.tertiary,
        explanation = stringResource(R.string.spotify_hub_explanation),
        selected = selected
    )
}

@Composable
internal fun SpotifySettingsPage(onBack: () -> Unit, model: SpotifyViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val auth = model.auth
    val context = LocalContext.current
    @Suppress("DEPRECATION") // The newer clipboard API has no plain-text one-liner; Last.fm's page uses this too.
    val clipboard = LocalClipboardManager.current
    var clientId by rememberSaveable { mutableStateOf(state.clientId) }
    var copied by remember { mutableStateOf(false) }
    val redirect = state.redirect
    val backend = model.backend
    val remote by backend.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val send: (SpotifyCommand) -> Unit = { command ->
        scope.launch { backend.execute(command)?.let(backend::report) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    /*
     * Spotify's lifecycle guide, not ours: connect while the controls are on
     * screen, disconnect when they are not, and never leave the connection
     * behind in the background or Spotify cannot shut down. repeatOnLifecycle
     * gives exactly that boundary, and its cancellation is where the
     * disconnect lives, so leaving the page and backgrounding the app both
     * let go.
     */
    LaunchedEffect(lifecycleOwner, state.connected) {
        if (!state.connected) {
            backend.disconnect()
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            backend.connect()
            try {
                awaitCancellation()
            } finally {
                backend.disconnect()
            }
        }
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    val openBrowser: (String) -> Unit = { url ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            auth.reportBrowserFailure()
        }
    }

    SettingsDetailScaffold(title = stringResource(R.string.spotify_title), onBack = onBack, itemSpacing = 4.dp) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Rounded.Album, null, modifier = Modifier.size(40.dp))
                    Text(
                        stringResource(R.string.spotify_intro_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(stringResource(R.string.spotify_intro_body), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        state.error?.let { error ->
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                ) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Info, null)
                        Text(error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        if (state.connected) {
            item {
                SettingsSection(title = stringResource(R.string.spotify_section_account)) {
                    SettingsCard {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (state.busy) LoadingIndicator(Modifier.size(24.dp))
                                else Icon(
                                    Icons.Rounded.CheckCircle,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    stringResource(R.string.spotify_account, state.account.ifBlank { "\u2014" }),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            if (state.checkedAt > 0) {
                                Text(
                                    stringResource(
                                        R.string.spotify_tested_at,
                                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                            .format(Date(state.checkedAt))
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (state.checkOk) {
                                Text(
                                    stringResource(R.string.spotify_test_ok),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Button(
                                onClick = { auth.test() },
                                enabled = !state.busy,
                                shapes = ButtonDefaults.shapes(),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.spotify_test))
                            }
                        }
                    }
                }
            }
            item {
                SpotifyRemoteSection(
                    state = remote,
                    onCommand = send,
                    onRetry = { backend.connect() }
                )
            }
            item {
                SettingsSection(title = stringResource(R.string.spotify_redirect_title)) {
                    SettingsCard {
                        SpotifyRedirectDetail(redirect, copied, showLabel = false) {
                            clipboard.setText(AnnotatedString(redirect.uri))
                            copied = true
                        }
                        SettingsChoiceRow(
                            icon = Icons.Rounded.Album,
                            title = stringResource(R.string.spotify_redirect_mode),
                            subtitle = stringResource(R.string.spotify_redirect_mode_help),
                            labels = listOf(
                                stringResource(R.string.spotify_redirect_https),
                                stringResource(R.string.spotify_redirect_scheme)
                            ),
                            selectedIndex = if (redirect == SpotifyRedirect.APP_SCHEME) 1 else 0,
                            onSelect = { index ->
                                auth.setRedirect(
                                    if (index == 1) SpotifyRedirect.APP_SCHEME else SpotifyRedirect.HTTPS_BRIDGE
                                )
                            }
                        )
                    }
                }
            }
            item {
                TextButton(
                    onClick = auth::disconnect,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.spotify_sign_out))
                }
            }
        } else {
            item {
                SettingsSection(title = stringResource(R.string.spotify_section_account)) {
                    SettingsCard {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            OutlinedTextField(
                                value = clientId,
                                onValueChange = { clientId = it.trim() },
                                singleLine = true,
                                enabled = !state.busy && !state.pending,
                                label = { Text(stringResource(R.string.spotify_client_id)) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium
                            )
                            Text(
                                stringResource(R.string.spotify_client_id_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        SettingsChoiceRow(
                            icon = Icons.Rounded.Album,
                            title = stringResource(R.string.spotify_redirect_mode),
                            subtitle = stringResource(R.string.spotify_redirect_mode_help),
                            labels = listOf(
                                stringResource(R.string.spotify_redirect_https),
                                stringResource(R.string.spotify_redirect_scheme)
                            ),
                            selectedIndex = if (redirect == SpotifyRedirect.APP_SCHEME) 1 else 0,
                            onSelect = { index ->
                                auth.setRedirect(
                                    if (index == 1) SpotifyRedirect.APP_SCHEME else SpotifyRedirect.HTTPS_BRIDGE
                                )
                            }
                        )
                        SpotifyRedirectDetail(redirect, copied) {
                            clipboard.setText(AnnotatedString(redirect.uri))
                            copied = true
                        }
                    }
                }
            }
            if (state.pending) {
                item {
                    SettingsCard {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (state.busy) LoadingIndicator(Modifier.size(24.dp))
                                else Icon(Icons.AutoMirrored.Rounded.OpenInNew, null)
                                Text(
                                    stringResource(R.string.spotify_waiting),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            OutlinedButton(
                                onClick = auth::cancelLogin,
                                enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(R.string.spotify_cancel_sign_in))
                            }
                        }
                    }
                }
            } else {
                item {
                    Button(
                        onClick = { auth.beginLogin(clientId, openBrowser) },
                        enabled = !state.busy,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        if (state.busy) LoadingIndicator(Modifier.size(24.dp))
                        else Text(stringResource(R.string.spotify_sign_in))
                    }
                }
            }
        }

        item {
            Text(
                stringResource(R.string.spotify_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun SpotifyRemoteSection(
    state: SpotifyRemoteState,
    onCommand: (SpotifyCommand) -> Unit,
    onRetry: () -> Unit
) {
    val playback = state.playback
    val shuffleOn = playback?.shuffle == true
    val repeatOn = playback?.repeat?.let { it != SpotifyRepeat.OFF } == true
    SettingsSection(title = stringResource(R.string.spotify_app_title)) {
        SettingsCard {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (state.status == SpotifyRemoteStatus.CONNECTING ||
                        state.status == SpotifyRemoteStatus.RECONNECTING
                    ) {
                        LoadingIndicator(Modifier.size(20.dp))
                    } else {
                        Icon(
                            if (state.status == SpotifyRemoteStatus.CONNECTED) Icons.Rounded.CheckCircle
                            else Icons.Rounded.Info,
                            contentDescription = null,
                            tint = if (state.status == SpotifyRemoteStatus.CONNECTED) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    Text(
                        when (state.status) {
                            SpotifyRemoteStatus.IDLE -> stringResource(R.string.spotify_app_idle)
                            SpotifyRemoteStatus.CONNECTING -> stringResource(R.string.spotify_app_connecting)
                            SpotifyRemoteStatus.CONNECTED -> stringResource(R.string.spotify_app_connected)
                            SpotifyRemoteStatus.RECONNECTING -> stringResource(
                                R.string.spotify_app_reconnecting,
                                (state.retryInMs / 1000).coerceAtLeast(1)
                            )
                            SpotifyRemoteStatus.FAILED -> stringResource(R.string.spotify_app_failed)
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    stringResource(R.string.spotify_app_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.failure?.let { failure ->
                    Text(
                        stringResource(failure.messageRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                if (state.status == SpotifyRemoteStatus.FAILED &&
                    state.failure != SpotifyFailure.APP_NOT_INSTALLED
                ) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.spotify_app_retry))
                    }
                }
                if (state.status == SpotifyRemoteStatus.CONNECTED) {
                    val track = playback?.track
                    if (track == null) {
                        Text(
                            stringResource(R.string.spotify_app_nothing_playing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                track.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                listOf(track.artist, track.album)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" \u00b7 "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    state.canPlayOnDemand?.let { onDemand ->
                        Text(
                            stringResource(
                                if (onDemand) R.string.spotify_on_demand_yes
                                else R.string.spotify_on_demand_no
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { onCommand(SpotifyCommand.SkipPrevious) },
                            enabled = playback?.canSkipPrevious == true
                        ) {
                            Icon(
                                Icons.Rounded.SkipPrevious,
                                stringResource(R.string.spotify_previous)
                            )
                        }
                        FilledIconButton(
                            onClick = {
                                onCommand(
                                    if (playback?.isPaused == true) SpotifyCommand.Resume
                                    else SpotifyCommand.Pause
                                )
                            },
                            enabled = playback?.track != null
                        ) {
                            if (playback?.isPaused == true) {
                                Icon(Icons.Rounded.PlayArrow, stringResource(R.string.spotify_resume))
                            } else {
                                Icon(Icons.Rounded.Pause, stringResource(R.string.spotify_pause))
                            }
                        }
                        IconButton(
                            onClick = { onCommand(SpotifyCommand.SkipNext) },
                            enabled = playback?.canSkipNext == true
                        ) {
                            Icon(Icons.Rounded.SkipNext, stringResource(R.string.spotify_next))
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { onCommand(SpotifyCommand.ToggleShuffle) },
                            enabled = playback?.canToggleShuffle == true
                        ) {
                            Icon(
                                Icons.Rounded.Shuffle,
                                stringResource(R.string.spotify_shuffle),
                                tint = if (shuffleOn) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { onCommand(SpotifyCommand.ToggleRepeat) },
                            enabled = playback != null && (playback.canRepeatOne || playback.canRepeatAll)
                        ) {
                            Icon(
                                Icons.Rounded.Repeat,
                                stringResource(R.string.spotify_repeat),
                                tint = if (repeatOn) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { onCommand(SpotifyCommand.SeekBy(-SEEK_STEP_MS)) },
                            enabled = playback?.canSeek == true
                        ) {
                            Icon(Icons.Rounded.Replay10, stringResource(R.string.spotify_back_ten))
                        }
                        IconButton(
                            onClick = { onCommand(SpotifyCommand.SeekBy(SEEK_STEP_MS)) },
                            enabled = playback?.canSeek == true
                        ) {
                            Icon(Icons.Rounded.Forward10, stringResource(R.string.spotify_forward_ten))
                        }
                    }
                }
            }
        }
    }
}

/** One press of the seek buttons. */
private const val SEEK_STEP_MS = 10_000L

@StringRes
private fun SpotifyFailure.messageRes(): Int = when (this) {
    SpotifyFailure.APP_NOT_INSTALLED -> R.string.spotify_failure_not_installed
    SpotifyFailure.NOT_SIGNED_IN -> R.string.spotify_failure_not_signed_in
    SpotifyFailure.NOT_AUTHORIZED -> R.string.spotify_failure_not_authorized
    SpotifyFailure.AUTH_FAILED -> R.string.spotify_failure_auth
    SpotifyFailure.OFFLINE -> R.string.spotify_failure_offline
    SpotifyFailure.UNSUPPORTED_VERSION -> R.string.spotify_failure_old_app
    SpotifyFailure.DISCONNECTED -> R.string.spotify_failure_disconnected
    SpotifyFailure.NO_CLIENT_ID -> R.string.spotify_failure_no_client_id
    SpotifyFailure.ON_DEMAND_UNAVAILABLE -> R.string.spotify_failure_on_demand
    SpotifyFailure.NO_ANSWER -> R.string.spotify_failure_no_answer
    SpotifyFailure.UNKNOWN -> R.string.spotify_failure_unknown
}

@Composable
private fun SpotifyRedirectDetail(
    redirect: SpotifyRedirect,
    copied: Boolean,
    showLabel: Boolean = true,
    onCopy: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showLabel) {
            Text(
                stringResource(R.string.spotify_redirect_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            redirect.uri,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            stringResource(R.string.spotify_redirect_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onCopy, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(if (copied) R.string.spotify_copied else R.string.spotify_copy))
        }
    }
}
