/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.pictureshow.core.SegmentMode
import app.pictureshow.core.SegmentType
import app.pictureshow.core.playback.PlaybackSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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
    val appearance get() = SubtitleAppearance(subtitleSize, subtitleBackground)

    companion object {
        val BITRATES = listOf(null, 120, 80, 40, 20, 10, 4)
    }
}

class PlaybackPreferencesStore(private val preferences: DataStore<Preferences>) {
    val values: Flow<PlaybackPreferences> = preferences.data.map(::decode)
    suspend fun read(): PlaybackPreferences = values.first()

    suspend fun update(change: (PlaybackPreferences) -> PlaybackPreferences) {
        preferences.edit { data ->
            val next = change(decode(data))
            require(next.bitrateMbps in PlaybackPreferences.BITRATES)
            next.bitrateMbps?.let { data[BITRATE] = it } ?: data.remove(BITRATE)
            data[PASSTHROUGH] = next.passthrough
            data[SIZE] = next.subtitleSize.name
            data[BACKGROUND] = next.subtitleBackground.name
            SegmentType.entries.filter { it != SegmentType.Unknown }.forEach { type ->
                data[segmentKey(type)] = (next.segmentModes[type] ?: SegmentMode.Ask).name
            }
        }
    }

    private fun decode(data: Preferences) = PlaybackPreferences(
        bitrateMbps = data[BITRATE]?.takeIf { it in PlaybackPreferences.BITRATES },
        passthrough = data[PASSTHROUGH] ?: true,
        subtitleSize = SubtitleSize.entries.firstOrNull { it.name == data[SIZE] } ?: SubtitleSize.System,
        subtitleBackground = SubtitleBackground.entries.firstOrNull { it.name == data[BACKGROUND] } ?: SubtitleBackground.System,
        segmentModes = SegmentType.entries.filter { it != SegmentType.Unknown }.associateWith { type ->
            SegmentMode.entries.firstOrNull { it.name == data[segmentKey(type)] } ?: SegmentMode.Ask
        }
    )

    private fun segmentKey(type: SegmentType) = stringPreferencesKey("segment_${type.name.lowercase()}")

    private companion object {
        val BITRATE = intPreferencesKey("playback_bitrate_mbps")
        val PASSTHROUGH = booleanPreferencesKey("playback_passthrough")
        val SIZE = stringPreferencesKey("subtitle_size")
        val BACKGROUND = stringPreferencesKey("subtitle_background")
    }
}
