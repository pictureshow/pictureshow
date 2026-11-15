/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/** Report source metadata separately from received frames and the display's current output evidence. */
object PlaybackFormatInformation {
    fun range(range: VideoRangeType?, profile: Int? = null): String {
        val dv = "Dolby Vision" + (profile?.takeIf { it in 1..10 }?.let { " profile $it" } ?: "")
        return when (range) {
            null, VideoRangeType.Unknown -> "Not reported"
            VideoRangeType.SDR -> "SDR"
            VideoRangeType.HDR10 -> "HDR10"
            VideoRangeType.HDR10Plus -> "HDR10+"
            VideoRangeType.HLG -> "HLG"
            VideoRangeType.DOVI -> dv
            VideoRangeType.DOVIWithHDR10 -> "$dv · HDR10 base layer"
            VideoRangeType.DOVIWithHLG -> "$dv · HLG base layer"
            VideoRangeType.DOVIWithSDR -> "$dv · SDR base layer"
            VideoRangeType.DOVIWithEL -> "$dv · enhancement layer"
            VideoRangeType.DOVIWithHDR10Plus -> "$dv · HDR10+ base layer"
            VideoRangeType.DOVIWithELHDR10Plus -> "$dv · HDR10+ enhancement layer"
            VideoRangeType.DOVIInvalid -> "Dolby Vision (invalid metadata)"
        }
    }

    fun profile(value: String?): String = value?.takeIf { it.matches(Regex("[A-Za-z0-9_. ()-]{1,48}")) } ?: "Not reported"

    fun transfer(value: String?): String = when (value?.lowercase()) {
        "pq", "smpte2084" -> "HDR (PQ)"
        "hlg", "arib-std-b67" -> "HDR (HLG)"
        "bt.1886", "bt.709", "srgb", "gamma2.2", "gamma2.4" -> "SDR"
        "linear" -> "Linear light"
        else -> "Not reported"
    }

    /** A requested HDR target is not evidence of a display switch or physical panel output. */
    fun display(receivedTransfer: String?, output: HdrOutput): String {
        val received = transfer(receivedTransfer)
        if (received == "SDR") return "SDR video"
        if (!received.startsWith("HDR")) return "Not reported"
        return when (output) {
            HdrOutput.HdrEdR -> "HDR active (EDR)"
            HdrOutput.HdrScRgb -> "HDR active (scRGB)"
            HdrOutput.Sdr -> "HDR video on an SDR display · mapped to SDR"
            HdrOutput.WaylandPq -> "HDR sent to compositor (PQ / BT.2020) · display HDR not reported"
            HdrOutput.Unknown -> "Not reported"
        }
    }

    fun decoder(hwdec: String?): String = when {
        hwdec == "no" -> "Software (mpv)"
        hwdec != null && hwdec.matches(Regex("[a-zA-Z0-9_-]{1,40}")) -> "Hardware (mpv · $hwdec)"
        else -> "Not reported"
    }
}

enum class HdrOutput { Unknown, Sdr, HdrEdR, HdrScRgb, WaylandPq }
