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
import app.pictureshow.core.playback.PlaybackPreferenceJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

typealias PlaybackPreferences = app.pictureshow.core.playback.PlaybackPreferences

val PlaybackPreferences.appearance get() = SubtitleAppearance(subtitleSize, subtitleBackground, subtitleStyle)

class PlaybackPreferencesStore(private val preferences: DataStore<Preferences>) {
    val values: Flow<PlaybackPreferences> = preferences.data.map(::decode)
    suspend fun read(): PlaybackPreferences = values.first()

    suspend fun update(change: (PlaybackPreferences) -> PlaybackPreferences) {
        preferences.edit { data ->
            val next = change(decode(data))
            PlaybackPreferences.validateBitrate(next.bitrateMbps)
            next.bitrateMbps?.let { data[BITRATE] = it } ?: data.remove(BITRATE)
            data[PASSTHROUGH] = next.passthrough
            data[CONVERSION_NOTICE] = next.conversionNotice
            data[MUSIC_QUALITY] = next.musicQuality
            data[REMOTE_RECEIVER] = next.remoteReceiver
            data[TUNING] = json.encodeToString(next.tuning)
            data[STYLE] = json.encodeToString(next.subtitleStyle.normalized())
            data[SIZE] = next.subtitleSize.name
            data[BACKGROUND] = next.subtitleBackground.name
            SegmentType.entries.filter { it != SegmentType.Unknown }.forEach { type ->
                data[segmentKey(type)] = (next.segmentModes[type] ?: SegmentMode.Ask).name
            }
        }
    }

    private fun decode(data: Preferences) = PlaybackPreferences.decode(
        bitrateMbps = data[BITRATE],
        passthrough = data[PASSTHROUGH],
        subtitleSize = data[SIZE],
        subtitleBackground = data[BACKGROUND],
        segmentModes = SegmentType.entries.filter { it != SegmentType.Unknown }.associateWith { data[segmentKey(it)] },
        conversionNotice = data[CONVERSION_NOTICE],
        musicQuality = data[MUSIC_QUALITY],
        remoteReceiver = data[REMOTE_RECEIVER]
    ).copy(
        tuning = PlaybackPreferenceJson.tuning(data[TUNING]),
        subtitleStyle = PlaybackPreferenceJson.style(data[STYLE])
    )

    private fun segmentKey(type: SegmentType) = stringPreferencesKey("segment_${type.name.lowercase()}")

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        val TUNING = stringPreferencesKey("playback_tuning")
        val STYLE = stringPreferencesKey("subtitle_style")
        val BITRATE = intPreferencesKey("playback_bitrate_mbps")
        val PASSTHROUGH = booleanPreferencesKey("playback_passthrough")
        val CONVERSION_NOTICE = booleanPreferencesKey("playback_conversion_notice")
        val MUSIC_QUALITY = stringPreferencesKey("music_quality")
        val REMOTE_RECEIVER = stringPreferencesKey("remote_receiver")
        val SIZE = stringPreferencesKey("subtitle_size")
        val BACKGROUND = stringPreferencesKey("subtitle_background")
    }
}
