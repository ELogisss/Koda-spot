package com.ivor.ivormusic.ui.settings

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ivor.ivormusic.R
import com.ivor.ivormusic.data.spotify.SpotifyAuth
import com.ivor.ivormusic.data.spotify.SpotifyRedirect
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

class SpotifyViewModel(application: Application) : AndroidViewModel(application) {
    val auth = SpotifyAuth.get(application)
    val state = auth.state
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
