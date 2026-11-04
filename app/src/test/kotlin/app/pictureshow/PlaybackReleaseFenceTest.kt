/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RenderersFactory
import app.pictureshow.core.Server
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class PlaybackReleaseFenceTest {
    private val ticking = AtomicBoolean(false)
    private lateinit var clock: Thread

    @Before
    fun advanceThePausedAndroidClockWhileMedia3Waits() {
        ticking.set(true)
        clock = Thread {
            while (ticking.get()) {
                Thread.sleep(10)
                ShadowSystemClock.advanceBy(Duration.ofMillis(10))
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    @After
    fun stopClock() {
        ShadowLooper.shadowMainLooper().idle()
        ticking.set(false)
        clock.join(1_000)
    }

    @Test
    fun nextViewingCannotEnableAVideoRendererUntilTheOldReleaseFinishes() = checkOrdering(reopen = false)

    @Test
    fun sourceReopenCannotEnableAVideoRendererUntilTheOldReleaseFinishes() = checkOrdering(reopen = true)

    @Test
    fun releasingInsideAPlayerCallbackStillReportsTheMedia3Timeout() = checkOrdering(reopen = false, reentrant = true)

    private fun checkOrdering(reopen: Boolean, reentrant: Boolean = false) {
        val allowRelease = CountDownLatch(1)
        val old = harness(allowRelease)
        val next = if (reopen) old else harness()
        try {
            old.open()
            pumpUntil { old.videos.first().enabled.count == 0L }
            if (reopen) {
                old.output.beginSeek()
                next.open(1_000)
            } else {
                if (reentrant) {
                    active(old.output).addListener(object : Player.Listener {
                        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = old.output.release()
                    })
                    active(old.output).play()
                } else {
                    old.output.release()
                }
                next.open()
            }
            ShadowLooper.shadowMainLooper().idle()
            assertTrue(old.videos.first().releaseEntered.await(1, TimeUnit.SECONDS))
            val newVideo = next.videos.last()
            // The actual Media3 source/track selection drives onEnabled on its playback thread.
            // Even an external prepare command must not bypass the fence.
            next.output.player.value!!.prepare()
            pumpFor(150)
            assertEquals(1L, newVideo.enabled.count, "Replacement enabled video while the retiring renderer was held")
            assertEquals(Player.STATE_BUFFERING, next.output.player.value!!.playbackState)
            next.output.player.value!!.play()
            next.output.player.value!!.pause()
            allowRelease.countDown()
            pumpUntil { newVideo.enabled.count == 0L }
            assertFalse(next.output.player.value!!.playWhenReady, "Media controls during the wait must survive prepare")
            assertTrue(releaseLogs().any { it.contains("timeout=true") && !it.contains("finishedMs=pending") })
        } finally {
            allowRelease.countDown()
            old.output.release()
            if (!reopen) next.output.release()
        }
    }

    @Test
    fun aStuckReleaseHasABoundedWaitAndDoesNotBlockSurfaceChangesOrTheApplicationLooper() {
        val allow = CountDownLatch(1)
        val old = harness(allow)
        val next = harness()
        val texture = android.graphics.SurfaceTexture(0)
        val surface = android.view.Surface(texture)
        try {
            old.open()
            pumpUntil { old.videos.first().enabled.count == 0L }
            old.output.release()
            ShadowLooper.shadowMainLooper().idle()
            val start = System.nanoTime()
            next.open()
            assertTrue(System.nanoTime() - start < 400_000_000, "Open waited on the application thread")
            val player = active(next.output)
            player.setVideoSurface(surface)
            val detach = System.nanoTime()
            player.clearVideoSurface()
            assertTrue(System.nanoTime() - detach < 200_000_000, "Surface detach waited behind the old release")
            val responsive = CountDownLatch(1)
            android.os.Handler(Looper.getMainLooper()).post { responsive.countDown() }
            ShadowLooper.shadowMainLooper().idle()
            assertEquals(0L, responsive.count)
            assertNull(player.playerError)
            pumpUntil(7_000) { next.videos.last().enabled.count == 0L }
            assertEquals(1L, allow.count, "Test accidentally released the old renderer before checking the bound")
            assertTrue(releaseLogs().any { it.contains("boundReached=true") && it.contains("finishedMs=pending") })
        } finally {
            allow.countDown()
            old.output.release()
            next.output.release()
            surface.release()
            texture.release()
        }
    }

    @Test
    fun cancellingAWaitingReplacementDoesNotLetTheThirdPlayerPassTheFirstRelease() {
        val allow = CountDownLatch(1)
        val first = harness(allow)
        val second = harness()
        val third = harness()
        try {
            first.open()
            pumpUntil { first.videos.first().enabled.count == 0L }
            first.output.release()
            ShadowLooper.shadowMainLooper().idle()
            second.open()
            second.output.release()
            val start = System.nanoTime()
            ShadowLooper.shadowMainLooper().idle()
            assertTrue(System.nanoTime() - start < 200_000_000, "Cancelling an unprepared player waited for its predecessor")
            third.open()
            pumpFor(150)
            assertEquals(1L, second.videos.last().enabled.count, "Cancelled player configured a renderer")
            assertEquals(1L, third.videos.last().enabled.count, "Cancellation broke the predecessor chain")
            allow.countDown()
            pumpUntil { third.videos.last().enabled.count == 0L }
        } finally {
            allow.countDown()
            first.output.release()
            second.output.release()
            third.output.release()
        }
    }

    @Test
    fun aFailedConstructionDoesNotLeaveAnOwnedPlaybackThreadAlive() {
        val before = Thread.getAllStackTraces().keys.filter { it.name == "Pictureshow:Playback" }.toSet()
        val output = Media3PlaybackOutput(
            RuntimeEnvironment.getApplication(),
            Server("server", "Test", "https://example.test", "12"),
            renderersFactory = RenderersFactory { _, _, _, _, _ -> throw IllegalStateException("Renderer unavailable") }
        )
        assertFailsWith<IllegalStateException> { output.open(decision, 0, -1, -1, true) }
        Thread.getAllStackTraces().keys.filter { it.name == "Pictureshow:Playback" && it !in before }.forEach { it.join(1_000) }
        assertTrue(Thread.getAllStackTraces().keys.none { it.name == "Pictureshow:Playback" && it !in before && it.isAlive })
        output.release()
    }

    @Test
    fun replacingTheApplicationDoesNotInheritItsFenceOrBlockOldQueuedCleanup() {
        val allow = CountDownLatch(1)
        val old = harness(allow)
        val context = object : android.content.ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): android.content.Context = this
        }
        val next = harness(context = context)
        try {
            old.open()
            pumpUntil { old.videos.first().enabled.count == 0L }
            val thread = active(old.output).playbackLooper.thread
            old.output.release() // Deliberately do not dispatch the queued release before the owner changes.
            next.open()
            ShadowLooper.shadowMainLooper().idle()
            pumpUntil { next.videos.last().enabled.count == 0L }
            assertTrue(old.videos.first().releaseEntered.await(1, TimeUnit.SECONDS))
            assertEquals(1L, allow.count, "Replacement must prepare while the previous owner's release is held")
            allow.countDown()
            thread.join(1_000)
            assertFalse(thread.isAlive, "Replacing the owner abandoned a queued player release")
        } finally {
            allow.countDown()
            old.output.release()
            next.output.release()
        }
    }

    private class Harness(val output: Media3PlaybackOutput, val videos: MutableList<Video>) {
        fun open(positionMs: Long = 0) = output.open(decision, positionMs, -1, -1, true)
    }

    private fun harness(
        allow: CountDownLatch = CountDownLatch(0),
        context: android.content.Context = RuntimeEnvironment.getApplication()
    ): Harness {
        val videos = mutableListOf<Video>()
        val data = requireNotNull(javaClass.getResourceAsStream("/playback/copied-audio-preroll.ts")).use { it.readBytes() }
        val output = Media3PlaybackOutput(
            context,
            Server("server", "Test", "https://example.test", "12"),
            upstream = DataSource.Factory { ByteArrayDataSource(data) },
            renderersFactory = RenderersFactory { _, _, _, _, _ ->
                arrayOf(Video(if (videos.isEmpty()) allow else CountDownLatch(0)).also { videos += it })
            }
        )
        return Harness(output, videos)
    }

    private fun releaseLogs() = ShadowLog.getLogsForTag("PictureshowPlayback").map { it.msg }.filter { it.startsWith("release ") }

    private fun active(output: Media3PlaybackOutput) = Media3PlaybackOutput::class.java.getDeclaredField("activePlayer")
        .apply { isAccessible = true }.get(output) as ExoPlayer

    private fun pumpFor(ms: Long) {
        val end = System.nanoTime() + ms * 1_000_000
        do {
            ShadowLooper.shadowMainLooper().idle()
            Thread.sleep(5)
        } while (System.nanoTime() < end)
    }

    private fun pumpUntil(ms: Long = 3_000, condition: () -> Boolean) {
        val end = System.nanoTime() + ms * 1_000_000
        while (!condition() && System.nanoTime() < end) pumpFor(10)
        assertTrue(condition(), "Playback condition did not become true within ${ms}ms")
    }

    private class Video(private val allow: CountDownLatch) : BaseRenderer(C.TRACK_TYPE_VIDEO) {
        val enabled = CountDownLatch(1)
        val releaseEntered = CountDownLatch(1)
        override fun getName() = "Release ordering video"
        override fun supportsFormat(format: Format) = RendererCapabilities.create(
            if (MimeTypes.isVideo(format.sampleMimeType)) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE
        )
        override fun isReady() = true
        override fun isEnded() = false
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) = Unit
        override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
            enabled.countDown()
        }
        override fun onRelease() {
            releaseEntered.countDown()
            check(allow.await(15, TimeUnit.SECONDS)) { "Test did not unblock release" }
        }
    }

    companion object {
        private val decision = PlayDecision(
            "item",
            MediaSourceInfo(id = "source", runTimeTicks = 600_000_000),
            PlayMethod.DirectPlay,
            "https://example.test/video.ts",
            emptyMap(),
            "session"
        )
    }
}
