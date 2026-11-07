/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import app.pictureshow.core.Server
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class PlaybackStopIntentTest {
    private fun decision(id: String) = PlayDecision(
        "film",
        MediaSourceInfo(id = "source", runTimeTicks = 6_000_000_000),
        PlayMethod.Transcode,
        "https://example.test/Videos/film/stream.ts?PlaySessionId=$id",
        emptyMap(),
        id
    )

    @Test fun pauseBeforeFirstPlayerSurvivesThePendingOpen() {
        val output = Media3PlaybackOutput(RuntimeEnvironment.getApplication(), Server("server", "Test", "https://example.test", "12"))
        try {
            output.pause()
            output.open(decision("first"), 30_000, -1, -1, false)
            println("after-preparation-pause playWhenReady=${output.player.value!!.playWhenReady} paused=${output.position().paused}")
            assertFalse(output.player.value!!.playWhenReady, "A pause without a published player must survive its pending open")
            assertTrue(output.position().paused, "Start/progress must report the retained pause")
            assertEquals(30_000L, output.position().milliseconds)
        } finally {
            output.release()
        }
    }

    // On a real ExoPlayer, because its own stop for an unplugged headset looks like the viewer's pause, so a TV load after it starts paused.
    @Test fun anUnpluggedHeadsetReadsAsTheViewersPauseOnARealPlayer() {
        val context = RuntimeEnvironment.getApplication()
        val output = Media3PlaybackOutput(context, Server("server", "Test", "https://example.test", "12"))
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
        try {
            player.setHandleAudioBecomingNoisy(true)
            player.playWhenReady = true
            assertFalse(output.viewerPaused(player))
            // The player registers and answers on its own thread, so the first broadcast can arrive before it listens.
            val deadline = System.currentTimeMillis() + 10_000
            while (player.playWhenReady && System.currentTimeMillis() < deadline) {
                context.sendBroadcast(android.content.Intent(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                Thread.sleep(20)
            }
            assertFalse(player.playWhenReady, "the player stopped itself")
            assertTrue(output.viewerPaused(player))
        } finally {
            player.release()
            output.release()
        }
    }

    @Test fun aPauseOnTheRealPlayerItselfSurvivesGoingToTheBackground() {
        val output = Media3PlaybackOutput(RuntimeEnvironment.getApplication(), Server("server", "Test", "https://example.test", "12"))
        try {
            output.open(decision("first"), 30_000, -1, -1, false)
            assertFalse(output.viewerPaused())
            output.player.value!!.pause()
            assertTrue(output.viewerPaused(), "the player's own button sets no pause intent")
            output.setForeground(false)
            assertTrue(output.viewerPaused(), "the viewer's pause stays theirs under the app's")
        } finally {
            output.release()
        }
    }

    @Test fun theAppsPauseOnTheRealPlayerIsNotTheViewers() {
        val output = Media3PlaybackOutput(RuntimeEnvironment.getApplication(), Server("server", "Test", "https://example.test", "12"))
        try {
            output.open(decision("first"), 30_000, -1, -1, false)
            output.setForeground(false)
            assertFalse(output.player.value!!.playWhenReady, "the app has paused it")
            assertFalse(output.viewerPaused())
        } finally {
            output.release()
        }
    }

    @Test fun backgroundPlayIsDeniedAndReturnRequiresAnExplicitPlay() {
        val output = Media3PlaybackOutput(RuntimeEnvironment.getApplication(), Server("server", "Test", "https://example.test", "12"))
        try {
            output.setForeground(false)
            output.open(decision("background"), 30_000, -1, -1, false)
            output.player.value!!.play()
            assertFalse(output.player.value!!.playWhenReady, "System media commands cannot resume a stopped activity")
            assertTrue(output.position().paused)
            output.setForeground(true)
            output.open(decision("late-recovery"), 30_000, -1, -1, false)
            assertFalse(output.player.value!!.playWhenReady, "Returning before a recovery answer cannot erase the stop pause")
            output.player.value!!.play()
            assertTrue(output.player.value!!.playWhenReady)
            assertFalse(output.pauseIntent.value)
            assertFalse(output.position().paused)
        } finally {
            output.release()
        }
    }

    @Test fun aPauseAtPlayerPublicationWinsOverTheFinalOpenAssignment() {
        val output = Media3PlaybackOutput(RuntimeEnvironment.getApplication(), Server("server", "Test", "https://example.test", "12"))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate)
        val job = scope.launch {
            output.player.collect { if (it != null) output.setForeground(false) }
        }
        try {
            output.open(decision("publication"), 30_000, -1, -1, false)
            assertFalse(output.player.value!!.playWhenReady)
            assertTrue(output.pauseIntent.value)
            assertTrue(output.position().paused)
        } finally {
            job.cancel()
            output.release()
        }
    }

    @Test fun pauseDuringServerSeekIsNotOverwrittenByCapturedPlayingArgument() {
        val output = Media3PlaybackOutput(RuntimeEnvironment.getApplication(), Server("server", "Test", "https://example.test", "12"))
        try {
            output.open(decision("first"), 30_000, -1, -1, false)
            output.beginSeek()
            output.pause()
            output.open(decision("seek"), 60_000, -1, -1, false)
            assertFalse(output.player.value!!.playWhenReady, "A replacement cannot restore the old playing argument")
            assertEquals(PlaybackPosition(60_000, true), output.position())
        } finally {
            output.release()
        }
    }
}
