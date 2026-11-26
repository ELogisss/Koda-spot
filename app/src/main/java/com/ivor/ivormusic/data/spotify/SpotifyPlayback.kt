package com.ivor.ivormusic.data.spotify

/**
 * The remote-control rules, with no Android and no Spotify SDK types in them.
 *
 * The SDK speaks in Java classes (`PlayerState`, `CallResult`, exception
 * classes) that only exist on a device. Everything a decision depends on is
 * restated here as plain Kotlin, so the parts that are easy to get wrong - which
 * command is legal right now, which failure the user should read, how long to
 * wait before reconnecting - can be read, changed and tested without a phone.
 *
 * [SpotifyBackend] is the only class that touches the SDK; it translates both
 * ways and owns nothing but the connection.
 */

/**
 * Repeat, as the SDK numbers it.
 *
 * The SDK exposes these as the ints `Repeat.OFF`, `Repeat.ONE` and `Repeat.ALL`;
 * `setRepeat` takes a bare int, so the mapping has to live somewhere. Doing it
 * here keeps the numbers out of the UI and out of [SpotifyCommand].
 */
enum class SpotifyRepeat(val code: Int) {
    OFF(0),
    ONE(1),
    ALL(2);

    companion object {
        /** An unknown code from a newer Spotify app reads as off, never as a crash. */
        fun fromCode(code: Int): SpotifyRepeat = entries.firstOrNull { it.code == code } ?: OFF
    }
}

/**
 * One order for the Spotify app.
 *
 * Only what Koda's own transport needs. `play` and `queue` take a Spotify URI,
 * which is the only addressing the SDK accepts: there is no "play this track
 * object" call, and no way to read the queue back.
 */
sealed interface SpotifyCommand {
    /** Start a context (album, playlist, artist) or a single track. */
    data class Play(val uri: String) : SpotifyCommand

    /** Add to the end of the queue. */
    data class Queue(val uri: String) : SpotifyCommand

    data object Pause : SpotifyCommand
    data object Resume : SpotifyCommand
    data object SkipNext : SpotifyCommand
    data object SkipPrevious : SpotifyCommand

    data class SeekTo(val positionMs: Long) : SpotifyCommand

    /** Move relative to where Spotify is now, which does not go stale like an absolute read. */
    data class SeekBy(val deltaMs: Long) : SpotifyCommand

    data class SetShuffle(val enabled: Boolean) : SpotifyCommand
    data object ToggleShuffle : SpotifyCommand

    data class SetRepeat(val mode: SpotifyRepeat) : SpotifyCommand
    data object ToggleRepeat : SpotifyCommand

    /**
     * A single track is the one case Spotify gates on the account: without
     * on-demand playback it refuses, while a playlist may still play shuffled.
     * The doc's Premium rule, expressed where it can be checked before sending.
     */
    fun requiresOnDemand(): Boolean =
        this is Play && uri.startsWith("spotify:track:")
}

/** The playing track, flattened. */
data class SpotifyTrack(
    val name: String,
    val artist: String,
    val album: String,
    val uri: String,
    val durationMs: Long,
    /** `spotify:image:...`, which only the Spotify app can resolve. */
    val imageUri: String?,
    val isEpisode: Boolean,
)

/**
 * What the Spotify app reports, flattened.
 *
 * The restriction flags are not decoration: the same account that can skip in
 * one context cannot in another, so a transport built only from [isPaused] draws
 * buttons that do nothing.
 */
data class SpotifyPlayback(
    val track: SpotifyTrack?,
    val isPaused: Boolean,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeat: SpotifyRepeat,
    val canSeek: Boolean,
    val canSkipNext: Boolean,
    val canSkipPrevious: Boolean,
    val canToggleShuffle: Boolean,
    val canRepeatOne: Boolean,
    val canRepeatAll: Boolean,
) {
    /** Whether [command] will be accepted as things stand. */
    fun allows(command: SpotifyCommand): Boolean = when (command) {
        is SpotifyCommand.Play, is SpotifyCommand.Queue -> true
        SpotifyCommand.Pause, SpotifyCommand.Resume -> track != null
        SpotifyCommand.SkipNext -> canSkipNext
        SpotifyCommand.SkipPrevious -> canSkipPrevious
        is SpotifyCommand.SeekTo -> canSeek
        is SpotifyCommand.SeekBy -> canSeek
        is SpotifyCommand.SetShuffle, SpotifyCommand.ToggleShuffle -> canToggleShuffle
        is SpotifyCommand.SetRepeat -> when (command.mode) {
            SpotifyRepeat.OFF -> true
            SpotifyRepeat.ONE -> canRepeatOne
            SpotifyRepeat.ALL -> canRepeatAll
        }
        SpotifyCommand.ToggleRepeat -> canRepeatOne || canRepeatAll
    }
}

/** Where the connection to the Spotify app stands. */
enum class SpotifyRemoteStatus {
    /** Never asked, or disconnected on purpose. */
    IDLE,
    CONNECTING,
    CONNECTED,
    /** Lost and waiting to try again. */
    RECONNECTING,
    /** Gave up until something changes. */
    FAILED,
}

/**
 * Why the connection or a command failed, as the SDK's exception classes say it.
 *
 * The SDK reports types, not codes. [fromExceptionName] takes the class name so
 * the mapping itself stays pure; [SpotifyBackend] passes `Throwable` instances
 * through it and the settings page turns the result into a sentence.
 */
enum class SpotifyFailure {
    /** `CouldNotFindSpotifyApp`: the Spotify app is not on this phone. */
    APP_NOT_INSTALLED,

    /** `NotLoggedInException`: nobody is signed in to the Spotify app. */
    NOT_SIGNED_IN,

    /** `UserNotAuthorizedException`: `app-remote-control` was not granted. */
    NOT_AUTHORIZED,

    /** `AuthenticationFailedException`: client ID, fingerprint or redirect. */
    AUTH_FAILED,

    /** `OfflineModeException`: Spotify is set to offline. */
    OFFLINE,

    /** `UnsupportedFeatureVersionException`: the installed Spotify is too old. */
    UNSUPPORTED_VERSION,

    /** The connection ended: the app was closed, crashed or was killed. */
    DISCONNECTED,

    /** No client ID stored yet, so there is nothing to connect with. */
    NO_CLIENT_ID,

    /** The account cannot start a single track on demand. */
    ON_DEMAND_UNAVAILABLE,

    /** The Spotify app accepted the call and never answered. */
    NO_ANSWER,

    UNKNOWN;

    /**
     * Only a lost connection is worth a timer. An app that is not installed, a
     * user who is not signed in or a missing permission all need the person, and
     * retrying them just burns the battery and hides the message.
     */
    val retryable: Boolean
        get() = this == DISCONNECTED || this == NO_ANSWER

    companion object {
        fun fromExceptionName(name: String?): SpotifyFailure = when (name) {
            "CouldNotFindSpotifyApp" -> APP_NOT_INSTALLED
            "NotLoggedInException" -> NOT_SIGNED_IN
            "UserNotAuthorizedException" -> NOT_AUTHORIZED
            "AuthenticationFailedException" -> AUTH_FAILED
            "OfflineModeException" -> OFFLINE
            "UnsupportedFeatureVersionException" -> UNSUPPORTED_VERSION
            "SpotifyDisconnectedException",
            "SpotifyConnectionTerminatedException" -> DISCONNECTED
            else -> UNKNOWN
        }

        fun of(error: Throwable): SpotifyFailure = fromExceptionName(error::class.java.simpleName)
    }
}

/**
 * How long to wait before the next attempt.
 *
 * One second, doubling to thirty: the Spotify app is usually closed on purpose,
 * so the first retry is quick and a phone left alone stops asking often.
 */
object SpotifyRetry {
    const val MAX_DELAY_MS = 30_000L

    fun delayMs(attempt: Int): Long {
        if (attempt <= 0) return 0L
        val shift = (attempt - 1).coerceAtMost(5)
        return (1_000L shl shift).coerceAtMost(MAX_DELAY_MS)
    }
}

/** The whole remote, as one value the UI can draw. */
data class SpotifyRemoteState(
    val status: SpotifyRemoteStatus = SpotifyRemoteStatus.IDLE,
    val playback: SpotifyPlayback? = null,
    /**
     * `Capabilities.canPlayOnDemand`, or null while unknown. This is the check
     * that replaces the `product` field the Web API no longer returns.
     */
    val canPlayOnDemand: Boolean? = null,
    val failure: SpotifyFailure? = null,
    /** How long the next reconnect waits, for the status line. */
    val retryInMs: Long = 0,
)
