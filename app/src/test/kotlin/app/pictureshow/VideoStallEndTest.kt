/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.Renderer
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class VideoStallEndTest {
    @Test
    fun drainedAudioAndStalledVideoEndOnceAfterActualExtractedVideoTail() {
        val f = Fixture()
        f.render(0)
        f.render(3_000_000)
        assertEquals(listOf("video-stalled"), f.ends)
        f.render(9_000_000)
        assertEquals(1, f.ends.size)
    }

    @Test
    fun finalVideoWithMissingOutputEosAlsoEndsButFrozenMediaPositionNeverDoes() {
        val f = Fixture()
        f.videoFinal = true
        f.render(0)
        f.render(30_000_000, advance = false)
        assertEquals(emptyList(), f.ends, "Wall time cannot finish a viewing")
        f.render(33_000_000)
        assertEquals(listOf("video-stalled"), f.ends)
    }

    @Test
    fun anyNewFrameOrQueuedInputRestartsTheStallObservation() {
        val f = Fixture()
        f.render(0)
        f.snapshot = f.snapshot.copy(rendered = 11, lastRenderedUs = 59_500_000)
        f.render(2_900_000)
        f.render(5_800_000)
        assertEquals(emptyList(), f.ends, "A slow video that still produces frames is not stalled")
        f.snapshot = f.snapshot.copy(queued = 21, lastQueuedUs = 59_900_000)
        f.render(5_900_000)
        f.render(8_800_000)
        assertEquals(emptyList(), f.ends, "Decoder input progress also restarts observation")
        f.render(8_900_000)
        assertEquals(listOf("video-stalled"), f.ends)
    }

    @Test
    fun pausedBufferingFutureVideoAndIncompleteExtractionCannotEnd() {
        val f = Fixture()
        f.playing = false
        f.render(0)
        f.render(10_000_000)
        assertEquals(emptyList(), f.ends)
        f.playing = true
        f.snapshot = f.snapshot.copy(positionUs = 55_000_000)
        f.render(20_000_000, advance = false)
        f.render(30_000_000, advance = false)
        assertEquals(emptyList(), f.ends, "Audio may legitimately end before the video")
        f.snapshot = f.snapshot.copy(positionUs = 61_000_000)
        f.tail.reset()
        f.tail.sample(60_000_000)
        f.render(40_000_000)
        f.render(50_000_000)
        assertEquals(emptyList(), f.ends, "A completed HTTP range is not proof extraction reached EOF")
        f.tail.complete()
        f.render(60_000_000)
        f.accept = false
        f.render(63_000_000)
        assertEquals(emptyList(), f.ends, "Pending seek BUFFERING still rejects delivery")
        f.accept = true
        f.render(64_000_000)
        assertEquals(listOf("video-stalled"), f.ends)
    }

    @Test
    fun progressPauseOrSeekBetweenPostingAndDeliveryRejectsStaleEnd() {
        for (change in listOf<(Fixture) -> Unit>(
            { it.snapshot = it.snapshot.copy(rendered = 12) },
            { it.playing = false },
            { it.end.invalidate() }
        )) {
            val f = Fixture()
            f.render(0)
            f.render(3_000_000, deliver = false)
            change(f)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(emptyList(), f.ends)
        }
    }

    @Test
    fun selectedAvStillUsesDrainedReasonAndMissingBodyOrAudioCannotStallEnd() {
        val f = Fixture()
        f.audioEnded = false
        f.render(0)
        f.render(10_000_000)
        assertEquals(emptyList(), f.ends)
        f.audioEnded = true
        f.videoEnded = true
        f.render(11_000_000)
        assertEquals(listOf("av-drained"), f.ends)
        val short = Fixture(completeBody = false)
        short.render(0)
        short.render(10_000_000)
        assertEquals(emptyList(), short.ends)
    }

    @Test
    fun seekResetsObservationAndUnavailableOrStoppedRendererCannotQualify() {
        val f = Fixture()
        f.render(0)
        f.render(2_000_000)
        f.end.invalidate()
        f.render(3_000_000)
        f.render(5_900_000)
        assertEquals(emptyList(), f.ends)
        f.render(6_000_000)
        assertEquals(listOf("video-stalled"), f.ends)
        for (available in listOf(false, true)) {
            val guarded = Fixture()
            guarded.available = available
            if (available) guarded.videoState = Renderer.STATE_ENABLED
            guarded.render(0)
            guarded.render(30_000_000)
            assertEquals(emptyList(), guarded.ends)
        }
    }

    @Test
    fun finalStillFrameIsHeldForItsKnownTimelineAndSamplesBeyondTimelineAreProtected() {
        val f = Fixture()
        f.tail.durationUs = 90_000_000
        f.render(0)
        f.render(20_000_000)
        assertEquals(emptyList(), f.ends, "The last sample PTS is not its display end")
        f.render(32_000_000)
        assertEquals(listOf("video-stalled"), f.ends)
        val late = Fixture()
        late.tail.sample(90_000_000)
        late.render(0)
        late.render(20_000_000)
        assertEquals(emptyList(), late.ends, "Actual future samples override an understated duration")
        late.render(32_000_000)
        assertEquals(emptyList(), late.ends, "Start a full observation at the actual tail")
        late.render(35_000_000)
        assertEquals(listOf("video-stalled"), late.ends)
    }

    @Test
    fun appThreadPauseMaskRejectsPostedStallBeforePlayingEventReachesPlaybackThread() {
        var currentlyPlaying = true
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getPlaybackState" -> Player.STATE_READY
                "getPlayerError" -> null
                "isPlaying" -> currentlyPlaying
                else -> null
            }
        } as Player
        val signal = PlaybackEndSignal({ player }) {}
        val f = Fixture(canEndVideoStalled = signal::canEndPlaying)
        f.render(0)
        f.render(3_000_000, deliver = false)
        currentlyPlaying = false // ExoPlayer's app-thread pause mask precedes onEvents.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList(), f.ends, "A stale published isPlaying=true must not finish a paused viewing")
        currentlyPlaying = true
        f.render(4_000_000)
        assertEquals(listOf("video-stalled"), f.ends, "Rejected main-thread delivery remains available after resume")
    }

    private class Fixture(completeBody: Boolean = true, canEndVideoStalled: () -> Boolean = { true }) {
        var playing = true
        var available = true
        var videoState = Renderer.STATE_STARTED
        var accept = true
        var audioEnded = true
        var videoEnded = false
        var videoFinal = false
        var snapshot = VideoEndSnapshot("test.decoder", 20, 10, 0, 0, 59_000_000, 59_000_000, 61_000_000)
        val tail = VideoTail().apply {
            durationUs = 60_000_000
            sample(60_000_000)
            complete()
        }
        val ends = mutableListOf<String>()
        private val uri = Uri.parse("https://example.test/video")
        val end = PhysicalPlaybackEnd(
            uri,
            canEnd = { accept },
            canEndVideoStalled = canEndVideoStalled,
            videoTail = tail,
            videoSnapshots = { if (available) listOf(snapshot) else emptyList() },
            isPlaying = { playing },
            onVideoStalled = { ends += "video-stalled" }
        ) { ends += "av-drained" }
        private fun renderer(type: Int): Renderer = Proxy.newProxyInstance(
            Renderer::class.java.classLoader,
            arrayOf(Renderer::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getTrackType" -> type
                "getState" -> if (type == C.TRACK_TYPE_VIDEO) videoState else Renderer.STATE_STARTED
                "isCurrentStreamFinal" -> type == C.TRACK_TYPE_AUDIO || videoEnded || videoFinal
                "isEnded" -> if (type == C.TRACK_TYPE_AUDIO) audioEnded else videoEnded
                else -> null
            }
        } as Renderer
        private val renderers = end.wrap(
            arrayOf(renderer(C.TRACK_TYPE_AUDIO), renderer(C.TRACK_TYPE_VIDEO)),
            Handler(Looper.getMainLooper())
        )
        init {
            val source = end.sourceFactory(DataSource.Factory { Body() }).createDataSource()
            source.open(DataSpec(uri))
            if (completeBody) source.read(ByteArray(1), 0, 1)
            source.close()
        }
        private var lastTimeUs = 0L
        fun render(timeUs: Long, deliver: Boolean = true, advance: Boolean = true) {
            if (advance) snapshot = snapshot.copy(positionUs = snapshot.positionUs + (timeUs - lastTimeUs))
            lastTimeUs = timeUs
            renderers[1].render(snapshot.positionUs, timeUs)
            if (deliver) shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private class Body : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun getUri(): Uri? = null
        override fun open(dataSpec: DataSpec) = 1L
        override fun read(buffer: ByteArray, offset: Int, length: Int) = 1
        override fun close() = Unit
    }
}
