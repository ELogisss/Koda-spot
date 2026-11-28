package com.ivor.ivormusic.data.spotify

import android.content.Context
import com.ivor.ivormusic.util.KLog
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.Capabilities
import com.spotify.protocol.types.Empty
import com.spotify.protocol.types.PlayerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The connection to the official Spotify app, and the only class here that
 * imports the App Remote SDK.
 *
 * Koda plays nothing. This sends orders to Spotify and reads back what Spotify
 * says it is doing; the audio, the queue, the network and the notification all
 * belong to the Spotify app. Every decision that can be made without the SDK -
 * which command is legal, what a failure means, how long to wait - lives in
 * [SpotifyPlayback] instead, so this file stays a thin translation layer.
 *
 * ## The connection is not a service
 *
 * Spotify's own lifecycle guide is explicit: connect while the controls are on
 * screen, disconnect when they are not, and never hold the connection in the
 * background, because Spotify cannot shut down while something is attached. So
 * [connect] and [disconnect] are driven by the page's lifecycle, not by the
 * process. What this class does own is the case the plan calls out: Spotify
 * closing, crashing or being killed ends the connection underneath us, and a
 * retry brings it back when the page is still there.
 *
 * ## Authorization
 *
 * The SDK authorizes itself inside the Spotify app: with `showAuthView(true)` it
 * asks for `app-remote-control` the first time and remembers it. Koda's own
 * PKCE token is for the Web API and is not involved, which is why the client ID
 * and redirect are all this needs from [SpotifyAuth].
 */
class SpotifyBackend private constructor(context: Context) {

    private val app = context.applicationContext
    private val auth = SpotifyAuth.get(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(SpotifyRemoteState())

    /** The whole remote as one value; the settings page and, later, the player draw it. */
    val state: StateFlow<SpotifyRemoteState> = mutable.asStateFlow()

    @Volatile private var remote: SpotifyAppRemote? = null
    private var playerSubscription: Subscription<PlayerState>? = null
    private var capabilitySubscription: Subscription<Capabilities>? = null
    private var retry: Job? = null
    private var wanted = false
    private var connecting = false
    private var attempts = 0

    /**
     * Ask for a connection and keep it until [disconnect].
     *
     * Calling it while connected is free, which matters because it is called
     * from the page's start: Spotify's guide warns that a second connection
     * keeps the Spotify app alive until Koda is killed.
     */
    fun connect() {
        wanted = true
        if (remote?.isConnected() == true || connecting) return
        retry?.cancel()
        retry = null
        attempts = 0
        open()
    }

    /** Let go on purpose. Spotify is then free to shut down. */
    fun disconnect() {
        wanted = false
        connecting = false
        attempts = 0
        retry?.cancel()
        retry = null
        cancelSubscriptions()
        val current = remote
        remote = null
        if (current != null) {
            try {
                SpotifyAppRemote.disconnect(current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Already gone: the app closed between the check and the call.
                KLog.w(TAG, "Spotify disconnect failed", e)
            }
        }
        mutable.value = SpotifyRemoteState()
    }

    /**
     * Send one command.
     *
     * Returns null when Spotify accepted it, or the reason it did not. The
     * caller decides whether to show it; a rejected command is not an exception
     * because a skip that the context forbids is an ordinary answer.
     */
    suspend fun execute(command: SpotifyCommand): SpotifyFailure? {
        val current = remote
        if (current == null || !current.isConnected()) {
            if (wanted) open()
            return SpotifyFailure.DISCONNECTED
        }
        if (command.requiresOnDemand() && state.value.canPlayOnDemand == false) {
            return SpotifyFailure.ON_DEMAND_UNAVAILABLE
        }
        val call = try {
            current.send(command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SpotifyFailure.of(e)
        }
        val result = withTimeoutOrNull(COMMAND_TIMEOUT_MS) { call.awaitAnswer() }
            ?: CommandAnswer.Failed(SpotifyFailure.NO_ANSWER)
        return when (result) {
            CommandAnswer.Ok -> {
                if (state.value.failure != null) mutable.value = state.value.copy(failure = null)
                null
            }
            is CommandAnswer.Failed -> {
                if (result.failure.retryable) lost(result.failure)
                result.failure
            }
        }
    }

    /** Re-read the player and the account capability, for a pull-to-refresh or a page open. */
    fun refresh() {
        val current = remote ?: return
        current.userApi.getCapabilities()
            .setResultCallback { c -> mutable.value = mutable.value.copy(canPlayOnDemand = c.canPlayOnDemand) }
            .setErrorCallback { /* advisory: playback works without knowing */ }
        current.playerApi.getPlayerState()
            .setResultCallback { s -> mutable.value = mutable.value.copy(playback = s.toPlayback()) }
            .setErrorCallback { }
    }

    /**
     * Show a rejected command without pretending the connection is gone.
     *
     * `execute` already puts a lost connection into the state; this is for the
     * answers that are not losses, such as an account that cannot start a single
     * track on demand.
     */
    fun report(failure: SpotifyFailure) {
        mutable.value = mutable.value.copy(failure = failure)
    }

    private fun open() {
        if (!wanted || connecting) return
        val clientId = auth.state.value.clientId
        if (clientId.isEmpty()) {
            fail(SpotifyFailure.NO_CLIENT_ID)
            return
        }
        // Checked before connecting so the message is "Spotify is not installed"
        // rather than a generic connection failure. The AAR's own manifest
        // declares the package queries this needs on Android 11 and later.
        if (!SpotifyAppRemote.isSpotifyInstalled(app)) {
            fail(SpotifyFailure.APP_NOT_INSTALLED)
            return
        }
        connecting = true
        mutable.value = mutable.value.copy(
            status = if (attempts == 0) SpotifyRemoteStatus.CONNECTING else SpotifyRemoteStatus.RECONNECTING,
            failure = null,
            retryInMs = 0
        )
        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(auth.state.value.redirect.uri)
            .showAuthView(true)
            .build()
        try {
            SpotifyAppRemote.connect(app, params, listener)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            connecting = false
            lost(SpotifyFailure.of(e))
        }
    }

    private val listener = object : Connector.ConnectionListener {
        override fun onConnected(spotifyAppRemote: SpotifyAppRemote) {
            connecting = false
            attempts = 0
            remote = spotifyAppRemote
            mutable.value = mutable.value.copy(
                status = SpotifyRemoteStatus.CONNECTED,
                failure = null,
                retryInMs = 0
            )
            subscribe(spotifyAppRemote)
        }

        override fun onFailure(error: Throwable) {
            connecting = false
            remote = null
            lost(SpotifyFailure.of(error))
        }
    }

    private fun subscribe(spotify: SpotifyAppRemote) {
        cancelSubscriptions()

        val player = spotify.playerApi.subscribeToPlayerState()
            .setLifecycleCallback(object : Subscription.LifecycleCallback {
                override fun onStart() = Unit

                override fun onStop() {
                    // The Spotify app ends the subscription when it closes or is
                    // killed. That is the reconnect trigger the plan asks for.
                    // Posted rather than handled inline: the SDK can deliver this
                    // from inside our own cancel(), and the remote check drops
                    // that echo once teardown has run.
                    if (!wanted) return
                    scope.launch {
                        if (wanted && remote != null) lost(SpotifyFailure.DISCONNECTED)
                    }
                }
            })
            .setEventCallback { playerState ->
                mutable.value = mutable.value.copy(playback = playerState.toPlayback())
            }
        player.setErrorCallback { error -> lost(SpotifyFailure.of(error)) }
        playerSubscription = player

        val capabilities = spotify.userApi.subscribeToCapabilities()
            .setEventCallback { c ->
                mutable.value = mutable.value.copy(canPlayOnDemand = c.canPlayOnDemand)
            }
        capabilities.setErrorCallback { /* advisory */ }
        capabilitySubscription = capabilities

        // Both reads also happen as subscriptions, but the first event can take
        // a moment and the page should not open blank.
        spotify.userApi.getCapabilities()
            .setResultCallback { c -> mutable.value = mutable.value.copy(canPlayOnDemand = c.canPlayOnDemand) }
            .setErrorCallback { }
        spotify.playerApi.getPlayerState()
            .setResultCallback { s -> mutable.value = mutable.value.copy(playback = s.toPlayback()) }
            .setErrorCallback { }
    }

    /**
     * The connection ended, or could not start.
     *
     * A lost connection is retried while the page still wants one; everything
     * else stops and waits for the person, because no timer installs Spotify or
     * grants a permission.
     */
    private fun lost(failure: SpotifyFailure) {
        connecting = false
        cancelSubscriptions()
        remote = null
        if (!wanted) {
            mutable.value = SpotifyRemoteState()
            return
        }
        if (!failure.retryable) {
            fail(failure)
            return
        }
        attempts += 1
        val wait = SpotifyRetry.delayMs(attempts)
        mutable.value = mutable.value.copy(
            status = SpotifyRemoteStatus.RECONNECTING,
            playback = null,
            failure = failure,
            retryInMs = wait
        )
        retry?.cancel()
        retry = scope.launch {
            delay(wait)
            if (wanted) open()
        }
    }

    private fun fail(failure: SpotifyFailure) {
        mutable.value = mutable.value.copy(
            status = SpotifyRemoteStatus.FAILED,
            playback = null,
            failure = failure,
            retryInMs = 0
        )
    }

    private fun cancelSubscriptions() {
        playerSubscription?.cancel()
        playerSubscription = null
        capabilitySubscription?.cancel()
        capabilitySubscription = null
    }

    private fun SpotifyAppRemote.send(command: SpotifyCommand): CallResult<Empty> = when (command) {
        is SpotifyCommand.Play -> playerApi.play(command.uri)
        is SpotifyCommand.Queue -> playerApi.queue(command.uri)
        SpotifyCommand.Pause -> playerApi.pause()
        SpotifyCommand.Resume -> playerApi.resume()
        SpotifyCommand.SkipNext -> playerApi.skipNext()
        SpotifyCommand.SkipPrevious -> playerApi.skipPrevious()
        is SpotifyCommand.SeekTo -> playerApi.seekTo(command.positionMs)
        is SpotifyCommand.SeekBy -> playerApi.seekToRelativePosition(command.deltaMs)
        is SpotifyCommand.SetShuffle -> playerApi.setShuffle(command.enabled)
        SpotifyCommand.ToggleShuffle -> playerApi.toggleShuffle()
        is SpotifyCommand.SetRepeat -> playerApi.setRepeat(command.mode.code)
        SpotifyCommand.ToggleRepeat -> playerApi.toggleRepeat()
    }

    /**
     * The SDK answers on the main thread through these two callbacks, and only
     * one of them ever fires. [withTimeoutOrNull] in [execute] covers the third
     * case: neither arrives because the connection died mid-call.
     */
    private suspend fun CallResult<Empty>.awaitAnswer(): CommandAnswer =
        suspendCancellableCoroutine { continuation ->
            setResultCallback { if (continuation.isActive) continuation.resume(CommandAnswer.Ok) }
                .setErrorCallback { error ->
                    if (continuation.isActive) {
                        continuation.resume(CommandAnswer.Failed(SpotifyFailure.of(error)))
                    }
                }
        }

    private fun PlayerState.toPlayback(): SpotifyPlayback {
        val current = track
        return SpotifyPlayback(
            track = current?.let {
                SpotifyTrack(
                    name = it.name.orEmpty(),
                    artist = it.artist?.name.orEmpty(),
                    album = it.album?.name.orEmpty(),
                    uri = it.uri.orEmpty(),
                    durationMs = it.duration,
                    imageUri = it.imageUri?.raw,
                    isEpisode = it.isEpisode,
                )
            },
            isPaused = isPaused,
            positionMs = playbackPosition,
            shuffle = playbackOptions?.isShuffling ?: false,
            repeat = SpotifyRepeat.fromCode(playbackOptions?.repeatMode ?: SpotifyRepeat.OFF.code),
            canSeek = playbackRestrictions?.canSeek ?: false,
            canSkipNext = playbackRestrictions?.canSkipNext ?: false,
            canSkipPrevious = playbackRestrictions?.canSkipPrev ?: false,
            canToggleShuffle = playbackRestrictions?.canToggleShuffle ?: false,
            canRepeatOne = playbackRestrictions?.canRepeatTrack ?: false,
            canRepeatAll = playbackRestrictions?.canRepeatContext ?: false,
        )
    }

    private sealed interface CommandAnswer {
        data object Ok : CommandAnswer
        data class Failed(val failure: SpotifyFailure) : CommandAnswer
    }

    companion object {
        private const val TAG = "SpotifyBackend"

        /** Longer than any healthy answer; shorter than a user's patience. */
        private const val COMMAND_TIMEOUT_MS = 8_000L

        @Volatile private var instance: SpotifyBackend? = null

        fun get(context: Context): SpotifyBackend = instance ?: synchronized(this) {
            instance ?: SpotifyBackend(context.applicationContext).also { instance = it }
        }
    }
}
