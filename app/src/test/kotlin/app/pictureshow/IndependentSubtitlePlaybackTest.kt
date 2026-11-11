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
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.text.TextRenderer
import app.pictureshow.core.Server
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamProtocol
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real HTTP + Media3 HLS/TS/Loader/TextRenderer. Only platform A/V decoding is replaced.
 * The cold-response latch makes READY before subtitle delivery a causal assertion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class IndependentSubtitlePlaybackTest {
    @get:Rule
    val timeLimit = PerTestTimeLimit(600)

    @Test
    fun releaseDiagnosticsReportAutomaticSelectionRetriesAndViewReceiptsAcrossChoices() {
        org.robolectric.shadows.ShadowLog.clear()
        Fixture(failures = 2).use { f ->
            f.release.countDown()
            f.open()
            f.until { f.player.currentCues.cues.isNotEmpty() }
            val diagnostics = requireNotNull(f.output.subtitleDiagnostics)
            val view = androidx.media3.ui.SubtitleView(RuntimeEnvironment.getApplication())
            fun show() {
                val group = f.player.currentCues
                showSubtitleCues(view, group, group.cues, 0, diagnostics)
            }
            show()
            show() // Layout/style recomposition must not repeat a receipt.
            assertTrue(f.output.select(MediaStreamType.Subtitle, 2))
            f.until { f.player.currentCues.cues.any { it.text.toString().contains("Caption 2") } }
            show()
            assertTrue(f.output.select(MediaStreamType.Subtitle, -1))
            val lines = org.robolectric.shadows.ShadowLog.getLogsForTag("PictureshowPlayback").map { it.msg }
                .filter { it.startsWith("subtitle-") }
            assertEquals(1, lines.count { it == "subtitle-select item=item session=session stream=1 paused=true positionMs=0 selection=1" })
            assertTrue(lines.contains("subtitle-loaded item=item session=session stream=1 attempts=3"), lines.toString())
            assertTrue(lines.contains("subtitle-loaded item=item session=session stream=2 attempts=1"), lines.toString())
            assertEquals(
                listOf(
                    "subtitle-cues item=item session=session stream=1 firstCueAtMs=0 selection=1",
                    "subtitle-cues item=item session=session stream=2 firstCueAtMs=0 selection=2"
                ),
                lines.filter { it.startsWith("subtitle-cues") }
            )
            assertTrue(lines.contains("subtitle-select item=item session=session stream=2 paused=true positionMs=0 selection=2"))
            assertTrue(lines.contains("subtitle-select item=item session=session stream=off paused=true positionMs=0 selection=3"))
        }
    }

    @Test
    fun legacyProgressiveSubtitleChildrenBlockPreparationUntilHttpResponds() {
        Fixture(legacy = true).use { f ->
            f.open()
            f.until { f.requests.isNotEmpty() }
            assertEquals(Player.STATE_BUFFERING, f.player.playbackState)
            f.release.countDown()
            f.until { f.player.playbackState == Player.STATE_READY }
        }
    }

    @Test
    fun legacyProgressiveSubtitleFailureDuringPreparePropagatesASourcePlaybackError() {
        Fixture(failures = Int.MAX_VALUE, legacy = true).use { f ->
            f.release.countDown()
            f.open()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (f.player.playerError == null && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
                Thread.sleep(5)
            }
            val error = f.player.playerError
            assertTrue(error is androidx.media3.exoplayer.ExoPlaybackException)
            assertEquals(androidx.media3.exoplayer.ExoPlaybackException.TYPE_SOURCE, error.type)
            assertTrue(f.requests.size >= 4, "Exercise default retry exhaustion: ${f.requests}")
        }
    }

    @Test
    fun coldSubtitlesNeverGateReadyAndArriveWithoutReselectionWhileThirtyFiveOthersStayIdle() {
        Fixture().use { f ->
            f.open()
            f.until { f.player.playbackState == Player.STATE_READY }
            // Optional subtitle loading is independent of video readiness. Its
            // HTTP worker may reach the held server after the READY callback.
            f.until { f.requests.isNotEmpty() }
            assertEquals(Player.STATE_READY, f.player.playbackState,
                "A held selected subtitle response must not withdraw video readiness")
            val requested = synchronized(f.requests) { f.requests.toList() }
            assertEquals(1, requested.size, "Only selected subtitle may open; requests=$requested")
            assertEquals("/sub1.srt", requested.single())
            assertTrue(f.texts.none { it.contains("Caption") })
            f.release.countDown()
            f.until { f.texts.any { it.contains("Caption 1") } }
            assertEquals(requested, synchronized(f.requests) { f.requests.toList() },
                "Other subtitles must stay idle through selected cue delivery")
            assertNull(f.player.playerError)
            assertEquals(Player.STATE_READY, f.player.playbackState)
        }
    }

    @Test
    fun selectedSubtitleSurvivesAReal242SecondExtractionAndItsReadTimeouts() {
        Fixture().use { f ->
            f.open()
            f.until { f.player.playbackState == Player.STATE_READY }
            val ready = System.nanoTime()
            val deadline = ready + TimeUnit.SECONDS.toNanos(242)
            while (System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
                Thread.sleep(10)
                assertNull(f.player.playerError)
                assertEquals(Player.STATE_READY, f.player.playbackState)
            }
            assertTrue(f.requests.size >= 4, "Must cross actual production HTTP read timeouts")
            f.release.countDown()
            f.until(35) { f.texts.any { it.contains("Caption 1") } }
            println(
                "242-second extraction: READY held; subtitle attempts=${f.requests.size}; cueMs=" +
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - ready)
            )
        }
    }

    @Test
    fun repeatedSubtitleFailuresDoNotDropTheSelectedTrackOrFailPlaybackAndThenRecover() {
        Fixture(failures = 6).use { f ->
            f.release.countDown()
            f.open()
            f.until { f.player.playbackState == Player.STATE_READY }
            f.until(40) { f.texts.any { it.contains("Caption 1") } }
            assertEquals(7, f.requests.size, "Must exceed Media3's ordinary retry budget")
            assertNull(f.player.playerError)
            assertEquals(Player.STATE_READY, f.player.playbackState)
            assertTrue(f.player.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected })
        }
    }

    @Test
    fun switchingOffOnAndSeekingKeepTheChosenCuesWithoutReloadingCachedText() {
        Fixture().use { f ->
            f.open()
            f.until { f.player.playbackState == Player.STATE_READY }
            assertTrue(f.output.select(MediaStreamType.Subtitle, -1))
            f.until { f.player.currentTracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.isSelected } }
            assertTrue(f.output.select(MediaStreamType.Subtitle, 2))
            f.until { synchronized(f.requests) { "/sub2.srt" in f.requests } }
            f.release.countDown()
            f.until { f.texts.lastOrNull()?.contains("Caption 2") == true }
            f.player.seekTo(1_000)
            f.until { f.player.playbackState == Player.STATE_READY && f.player.currentPosition == 1_000L }
            assertTrue(f.output.select(MediaStreamType.Subtitle, -1))
            f.until { f.player.currentTracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.isSelected } }
            val count = f.requests.size
            assertTrue(f.output.select(MediaStreamType.Subtitle, 2))
            f.until { f.texts.lastOrNull()?.contains("Caption 2") == true }
            assertEquals(count, f.requests.size, "Off/on reuses parsed cues in this period")
            assertNull(f.player.playerError)
        }
    }

    @Test
    fun quickOffOnDuringABlockedRequestRestartsSelectionAndNeverDeliversTheOldTrack() {
        Fixture().use { f ->
            f.open()
            f.until { f.player.playbackState == Player.STATE_READY && f.requests.isNotEmpty() }
            assertTrue(f.output.select(MediaStreamType.Subtitle, -1))
            f.until { f.player.currentTracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.isSelected } }
            assertTrue(f.output.select(MediaStreamType.Subtitle, 1))
            f.until { f.player.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected } }
            assertTrue(f.output.select(MediaStreamType.Subtitle, 2))
            f.until { synchronized(f.requests) { "/sub2.srt" in f.requests } }
            f.release.countDown()
            f.until { f.texts.lastOrNull()?.contains("Caption 2") == true }
            assertTrue(f.texts.none { it.contains("Caption 1") }, "Canceled selection leaked a late cue")
        }
    }

    @Test
    fun directProgressiveVideoAlsoPreparesBeforeExternalTextArrives() {
        Fixture(direct = true).use { f ->
            f.open()
            f.until { f.player.playbackState == Player.STATE_READY }
            f.release.countDown()
            f.until { f.texts.any { it.contains("Caption 1") } }
            assertEquals(Player.STATE_READY, f.player.playbackState)
        }
    }

    @Test
    fun directEmbeddedTextStillUsesTheContainerAndNeverRequestsExternalText() {
        org.robolectric.shadows.ShadowLog.clear()
        Fixture(embedded = true, direct = true).use { f ->
            f.open()
            f.until { f.player.playbackState == Player.STATE_READY }
            f.until { f.texts.any { it.contains("Embedded caption") } }
            assertTrue(f.requests.isEmpty(), "Embedded text must not be sideloaded")
            val group = f.player.currentCues
            showSubtitleCues(
                androidx.media3.ui.SubtitleView(RuntimeEnvironment.getApplication()),
                group,
                group.cues,
                0,
                requireNotNull(f.output.subtitleDiagnostics)
            )
            assertTrue(
                org.robolectric.shadows.ShadowLog.getLogsForTag("PictureshowPlayback").any {
                    it.msg == "subtitle-cues item=item session=session stream=1 firstCueAtMs=0 selection=1"
                },
                "Embedded view receipts must identify the actual container track"
            )
            f.player.play()
            f.until { f.player.playbackState == Player.STATE_ENDED }
        }
    }

    @Test
    fun serverSeekAndSwitchBetweenRebasedAndFullFileCuesKeepTheSameAuthoredMoment() {
        for (clipping in listOf(false, true)) {
            println("Server-seek case clipping=$clipping")
            Fixture(serverSeek = true, clipping = clipping).use { f ->
                f.open()
                f.until { f.player.playbackState == Player.STATE_READY }
                assertEquals(126_000L, f.player.currentPosition)
                f.release.countDown()
                f.until { f.texts.lastOrNull()?.contains("Caption 1") == true }
                assertTrue(f.output.select(MediaStreamType.Subtitle, 2))
                f.until { f.texts.lastOrNull()?.contains("Caption 2") == true }
                assertEquals(126_000L, f.player.currentPosition)
                assertTrue(f.output.select(MediaStreamType.Subtitle, 1))
                f.until { f.texts.lastOrNull()?.contains("Caption 1") == true }
                assertEquals(126_000L, f.player.currentPosition)
                assertEquals(Player.STATE_READY, f.player.playbackState)
            }
        }
    }

    @Test
    fun aPendingSubtitleNeverHoldsNaturalEndAfterAudioAndVideoDrain() {
        Fixture().use { f ->
            f.open()
            f.until { f.player.playbackState == Player.STATE_READY }
            f.player.play()
            f.until { f.player.playbackState == Player.STATE_ENDED }
            assertEquals(1L, f.release.count, "Natural end must not require the pending subtitle response")
        }
    }

    @Test
    fun sourceReleaseDoesNotWaitForSubtitleResponse() {
        Fixture().use { f ->
            f.open()
            f.until { f.player.playbackState == Player.STATE_READY }
            val start = System.nanoTime()
            f.output.release()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500)
        }
    }

    private class Fixture(
        failures: Int = 0,
        private val legacy: Boolean = false,
        direct: Boolean = false,
        private val serverSeek: Boolean = false,
        private val clipping: Boolean = false,
        private val embedded: Boolean = false
    ) : AutoCloseable {
        val release = CountDownLatch(1)
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val texts = mutableListOf<String>()
        private val attempts = AtomicInteger()
        private val delay = SubtitleDelay()
        private val handlers = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val segment = requireNotNull(
            javaClass.getResourceAsStream(
                when {
                    embedded -> "/playback/embedded-text.mp4"
                    serverSeek -> "/playback/server-seek.ts"
                    else -> "/playback/copied-audio-preroll.ts"
                }
            )
        ).use { it.readBytes() }
        private val base: String
        lateinit var output: Media3PlaybackOutput
            private set
        private var legacyPlayer: androidx.media3.exoplayer.ExoPlayer? = null
        val player: Player get() = legacyPlayer ?: requireNotNull(output.player.value)
        private val decision: PlayDecision

        init {
            server.executor = handlers
            server.createContext("/") { exchange ->
                runCatching {
                    val path = exchange.requestURI.path
                    var status = 200
                    val body = when {
                        path.endsWith("master.m3u8") -> (
                            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=400000,CODECS=\"avc1.64000a,mp4a.40.2\"\nmain.m3u8\n"
                            ).toByteArray()
                        path.endsWith("main.m3u8") ->
                            "#EXTM3U\n#EXT-X-TARGETDURATION:3\n#EXTINF:3.0,\nsegment.ts\n#EXT-X-ENDLIST\n".toByteArray()
                        path.endsWith(".srt") -> {
                            requests += path
                            release.await(320, TimeUnit.SECONDS)
                            if (attempts.incrementAndGet() <= failures) status = 503
                            val number = if (path.contains("/Subtitles/")) 1 else path.removePrefix("/sub").removeSuffix(".srt").toInt()
                            val interval = when {
                                serverSeek && number == 2 && clipping -> "00:02:05,500 --> 00:02:09,000"
                                serverSeek && number == 2 -> "00:02:06,000 --> 00:02:09,000"
                                serverSeek && clipping -> "00:00:01,500 --> 00:00:05,000"
                                else -> "00:00:00,000 --> 00:00:03,000"
                            }
                            "1\n$interval\nCaption $number\n\n".toByteArray()
                        }
                        else -> segment
                    }
                    exchange.sendResponseHeaders(status, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                exchange.close()
            }
            server.start()
            base = "http://127.0.0.1:${server.address.port}"
            output = Media3PlaybackOutput(
                RuntimeEnvironment.getApplication(),
                Server("server", "Test", base, "12"),
                renderersFactory = RenderersFactory { handler, _, _, text, _ ->
                    arrayOf(
                        ReadingRenderer(C.TRACK_TYPE_VIDEO),
                        ReadingRenderer(C.TRACK_TYPE_AUDIO),
                        SubtitleDelayRenderer(
                            SubtitleCueRenderer({ cues ->
                                texts += cues.cues.joinToString { it.text.toString() }
                                text.onCues(cues)
                            }, handler, requireNotNull(output.subtitleDiagnostics)),
                            delay
                        )
                    )
                },
                subtitleDelay = delay
            )
            decision = PlayDecision(
                "item",
                MediaSourceInfo(
                    transcodingSubProtocol = MediaStreamProtocol.Hls,
                    transcodingContainer = if (serverSeek) "ts" else null,
                    runTimeTicks = if (serverSeek) (if (clipping) 1_290_000_000 else 2_000_000_000) else null,
                    mediaStreams =
                    (if (serverSeek) listOf(MediaStream(type = MediaStreamType.Video, realFrameRate = 60.0)) else emptyList()) +
                        (1..(if (embedded) 1 else 36)).map {
                            MediaStream(
                                type = MediaStreamType.Subtitle,
                                index = it,
                                codec = if (embedded) "mov_text" else "subrip",
                                language = "eng",
                                isDefault = it == 1,
                                isExternal = !embedded,
                                deliveryUrl = if (serverSeek &&
                                    it == 1
                                ) {
                                    "$base/Videos/item/source/Subtitles/1/Stream.srt"
                                } else {
                                    "$base/sub$it.srt"
                                }
                            )
                        }
                ),
                if (direct) PlayMethod.DirectPlay else PlayMethod.Transcode,
                if (direct) "$base/stream.${if (embedded) "mp4" else "ts"}" else "$base/master.m3u8?VideoCodec=h264&MaxFramerate=30",
                emptyMap(),
                "session"
            )
        }
        fun open() {
            if (!legacy) {
                output.open(decision, if (serverSeek) 126_000 else 0, 0, 1, true)
                return
            }
            // Reproduce rc22's native-HLS construction, using the pinned 1.11.1 classes.
            val data = PlaybackMedia.dataSourceFactory(Server("server", "Test", base, "12"), decision)
            val item = PlaybackMedia.item(Server("server", "Test", base, "12"), decision)
            val hls = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(data)
                .setAllowChunklessPreparation(false).createMediaSource(item)
            val parsers = androidx.media3.extractor.text.DefaultSubtitleParserFactory()
            val format = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("en").build()
            val subtitle = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                data,
                androidx.media3.extractor.ExtractorsFactory {
                    arrayOf(androidx.media3.extractor.text.SubtitleExtractor(parsers.create(format), format))
                }
            ).setLoadOnlySelectedTracks(true).createMediaSource(androidx.media3.common.MediaItem.fromUri("$base/sub1.srt"))
            legacyPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(
                RuntimeEnvironment.getApplication(),
                RenderersFactory { handler, _, _, _, _ ->
                    arrayOf(ReadingRenderer(C.TRACK_TYPE_VIDEO), TextRenderer({}, handler.looper))
                }
            ).build().apply {
                setMediaSource(androidx.media3.exoplayer.source.MergingMediaSource(hls, subtitle))
                prepare()
            }
        }
        fun until(seconds: Long = 8, predicate: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (!predicate() && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
                Thread.sleep(5)
            }
            assertNull(player.playerError)
            assertTrue(
                predicate(),
                "Condition timed out: state=${player.playbackState}, clipping=$clipping offsetMs=${delay.sourceStartMs}, requests=$requests, texts=$texts"
            )
        }
        override fun close() {
            release.countDown()
            legacyPlayer?.release()
            output.release()
            shadowOf(Looper.getMainLooper()).idle()
            server.stop(0)
            handlers.shutdownNow()
        }
    }

    private class ReadingRenderer(type: Int) : BaseRenderer(type) {
        private val holder = FormatHolder()
        private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        private var ended = false
        private var lastSampleUs = 0L
        private var renderPositionUs = 0L
        override fun getName() = "Reading renderer"
        override fun supportsFormat(format: Format) = RendererCapabilities.create(
            if ((trackType == C.TRACK_TYPE_AUDIO && MimeTypes.isAudio(format.sampleMimeType)) ||
                (trackType == C.TRACK_TYPE_VIDEO && MimeTypes.isVideo(format.sampleMimeType))
            ) {
                C.FORMAT_HANDLED
            } else {
                C.FORMAT_UNSUPPORTED_TYPE
            }
        )
        override fun isReady() = isSourceReady
        override fun isEnded() = ended && renderPositionUs >= lastSampleUs
        override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
            ended = false
            lastSampleUs = positionUs
            renderPositionUs = positionUs
        }
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            renderPositionUs = positionUs
            while (!ended) {
                buffer.clear()
                when (readSource(holder, buffer, 0)) {
                    C.RESULT_NOTHING_READ -> return
                    C.RESULT_BUFFER_READ -> {
                        ended = buffer.isEndOfStream
                        if (!ended) lastSampleUs = maxOf(lastSampleUs, buffer.timeUs)
                    }
                }
            }
        }
    }
}
