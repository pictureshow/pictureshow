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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.Renderer
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PhysicalPlaybackEndTest {
    private val uri = Uri.parse("https://example.test/video")

    @Test
    fun completedBodyAndAllFinalAudioVideoOutputsMustDrainBeforeEndEvenIfTimelineIsWrong() {
        var ends = 0
        val end = PhysicalPlaybackEnd(uri) { ends++ }
        val audio = FakeRenderer(C.TRACK_TYPE_AUDIO)
        val video = FakeRenderer(C.TRACK_TYPE_VIDEO)
        val text = FakeRenderer(C.TRACK_TYPE_TEXT)
        val disabled = FakeRenderer(C.TRACK_TYPE_AUDIO).apply { state = Renderer.STATE_DISABLED }
        val renderers = end.wrap(arrayOf(audio.renderer, video.renderer, text.renderer, disabled.renderer), Handler(Looper.getMainLooper()))
        fun render() {
            renderers[0].render(60_031_000, 0)
            shadowOf(Looper.getMainLooper()).idle()
        }
        audio.ended = true
        video.ended = true
        render()
        assertEquals(0, ends, "A/V EOF alone must not turn a short body into a completed watch")
        val source = end.sourceFactory(DataSource.Factory { Body(4, 4) }).createDataSource()
        source.open(DataSpec(uri))
        source.read(ByteArray(4), 0, 4)
        source.close()
        video.ended = false
        render()
        assertEquals(0, ends, "Wait for the longer track to drain")
        video.ended = true
        video.final = false
        render()
        assertEquals(0, ends, "Intermediate streams are not the end of the item")
        video.final = true
        render()
        render()
        assertEquals(1, ends, "Text with a bogus duration and disabled decoders must not hold A/V open")
    }

    @Test
    fun seekOrReleaseInvalidatesAnEndAlreadyPostedFromThePlaybackThread() {
        var ends = 0
        val end = PhysicalPlaybackEnd(uri) { ends++ }
        val source = end.sourceFactory(DataSource.Factory { Body(4, 4) }).createDataSource()
        source.open(DataSpec(uri))
        source.read(ByteArray(4), 0, 4)
        val audio = FakeRenderer(C.TRACK_TYPE_AUDIO).apply { ended = true }
        val renderer = end.wrap(arrayOf(audio.renderer), Handler(Looper.getMainLooper())).single()
        renderer.render(60_031_000, 0)
        end.invalidate()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, ends)
        renderer.render(60_031_000, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, ends)
        source.close()
    }

    @Test
    fun prematureHttpEndThrowsAndNeverSignalsCompletionAndSubtitleReadsDoNotQualify() {
        val end = PhysicalPlaybackEnd(uri) {}
        val source = end.sourceFactory(DataSource.Factory { Body(8, 4) }).createDataSource()
        source.open(DataSpec(uri))
        source.read(ByteArray(8), 0, 8)
        assertFailsWith<IOException> { source.read(ByteArray(8), 0, 8) }
        assertFalse(end.bodyComplete)
        source.close()
        val subtitle = end.sourceFactory(DataSource.Factory { Body(4, 4) }).createDataSource()
        subtitle.open(DataSpec(Uri.parse("https://example.test/captions.srt")))
        subtitle.read(ByteArray(4), 0, 4)
        assertFalse(end.bodyComplete)
        subtitle.close()
        val complete = end.sourceFactory(DataSource.Factory { Body(4, 4) }).createDataSource()
        complete.open(DataSpec(uri))
        complete.read(ByteArray(4), 0, 4)
        assertTrue(end.bodyComplete)
        complete.close()
    }

    @Test
    fun pendingSeekErrorOrReplacedPlayerCannotDeliverEndAndNaturalEndIsOnlyDeliveredOnce() {
        var state = Player.STATE_BUFFERING
        var failure: PlaybackException? = null
        var current = true
        var ends = 0
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getPlaybackState" -> state
                "getPlayWhenReady" -> true
                "getPlayerError" -> failure
                else -> null
            }
        } as Player
        val signal = PlaybackEndSignal({ player.takeIf { current } }) { ends++ }
        signal.ended() // seekTo masks the state before the playback-thread reset has happened.
        assertEquals(0, ends)
        state = Player.STATE_READY
        failure = PlaybackException("Stream failed", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        signal.ended()
        assertEquals(0, ends)
        failure = null
        current = false
        signal.ended()
        assertEquals(0, ends)
        current = true
        signal.ended()
        state = Player.STATE_ENDED
        signal.ended()
        assertEquals(1, ends)
    }

    private class Body(private val declared: Long, private var remaining: Int) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun getUri(): Uri? = null
        override fun open(dataSpec: DataSpec) = declared
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0) return C.RESULT_END_OF_INPUT
            val count = minOf(remaining, length)
            remaining -= count
            return count
        }
        override fun close() = Unit
    }

    private class FakeRenderer(val type: Int) {
        var state = Renderer.STATE_STARTED
        var ended = false
        var final = true
        val renderer = Proxy.newProxyInstance(Renderer::class.java.classLoader, arrayOf(Renderer::class.java)) { _, method, _ ->
            when (method.name) {
                "getTrackType" -> type
                "getState" -> state
                "isEnded" -> ended
                "isCurrentStreamFinal" -> final
                else -> null
            }
        } as Renderer
    }
}
