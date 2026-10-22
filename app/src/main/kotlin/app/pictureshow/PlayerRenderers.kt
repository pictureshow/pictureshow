package app.pictureshow

import android.content.Context
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import kotlin.math.max

/** Platform decoders first; optional audio extensions fill the gaps by reflection. */
@UnstableApi
class PictureshowRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
        setEnableDecoderFallback(true)
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        val firstNewRenderer = out.size
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
        // Replace in place. In PREFER mode extensions precede this renderer; in ON they follow it.
        for (index in firstNewRenderer until out.size) {
            if (out[index].javaClass == MediaCodecAudioRenderer::class.java) {
                out[index] = FlacBufferAudioRenderer(
                    context,
                    codecAdapterFactory,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    audioSink
                )
            }
        }
    }
}

@UnstableApi
internal open class FlacBufferAudioRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: AudioRendererEventListener,
    audioSink: AudioSink
) : MediaCodecAudioRenderer(
    context,
    codecAdapterFactory,
    mediaCodecSelector,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    audioSink
) {
    override fun getCodecMaxInputSize(codecInfo: MediaCodecInfo, format: Format, streamFormats: Array<Format>): Int {
        val platformSize = super.getCodecMaxInputSize(codecInfo, format, streamFormats)
        if (format.sampleMimeType != MimeTypes.AUDIO_FLAC) return platformSize
        // STREAMINFO can be absent or malformed. Preserve the upstream value in that case.
        return streamFormats.filter { it.sampleMimeType == MimeTypes.AUDIO_FLAC }
            .fold(max(platformSize, flacInputSize(format))) { size, candidate -> max(size, flacInputSize(candidate)) }
    }
}

/** Matroska FLAC codec private data is fLaC + the metadata block header + 34-byte STREAMINFO. */
@UnstableApi
internal fun flacInputSize(format: Format): Int {
    if (format.sampleMimeType != MimeTypes.AUDIO_FLAC) return Format.NO_VALUE
    val data = format.initializationData.firstOrNull() ?: return Format.NO_VALUE
    val offset = when {
        data.size >= 42 &&
            data[0] == 'f'.code.toByte() &&
            data[1] == 'L'.code.toByte() &&
            data[2] == 'a'.code.toByte() &&
            data[3] == 'C'.code.toByte() &&
            (data[4].toInt() and 0x7f) == 0 -> 8
        data.size == 34 -> 0
        else -> return Format.NO_VALUE
    }
    fun byte(index: Int) = data[offset + index].toInt() and 0xff
    val maxBlockSize = (byte(2) shl 8) or byte(3)
    val maxFrameSize = (byte(7) shl 16) or (byte(8) shl 8) or byte(9)
    val channels = ((byte(12) shr 1) and 7) + 1
    val bitsPerSample = (((byte(12) and 1) shl 4) or (byte(13) shr 4)) + 1
    val uncompressedSize = (maxBlockSize.toLong() * channels * bitsPerSample + 7) / 8
    return max(maxFrameSize.toLong(), uncompressedSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/** Retaining the keyframe prevents an unbound HLS sample queue discarding it during resume. */
@UnstableApi
fun createPictureshowLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
    .setBackBuffer(0, true)
    .build()
