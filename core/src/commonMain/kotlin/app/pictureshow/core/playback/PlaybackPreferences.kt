/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import app.pictureshow.core.SegmentMode
import app.pictureshow.core.SegmentType

enum class SubtitleSize { System, Small, Normal, Large, ExtraLarge }
enum class SubtitleBackground { System, None, Shadow, Box }

/** Null is the UI's Auto choice; the protocol always receives the explicit LAN default. */
data class PlaybackPreferences(
    val bitrateMbps: Int? = null,
    val passthrough: Boolean = true,
    val subtitleSize: SubtitleSize = SubtitleSize.System,
    val subtitleBackground: SubtitleBackground = SubtitleBackground.System,
    val segmentModes: Map<SegmentType, SegmentMode> = emptyMap(),
    /** PLAY-27: tell the viewer when the server converts video for this device. */
    val conversionNotice: Boolean = true,
    /** REM-09: off, same-viewer or server-allowed. Server policy stays the ceiling. */
    val remoteReceiver: String = "same-viewer",
    /** MUS-06: auto, original, 256 or 128. */
    val musicQuality: String = "auto",
    val tuning: PlaybackTuning = PlaybackTuning(),
    val subtitleStyle: SubtitleStyle = SubtitleStyle()
) {
    fun deviceSettings(
        network: PlaybackNetwork = PlaybackNetwork.Home,
        estimatedBandwidth: Long? = null,
        qualityOverrideMbps: Int? = null
    ) = PlaybackSettings(
        maxStreamingBitrate = tuning.bitrate(bitrateMbps, network, estimatedBandwidth, qualityOverrideMbps),
        passthroughEnabled = passthrough,
        tuning = tuning.normalized()
    )

    companion object {
        /** Invalid persisted values fall back exactly as the Android preferences store does. */
        fun decode(
            bitrateMbps: Int? = null,
            passthrough: Boolean? = null,
            subtitleSize: String? = null,
            subtitleBackground: String? = null,
            segmentModes: Map<SegmentType, String?> = emptyMap(),
            conversionNotice: Boolean? = null,
            remoteReceiver: String? = null,
            musicQuality: String? = null
        ) = PlaybackPreferences(
            bitrateMbps = bitrateMbps?.takeIf { it in BITRATES },
            passthrough = passthrough ?: true,
            subtitleSize = SubtitleSize.entries.firstOrNull { it.name == subtitleSize } ?: SubtitleSize.System,
            subtitleBackground = SubtitleBackground.entries.firstOrNull { it.name == subtitleBackground } ?: SubtitleBackground.System,
            segmentModes = SegmentType.entries.filter { it != SegmentType.Unknown }.associateWith { type ->
                SegmentMode.entries.firstOrNull { it.name == segmentModes[type] } ?: SegmentMode.Ask
            },
            conversionNotice = conversionNotice ?: true,
            remoteReceiver = remoteReceiver?.takeIf { it in listOf("off", "same-viewer", "server-allowed") } ?: "same-viewer",
            musicQuality = musicQuality?.takeIf { it in listOf("auto", "original", "256", "128") } ?: "auto"
        )

        fun validateBitrate(bitrateMbps: Int?) {
            require(bitrateMbps in BITRATES)
        }

        val BITRATES = listOf(null, 120, 80, 40, 20, 10, 4)
    }
}
