/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/** A derived negotiation view. The platform's detected snapshot is never rewritten. */
object PlaybackCapabilityPolicy {
    fun effective(detected: DeviceCapabilities): DeviceCapabilities {
        val tuning = detected.settings.tuning.normalized()
        val outputLimit = if (tuning.downmix) 2 else tuning.maxAudioChannels
        val formats = detected.audioOutput.passthrough.filterTo(mutableSetOf()) {
            detected.settings.passthroughEnabled &&
                !tuning.downmix &&
                it !in tuning.disabledPassthrough &&
                (outputLimit == null || it.maxChannels <= outputLimit)
        }
        val pcm = minOf(detected.audioOutput.pcmChannels, outputLimit ?: Int.MAX_VALUE)
        return detected.copy(
            videoDecoders = detected.videoDecoders.map { decoder ->
                val hdr = hdrFormats(decoder.hdrFormats, tuning)
                val force = tuning.hdr == CapabilityOverride.Force || tuning.dolbyVision == CapabilityOverride.Force
                decoder.copy(
                    hdrFormats = hdr,
                    dolbyVisionProfiles = when (tuning.dolbyVision) {
                        CapabilityOverride.Disable -> emptySet()
                        CapabilityOverride.Force ->
                            decoder.dolbyVisionProfiles +
                                if (decoder.codec == VideoCodec.HEVC) setOf(5, 7, 8) else emptySet()
                        CapabilityOverride.Detected -> decoder.dolbyVisionProfiles
                    },
                    maxBitDepth = if (force && decoder.codec == VideoCodec.HEVC) maxOf(10, decoder.maxBitDepth) else decoder.maxBitDepth,
                    profileCapabilities = decoder.profileCapabilities.map { tuple ->
                        tuple.copy(hdrFormats = hdrFormats(tuple.hdrFormats, tuning))
                    }
                )
            },
            display = detected.display.copy(hdrFormats = hdrFormats(detected.display.hdrFormats, tuning)),
            audioDecoders = detected.audioDecoders.map { decoder ->
                decoder.copy(maxChannels = minOf(decoder.maxChannels, outputLimit ?: Int.MAX_VALUE))
            },
            audioOutput = AudioOutput(pcm, formats)
        )
    }

    private fun hdrFormats(detected: Set<HdrFormat>, tuning: PlaybackTuning): Set<HdrFormat> {
        val hdr = setOf(HdrFormat.HDR10, HdrFormat.HDR10_PLUS, HdrFormat.HLG)
        var result = when (tuning.hdr) {
            CapabilityOverride.Detected -> detected
            CapabilityOverride.Force -> detected + hdr
            CapabilityOverride.Disable -> detected - hdr
        }
        result = when (tuning.dolbyVision) {
            CapabilityOverride.Detected -> result
            CapabilityOverride.Force -> result + HdrFormat.DOLBY_VISION
            CapabilityOverride.Disable -> result - HdrFormat.DOLBY_VISION
        }
        if (tuning.preferDolbyVision && HdrFormat.DOLBY_VISION in result) result -= HdrFormat.HDR10_PLUS
        return result
    }
}
