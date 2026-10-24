/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.os.Looper
import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import app.pictureshow.core.ClientResult
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamProtocol
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercise the real negotiation queue and reporting with activity keys, not fake pause snapshots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class, shadows = [ActivityMediaKeysTest.ControllerBridge::class])
class ReopenActivityIntentTest {
    @Test fun subtitleReopenUsesRemoteIntentAndReportsPause() = negotiate(Route.Subtitle)

    @Test fun audioReopenUsesRemoteIntentAndReportsPause() = negotiate(Route.Audio)

    @Test fun serverSeekUsesRemoteIntentAndReportsPause() = negotiate(Route.Seek)

    @Test fun recoveryReopenUsesRemoteIntentAndReportsPause() = negotiate(Route.Recovery)

    private enum class Route { Subtitle, Audio, Seek, Recovery }

    private fun negotiate(route: Route) = runTest {
        shadowOf(
            RuntimeEnvironment.getApplication().packageManager
        ).setSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK, true)
        val gate = CompletableDeferred<Unit>()
        var prepares = 0
        val boundaries = mutableListOf<String>()
        val progresses = mutableListOf<PlaybackProgressInfo>()
        val decisions = mutableListOf<PlayDecision>()
        val gateway = object : PlaybackGateway {
            override suspend fun prepare(request: PlaybackRequest): ClientResult<PlayOutcome> {
                if (++prepares > 1) gate.await()
                val decision = PlayDecision(
                    "example",
                    MediaSourceInfo(
                        id = "source",
                        runTimeTicks = 1_200_000_000,
                        transcodingSubProtocol = MediaStreamProtocol.Hls,
                        transcodingContainer = "ts",
                        mediaStreams = listOf(MediaStream(type = MediaStreamType.Video, codec = "av1", realFrameRate = 60.0))
                    ),
                    if (route == Route.Recovery && prepares == 1) PlayMethod.DirectPlay else PlayMethod.Transcode,
                    "https://example.test/Videos/example/master.m3u8?MaxFramerate=30",
                    emptyMap(),
                    "encode-$prepares"
                )
                decisions += decision
                return ClientResult.Success(PlayOutcome.Play(decision))
            }
            override suspend fun start(info: PlaybackProgressInfo) = ClientResult.Success(Unit).also { boundaries += "start" }
            override suspend fun stopped(info: PlaybackStopInfo) = ClientResult.Success(Unit).also { boundaries += "stop" }
            override suspend fun progress(info: PlaybackProgressInfo) = ClientResult.Success(Unit).also { progresses += info }
            override suspend fun stopEncoding(sessionId: String) = ClientResult.Success(Unit)
        }
        val output = KeyOutput()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        try {
            session.start(12_000)
            runCurrent()
            output.ready()
            session.ready()
            runCurrent()
            assertEquals(listOf("start"), boundaries)
            when (route) {
                Route.Subtitle -> session.select(MediaStreamType.Subtitle, -1)
                Route.Audio -> session.select(MediaStreamType.Audio, 2)
                Route.Seek -> assertTrue(session.seek(32_000))
                Route.Recovery -> session.failed(true, decisions.last())
            }
            runCurrent()
            if (route == Route.Seek) {
                advanceTimeBy(251)
                runCurrent()
            }
            assertEquals(2, prepares)
            assertEquals(1, output.opens, "PlaybackInfo must still be held before replacement open")
            idle(5_100)
            press(activity, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            idle(400)
            assertTrue(output.position().paused, "Remote pause must be retained during $route negotiation")
            gate.complete(Unit)
            runCurrent()
            assertEquals(2, output.opens)
            assertTrue(output.position().paused, "$route open must reread the live pause intent")
            assertEquals(Player.STATE_BUFFERING, output.current!!.playbackState)
            // Last press wins after open as well: play, pause, then READY remains paused.
            press(activity, KeyEvent.KEYCODE_MEDIA_PLAY)
            idle()
            assertFalse(output.position().paused)
            press(activity, KeyEvent.KEYCODE_MEDIA_PAUSE)
            idle()
            assertTrue(output.position().paused)
            output.ready()
            session.ready()
            runCurrent()
            assertTrue(progresses.last().isPaused, "Server progress must report the new stream paused")
            assertEquals(listOf("start"), boundaries, "A decoder replacement is not a new viewing")
            session.close()
            runCurrent()
            assertEquals(listOf("start", "stop"), boundaries)
        } finally {
            session.close()
            runCurrent()
            output.release()
            ActivityMediaKeysTest.ControllerBridge.platform = null
            idle(400)
        }
    }

    private class KeyOutput : PlaybackOutput {
        var current: ReopeningPlayer? = null
        private var raw: Decoder? = null
        private var handle: PlaybackMediaSession.Handle? = null
        var opens = 0

        override fun position() = current?.let { PlaybackPosition(it.currentPosition, !it.playWhenReady) } ?: PlaybackPosition(0, true)

        override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) {
            opens++
            raw?.release()
            val next = Decoder(positionMs, !paused)
            raw = next
            current = ReopeningPlayer(next)
            val active = handle
            if (active == null) {
                handle = PlaybackMediaSession.open(RuntimeEnvironment.getApplication(), current!!) {}
                val impl = field(handle!!.session, "impl")
                val legacy = field(impl, "sessionLegacyStub")
                val compat = legacy.javaClass.getDeclaredMethod("getSessionCompat").apply { isAccessible = true }.invoke(legacy)
                ActivityMediaKeysTest.ControllerBridge.platform =
                    compat.javaClass.getMethod("getMediaSession").invoke(compat) as android.media.session.MediaSession
                ActivityMediaKeysTest.ControllerBridge.callerPackage = RuntimeEnvironment.getApplication().packageName
            } else {
                active.replacePlayer(current!!)
            }
        }

        override fun beginSeek() {
            current!!.beginReopen()
            raw!!.state(Player.STATE_IDLE)
        }
        override fun select(type: MediaStreamType, index: Int) = false
        override fun pause() = current!!.pause()
        override fun release() {
            handle?.close()
            handle = null
            raw?.release()
            raw = null
            current = null
        }
        fun ready() = raw!!.state(Player.STATE_READY)
    }

    private class Decoder(positionMs: Long, playing: Boolean) : SimpleBasePlayer(Looper.getMainLooper()) {
        private var value = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder().addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_RELEASE,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA
                ).build()
            )
            .setPlaylist(listOf(MediaItemData.Builder("example").setDurationUs(120_000_000).build()))
            .setPlaybackState(Player.STATE_BUFFERING)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(positionMs).build()
        override fun getState(): State = value
        override fun handleSetPlayWhenReady(playWhenReady: Boolean) = Futures.immediateVoidFuture().also {
            value = value.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        }
        override fun handleRelease() = Futures.immediateVoidFuture()
        fun state(state: Int) {
            value = value.buildUpon().setPlaybackState(state).build()
            invalidateState()
        }
    }

    companion object {
        private fun field(target: Any, name: String): Any =
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)!!
        private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
        private fun press(activity: MainActivity, code: Int) {
            assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)))
            assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)))
        }
    }
}
