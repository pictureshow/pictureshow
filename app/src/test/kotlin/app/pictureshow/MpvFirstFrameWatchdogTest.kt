/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import app.pictureshow.core.playback.FirstFrameAction
import app.pictureshow.mpv.MpvEvent
import app.pictureshow.mpv.MpvNative
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * PLAY-61: mpv that opens a file and never shows a picture (as it did on the household projector, waiting for good on
 * audio passthrough) must not leave the viewer on a black screen. This fake mpv loads the file, buffers, and never
 * produces a frame unless the test sends one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class MpvFirstFrameWatchdogTest {
    private val events = LinkedBlockingQueue<MpvEvent>()

    /** What mpv's decoder reports; 0 when no video decodes and mpv shows only its window. */
    @Volatile private var decodedWidth = 1920L

    /** mpv's codec name for the file's one audio track, selected; null for a file without audio. */
    @Volatile private var audioCodec: String? = "eac3"
    private val surfaces = mutableListOf<Surface>()
    private val players = mutableListOf<MpvPlayer>()

    /** Events the fake handed to the player's event thread, and how many of those it has passed on to the main looper. */
    @Volatile private var handedOut = 0

    @Volatile private var passedOn = 0

    /** Marks the mpv command thread reaches in order; see [settle]. */
    @Volatile private var mpvMarks = 0

    /** Optional pause in the mpv thread's track read, to show the tests don't depend on how quickly it runs. */
    @Volatile private var trackReadDelayMs = 0L

    private val silentMpv = object : MpvApi {
        override fun create(context: Context) = 1L
        override fun setOptionString(handle: Long, name: String, value: String) = 0
        override fun initialize(handle: Long) = 0
        override fun requestLogMessages(handle: Long, level: String) = 0
        override fun observeProperty(handle: Long, replyId: Long, name: String, format: Int) = 0
        override fun waitEvent(handle: Long, timeoutSeconds: Double): MpvEvent? {
            // The event thread asks again only after posting the last event to the main looper.
            passedOn = handedOut
            return events.poll(50, TimeUnit.MILLISECONDS)?.also { handedOut++ }
        }
        override fun destroy(handle: Long) = Unit
        override fun command(handle: Long, args: Array<String>) = 0
        override fun getPropertyString(handle: Long, name: String): String? = when (name) {
            "track-list/0/type" -> "audio"
            "track-list/0/codec" -> {
                if (trackReadDelayMs > 0) Thread.sleep(trackReadDelayMs)
                audioCodec
            }
            else -> null
        }
        override fun getPropertyLong(handle: Long, name: String) = when (name) {
            "dwidth" -> 1920L
            "dheight" -> 1080L
            "video-params/w" -> decodedWidth
            "track-list/count" -> if (audioCodec == null) 0L else 1L
            "track-list/0/id" -> 1L
            else -> 0L
        }
        override fun getPropertyFlag(handle: Long, name: String) = if (name == "track-list/0/selected") 1 else 0
        override fun setPropertyString(handle: Long, name: String, value: String) = 0
        override fun setPropertyDouble(handle: Long, name: String, value: Double): Int {
            if (name == "sub-delay") mpvMarks++
            return 0
        }
        override fun setPropertyLong(handle: Long, name: String, value: Long) = 0
        override fun setPropertyFlag(handle: Long, name: String, value: Boolean) = 0
        override fun attachSurface(handle: Long, surface: Surface) = 0
        override fun detachSurface(handle: Long) = 0
    }

    private fun source(spdif: List<String>) = MpvSource(
        url = "http://127.0.0.1:1/secret/stream",
        title = "Title",
        itemId = "item",
        startMs = 0,
        durationMs = 1_200_000,
        streams = emptyList(),
        externalSubtitles = emptyList(),
        transcoding = false,
        spdif = spdif
    )

    private fun event(id: Int) = MpvEvent(id, 0, 0, null, MpvNative.FORMAT_NONE, null, 0, 0.0, 0, null)
    private fun cached(seconds: Double) =
        MpvEvent(MpvEvent.PROPERTY_CHANGE, 0, 0, "demuxer-cache-duration", MpvNative.FORMAT_DOUBLE, null, 0, seconds, 0, null)

    /** Starts a viewing and returns the watchdog's calls. */
    private fun play(spdif: List<String>): MutableList<FirstFrameAction> {
        val actions = mutableListOf<FirstFrameAction>()
        val player = MpvPlayer(ApplicationProvider.getApplicationContext(), source(spdif), silentMpv)
        players += player
        player.onFirstFrameAction = { actions += it }
        val surface = Surface(SurfaceTexture(0)).also { surfaces += it }
        player.setVideoSurface(surface)
        player.prepare()
        player.playWhenReady = true
        return actions
    }

    /**
     * Hands [sent] to the player's event thread and lets the main looper apply them, then [settle]s. Waits on what the
     * threads have done, never on a fixed sleep: the watchdog's time is the main looper's, which only the test moves.
     */
    private fun send(vararg sent: MpvEvent) {
        val expected = handedOut + events.size + sent.size
        sent.forEach(events::put)
        awaitThreads("the event thread to pass on $expected events") { passedOn >= expected }
        shadowOf(Looper.getMainLooper()).idle()
        settle()
    }

    /**
     * Applying an event can ask the mpv thread for more (the tracks after FILE_LOADED, the video size after
     * VIDEO_RECONFIG), which answers on the main looper. The mpv thread runs its work in order, so a mark queued behind
     * that work (the player's subtitle delay, which the fake counts) means the answers are posted; idling then applies
     * them. Repeats until a round brings nothing new. A CI runner that read the tracks after the test had already moved
     * the clock 9 s got the watchdog's decision without knowing the track was passed through (PLATFORM, not a retry).
     */
    private fun settle() {
        val main = shadowOf(Looper.getMainLooper())
        do {
            val mark = mpvMarks + 1
            players.last().subtitleDelayMs(0)
            awaitThreads("the mpv thread to finish its queued work") { mpvMarks >= mark }
            val more = !main.isIdle
            main.idle()
        } while (more)
    }

    private fun awaitThreads(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done()) {
            check(System.nanoTime() < deadline) { "Waited for $what" }
            Thread.sleep(1)
        }
    }

    private fun waitMs(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @AfterTest fun release() {
        players.forEach { it.release() }
        surfaces.forEach { it.release() }
    }

    @Test fun noPictureWithPassthroughRetriesWithDecodedAudioOnce() {
        val actions = play(listOf("ac3", "eac3"))
        send(event(MpvEvent.START_FILE), event(MpvEvent.FILE_LOADED), cached(5.0))
        waitMs(7_000)
        assertEquals(emptyList(), actions, "gave up before the decoder had its time")
        waitMs(2_000)
        assertEquals(listOf(FirstFrameAction.WithoutPassthrough), actions)
        waitMs(30_000)
        assertEquals(listOf(FirstFrameAction.WithoutPassthrough), actions, "the watchdog answers once per open")
    }

    /** The flake's shape, made certain: the mpv thread reads the tracks slowly and the decision still knows them. */
    @Test fun aSlowTrackReadStillRetriesWithDecodedAudio() {
        trackReadDelayMs = 300
        val actions = play(listOf("ac3", "eac3"))
        send(event(MpvEvent.START_FILE), event(MpvEvent.FILE_LOADED), cached(5.0))
        waitMs(9_000)
        assertEquals(listOf(FirstFrameAction.WithoutPassthrough), actions)
    }

    @Test fun aSlowTrackReadDoesNotHideAPicture() {
        trackReadDelayMs = 300
        val actions = play(listOf("ac3", "eac3"))
        send(event(MpvEvent.START_FILE), event(MpvEvent.FILE_LOADED), cached(5.0))
        send(event(MpvEvent.VIDEO_RECONFIG))
        send(event(MpvEvent.PLAYBACK_RESTART))
        waitMs(30_000)
        assertEquals(emptyList(), actions)
    }

    @Test fun aTrackThatIsNotPassedThroughSkipsTheRetry() {
        audioCodec = "aac"
        val actions = play(listOf("ac3", "eac3"))
        send(event(MpvEvent.START_FILE), event(MpvEvent.FILE_LOADED), cached(5.0))
        waitMs(9_000)
        assertEquals(listOf(FirstFrameAction.Platform), actions, "decoding AAC again can't bring a picture")
    }

    @Test fun aTrackOutsideThePassthroughListSkipsTheRetry() {
        audioCodec = "dts"
        val actions = play(listOf("ac3", "eac3"))
        send(event(MpvEvent.START_FILE), event(MpvEvent.FILE_LOADED), cached(5.0))
        waitMs(9_000)
        assertEquals(listOf(FirstFrameAction.Platform), actions)
    }

    @Test fun noPictureWithDecodedAudioGivesWayToTheBuiltInPlayer() {
        val actions = play(emptyList())
        send(event(MpvEvent.START_FILE), event(MpvEvent.FILE_LOADED), cached(5.0))
        waitMs(9_000)
        assertEquals(listOf(FirstFrameAction.Platform), actions)
    }

    @Test fun aSlowNetworkGetsTheLongerCeiling() {
        val actions = play(emptyList())
        send(event(MpvEvent.START_FILE), event(MpvEvent.FILE_LOADED), cached(0.5))
        waitMs(18_000)
        assertEquals(emptyList(), actions, "no buffer yet: the network, not the player, is what's slow")
        waitMs(3_000)
        assertEquals(listOf(FirstFrameAction.Platform), actions)
    }

    @Test fun aPictureStopsTheWatchdog() {
        val actions = play(listOf("ac3"))
        send(event(MpvEvent.START_FILE), event(MpvEvent.FILE_LOADED), cached(5.0))
        waitMs(1_000)
        send(event(MpvEvent.VIDEO_RECONFIG))
        send(event(MpvEvent.PLAYBACK_RESTART))
        waitMs(30_000)
        assertEquals(emptyList(), actions)
    }

    @Test fun anEmptyWindowIsNotAPicture() {
        decodedWidth = 0
        val actions = play(emptyList())
        send(event(MpvEvent.START_FILE), event(MpvEvent.FILE_LOADED), cached(5.0))
        send(event(MpvEvent.VIDEO_RECONFIG))
        send(event(MpvEvent.PLAYBACK_RESTART))
        waitMs(9_000)
        assertEquals(listOf(FirstFrameAction.Platform), actions, "audio playing under a blank window is still no picture")
    }
}
