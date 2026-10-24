/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter

/** Capture AAC setup before crossing into the platform; no media URLs, labels or sample data. */
internal class AudioCodecDiagnostics(private val delegate: MediaCodecAdapter.Factory) : MediaCodecAdapter.Factory {
    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        if (configuration.format.sampleMimeType == MimeTypes.AUDIO_AAC) {
            // Reading a duplicate is essential: consuming the original would change codec setup.
            val buffer = configuration.mediaFormat.getByteBuffer("csd-0")?.duplicate()
            val size = buffer?.remaining() ?: 0
            // Oversize data may be a sample misdelivered as config. Retain its length and prefix.
            val truncated = size > 1024
            val csd = if (buffer == null) {
                "none"
            } else {
                ByteArray(if (truncated) 16 else size).also(buffer::get).joinToString("") { "%02x".format(it) }
            }
            Log.i(
                "PictureshowPlayback",
                "audio-config codec=${configuration.codecInfo.name} uptimeMs=${SystemClock.uptimeMillis()} " +
                    "mediaFormat=${configuration.mediaFormat} csd0len=$size csd0=$csd truncated=$truncated"
            )
        }
        return delegate.createAdapter(configuration)
    }
}
