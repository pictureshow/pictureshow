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

/** Apply viewer format permissions to both sink queries and its actual fallback encoding. */
internal class FormatAudioOutputProvider(delegate: AudioOutputProvider, private val disabled: Set<Int>) :
    ForwardingAudioOutputProvider(delegate) {
    private fun refused(config: AudioOutputProvider.FormatConfig): Boolean {
        val encoding = MimeTypes.getEncoding(config.format.sampleMimeType ?: "", config.format.codecs)
        if (encoding in disabled) return true
        if (disabled.isEmpty() || config.format.sampleMimeType == MimeTypes.AUDIO_RAW) return false
        return try {
            super.getOutputConfig(config).encoding in disabled
        } catch (_: AudioOutputProvider.ConfigurationException) {
            false
        }
    }
    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport =
        if (refused(formatConfig)) AudioOutputProvider.FormatSupport.UNSUPPORTED else super.getFormatSupport(formatConfig)
    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        if (refused(formatConfig)) throw AudioOutputProvider.ConfigurationException("This audio format is disabled in settings")
        return super.getOutputConfig(formatConfig)
    }
}
