/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider

/** Keeps route-aware PCM output while preventing both encoded passthrough and encoded offload. */
internal class PcmAudioOutputProvider(delegate: AudioOutputProvider) : ForwardingAudioOutputProvider(delegate) {
    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport =
        if (formatConfig.format.sampleMimeType == MimeTypes.AUDIO_RAW) {
            super.getFormatSupport(formatConfig)
        } else {
            AudioOutputProvider.FormatSupport.UNSUPPORTED
        }

    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        if (formatConfig.format.sampleMimeType != MimeTypes.AUDIO_RAW) {
            throw AudioOutputProvider.ConfigurationException("Encoded audio output is disabled")
        }
        return super.getOutputConfig(formatConfig)
    }
}
