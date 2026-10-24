/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.media.MediaCodec
import androidx.media3.common.util.ThrowingRunnable
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter

/** Distinguishes unavailable codec slots from a renderer holding a decoded output buffer. */
internal class VideoCodecProbe(private val delegate: MediaCodecAdapter.Factory) : MediaCodecAdapter.Factory {
    var inputMisses = 0L
        private set
    var outputMisses = 0L
        private set

    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        inputMisses = 0
        outputMisses = 0
        val codec = delegate.createAdapter(configuration)
        return object : MediaCodecAdapter by codec {
            // Kotlin delegation does not forward Java default methods. Preserve adapter locking
            // and asynchronous wakeups instead of silently using the interface defaults.
            override fun <E : Exception> useBuffer(runnable: ThrowingRunnable<E>) = codec.useBuffer(runnable)
            override fun registerOnBufferAvailableListener(listener: MediaCodecAdapter.OnBufferAvailableListener): Boolean =
                codec.registerOnBufferAvailableListener(listener)

            override fun dequeueInputBufferIndex(): Int = codec.dequeueInputBufferIndex().also {
                inputMisses = if (it < 0) inputMisses + 1 else 0
            }
            override fun dequeueOutputBufferIndex(bufferInfo: MediaCodec.BufferInfo): Int =
                codec.dequeueOutputBufferIndex(bufferInfo).also {
                    outputMisses = if (it == MediaCodec.INFO_TRY_AGAIN_LATER) outputMisses + 1 else 0
                }
        }
    }
}
