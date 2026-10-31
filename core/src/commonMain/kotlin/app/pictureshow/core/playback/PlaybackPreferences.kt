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
    val segmentModes: Map<SegmentType, SegmentMode> = emptyMap()
) {
    fun deviceSettings() = PlaybackSettings(
        maxStreamingBitrate = bitrateMbps?.times(1_000_000) ?: PlaybackSettings.DEFAULT_MAX_STREAMING_BITRATE,
        passthroughEnabled = passthrough
    )

    companion object {
        /** Invalid persisted values fall back exactly as the Android preferences store does. */
        fun decode(
            bitrateMbps: Int? = null,
            passthrough: Boolean? = null,
            subtitleSize: String? = null,
            subtitleBackground: String? = null,
            segmentModes: Map<SegmentType, String?> = emptyMap()
        ) = PlaybackPreferences(
            bitrateMbps = bitrateMbps?.takeIf { it in BITRATES },
            passthrough = passthrough ?: true,
            subtitleSize = SubtitleSize.entries.firstOrNull { it.name == subtitleSize } ?: SubtitleSize.System,
            subtitleBackground = SubtitleBackground.entries.firstOrNull { it.name == subtitleBackground } ?: SubtitleBackground.System,
            segmentModes = SegmentType.entries.filter { it != SegmentType.Unknown }.associateWith { type ->
                SegmentMode.entries.firstOrNull { it.name == segmentModes[type] } ?: SegmentMode.Ask
            }
        )

        fun validateBitrate(bitrateMbps: Int?) {
            require(bitrateMbps in BITRATES)
        }

        val BITRATES = listOf(null, 120, 80, 40, 20, 10, 4)
    }
}
