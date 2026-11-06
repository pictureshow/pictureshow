/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pictureshow.core.control.RemoteNowPlaying
import app.pictureshow.core.control.RemoteTarget
import app.pictureshow.core.control.RemoteTrack
import app.pictureshow.core.control.remoteTrackSelected

internal fun remoteTargetLabel(target: RemoteTarget): String = buildString {
    append(target.name)
    if (target.duplicateName) append(" (").append(target.device.takeLast(4)).append(')')
    if (!target.sameViewer) target.viewerName?.let { append(", ").append(it) }
}

internal fun remoteTargetDetail(target: RemoteTarget): String = when {
    target.now?.title != null -> "Playing ${target.now?.title}"
    target.now != null -> "In use"
    else -> "Ready"
}

internal fun remoteClock(ticks: Long): String {
    val total = ticks / 10_000_000
    val hours = total / 3_600
    val minutes = (total % 3_600) / 60
    val seconds = total % 60
    fun two(value: Long) = value.toString().padStart(2, '0')
    return if (hours > 0) "$hours:${two(minutes)}:${two(seconds)}" else "$minutes:${two(seconds)}"
}

/** Chooses a TV. It lists what the server says now; picking one does not start anything by itself. */
@Composable
internal fun TouchRemotePicker(state: RemoteControllerUi, onPick: (RemoteTarget) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Play on", modifier = Modifier.semantics { heading() }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                when {
                    state.listing -> Text("Looking for TVs…")
                    state.listFailed && state.targets.isEmpty() -> Text("Couldn't reach the server. Check your connection and try again.")
                    state.targets.isEmpty() -> Text("No TVs are available. Open Pictureshow on your TV and sign in, then try again.")
                }
                state.targets.forEach { target ->
                    OutlinedButton(
                        onClick = { onPick(target) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("remote:target:${target.session}")
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(remoteTargetLabel(target))
                            Text(remoteTargetDetail(target), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Stays in reach while browsing. It shows what the TV reports and opens the remote; it sends nothing itself. */
@Composable
internal fun TouchRemoteBar(state: RemoteControllerUi, nowMs: Long, onOpen: () -> Unit) {
    val target = state.controller?.target ?: return
    val line = remoteProblem(state.controller.phase)?.let { "Needs attention" }
        ?: remoteCommandStatus(state.controller, nowMs)
        ?: target.now?.let { now -> "${now.title ?: "Playing"}${if (now.paused) ", paused" else ""}" }
        ?: "Nothing playing"
    TextButton(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("remote:bar").semantics {
            liveRegion =
                LiveRegionMode.Polite
        }
    ) {
        // One line whatever it says, so the bar's height never moves what is around it.
        Text("${remoteTargetLabel(target)}: $line", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TouchRemoteScreen(state: RemoteControllerUi, host: RemoteControllerHost, onChoose: () -> Unit, onBack: () -> Unit) {
    val controller = state.controller
    val target = controller?.target
    val attached = controller?.phase == "attached"
    val ready = attached && controller?.command?.fresh == true
    val now = target?.now?.takeIf { attached }
    val problem = remoteProblem(controller?.phase)
    val status = remoteCommandStatus(controller, host.now())
    TouchPage(target?.let(::remoteTargetLabel) ?: "Remote", onBack) {
        Column(
            modifier = Modifier.fillMaxWidth().testTag("touch:remote"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (problem != null) {
                Text(problem, modifier = Modifier.testTag("remote:problem").semantics { liveRegion = LiveRegionMode.Polite })
                val replacement = state.targets.firstOrNull { it.session == controller?.replacement && it.device == target?.device }
                if (controller?.phase == "target-restarted" && replacement != null) {
                    Button(onClick = { host.attach(replacement) }, modifier = Modifier.heightIn(min = 48.dp).testTag("remote:reconnect")) {
                        Text("Reconnect")
                    }
                }
            }
            if (target == null) {
                Button(onClick = onChoose, modifier = Modifier.heightIn(min = 48.dp).testTag("remote:choose")) { Text("Choose a TV") }
                return@Column
            }
            RemoteStatusSlot(status, attached, ready, now == null)
            now?.let { RemoteTransport(it, ready, host) }
            if ("MoveUp" in target.commands) RemoteDpad(ready, host)
            if ("SendString" in target.commands) RemoteText(ready, host)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { host.detach() },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("remote:disconnect").semantics {
                        contentDescription = "Disconnect this phone. Playback on the TV continues."
                    }
                ) { Text("Disconnect") }
                OutlinedButton(onClick = onChoose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose another TV") }
            }
        }
    }
}

/** One line of state in room reserved for the longest wording, so no control moves when it comes or goes (REM-04). */
@Composable
private fun RemoteStatusSlot(status: String?, attached: Boolean, ready: Boolean, idle: Boolean) {
    Box(Modifier.fillMaxWidth()) {
        REMOTE_STATUS_WORDINGS.forEach { RemoteReservedText(it) }
        when {
            status != null -> Text(status, modifier = Modifier.testTag("remote:status").semantics { liveRegion = LiveRegionMode.Polite })
            attached && !ready -> Text(REMOTE_CHECKING, modifier = Modifier.testTag("remote:checking"))
            attached && idle -> Text(REMOTE_IDLE, modifier = Modifier.testTag("remote:idle"))
        }
    }
}

@Composable
private fun RemoteReservedText(wording: String) {
    Text(wording, Modifier.alpha(0f).clearAndSetSemantics {})
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoteTransport(now: RemoteNowPlaying, ready: Boolean, host: RemoteControllerHost) {
    Text(now.title ?: "Playing", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("remote:title"))
    Box(Modifier.fillMaxWidth()) {
        RemoteReservedText("${remoteClock(now.positionTicks)} of ${remoteClock(now.durationTicks)}, paused")
        Text(
            "${remoteClock(now.positionTicks)} of ${remoteClock(now.durationTicks)}${if (now.paused) ", paused" else ""}",
            modifier = Modifier.testTag("remote:position")
        )
    }
    var drag by remember(now.item) { mutableStateOf<Float?>(null) }
    if (now.canSeek && now.durationTicks > 0) {
        Slider(
            value = drag ?: (now.positionTicks.toFloat() / now.durationTicks).coerceIn(0f, 1f),
            onValueChange = { drag = it },
            onValueChangeFinished = {
                drag?.let { host.seekTo((it * now.durationTicks).toLong()) }
                drag = null
            },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().testTag("remote:seek").semantics {
                contentDescription = "Position on the TV"
                stateDescription = remoteClock(now.positionTicks)
            }
        )
    }
    // Fixed places, learned by position: Pause never trades lines with its neighbours at any font, and a title that can't
    // seek keeps the gaps. Marks and short numbers don't grow past their cells the way words do.
    Row(Modifier.fillMaxWidth().testTag("remote:transport"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val cell = Modifier.weight(1f)
        RemoteMarkButton("previous", "Previous", ready, "remote:previous", cell) { host.previous() }
        if (now.canSeek) {
            RemoteSeekButton("\u221210", "Back 10 seconds", ready, "remote:back", cell) { host.seekBy(-100_000_000) }
        } else {
            Spacer(cell)
        }
        Button(
            onClick = { if (now.paused) host.play() else host.pause() },
            enabled = ready,
            contentPadding = PaddingValues(0.dp),
            modifier = cell.heightIn(min = 48.dp).testTag("remote:playpause").semantics {
                contentDescription = if (now.paused) "Play" else "Pause"
                stateDescription = if (now.paused) "Paused" else "Playing"
            }
        ) { Icon(touchActionIcon(if (now.paused) "play" else "pause"), contentDescription = null) }
        if (now.canSeek) {
            RemoteSeekButton("+30", "Forward 30 seconds", ready, "remote:forward", cell) { host.seekBy(300_000_000) }
        } else {
            Spacer(cell)
        }
        RemoteMarkButton("next", "Next", ready, "remote:next", cell) { host.next() }
    }
    // Apart, at the far end as in the player: it ends the viewing, so a slip on the row above can't reach it.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        RemoteButton("Stop", ready, "remote:stop") { host.stop() }
    }
    val audio = now.tracks.filter { it.type == "Audio" }
    val subtitles = now.tracks.filter { it.type == "Subtitle" }
    if (audio.size > 1) {
        RemoteTrackHeading("Audio", reported = now.audio != null)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            audio.zip(remoteTrackLabels(audio)).forEach { (track, label) ->
                RemoteTrackButton(label, remoteTrackSelected(now.audio, track.index), ready) { host.audio(track.index) }
            }
        }
    }
    if (subtitles.isNotEmpty()) {
        RemoteTrackHeading("Subtitles", reported = now.subtitle != null)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteTrackButton("Off", remoteTrackSelected(now.subtitle, -1), ready) { host.subtitle(-1) }
            subtitles.zip(remoteTrackLabels(subtitles)).forEach { (track, label) ->
                RemoteTrackButton(label, remoteTrackSelected(now.subtitle, track.index), ready) { host.subtitle(track.index) }
            }
        }
    }
}

/** The same names as the player's own track lists; the server's wording only for a stream it didn't describe. */
internal fun remoteTrackLabels(tracks: List<RemoteTrack>): List<String> {
    val streams = tracks.mapNotNull(RemoteTrack::stream)
    return if (streams.size == tracks.size) trackLabels(streams) else tracks.map(RemoteTrack::label)
}

/**
 * Nothing shows as selected until the TV reports a track, and null isn't Off: the TV may well be showing subtitles it chose
 * by itself. The note takes its room either way, on the heading's own line, so a first pick moves nothing (REM-04).
 */
@Composable
private fun RemoteTrackHeading(title: String, reported: Boolean) {
    val note = "$title \u00b7 $REMOTE_TRACK_UNREPORTED"
    Box(Modifier.fillMaxWidth()) {
        RemoteReservedText(note)
        Text(if (reported) title else note, modifier = Modifier.semantics { heading() })
    }
}

/**
 * The current choice is a filled state rather than a disabled one, whether or not commands can go yet: it is the answer to
 * "what is on", not an option that's unavailable. Both states keep one width, so a change of track moves nothing (REM-04).
 * The border carries the state against the page, where the fill alone is 2:1. There's nothing to do on the current choice,
 * so a screen reader isn't offered an action.
 */
@Composable
private fun RemoteTrackButton(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (selected) {
        FilledTonalButton(
            onClick = {},
            modifier = Modifier.heightIn(min = 48.dp).clearAndSetSemantics {
                this.selected = true
                text = AnnotatedString(label)
            },
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        ) { Text(label) }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp).semantics {
                this.selected = false
                stateDescription = "Not selected"
            },
            border = touchOutlineBorder(enabled)
        ) { Text(label) }
    }
}

@Composable
private fun RemoteButton(label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) { Text(label) }
}

/** A transport cell: its size comes from the grid, so it drops the button's own padding and minimum. Its name is its label. */
@Composable
private fun RemoteCell(name: String, enabled: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(0.dp),
        border = touchOutlineBorder(enabled),
        modifier = modifier.heightIn(min = 48.dp).testTag(tag).semantics { contentDescription = name }
    ) { content() }
}

@Composable
private fun RemoteMarkButton(mark: String, name: String, enabled: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    RemoteCell(name, enabled, tag, modifier, onClick) { Icon(touchActionIcon(mark), contentDescription = null) }
}

@Composable
private fun RemoteSeekButton(amount: String, name: String, enabled: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    RemoteCell(name, enabled, tag, modifier, onClick) {
        Text(amount, Modifier.clearAndSetSemantics {}, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun RemoteDpad(ready: Boolean, host: RemoteControllerHost) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp).testTag("remote:dpad-toggle")) {
        Text(if (open) "Hide TV navigation" else "Navigate the TV")
    }
    if (!open) return
    Text("Moves around Pictureshow on the TV. It doesn't control the TV's own menus.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteButton("Up", ready, "remote:up") { host.navigate("MoveUp") }
        RemoteButton("Down", ready, "remote:down") { host.navigate("MoveDown") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteButton("Left", ready, "remote:left") { host.navigate("MoveLeft") }
        RemoteButton("OK", ready, "remote:select") { host.navigate("Select") }
        RemoteButton("Right", ready, "remote:right") { host.navigate("MoveRight") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteButton("Go back", ready, "remote:tv-back") { host.navigate("Back") }
        RemoteButton("Home", ready, "remote:home") { host.navigate("GoHome") }
    }
}

@Composable
private fun RemoteText(ready: Boolean, host: RemoteControllerHost) {
    var value by rememberSaveable { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value,
        {
            value = it
            failed = false
        },
        label = { Text("Search on the TV") },
        singleLine = true,
        enabled = ready,
        modifier = Modifier.fillMaxWidth().testTag("remote:text")
    )
    Text("Works when the TV has its search screen open. Passwords can't be typed from here.")
    Box(Modifier.fillMaxWidth()) {
        val wording = "That can't be sent. Use up to 256 characters on one line."
        RemoteReservedText(wording)
        if (failed) {
            Text(
                wording,
                modifier = Modifier.semantics {
                    liveRegion =
                        LiveRegionMode.Polite
                }
            )
        }
    }
    Button(
        onClick = {
            if (host.text(value)) value = "" else failed = true
        },
        enabled = ready && value.isNotBlank(),
        modifier = Modifier.heightIn(min = 48.dp).testTag("remote:send")
    ) { Text("Send") }
}
