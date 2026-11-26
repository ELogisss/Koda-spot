package com.ivor.ivormusic.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remote's rules without a phone, an SDK or a Spotify account.
 *
 * This is the reason the rules live in their own file: the SDK reports failures
 * as exception classes and the player's restrictions as flags, and both are
 * easy to get wrong in a way that only shows up on a device.
 */
class SpotifyPlaybackTest {

    private fun playback(
        track: SpotifyTrack? = SpotifyTrack("Song", "Artist", "Album", "spotify:track:1", 200_000, null, false),
        canSeek: Boolean = true,
        canSkipNext: Boolean = true,
        canSkipPrevious: Boolean = true,
        canToggleShuffle: Boolean = true,
        canRepeatOne: Boolean = true,
        canRepeatAll: Boolean = true,
    ) = SpotifyPlayback(
        track = track,
        isPaused = false,
        positionMs = 0,
        shuffle = false,
        repeat = SpotifyRepeat.OFF,
        canSeek = canSeek,
        canSkipNext = canSkipNext,
        canSkipPrevious = canSkipPrevious,
        canToggleShuffle = canToggleShuffle,
        canRepeatOne = canRepeatOne,
        canRepeatAll = canRepeatAll,
    )

    @Test
    fun sdkExceptionNamesBecomeReadableFailures() {
        assertEquals(SpotifyFailure.APP_NOT_INSTALLED, SpotifyFailure.fromExceptionName("CouldNotFindSpotifyApp"))
        assertEquals(SpotifyFailure.NOT_SIGNED_IN, SpotifyFailure.fromExceptionName("NotLoggedInException"))
        assertEquals(SpotifyFailure.NOT_AUTHORIZED, SpotifyFailure.fromExceptionName("UserNotAuthorizedException"))
        assertEquals(SpotifyFailure.AUTH_FAILED, SpotifyFailure.fromExceptionName("AuthenticationFailedException"))
        assertEquals(SpotifyFailure.OFFLINE, SpotifyFailure.fromExceptionName("OfflineModeException"))
        assertEquals(
            SpotifyFailure.UNSUPPORTED_VERSION,
            SpotifyFailure.fromExceptionName("UnsupportedFeatureVersionException")
        )
        // Both mean "the connection is gone"; the SDK documents a reconnect for each.
        assertEquals(SpotifyFailure.DISCONNECTED, SpotifyFailure.fromExceptionName("SpotifyDisconnectedException"))
        assertEquals(
            SpotifyFailure.DISCONNECTED,
            SpotifyFailure.fromExceptionName("SpotifyConnectionTerminatedException")
        )
        assertEquals(SpotifyFailure.UNKNOWN, SpotifyFailure.fromExceptionName("SomethingNew"))
        assertEquals(SpotifyFailure.UNKNOWN, SpotifyFailure.fromExceptionName(null))
    }

    @Test
    fun onlyALostConnectionIsRetried() {
        assertTrue(SpotifyFailure.DISCONNECTED.retryable)
        assertTrue(SpotifyFailure.NO_ANSWER.retryable)
        assertFalse(SpotifyFailure.APP_NOT_INSTALLED.retryable)
        assertFalse(SpotifyFailure.NOT_SIGNED_IN.retryable)
        assertFalse(SpotifyFailure.NOT_AUTHORIZED.retryable)
        assertFalse(SpotifyFailure.AUTH_FAILED.retryable)
        assertFalse(SpotifyFailure.ON_DEMAND_UNAVAILABLE.retryable)
    }

    @Test
    fun retryBacksOffToOneSecondThenThirty() {
        assertEquals(0L, SpotifyRetry.delayMs(0))
        assertEquals(1_000L, SpotifyRetry.delayMs(1))
        assertEquals(2_000L, SpotifyRetry.delayMs(2))
        assertEquals(4_000L, SpotifyRetry.delayMs(3))
        assertEquals(8_000L, SpotifyRetry.delayMs(4))
        assertEquals(16_000L, SpotifyRetry.delayMs(5))
        assertEquals(30_000L, SpotifyRetry.delayMs(6))
        assertEquals(30_000L, SpotifyRetry.delayMs(60))
    }

    @Test
    fun onlyASingleTrackNeedsOnDemandPlayback() {
        assertTrue(SpotifyCommand.Play("spotify:track:4uLU6hMCjMI75M1A2tKUQC").requiresOnDemand())
        assertFalse(SpotifyCommand.Play("spotify:album:4nZ5wPL5XxSY2OuDgbnYdc").requiresOnDemand())
        assertFalse(SpotifyCommand.Play("spotify:playlist:37i9dQZF1DX2sUQwD7tbmL").requiresOnDemand())
        assertFalse(SpotifyCommand.Queue("spotify:track:4uLU6hMCjMI75M1A2tKUQC").requiresOnDemand())
    }

    @Test
    fun restrictionsDecideWhichButtonsWork() {
        val locked = playback(
            canSeek = false,
            canSkipNext = false,
            canSkipPrevious = false,
            canToggleShuffle = false,
            canRepeatOne = false,
            canRepeatAll = false,
        )
        assertFalse(locked.allows(SpotifyCommand.SeekTo(1_000)))
        assertFalse(locked.allows(SpotifyCommand.SeekBy(10_000)))
        assertFalse(locked.allows(SpotifyCommand.SkipNext))
        assertFalse(locked.allows(SpotifyCommand.SkipPrevious))
        assertFalse(locked.allows(SpotifyCommand.ToggleShuffle))
        assertFalse(locked.allows(SpotifyCommand.SetRepeat(SpotifyRepeat.ONE)))
        // Repeat off is always possible; there is nothing to forbid.
        assertTrue(locked.allows(SpotifyCommand.SetRepeat(SpotifyRepeat.OFF)))
        // Pause needs a track, not a restriction.
        assertTrue(locked.allows(SpotifyCommand.Pause))
    }

    @Test
    fun pauseNeedsATrackAndPlayNeverDoes() {
        val empty = playback(track = null)
        assertFalse(empty.allows(SpotifyCommand.Pause))
        assertFalse(empty.allows(SpotifyCommand.Resume))
        assertTrue(empty.allows(SpotifyCommand.Play("spotify:album:1")))
        assertTrue(empty.allows(SpotifyCommand.Queue("spotify:track:1")))
    }

    @Test
    fun repeatCodesMatchTheSdkConstants() {
        // Repeat.OFF/ONE/ALL in the SDK are 0/1/2; the mapping is the contract.
        assertEquals(0, SpotifyRepeat.OFF.code)
        assertEquals(1, SpotifyRepeat.ONE.code)
        assertEquals(2, SpotifyRepeat.ALL.code)
        assertEquals(SpotifyRepeat.OFF, SpotifyRepeat.fromCode(0))
        assertEquals(SpotifyRepeat.ONE, SpotifyRepeat.fromCode(1))
        assertEquals(SpotifyRepeat.ALL, SpotifyRepeat.fromCode(2))
        assertEquals(SpotifyRepeat.OFF, SpotifyRepeat.fromCode(99))
    }
}
