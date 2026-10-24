/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.legacy.PlaybackStateCompat
import com.google.common.util.concurrent.Futures
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class PlaybackSessionRebufferTest {
    @Test
    fun shortRebufferKeepsLegacyPlayingWithFrozenPositionAndNoMetadataEvents() = viewing { player, handle ->
        val states = mutableListOf<Int>()
        var metadataChanges = 0
        handle.session.player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                states += playbackState
            }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                metadataChanges++
            }
        })
        player.change(Player.STATE_BUFFERING)
        assertEquals(Player.STATE_BUFFERING, player.playbackState)
        assertFalse(handle.session.player.isPlaying)
        assertEquals(PlaybackStateCompat.STATE_PLAYING, legacy(handle).state)
        assertEquals(0f, legacy(handle).playbackSpeed)
        val position = legacy(handle).position
        idle(4_999)
        assertEquals(position, legacy(handle).position)
        player.change(Player.STATE_READY)
        assertEquals(PlaybackStateCompat.STATE_PLAYING, legacy(handle).state)
        assertEquals(1f, legacy(handle).playbackSpeed)
        assertEquals(emptyList(), states)
        assertEquals(0, metadataChanges)
    }

    @Test
    fun longStallExpiresAtFiveSecondsWithoutExtrapolatingOrRearming() = viewing { player, handle ->
        player.change(Player.STATE_BUFFERING)
        val position = player.currentPosition
        idle(3_000)
        player.invalidateForTest()
        idle(1_999)
        assertEquals(PlaybackStateCompat.STATE_PLAYING, legacy(handle).state)
        idle(1)
        assertEquals(PlaybackStateCompat.STATE_BUFFERING, legacy(handle).state)
        assertEquals(0f, legacy(handle).playbackSpeed)
        idle(60_000)
        assertEquals(position, legacy(handle).position)
        assertEquals(position, player.currentPosition)
        player.invalidateForTest()
        assertEquals(PlaybackStateCompat.STATE_BUFFERING, legacy(handle).state)
        player.change(Player.STATE_READY)
        assertEquals(PlaybackStateCompat.STATE_PLAYING, legacy(handle).state)
    }

    @Test
    fun getterAfterDeadlineCannotCancelThePendingExpiryEvent() = viewing { player, handle ->
        player.change(Player.STATE_BUFFERING)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(5))
        assertEquals(Player.STATE_BUFFERING, handle.session.player.playbackState)
        idle()
        assertEquals(PlaybackStateCompat.STATE_BUFFERING, legacy(handle).state)
    }

    @Test
    fun initialPreparationRemainsBuffering() {
        val player = StatePlayer(Player.STATE_BUFFERING)
        val handle = open(player)
        try {
            assertEquals(PlaybackStateCompat.STATE_BUFFERING, legacy(handle).state)
            idle(6_000)
            assertEquals(PlaybackStateCompat.STATE_BUFFERING, legacy(handle).state)
        } finally {
            handle.close()
            player.release()
            idle()
        }
    }

    @Test
    fun pauseDuringGraceIsImmediateAndPlayDoesNotRestartTheGrace() = viewing { player, handle ->
        player.change(Player.STATE_BUFFERING)
        handle.session.player.pause()
        idle()
        assertEquals(PlaybackStateCompat.STATE_PAUSED, legacy(handle).state)
        handle.session.player.play()
        idle()
        assertEquals(PlaybackStateCompat.STATE_BUFFERING, legacy(handle).state)
        idle(6_000)
        assertEquals(PlaybackStateCompat.STATE_BUFFERING, legacy(handle).state)
    }

    @Test
    fun terminalStatesAndErrorsBypassGrace() {
        for ((state, expected) in listOf(
            Player.STATE_IDLE to PlaybackStateCompat.STATE_NONE,
            Player.STATE_ENDED to PlaybackStateCompat.STATE_STOPPED
        )) {
            viewing { player, handle ->
                player.change(Player.STATE_BUFFERING)
                player.change(state)
                assertEquals(expected, legacy(handle).state)
                idle(6_000)
                assertEquals(expected, legacy(handle).state)
            }
        }
        viewing { player, handle ->
            player.change(Player.STATE_BUFFERING)
            player.fail()
            assertEquals(PlaybackStateCompat.STATE_ERROR, legacy(handle).state)
        }
    }

    @Test
    fun realMetadataAndIntentChangesStillReachSessionListeners() = viewing { player, handle ->
        val titles = mutableListOf<CharSequence?>()
        val intents = mutableListOf<Boolean>()
        handle.session.player.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                titles += mediaMetadata.title
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                intents += playWhenReady
            }
        })
        player.changeTitle("Another example")
        handle.session.player.pause()
        idle()
        handle.session.player.play()
        idle()
        assertEquals(listOf<CharSequence?>("Another example"), titles)
        assertEquals(listOf(false, true), intents)
    }

    @Test
    fun suppressionBypassesGrace() = viewing { player, handle ->
        player.change(Player.STATE_BUFFERING)
        player.suppress()
        assertEquals(PlaybackStateCompat.STATE_PAUSED, legacy(handle).state)
    }

    @Test
    fun replacementKeepsOnlyRemainingGraceAndDirectForwardingShape() = viewing { player, handle ->
        player.change(Player.STATE_BUFFERING)
        idle(3_000)
        val replacement = StatePlayer(Player.STATE_BUFFERING)
        try {
            handle.replacePlayer(replacement)
            idle()
            assertSame(replacement, (handle.session.player as androidx.media3.common.ForwardingPlayer).wrappedPlayer)
            assertEquals(PlaybackStateCompat.STATE_PLAYING, legacy(handle).state)
            idle(1_999)
            assertEquals(PlaybackStateCompat.STATE_PLAYING, legacy(handle).state)
            idle(1)
            assertEquals(PlaybackStateCompat.STATE_BUFFERING, legacy(handle).state)
        } finally {
            replacement.release()
        }
    }

    @Test
    fun productionReopenRetainsGraceWhenRawDecoderIsIdleAndReleasedBeforeReplacement() {
        for (progressive in listOf(false, true)) {
            val decoder = StatePlayer()
            val managed = ReopeningPlayer(decoder)
            val exposed = if (progressive) TranscodeSeekPlayer(managed, 8_000, 120_000) { true } else managed
            val handle = open(exposed)
            val next = StatePlayer(Player.STATE_BUFFERING)
            try {
                // Match releasePlayer(false): save the exposed BUFFERING state before retiring raw Exo.
                managed.beginReopen()
                idle()
                idle(3_000)
                decoder.change(Player.STATE_IDLE)
                decoder.release()
                idle()
                assertEquals(Player.STATE_IDLE, decoder.playbackState)
                assertEquals(1, decoder.releases)
                assertEquals(Player.STATE_BUFFERING, exposed.playbackState)
                assertEquals(PlaybackStateCompat.STATE_PLAYING, legacy(handle).state)
                handle.replacePlayer(next)
                idle()
                assertEquals(PlaybackStateCompat.STATE_PLAYING, legacy(handle).state)
                idle(1_999)
                assertEquals(PlaybackStateCompat.STATE_PLAYING, legacy(handle).state)
                idle(1)
                assertEquals(PlaybackStateCompat.STATE_BUFFERING, legacy(handle).state)
            } finally {
                handle.close()
                next.release()
                if (decoder.releases == 0) decoder.release()
                idle()
            }
        }
    }

    @Test
    fun stopCancelsGraceAndCloseDetachesWithoutReleasingOutput() {
        val player = StatePlayer()
        var stops = 0
        val handle = PlaybackMediaSession.open(RuntimeEnvironment.getApplication(), player) { stops++ }
        idle()
        player.change(Player.STATE_BUFFERING)
        handle.session.player.stop()
        idle()
        assertEquals(1, stops)
        assertEquals(PlaybackStateCompat.STATE_STOPPED, legacy(handle).state)
        handle.session.player.stop()
        assertEquals(1, stops)
        handle.close()
        idle(6_000)
        assertEquals(0, player.releases)
        player.release()
    }

    @Test
    fun stopWhileReadyUpdatesIsPlayingAndCloseDuringGraceCancelsListenerCallbacks() {
        viewing { player, handle ->
            val playing = mutableListOf<Boolean>()
            handle.session.player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing += isPlaying
                }
            })
            handle.session.player.stop()
            idle()
            assertEquals(listOf(false), playing)
            assertFalse(handle.session.player.isPlaying)
            assertEquals(PlaybackStateCompat.STATE_STOPPED, legacy(handle).state)
            assertEquals(Player.STATE_READY, player.playbackState)
        }
        viewing { player, handle ->
            val states = mutableListOf<Int>()
            handle.session.player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    states += playbackState
                }
            })
            player.change(Player.STATE_BUFFERING)
            handle.close()
            idle(6_000)
            assertEquals(emptyList(), states)
            assertEquals(0, player.releases)
        }
    }

    private fun viewing(test: (StatePlayer, PlaybackMediaSession.Handle) -> Unit) {
        val player = StatePlayer()
        val handle = open(player)
        try {
            test(player, handle)
        } finally {
            handle.close()
            player.release()
            idle()
        }
    }

    private fun open(player: Player): PlaybackMediaSession.Handle =
        PlaybackMediaSession.open(RuntimeEnvironment.getApplication(), player) {}.also { idle() }

    // Robolectric's platform controller shadow does not mirror MediaSession's state. Inspect the
    // actual PlaybackStateCompat produced by pinned Media3, immediately before its framework call.
    private fun legacy(handle: PlaybackMediaSession.Handle): PlaybackStateCompat {
        var value: Any = handle.session
        for (name in listOf("impl", "sessionLegacyStub", "sessionCompat", "impl", "playbackState")) {
            var type: Class<*>? = value.javaClass
            var field: java.lang.reflect.Field? = null
            while (type != null && field == null) {
                field = runCatching { type!!.getDeclaredField(name) }.getOrNull()
                type = type.superclass
            }
            value = checkNotNull(checkNotNull(field).apply { isAccessible = true }.get(value))
        }
        return value as PlaybackStateCompat
    }

    private fun idle(ms: Long = 0) {
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    }

    private class StatePlayer(initial: Int = Player.STATE_READY) : SimpleBasePlayer(Looper.getMainLooper()) {
        var releases = 0
        private var state = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder().addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_RELEASE
                ).build()
            )
            .setPlaylist(
                listOf(
                    MediaItemData.Builder("example").setMediaItem(
                        MediaItem.Builder().setMediaId(
                            "example"
                        ).setMediaMetadata(MediaMetadata.Builder().setTitle("Example feature").build()).build()
                    ).setDurationUs(120_000_000).build()
                )
            )
            .setPlaybackState(initial)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(12_000)
            .build()
        override fun getState(): State = state
        override fun handleSetPlayWhenReady(playWhenReady: Boolean) = Futures.immediateVoidFuture().also {
            state = state.buildUpon().setContentPositionMs(currentPosition)
                .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        }
        override fun handleRelease() = Futures.immediateVoidFuture().also { releases++ }
        fun change(playbackState: Int) {
            state = state.buildUpon().setContentPositionMs(currentPosition).setPlaybackState(playbackState).build()
            invalidateState()
            idle()
        }
        fun invalidateForTest() {
            invalidateState()
            idle()
        }
        fun fail() {
            state = state.buildUpon().setPlaybackState(Player.STATE_IDLE)
                .setPlayerError(PlaybackException("Example failure", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)).build()
            invalidateState()
            idle()
        }
        fun changeTitle(title: String) {
            state = state.buildUpon().setPlaylist(
                listOf(
                    MediaItemData.Builder("example").setMediaItem(
                        MediaItem.Builder().setMediaId("example").setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()
                    ).setDurationUs(120_000_000).build()
                )
            ).build()
            invalidateState()
            idle()
        }
        fun suppress() {
            state = state.buildUpon().setPlaybackSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS).build()
            invalidateState()
            idle()
        }
        private fun idle() {
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
