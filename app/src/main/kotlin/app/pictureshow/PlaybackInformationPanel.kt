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
    val available = PlaybackSpeeds.choices.filter { PlaybackSpeeds.allowed(it, state.encodedAudio) }
    TrackChoices(
        available.map { (it * 100).toInt() to PlaybackSpeeds.label(it) },
        (state.speed * 100).toInt(),
        { onSelect(it / 100f) },
        title = "Playback speed",
        touchEnabled = true,
        footer = {
            Text("Pitch is preserved. Resets to 1× next time.")
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
    val source = decision.mediaSource
    if (decision.method == app.pictureshow.core.playback.PlayMethod.Transcode) {
        InformationSection("Why the server converts this stream") {
            PlaybackInformation.reasons(decision, qualityMbps).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    InformationSection("Server-reported media") {
        Text("Container: ${PlaybackInformation.codec(source.container)}")
        Text("Total bitrate: ${PlaybackInformation.bitrate(source.bitrate)}")
        val video = source.mediaStreams.firstOrNull { it.type == MediaStreamType.Video }
        Text("Video: ${PlaybackInformation.codec(video?.codec)}")
        Text("Resolution: ${PlaybackInformation.resolution(video?.width, video?.height)}")
        Text("Video bitrate: ${PlaybackInformation.bitrate(video?.bitRate)}")
        val audio = source.mediaStreams.firstOrNull { it.type == MediaStreamType.Audio && it.index == audioIndex }
        Text(if (audioIndex < 0) "Audio: Off" else "Audio: ${PlaybackInformation.codec(audio?.codec)}")
        if (audioIndex >= 0) Text("Audio bitrate: ${PlaybackInformation.bitrate(audio?.bitRate)}")
        Text("Subtitles: ${PlaybackInformation.subtitle(source, subtitleIndex)}")
    }
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
    InformationSection("Stream received by this device") {
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
            Text("$label: ${PlaybackInformation.codec(codec)}")
            if (type == C.TRACK_TYPE_VIDEO) Text("Resolution: ${PlaybackInformation.resolution(format?.width, format?.height)}")
            Text("$label bitrate: ${PlaybackInformation.bitrate(rate)}")
        }
    }
    Text(
        "Server metadata may describe the converted stream. Received formats are read " +
            "from the player; missing values are not estimated.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("playback:metadata-note")
    )
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
