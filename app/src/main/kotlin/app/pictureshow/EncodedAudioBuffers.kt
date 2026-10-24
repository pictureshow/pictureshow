/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.media.AudioTrack
import androidx.media3.common.C
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink

/** Keep compressed buffers below AudioFlinger's next power-of-two allocation cliff. */
internal object EncodedAudioBuffers : DefaultAudioSink.AudioTrackBufferSizeProvider {
    // Android 11 TrackBase rounds bytes to a power of two, then adds the control block.
    // 2 MiB leaves room for that block and a retiring track in a 4 MiB client heap.
    const val MAX_BYTES = 2 * 1024 * 1024

    override fun getBufferSizeInBytes(
        minBufferSizeInBytes: Int,
        encoding: Int,
        outputMode: Int,
        pcmFrameSize: Int,
        sampleRate: Int,
        bitrate: Int,
        maxAudioTrackPlaybackSpeed: Double
    ): Int {
        val original = DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT.getBufferSizeInBytes(
            minBufferSizeInBytes,
            encoding,
            outputMode,
            pcmFrameSize,
            sampleRate,
            bitrate,
            maxAudioTrackPlaybackSpeed
        )
        if (!bounded(encoding)) return original
        // Never violate the platform minimum. If it cannot fit, the normal audio fallback remains available.
        return original.coerceAtMost(MAX_BYTES).coerceAtLeast(minBufferSizeInBytes)
    }

    fun smaller(config: AudioOutputProvider.OutputConfig): AudioOutputProvider.OutputConfig? {
        if (!bounded(config.encoding) || config.bufferSize != MAX_BYTES) return null
        val minimum = AudioTrack.getMinBufferSize(config.sampleRate, config.channelMask, config.encoding)
        // One bounded retry still holds 466 ms at Media3's 18 Mb/s DTS-HD maximum (342 ms at 24.5 Mb/s).
        // The sink's private one-second threshold prevents its own halving loop for unknown DTS-HD.
        if (minimum <= 0 || minimum > MAX_BYTES / 2) return null
        return config.buildUpon().setBufferSize(MAX_BYTES / 2).build()
    }

    // Preserve the working low-rate formats' sizing and retry policy exactly, including offload.
    private fun bounded(encoding: Int): Boolean = !Util.isEncodingLinearPcm(encoding) &&
        encoding !in setOf(
            C.ENCODING_AC3,
            C.ENCODING_E_AC3,
            C.ENCODING_E_AC3_JOC,
            C.ENCODING_DTS
        )
}
