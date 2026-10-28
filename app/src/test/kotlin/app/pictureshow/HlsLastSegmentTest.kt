/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RenderersFactory
import app.pictureshow.core.Server
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamProtocol
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog

/** Real HLS loader, TS extractor, queues and EOS; only platform decoding is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class HlsLastSegmentTest {
    @Test
    fun openInFinalSegmentWithAnEmptyDeclaredAudioTrackBecomesReadyAndEndsOnceAfterPlay() {
        for (target in listOf(3_000L, 3_020L, 3_021L)) checkStart(target)
    }

    @Test
    fun seeksDuringPreparationCanReachTheTailOrReverseAndRemainPaused() {
        checkStart(3_020, initialMs = 1_000, queued = listOf(3_020))
        checkStart(2_000, initialMs = 1_000, queued = listOf(3_020, 2_000))
    }

    @Test
    fun finalSegmentKeepsExternalCaptionsSelectableAcrossTrackReselection() {
        checkStart(3_020, captions = true)
    }

    private fun checkStart(target: Long, initialMs: Long = target, queued: List<Long> = emptyList(), captions: Boolean = false) {
        val data = requireNotNull(javaClass.getResourceAsStream("/playback/copied-audio-preroll.ts")).use { it.readBytes() }
        // Preserve the PMT's AAC declaration but remove all AAC PES packets. A genuine short
        // Jellyfin tail has precisely this property; no fake extractor or readiness callback.
        val emptyAudio = data.asList().chunked(188).filter { packet ->
            val pid = ((packet[1].toInt() and 0x1f) shl 8) or (packet[2].toInt() and 0xff)
            pid != 0x101
        }.flatten().toByteArray()
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=400000,CODECS="avc1.64000a,mp4a.40.2"
            main.m3u8
        """.trimIndent().toByteArray()
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:3
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:3.0,
            previous.ts
            #EXTINF:0.021,
            last.ts
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray()
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val releaseSegment = CountDownLatch(if (queued.isEmpty()) 0 else 1)
        val upstream = DataSource.Factory {
            object : DataSource {
                private var delegate: ByteArrayDataSource? = null
                override fun addTransferListener(transferListener: TransferListener) = Unit
                override fun getUri() = delegate?.uri
                override fun open(dataSpec: DataSpec): Long {
                    val name = requireNotNull(dataSpec.uri.lastPathSegment)
                    requests += name
                    if (name.endsWith(".ts")) releaseSegment.await()
                    val bytes = when (name) {
                        "master.m3u8" -> master
                        "main.m3u8" -> playlist
                        "last.ts" -> emptyAudio
                        "captions.srt" -> "1\n00:00:00,000 --> 00:00:05,000\nCaption\n".toByteArray()
                        else -> data
                    }
                    return ByteArrayDataSource(bytes).also { delegate = it }.open(dataSpec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int) = requireNotNull(delegate).read(buffer, offset, length)
                override fun close() = Unit
            }
        }
        val output = Media3PlaybackOutput(
            RuntimeEnvironment.getApplication(),
            Server("server", "Test", "https://example.test", "12"),
            upstream = upstream,
            renderersFactory = RenderersFactory { _, _, _, _, _ ->
                arrayOf(ReadingRenderer(C.TRACK_TYPE_VIDEO), ReadingRenderer(C.TRACK_TYPE_AUDIO), ReadingRenderer(C.TRACK_TYPE_TEXT))
            }
        )
        val decision = PlayDecision(
            "item",
            MediaSourceInfo(
                id = "source",
                runTimeTicks = 30_210_000,
                transcodingSubProtocol = MediaStreamProtocol.Hls,
                mediaStreams = if (captions) {
                    listOf(
                        MediaStream(
                            type = MediaStreamType.Subtitle,
                            index = 1,
                            codec = "subrip",
                            isExternal = true,
                            deliveryUrl = "https://example.test/captions.srt"
                        )
                    )
                } else {
                    emptyList()
                }
            ),
            PlayMethod.Transcode,
            "https://example.test/master.m3u8",
            emptyMap(),
            "session"
        )
        var ends = 0
        output.onEnded = { ends++ }
        try {
            output.open(decision, initialMs, 0, if (captions) 1 else -1, true)
            val player = requireNotNull(output.player.value)
            if (queued.isNotEmpty()) {
                waitUntil { requests.any { it.endsWith(".ts") } && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) }
                assertEquals(Player.STATE_BUFFERING, player.playbackState)
                queued.forEach(player::seekTo)
                releaseSegment.countDown()
            }
            waitUntil { player.playbackState in listOf(Player.STATE_READY, Player.STATE_ENDED) || player.playerError != null }
            assertNull(player.playerError)
            assertFalse(player.playWhenReady)
            assertEquals(0, ends, "A paused open must wait for explicit Play")
            assertTrue(
                kotlin.math.abs(player.currentPosition - target) <= 1,
                "Keep requested $target, never display the bootstrap position ${player.currentPosition}"
            )
            if (captions) {
                waitUntil { player.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected } }
                assertTrue(output.select(MediaStreamType.Subtitle, -1))
                waitUntil { player.currentTracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.isSelected } }
                assertTrue(output.select(MediaStreamType.Subtitle, 1))
                waitUntil { player.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected } }
                assertFalse(player.playWhenReady)
                assertTrue(kotlin.math.abs(player.currentPosition - target) <= 1)
            }
            player.play()
            waitUntil { ends > 0 }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(1, ends)
            assertTrue("previous.ts" in requests, "Track discovery needs the preceding segment")
        } catch (error: Throwable) {
            throw AssertionError(
                "target=$target requests=$requests logs=${ShadowLog.getLogsForTag("PictureshowPlayback").map {
                    it.msg
                }}",
                error
            )
        } finally {
            releaseSegment.countDown()
            output.release()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!predicate() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(2)
        }
        assertTrue(predicate(), "Native HLS must leave buffering and deliver EOS")
    }

    private class ReadingRenderer(type: Int) : BaseRenderer(type) {
        private val holder = FormatHolder()
        private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        private var ended = false
        override fun getName() = "Reading renderer"
        override fun supportsFormat(format: Format) = RendererCapabilities.create(
            if ((trackType == C.TRACK_TYPE_AUDIO && MimeTypes.isAudio(format.sampleMimeType)) ||
                (trackType == C.TRACK_TYPE_VIDEO && MimeTypes.isVideo(format.sampleMimeType)) ||
                (trackType == C.TRACK_TYPE_TEXT && MimeTypes.isText(format.sampleMimeType))
            ) {
                C.FORMAT_HANDLED
            } else {
                C.FORMAT_UNSUPPORTED_TYPE
            }
        )
        override fun isReady() = isSourceReady
        override fun isEnded() = ended
        override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
            ended = false
        }
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            while (!ended) {
                buffer.clear()
                when (readSource(holder, buffer, 0)) {
                    C.RESULT_NOTHING_READ -> return
                    C.RESULT_BUFFER_READ -> ended = buffer.isEndOfStream
                }
            }
        }
    }
}
