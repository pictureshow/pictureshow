/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Names and membership counts are facts supplied by the signed-in server, never inferred locally. */
internal data class SyncPlayGroupRow(val id: String, val name: String, val members: Int)

internal data class SyncPlayQueueRow(val id: String, val title: String, val current: Boolean)

internal data class SyncPlayPresentation(
    val server: String = "",
    val viewer: String = "",
    val phase: String = "idle",
    val status: String = "",
    val policy: String = "None",
    val groupId: String? = null,
    val groupName: String? = null,
    val groups: List<SyncPlayGroupRow> = emptyList(),
    val participants: List<String> = emptyList(),
    val queue: List<SyncPlayQueueRow> = emptyList(),
    val ignoreWait: Boolean = false,
    val repeat: String = "RepeatNone",
    val shuffle: String = "Sorted",
    val busy: Boolean = false
)

/**
 * One modal for both Android presentations. Actions are requests: only the next server state changes the selection.
 * Dialog supplies the modal reader boundary and returns input focus to the opener when it closes.
 */
@Composable
internal fun SyncPlayPanel(state: SyncPlayPresentation, onAction: (String, String?) -> Unit, onDismiss: () -> Unit) {
    val tv = usesTvInterface(LocalContext.current)
    val closeFocus = remember { FocusRequester() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // TV's outer theme uses tv-material; give this shared material3 sheet the same dark surface as touch.
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color(0xFFD0BCFF),
                surface = Color(0xFF10131C),
                surfaceContainer = Color(0xFF202330)
            )
        ) {
            Surface(
                modifier = Modifier.padding(if (tv) 24.dp else 12.dp).widthIn(max = 760.dp).fillMaxWidth()
                    .heightIn(max = if (tv) 460.dp else 760.dp).imePadding()
                    .testTag("sync:panel").semantics { paneTitle = "Watch together" },
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 6.dp
            ) {
                Column(Modifier.padding(if (tv) 24.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Watch together",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f).semantics { heading() }
                        )
                        SyncPlayAction("Close", onDismiss, modifier = Modifier.focusRequester(closeFocus).testTag("sync:close"))
                    }
                    HorizontalDivider()
                    Column(
                        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "${state.server.ifBlank { "This server" }} · ${state.viewer.ifBlank { "Signed-in viewer" }}",
                            style = MaterialTheme.typography.labelLarge
                        )
                        if (state.status.isNotBlank()) {
                            Text(
                                state.status,
                                modifier = Modifier.fillMaxWidth().testTag("sync:status").semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                        if (state.groupId == null && state.phase == "Reconnecting") {
                            Text("The group request is not confirmed. Leave before trying another group.")
                            SyncPlayAction("Leave uncertain group", { onAction("leave", null) })
                        } else if (state.groupId == null) {
                            SyncPlayGroups(state, onAction)
                        } else {
                            SyncPlayJoined(state, onAction)
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            if (tv) closeFocus.requestFocus()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SyncPlayGroups(state: SyncPlayPresentation, onAction: (String, String?) -> Unit) {
    val permitted = state.policy == "CreateAndJoinGroups" || state.policy == "JoinGroups"
    var name by rememberSaveable(state.server, state.viewer) { mutableStateOf("") }
    Text("Groups and participant names are visible to other authorized viewers on this server. There is no public invite link.")
    if (!permitted) {
        Text("This viewer is not allowed to join watch-together groups. Ask the server administrator to enable SyncPlay access.")
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SyncPlayAction(
            "Refresh groups",
            { onAction("refresh", null) },
            enabled = permitted && !state.busy,
            modifier = Modifier.testTag("sync:refresh")
        )
    }
    if (permitted && state.groups.isEmpty() && !state.busy && state.phase.lowercase() != "error") {
        Text("No groups are available on this server.", modifier = Modifier.testTag("sync:empty"))
    }
    if (permitted) {
        state.groups.forEach { group ->
            SyncPlayAction(
                "Join ${group.name} · ${group.members} ${if (group.members == 1) "participant" else "participants"}",
                { onAction("join", group.id) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().testTag("sync:join:${group.id}")
            )
        }
    }
    if (state.policy == "CreateAndJoinGroups") {
        HorizontalDivider()
        Text("Create a group", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Group name") },
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().testTag("sync:name")
        )
        SyncPlayAction(
            "Create and join",
            { onAction("create", name.trim()) },
            enabled = !state.busy && name.isNotBlank(),
            modifier = Modifier.testTag("sync:create")
        )
    } else if (state.policy == "JoinGroups") {
        Text("You can join an existing group. Creating groups is disabled by this viewer's server policy.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SyncPlayJoined(state: SyncPlayPresentation, onAction: (String, String?) -> Unit) {
    val ready = !state.busy && state.phase.lowercase() !in setOf("joining", "reconnecting", "leaving", "error")
    Text(
        state.groupName ?: "Your group",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag("sync:group").semantics { heading() }
    )
    Text(
        state.phase.replaceFirstChar { it.uppercase() },
        modifier = Modifier.testTag("sync:phase").semantics { liveRegion = LiveRegionMode.Polite }
    )
    if (state.participants.isNotEmpty()) {
        Text("Participants: ${state.participants.joinToString(", ")}", modifier = Modifier.testTag("sync:participants"))
    }
    if (state.phase == "Reconnecting" && state.groupId != null) {
        SyncPlayAction("Rejoin and refresh synchronization", { onAction("join", state.groupId) })
    }
    Text("Playback and the queue are shared. Tracks, subtitles and quality stay personal to this device.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SyncPlayAction("Previous", { onAction("previous", null) }, ready, Modifier.testTag("sync:previous"))
        SyncPlayAction("Play", { onAction("play", null) }, ready, Modifier.testTag("sync:play"))
        SyncPlayAction("Pause", { onAction("pause", null) }, ready, Modifier.testTag("sync:pause"))
        SyncPlayAction("Next", { onAction("next", null) }, ready, Modifier.testTag("sync:next"))
    }
    SyncPlayAction(
        if (state.ignoreWait) "Include this device in waiting" else "Ignore wait for this device",
        { onAction("ignore-wait", (!state.ignoreWait).toString()) },
        enabled = ready,
        modifier = Modifier.fillMaxWidth().testTag("sync:ignore-wait").semantics { selected = state.ignoreWait }
    )
    Text("Ignoring wait excludes only this device from the group's ready barrier. It does not remove another participant.")
    Text("Repeat", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("RepeatNone" to "Off", "RepeatOne" to "One", "RepeatAll" to "All").forEach { (value, label) ->
            SyncPlayAction(
                label,
                { onAction("repeat", value) },
                enabled = ready,
                modifier = Modifier.testTag("sync:repeat:$value").semantics { selected = state.repeat == value }
            )
        }
    }
    SyncPlayAction(
        if (state.shuffle == "Shuffle") "Turn shuffle off" else "Shuffle queue",
        { onAction("shuffle", if (state.shuffle == "Shuffle") "Sorted" else "Shuffle") },
        enabled = ready,
        modifier = Modifier.testTag("sync:shuffle").semantics { selected = state.shuffle == "Shuffle" }
    )
    HorizontalDivider()
    Text("Group queue", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    if (state.queue.isEmpty()) Text("Waiting for the server's queue. Choose an accessible title to start the group's queue.")
    state.queue.forEach { entry ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SyncPlayAction(
                "${if (entry.current) "Current: " else "Play: "}${entry.title}",
                { onAction("select-entry", entry.id) },
                enabled = ready,
                modifier = Modifier.fillMaxWidth().testTag("sync:entry:${entry.id}").semantics { selected = entry.current }
            )
            SyncPlayAction(
                "Remove from group queue",
                { onAction("remove-entry", entry.id) },
                enabled = ready,
                modifier = Modifier.testTag("sync:remove:${entry.id}").semantics {
                    contentDescription = "Remove ${entry.title} from the group queue"
                }
            )
        }
    }
    HorizontalDivider()
    Text("Leave keeps this device's position paused. The group can keep watching.")
    SyncPlayAction(
        "Leave group",
        { onAction("leave", null) },
        enabled = state.phase.lowercase() != "leaving",
        modifier = Modifier.fillMaxWidth().testTag("sync:leave")
    )
}

/** A visible focus outline is needed when this material3 button is reached with a TV remote. */
@Composable
private fun SyncPlayAction(label: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.heightIn(min = 48.dp).onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
    ) { Text(label) }
}
