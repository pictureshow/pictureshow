/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/** Viewer wording describes the requested ceiling, never a measured speed or guaranteed output bitrate. */
object PlaybackQualityPresentation {
    val choices: List<Int?> = PlaybackPreferences.BITRATES

    fun choiceLabel(mbps: Int?): String = mbps?.let { "$it Mbps" } ?: "Auto"

    fun automaticContext(network: PlaybackNetwork, configuredBitrate: Int): String = "Automatic: ${when (network) {
        PlaybackNetwork.Home -> "Home"
        PlaybackNetwork.Remote -> "Remote"
        PlaybackNetwork.Metered -> "Metered"
    }} limit ${bitrate(configuredBitrate)}"

    fun reductionNotice(overrideMbps: Int?, activeBitrate: Int, configuredBitrate: Int): String? =
        if (overrideMbps == null && activeBitrate < configuredBitrate) {
            "Quality limit reduced to ${bitrate(activeBitrate)} to avoid buffering."
        } else {
            null
        }

    private fun bitrate(bits: Int): String = if (bits < 1_000) {
        "<1 kbps"
    } else if (bits % 1_000_000 == 0) {
        "${bits / 1_000_000} Mbps"
    } else {
        "${bits / 1_000} kbps"
    }
}

/** Temporary choices belong to a viewing. Re-entering Auto tries its network limit with fresh samples. */
data class PlaybackQualitySelection(val overrideMbps: Int? = null, val estimatedBandwidth: Long? = null) {
    fun choose(mbps: Int?): PlaybackQualitySelection {
        require(mbps in PlaybackQualityPresentation.choices)
        return copy(overrideMbps = mbps, estimatedBandwidth = null)
    }
}
