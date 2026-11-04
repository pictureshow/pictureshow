/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import androidx.media3.exoplayer.ExoPlayer
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
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
class PlaybackThreadReuseTest {
    private val ticking = AtomicBoolean(false)
    private lateinit var clock: Thread

    @Before
    fun advanceClockDuringMedia3ReleaseWaits() {
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
    fun repeatedViewingsReuseThePlaybackLooperAndItsThreadLocalChoreographer() {
        val loopers = mutableSetOf<Looper>()
        val choreographers = mutableSetOf<Choreographer>()
        repeat(24) {
            val playback = PlaybackReleaseFence.playback(RuntimeEnvironment.getApplication())
            loopers += playback.looper
            val sampled = CountDownLatch(1)
            Handler(playback.looper).post {
                choreographers += Choreographer.getInstance()
                sampled.countDown()
            }
            assertTrue(sampled.await(1, TimeUnit.SECONDS))
            retire(playback)
        }
        assertEquals(1, loopers.size, "Completed viewings created another playback thread")
        assertEquals(1, choreographers.size, "Each viewing allocated a new thread-local display receiver")
        assertTrue(loopers.single().thread.isAlive)
    }

    @Test
    fun aReleaseBoundDoesNotReturnAnOccupiedThreadToTheCache() {
        val first = PlaybackReleaseFence.playback(RuntimeEnvironment.getApplication())
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        // Park the actual playback queue ahead of Media3's release. The app-side release
        // wait times out, then the fence's independent 5s bound expires while it stays parked.
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).setPlaybackLooper(first.looper).build()
        Handler(first.looper).post {
            entered.countDown()
            check(unblock.await(15, TimeUnit.SECONDS))
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        try {
            first.release(player, decision)
            pumpUntil(7_000) { releaseLogs().any { it.contains("boundReached=true") } }
            val second = PlaybackReleaseFence.playback(RuntimeEnvironment.getApplication())
            assertNotSame(first.looper, second.looper, "Bound expiry recycled a thread still running release")
            var ready = false
            second.prepare { ready = true }
            assertTrue(ready, "The expired retirement must not block replacement prepare")
            retire(second)
            val third = PlaybackReleaseFence.playback(RuntimeEnvironment.getApplication())
            retire(third)
            assertSame(second.looper, third.looper, "A completed replacement should be reusable while the old release is stuck")
            unblock.countDown()
            pumpUntil { releaseLogs().any { it.startsWith("release-finished ") } }
        } finally {
            unblock.countDown()
        }
    }

    @Test
    fun abortingConstructionDiscardsEvenABorrowedThread() {
        val first = PlaybackReleaseFence.playback(RuntimeEnvironment.getApplication())
        retire(first)
        val borrowed = PlaybackReleaseFence.playback(RuntimeEnvironment.getApplication())
        try {
            assertSame(first.looper, borrowed.looper)
        } finally {
            borrowed.abortConstruction()
        }
        borrowed.looper.thread.join(1_000)
        assertFalse(borrowed.looper.thread.isAlive, "A partially constructed player's queue must not be reused")
        val fresh = PlaybackReleaseFence.playback(RuntimeEnvironment.getApplication())
        assertNotSame(borrowed.looper, fresh.looper)
        fresh.abortConstruction()
    }

    @Test
    fun duplicateRetirementCannotLendAThreadTwiceOrAbortItsNewOwner() {
        val first = PlaybackReleaseFence.playback(RuntimeEnvironment.getApplication())
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).setPlaybackLooper(first.looper).build()
        first.release(player, decision)
        first.release(player, decision)
        ShadowLooper.shadowMainLooper().idle()
        val drained = CountDownLatch(1)
        assertTrue(Handler(first.looper).post { drained.countDown() })
        assertTrue(drained.await(1, TimeUnit.SECONDS), "Retirement sentinels did not drain")
        pumpUntil { releaseLogs().any { it.startsWith("release ") } }
        val second = PlaybackReleaseFence.playback(RuntimeEnvironment.getApplication())
        val third = PlaybackReleaseFence.playback(RuntimeEnvironment.getApplication())
        try {
            assertSame(first.looper, second.looper)
            assertNotSame(second.looper, third.looper, "Duplicate release cached the same thread twice")
            first.abortConstruction()
            val responsive = CountDownLatch(1)
            assertTrue(Handler(second.looper).post { responsive.countDown() })
            assertTrue(responsive.await(1, TimeUnit.SECONDS), "Late abort stopped another player's thread")
        } finally {
            second.abortConstruction()
            third.abortConstruction()
        }
    }

    private fun retire(playback: PlaybackReleaseFence.Playback) {
        val count = releaseLogs().count { it.startsWith("release ") }
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).setPlaybackLooper(playback.looper).build()
        playback.release(player, decision)
        pumpUntil { releaseLogs().count { it.startsWith("release ") } > count }
    }

    private fun releaseLogs() = ShadowLog.getLogsForTag("PictureshowPlayback").map { it.msg }

    private fun pumpUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val until = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition() && System.nanoTime() < until) {
            ShadowLooper.shadowMainLooper().idle()
            Thread.sleep(5)
        }
        assertTrue(condition(), "Playback retirement did not reach the expected checkpoint")
    }

    companion object {
        private val decision = PlayDecision(
            "item",
            MediaSourceInfo(id = "source"),
            PlayMethod.DirectPlay,
            "https://example.test/video",
            emptyMap(),
            "session"
        )
    }
}
