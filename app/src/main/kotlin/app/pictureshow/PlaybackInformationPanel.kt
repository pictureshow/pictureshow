/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlaybackInformation
import app.pictureshow.core.playback.PlaybackSpeeds

@Composable
internal fun SpeedChoices(state: PlaybackSpeedState, transcode: Boolean, onSelect: (Float) -> Unit) {
    val available = availablePlaybackSpeeds(state)
    TrackChoices(
        available.map { (it * 100).toInt() to PlaybackSpeeds.label(it) },
        (state.speed * 100).toInt(),
        { onSelect(it / 100f) },
        title = "Playback speed",
        touchEnabled = true,
        // TV TalkBack moves only between D-pad stops. With encoded audio 1× is the panel's only stop,
        // so its limitation must be part of that stop rather than footer text the reader never reaches.
        rowDescription = { index, label ->
            state.message?.takeIf { index == (state.speed * 100).toInt() }?.let { "$label. $it" }
        },
        footer = {
            Text(PLAYBACK_SPEED_NOTE)
            state.message?.let { Text(it) }
            if (transcode) Text(PlaybackSpeeds.TRANSCODE)
        }
    )
}

@Composable
internal fun PlaybackInformationContent(
    decision: PlayDecision?,
    audioIndex: Int,
    subtitleIndex: Int,
    qualityMbps: Int?,
    player: Player?,
    onClose: () -> Unit
) {
    val closeFocus = remember { FocusRequester() }
    Text("Playback info", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    Button(onClick = onClose, scale = playerButtonScale(), modifier = Modifier.focusRequester(closeFocus).playerTouchClick(onClose)) {
        Text("Close")
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { closeFocus.requestFocus() }
    if (decision == null) {
        Text("Waiting for playback information…")
        return
    }
    Text(PlaybackInformation.method(decision), style = MaterialTheme.typography.titleMedium)
    Text("↑ / ↓ scroll · Back closes", style = MaterialTheme.typography.labelMedium)
    val tracks = rememberReceivedTracks(player)
    playbackInformationSections(decision, audioIndex, subtitleIndex, qualityMbps, tracks).forEach { section ->
        InformationSection(section.title) {
            section.lines.forEach { line ->
                if (section.reasons) Text(line, style = MaterialTheme.typography.bodyMedium) else Text(line)
            }
        }
    }
    Text(
        PLAYBACK_INFORMATION_NOTE,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("playback:metadata-note")
    )
}

internal const val PLAYBACK_INFORMATION_NOTE =
    "Server metadata may describe the converted stream. Received formats are read from the player; missing values are not estimated."

internal const val PLAYBACK_SPEED_NOTE = "Pitch is preserved. Resets to 1× next time."

/** What both modes offer in the speed panel: encoded audio can't change rate, so only what it allows is listed. */
internal fun availablePlaybackSpeeds(state: PlaybackSpeedState): List<Float> =
    PlaybackSpeeds.choices.filter { PlaybackSpeeds.allowed(it, state.encodedAudio) }

internal data class PlaybackInformationSection(val title: String, val lines: List<String>, val reasons: Boolean = false)

/** PLAY-26's content, in its order: why first, then what the server reports, then what this device received. */
internal fun playbackInformationSections(
    decision: PlayDecision,
    audioIndex: Int,
    subtitleIndex: Int,
    qualityMbps: Int?,
    tracks: Tracks
): List<PlaybackInformationSection> = buildList {
    val source = decision.mediaSource
    if (decision.method == app.pictureshow.core.playback.PlayMethod.Transcode) {
        add(PlaybackInformationSection("Why the server converts this stream", PlaybackInformation.reasons(decision, qualityMbps), reasons = true))
    }
    val video = source.mediaStreams.firstOrNull { it.type == MediaStreamType.Video }
    val audio = source.mediaStreams.firstOrNull { it.type == MediaStreamType.Audio && it.index == audioIndex }
    add(
        PlaybackInformationSection(
            "Server-reported media",
            buildList {
                add("Container: ${PlaybackInformation.codec(source.container)}")
                add("Total bitrate: ${PlaybackInformation.bitrate(source.bitrate)}")
                add("Video: ${PlaybackInformation.codec(video?.codec)}")
                add("Resolution: ${PlaybackInformation.resolution(video?.width, video?.height)}")
                add("Video bitrate: ${PlaybackInformation.bitrate(video?.bitRate)}")
                add(if (audioIndex < 0) "Audio: Off" else "Audio: ${PlaybackInformation.codec(audio?.codec)}")
                if (audioIndex >= 0) add("Audio bitrate: ${PlaybackInformation.bitrate(audio?.bitRate)}")
                add("Subtitles: ${PlaybackInformation.subtitle(source, subtitleIndex)}")
            }
        )
    )
    add(
        PlaybackInformationSection(
            "Stream received by this device",
            buildList {
                listOf(C.TRACK_TYPE_VIDEO to "Video", C.TRACK_TYPE_AUDIO to "Audio").forEach { (type, label) ->
                    val group = tracks.groups.firstOrNull { it.type == type && it.isSelected }
                    val format = group?.let { selected ->
                        (0 until selected.length).firstOrNull { selected.isTrackSelected(it) }?.let(selected::getTrackFormat)
                    }
                    val codec = when (format?.sampleMimeType) {
                        "video/avc" -> "H264"
                        "video/hevc" -> "HEVC"
                        "video/av01" -> "AV1"
                        "audio/mp4a-latm" -> "AAC"
                        else -> format?.codecs ?: format?.sampleMimeType?.substringAfter('/')
                    }
                    val rate = format?.averageBitrate?.takeIf { it > 0 } ?: format?.peakBitrate
                    add("$label: ${PlaybackInformation.codec(codec)}")
                    if (type == C.TRACK_TYPE_VIDEO) add("Resolution: ${PlaybackInformation.resolution(format?.width, format?.height)}")
                    add("$label bitrate: ${PlaybackInformation.bitrate(rate)}")
                }
            }
        )
    )
}

/** The player's current tracks, following its changes. */
@Composable
internal fun rememberReceivedTracks(player: Player?): Tracks {
    var tracks by remember(player) { mutableStateOf(player?.currentTracks ?: Tracks.EMPTY) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(value: Tracks) {
                tracks = value
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    return tracks
}

@Composable
private fun InformationSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp).semantics { heading() }
        )
        content()
    }
}
