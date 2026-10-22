package app.pictureshow

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.opus.LibopusAudioRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class PlayerRenderersTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val handler = Handler(Looper.getMainLooper())
    private val codec = MediaCodecInfo.newInstance(
        "test.audio", MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_FLAC, null, false, true, false, false, false
    )

    @Test
    fun flacUsesLargerOfStreaminfoMaxFrameAndUncompressedFrame() {
        val format = flacFormat(block = 4096, channels = 6, bits = 24, frame = 33863)
        assertEquals(73728, flacInputSize(format))
        assertEquals(73728, renderer().inputSize(codec, format))
        assertEquals(90000, flacInputSize(flacFormat(block = 4096, channels = 6, bits = 24, frame = 90000)))
        assertEquals(120000, renderer().inputSize(codec, format.buildUpon().setMaxInputSize(120000).build()))
    }

    @Test
    fun onlyFlacChangesAndMalformedStreaminfoPreservesUpstreamValue() {
        val flac = flacFormat(block = 4096, channels = 6, bits = 24, frame = 33863)
        for (mime in listOf(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_E_AC3)) {
            val format = flac.buildUpon().setSampleMimeType(mime).setMaxInputSize(12345).build()
            assertEquals(Format.NO_VALUE, flacInputSize(format))
            assertEquals(12345, renderer().inputSize(codec, format))
        }
        val missing = flac.buildUpon().setInitializationData(emptyList()).setMaxInputSize(123).build()
        assertEquals(123, renderer().inputSize(codec, missing))
        assertEquals(Format.NO_VALUE, flacInputSize(flac.buildUpon().setInitializationData(listOf(byteArrayOf(1, 2))).build()))
    }

    @Test
    fun rendererReplacementKeepsExtensionPositionInEveryMode() {
        val modes = listOf(
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        )
        for (mode in modes) {
            val renderers = PictureshowRenderersFactory(context).setExtensionRendererMode(mode).createRenderers(
                handler,
                object : VideoRendererEventListener {},
                object : AudioRendererEventListener {},
                { _: CueGroup -> },
                {}
            ).toList()
            val platform = renderers.indexOfFirst { it is FlacBufferAudioRenderer }
            val extension = renderers.indexOfFirst { it is LibopusAudioRenderer }
            assertTrue(platform >= 0)
            assertEquals(1, renderers.count { it is FlacBufferAudioRenderer })
            when (mode) {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF -> assertEquals(-1, extension)
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON -> assertTrue(platform < extension)
                else -> assertTrue(extension in 0 until platform)
            }
            assertFalse(renderers.any { it.javaClass == androidx.media3.exoplayer.audio.MediaCodecAudioRenderer::class.java })
        }
    }

    @Test
    fun hlsResumeKeepsPreviousKeyframeWithNoTimedBackBuffer() {
        val loadControl = createPictureshowLoadControl()
        assertEquals(0L, loadControl.getBackBufferDurationUs(PlayerId.UNSET))
        assertTrue(loadControl.retainBackBufferFromKeyframe(PlayerId.UNSET))
    }

    private fun renderer() = InspectableRenderer(context, handler)

    private class InspectableRenderer(context: Context, handler: Handler) :
        FlacBufferAudioRenderer(
            context,
            MediaCodecAdapter.Factory.DEFAULT,
            MediaCodecSelector.DEFAULT,
            true,
            handler,
            object : AudioRendererEventListener {},
            DefaultAudioSink.Builder(context).build()
        ) {
        fun inputSize(codec: MediaCodecInfo, format: Format) = getCodecMaxInputSize(codec, format, arrayOf(format))
    }

    private fun flacFormat(block: Int, channels: Int, bits: Int, frame: Int): Format {
        val data = ByteArray(42)
        "fLaC".toByteArray().copyInto(data)
        data[4] = 0x80.toByte()
        data[7] = 34
        data[10] = (block shr 8).toByte()
        data[11] = block.toByte()
        data[15] = (frame shr 16).toByte()
        data[16] = (frame shr 8).toByte()
        data[17] = frame.toByte()
        data[20] = (((channels - 1) shl 1) or ((bits - 1) shr 4)).toByte()
        data[21] = ((bits - 1) shl 4).toByte()
        return Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC).setChannelCount(channels).setSampleRate(48000)
            .setInitializationData(listOf(data)).build()
    }
}
