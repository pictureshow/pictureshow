/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.Util
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PausedEndIntentTest {
    @Test fun drainedOrMedia3EndWaitsForPlayAndStillCompletesOnlyOnce() {
        for (state in listOf(Player.STATE_READY, Player.STATE_ENDED)) {
            var play = false
            var ends = 0
            val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
                when (method.name) {
                    "getPlaybackState" -> state
                    "getPlayerError" -> null
                    "getPlayWhenReady" -> play
                    else -> null
                }
            } as Player
            val signal = PlaybackEndSignal({ player }) { ends++ }
            signal.ended()
            assertEquals(0, ends, "Paused endpoint must hold for decoder state $state")
            play = true
            signal.ended()
            signal.ended()
            assertEquals(1, ends)
        }
    }

    @Test fun playAtAPausedDecoderEndDoesNotSeekBackToTheBeginning() {
        val decoder = EndPlayer()
        val exposed = ReopeningPlayer(decoder)
        var ends = 0
        val signal = PlaybackEndSignal({ decoder }) { ends++ }
        decoder.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && decoder.playbackState == Player.STATE_ENDED) signal.ended()
            }
        })
        signal.ended()
        assertEquals(0, ends)
        try {
            assertEquals(Player.STATE_READY, exposed.playbackState, "Expose a held endpoint, so Media3 Play cannot rewind it")
            Util.handlePlayButtonAction(exposed)
            assertEquals(0, decoder.seeks)
            assertEquals(true, decoder.playWhenReady)
            assertEquals(60_000L, decoder.currentPosition)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, ends, "The deferred Media3 end completes after explicit Play")
            signal.ended()
            assertEquals(1, ends)
        } finally {
            exposed.release()
        }
    }

    private class EndPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        var play = false
        var seeks = 0
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(ImmutableList.of(MediaItemData.Builder("film").setDurationUs(60_000_000).build()))
            .setCurrentMediaItemIndex(0).setContentPositionMs(60_000)
            .setPlaybackState(Player.STATE_ENDED)
            .setPlayWhenReady(play, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        override fun handleSetPlayWhenReady(playWhenReady: Boolean) = Futures.immediateVoidFuture().also { play = playWhenReady }
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int) = Futures.immediateVoidFuture().also { seeks++ }
        override fun handleRelease() = Futures.immediateVoidFuture()
    }
}
