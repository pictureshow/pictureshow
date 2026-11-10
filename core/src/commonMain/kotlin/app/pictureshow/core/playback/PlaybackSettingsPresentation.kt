/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/** Viewer-facing labels; stored protocol and enum identifiers never become UI choices. */
object PlaybackSettingsPresentation {
    fun canConfigurePassthrough(passthrough: Boolean, downmix: Boolean, supported: Boolean) = passthrough && !downmix && supported

    fun subtitleSizeLabel(value: SubtitleSize) = subtitleValueLabel(value.name)
    fun subtitleBackgroundLabel(value: SubtitleBackground) = subtitleValueLabel(value.name)
    fun subtitleValueLabel(value: String) = when (value) {
        "System" -> "Default"
        "ExtraLarge" -> "Extra large"
        "Authored" -> "As in the file"
        else -> value
    }
    fun postCreditsLabel(value: PostCredits) = if (value == PostCredits.KeepScenes) "Play to the end" else "Next episode when credits start"
    fun audioChannelLabel(value: Int?) = when (value) {
        2 -> "Stereo (2)"
        6 -> "5.1"
        8 -> "7.1"
        else -> "Automatic"
    }
    fun passthroughLabel(value: PassthroughEncoding) = when (value) {
        PassthroughEncoding.AC3 -> "Dolby Digital"
        PassthroughEncoding.EAC3 -> "Dolby Digital Plus"
        PassthroughEncoding.EAC3_JOC -> "Dolby Atmos (Dolby Digital Plus)"
        PassthroughEncoding.TRUEHD -> "Dolby TrueHD"
        PassthroughEncoding.DTS -> "DTS"
        PassthroughEncoding.DTS_HD -> "DTS-HD"
    }
    fun capabilityLabel(value: CapabilityOverride, hdr: Boolean) = when (value) {
        CapabilityOverride.Detected -> "Automatic"
        CapabilityOverride.Force -> "Always on"
        CapabilityOverride.Disable -> if (hdr) "Off (convert to SDR)" else "Off"
    }
    fun capabilityConsequence(value: CapabilityOverride, hdr: Boolean) = when (value) {
        CapabilityOverride.Force -> "Only if your device supports it but Automatic gets it wrong. Video may fail to play."
        CapabilityOverride.Disable -> if (hdr) {
            "Converts HDR video to SDR. This may require video conversion on your server."
        } else {
            "Uses another supported format or converts the video. This may require conversion on your server."
        }
        CapabilityOverride.Detected -> "Uses supported formats. HDR switches automatically with the video."
    }
}
