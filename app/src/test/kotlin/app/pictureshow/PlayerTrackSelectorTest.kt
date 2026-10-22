package app.pictureshow

import android.content.Context
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class PlayerTrackSelectorTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun serverTranscodeKeepsFirstVariantPerBandwidthAndStillAdaptsDown() {
        val group = masterTracks()
        val selection = assertNotNull(select(PictureshowTrackSelector(context, true), group))
        assertEquals(setOf(0, 3), (0 until selection.length()).map { selection.getIndexInTrackGroup(it) }.toSet())
        assertEquals(setOf(2_000_000, 700_000), (0 until selection.length()).map { selection.getFormat(it).peakBitrate }.toSet())
        assertTrue(selection.length() > 1)
    }

    @Test
    fun serverTranscodeKeepsLowerRatesAcrossVideoCodecs() {
        val selector = PictureshowTrackSelector(context, true)
        assertTrue(selector.parameters.allowVideoMixedMimeTypeAdaptiveness)
        val selection = assertNotNull(select(selector, masterTracks(mixedCodecs = true)))
        assertEquals(setOf(0, 3), (0 until selection.length()).map { selection.getIndexInTrackGroup(it) }.toSet())
        assertEquals(
            setOf(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264),
            (0 until selection.length()).map { selection.getFormat(it).sampleMimeType }.toSet()
        )
        assertFalse(PictureshowTrackSelector(context, false).parameters.allowVideoMixedMimeTypeAdaptiveness)
    }

    @Test
    fun directSourceDoesNotFilterVariants() {
        val selection = assertNotNull(select(PictureshowTrackSelector(context, false), masterTracks()))
        assertEquals(setOf(0, 1, 2, 3), (0 until selection.length()).map { selection.getIndexInTrackGroup(it) }.toSet())
    }

    @Test
    fun unknownBitratesAreNotDeduplicated() {
        val group = TrackGroup(*Array(2) { video("unknown-$it", Format.NO_VALUE) })
        val renderer = videoRenderer { format ->
            RendererCapabilities.create(
                if (format.id == "unknown-0") C.FORMAT_UNSUPPORTED_SUBTYPE else C.FORMAT_HANDLED
            )
        }
        val selection = assertNotNull(select(PictureshowTrackSelector(context, true), group, renderer))
        assertEquals(1, selection.length())
        assertEquals("unknown-1", selection.getFormat(0).id)
    }

    @Test
    fun realRendererReportsHighHevcLevelAsExceededAndSelectorStillAttemptsTrack() {
        val format = video("high-level", 1_100_000).buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hev1.2.4.L186.B0").setFrameRate(24f).build()
        val capabilities = CodecCapabilities.createFromProfileLevel(
            MimeTypes.VIDEO_H265,
            CodecProfileLevel.HEVCProfileMain10,
            CodecProfileLevel.HEVCMainTierLevel51
        )
        val decoder = MediaCodecInfo.newInstance(
            "test.hevc.decoder", MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H265, capabilities,
            true, false, true, false, false
        )
        val codecSelector = MediaCodecSelector { mime, _, _ -> if (mime == MimeTypes.VIDEO_H265) listOf(decoder) else emptyList() }
        val support = MediaCodecVideoRenderer.supportsFormat(context, codecSelector, format)
        assertTrue(decoder.isFormatSupported(context, format.buildUpon().setCodecs("hev1.2.4.L153.B0").build()))
        assertFalse(decoder.isFormatSupported(context, format))
        assertEquals(C.FORMAT_EXCEEDS_CAPABILITIES, RendererCapabilities.getFormatSupport(support))
        val renderer = videoRenderer { support }
        val selector = PictureshowTrackSelector(context, false)
        assertTrue(selector.parameters.exceedRendererCapabilitiesIfNecessary)
        assertEquals(format, assertNotNull(select(selector, TrackGroup(format), renderer)).getFormat(0))
        val strict = PictureshowTrackSelector(context, false)
        strict.parameters = strict.buildUponParameters().setExceedRendererCapabilitiesIfNecessary(false).build()
        assertNull(select(strict, TrackGroup(format), renderer))
    }

    private fun masterTracks(mixedCodecs: Boolean = false): TrackGroup {
        val mainCodecs = if (mixedCodecs) "hvc1.2.4.L153.B0" else "avc1.640028"
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,AVERAGE-BANDWIDTH=1900000,CODECS="$mainCodecs",RESOLUTION=1920x1080,VIDEO-RANGE=PQ
            copy.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,AVERAGE-BANDWIDTH=1800000,CODECS="avc1.640028",RESOLUTION=1920x1080,VIDEO-RANGE=SDR
            sdr-one.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,CODECS="avc1.640028",RESOLUTION=1920x1080,VIDEO-RANGE=SDR
            sdr-two.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=700000,CODECS="avc1.640028",RESOLUTION=640x360,VIDEO-RANGE=SDR
            lower.m3u8
        """.trimIndent()
        val playlist = HlsPlaylistParser().parse(
            Uri.parse("https://media.example.test/base/master.m3u8"),
            master.byteInputStream()
        ) as HlsMultivariantPlaylist
        assertEquals(4, playlist.variants.size)
        // HlsMediaPeriod derives the elementary sample MIME type before exposing video tracks.
        return TrackGroup(
            *playlist.variants.map { variant ->
                variant.format.buildUpon().setSampleMimeType(MimeTypes.getMediaMimeType(variant.format.codecs)).build()
            }.toTypedArray()
        )
    }

    private fun video(id: String, bitrate: Int): Format = Format.Builder().setId(id).setSampleMimeType(MimeTypes.VIDEO_H264)
        .setWidth(1920).setHeight(1080).setPeakBitrate(bitrate).build()

    private fun select(
        selector: PictureshowTrackSelector,
        group: TrackGroup,
        renderer: RendererCapabilities = videoRenderer {
            RendererCapabilities.create(
                C.FORMAT_HANDLED,
                RendererCapabilities.ADAPTIVE_SEAMLESS,
                RendererCapabilities.TUNNELING_NOT_SUPPORTED
            )
        }
    ): ExoTrackSelection? {
        // Set constraints before init: a live player normally activates parameter invalidations.
        // Avoid host display dimensions constraining a made-up stream in this test.
        selector.parameters = selector.buildUponParameters().clearViewportSizeConstraints().build()
        selector.init({}, DefaultBandwidthMeter.Builder(context).build())
        return selector.selectTracks(arrayOf(renderer), TrackGroupArray(group), MediaPeriodId("period"), Timeline.EMPTY).selections[0]
    }

    private fun videoRenderer(support: (Format) -> Int) = object : RendererCapabilities {
        override fun getName() = "test.video"
        override fun getTrackType() = C.TRACK_TYPE_VIDEO
        override fun supportsFormat(format: Format) = support(format)
        override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SEAMLESS
    }
}
