/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import app.pictureshow.core.ClientResult
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlayOutcome
import app.pictureshow.core.playback.PlaybackProgressInfo
import app.pictureshow.core.playback.PlaybackRequest
import app.pictureshow.core.playback.PlaybackStopInfo
import com.google.common.util.concurrent.Futures
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class PlaybackStopTest {
    @Test
    fun controllerStopFinishesBeforeChangingPositionOrStateAndAllowsSynchronousTeardown() {
        val fixture = Fixture()
        val future = MediaController.Builder(RuntimeEnvironment.getApplication(), fixture.handle.session.token).buildAsync()
        shadowOf(Looper.getMainLooper()).idle()
        val controller = future.get(2, TimeUnit.SECONDS)
        try {
            assertTrue(controller.isCommandAvailable(Player.COMMAND_STOP))
            controller.stop()
            shadowOf(Looper.getMainLooper()).idle()
            fixture.assertFinished()
            assertFalse(controller.isConnected)
        } finally {
            controller.release()
            fixture.close()
        }
    }

    @Test
    fun anonymousStopFinishesOnceAndConsumesUpAndHeldRepeats() {
        val fixture = Fixture()
        try {
            assertTrue(fixture.key(KeyEvent.ACTION_DOWN))
            assertTrue(fixture.key(KeyEvent.ACTION_UP))
            assertTrue(fixture.key(KeyEvent.ACTION_DOWN, repeat = 1))
            fixture.assertFinished()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun repeatedOrClosedOrReplacedSessionStopsCannotExitAgainOrAffectTheNewPlayer() {
        val old = Fixture(closeOnStop = false)
        val active = Fixture(closeOnStop = false)
        try {
            old.handle.session.player.stop()
            assertEquals(0, old.exits)
            active.handle.session.player.stop()
            active.handle.session.player.stop()
            assertEquals(1, active.exits)
            assertEquals(0, active.player.rawStops)
            active.handle.close()
            active.handle.session.player.stop()
            assertEquals(1, active.exits)
            assertEquals(0, active.player.rawStops)
        } finally {
            old.close()
            active.close()
        }
    }

    @Test
    fun controllerAndAnonymousStopsUseFinalProgressThenStoppedWithThePositionCapturedBeforeRelease() = runTest {
        for (anonymous in listOf(false, true)) {
            val events = mutableListOf<String>()
            val progress = mutableListOf<PlaybackProgressInfo>()
            val stopped = mutableListOf<PlaybackStopInfo>()
            val decision = PlayDecision(
                "example",
                MediaSourceInfo(id = "source"),
                PlayMethod.DirectPlay,
                "https://example.test/stream",
                emptyMap(),
                "session"
            )
            val gateway = object : PlaybackGateway {
                override suspend fun prepare(request: PlaybackRequest) = ClientResult.Success(PlayOutcome.Play(decision))
                override suspend fun start(info: PlaybackProgressInfo) = ClientResult.Success(Unit)
                override suspend fun progress(info: PlaybackProgressInfo) = ClientResult.Success(Unit).also {
                    events += "progress"
                    progress += info
                }
                override suspend fun stopped(info: PlaybackStopInfo) = ClientResult.Success(Unit).also {
                    events += "stopped"
                    stopped += info
                }
                override suspend fun stopEncoding(sessionId: String) = ClientResult.Success(Unit)
            }
            lateinit var playback: PlaybackSession
            val fixture = Fixture(onFinish = { playback.close() })
            var released = false
            var saved = PlaybackPosition(0, false)
            val output = object : PlaybackOutput {
                override fun position(): PlaybackPosition =
                    if (released) saved else PlaybackPosition(fixture.player.currentPosition, !fixture.player.playWhenReady)
                override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) = Unit
                override fun select(type: MediaStreamType, index: Int) = true
                override fun pause() = Unit
                override fun release() {
                    if (released) return
                    saved = position()
                    released = true
                    events += "release"
                    fixture.close()
                }
            }
            playback = PlaybackSession(gateway, output, backgroundScope, "en")
            playback.start(132_000)
            runCurrent()
            playback.ready()
            runCurrent()
            try {
                if (anonymous) fixture.key(KeyEvent.ACTION_DOWN) else fixture.handle.session.player.stop()
                runCurrent()
                fixture.assertFinished()
                assertEquals(listOf("release", "progress", "stopped"), events)
                assertEquals(1_320_000_000L, progress.single().positionTicks)
                assertEquals(1_320_000_000L, stopped.single().positionTicks)
                assertFalse(progress.single().isPaused)
            } finally {
                playback.close()
                runCurrent()
                fixture.close()
            }
        }
    }

    private class Fixture(closeOnStop: Boolean = true, onFinish: (() -> Unit)? = null) {
        val player = StatePlayer()
        var exits = 0
        private var capturedPosition: Long? = null
        private var capturedState: Int? = null
        val handle: PlaybackMediaSession.Handle

        init {
            handle = PlaybackMediaSession.open(RuntimeEnvironment.getApplication(), player) {
                exits++
                capturedPosition = player.currentPosition
                capturedState = player.playbackState
                if (onFinish != null) {
                    onFinish()
                } else if (closeOnStop) {
                    close()
                }
            }
        }

        fun assertFinished() {
            assertEquals(1, exits)
            assertEquals(132_000L, capturedPosition)
            assertEquals(Player.STATE_READY, capturedState)
            assertEquals(0, player.rawStops)
            assertEquals(1, player.releases)
        }

        fun key(action: Int, repeat: Int = 0): Boolean = AnonymousMediaButtons.onMediaButtonEvent(
            handle.session,
            MediaSession.ControllerInfo.createTestOnlyControllerInfo(
                MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME,
                123,
                2000,
                MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
                0,
                false,
                Bundle.EMPTY,
                false
            ),
            Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(
                Intent.EXTRA_KEY_EVENT,
                KeyEvent(0, 0, action, KeyEvent.KEYCODE_MEDIA_STOP, repeat)
            )
        )

        fun close() {
            handle.close()
            player.release()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private class StatePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        var rawStops = 0
        var releases = 0
        private var state = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder().addAll(
                    Player.COMMAND_STOP,
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_RELEASE,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA
                ).build()
            )
            .setPlaylist(listOf(MediaItemData.Builder("example").setDurationUs(1_200_000_000).setIsSeekable(true).build()))
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(132_000)
            .build()

        override fun getState(): State = state

        override fun handleStop() = Futures.immediateVoidFuture().also {
            rawStops++
            state = state.buildUpon().setPlaybackState(Player.STATE_IDLE).build()
        }

        override fun handleRelease() = Futures.immediateVoidFuture().also { releases++ }
    }
}
