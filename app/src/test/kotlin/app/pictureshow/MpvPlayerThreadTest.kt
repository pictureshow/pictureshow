/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.Surface
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import app.pictureshow.mpv.MpvEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * PLAY-60: a decoder that holds mpv's core must not freeze the app. Every call into this fake mpv blocks (as libmpv's
 * do while a stuck decoder holds the core) until the test ends; the player's controls, Back (release) and surface
 * changes must still return promptly on the main thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class MpvPlayerThreadTest {
    private val hungUntilTestEnds = CountDownLatch(1)
    private val calls = mutableListOf<String>()

    private val hungMpv = object : MpvApi {
        @Volatile var started = false
        private fun hang(name: String): Int {
            synchronized(calls) { calls += name }
            if (started) hungUntilTestEnds.await(HANG_SECONDS, TimeUnit.SECONDS)
            return 0
        }
        override fun create(context: Context) = 1L
        override fun setOptionString(handle: Long, name: String, value: String) = if (started) hang("option $name") else 0
        override fun initialize(handle: Long) = 0.also { started = true }
        override fun requestLogMessages(handle: Long, level: String) = 0
        override fun observeProperty(handle: Long, replyId: Long, name: String, format: Int) = 0
        override fun waitEvent(handle: Long, timeoutSeconds: Double): MpvEvent? {
            hungUntilTestEnds.await(HANG_SECONDS, TimeUnit.SECONDS)
            return MpvEvent(MpvEvent.SHUTDOWN, 0, 0, null, 0, null, 0, 0.0, 0, null)
        }
        override fun destroy(handle: Long) = Unit
        override fun command(handle: Long, args: Array<String>) = hang(args.first())
        override fun getPropertyString(handle: Long, name: String): String? = null.also { hang(name) }
        override fun getPropertyLong(handle: Long, name: String) = hang(name).toLong()
        override fun getPropertyFlag(handle: Long, name: String) = hang(name)
        override fun setPropertyString(handle: Long, name: String, value: String) = hang(name)
        override fun setPropertyDouble(handle: Long, name: String, value: Double) = hang(name)
        override fun setPropertyLong(handle: Long, name: String, value: Long) = hang(name)
        override fun setPropertyFlag(handle: Long, name: String, value: Boolean) = hang(name)
        override fun attachSurface(handle: Long, surface: Surface) = hang("attach")
        override fun detachSurface(handle: Long) = hang("detach")
    }

    private val source = MpvSource(
        url = "http://127.0.0.1:1/secret/stream",
        title = "Title",
        itemId = "item",
        startMs = 0,
        durationMs = 60_000,
        streams = emptyList(),
        externalSubtitles = emptyList(),
        transcoding = false,
        spdif = emptyList()
    )

    @AfterTest fun releaseFakeMpv() = hungUntilTestEnds.countDown()

    private fun player() = MpvPlayer(ApplicationProvider.getApplicationContext(), source, hungMpv)

    private inline fun elapsedMs(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }

    @Test fun controlsAndBackAnswerWhileMpvIsStuck() {
        val player = player()
        val surface = Surface(SurfaceTexture(0))
        val controls = elapsedMs {
            player.setVideoSurface(surface) // attaches, then loads the file: mpv sticks in its first call
            player.prepare()
            player.playWhenReady = true
            player.seekTo(30_000)
            player.setPlaybackSpeed(1.5f)
            player.stop()
            player.prepare()
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controls < PROMPT_MS, "controls took $controls ms while mpv was stuck")
        assertEquals(true, player.playWhenReady)
        assertEquals(30_000, player.currentPosition)
        assertEquals(Player.STATE_BUFFERING, player.playbackState)

        // Back: the surface has to be detached first, which waits a bounded time for a stuck mpv.
        val back = elapsedMs { player.release() }
        assertTrue(back < MpvPlayer.DETACH_WAIT_MS + PROMPT_MS, "release took $back ms while mpv was stuck")
        surface.release()
    }

    @Test fun mpvCallsStillRunInTheOrderTheyWereMade() {
        val player = player()
        player.prepare()
        player.seekTo(10_000)
        player.playWhenReady = true
        hungUntilTestEnds.countDown()
        player.release()
        // Let the mpv thread drain.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (synchronized(calls) { "quit" !in calls } && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(listOf("seek", "pause", "quit"), synchronized(calls) { calls.toList() })
    }

    private companion object {
        const val HANG_SECONDS = 20L
        const val PROMPT_MS = 500L
    }
}
