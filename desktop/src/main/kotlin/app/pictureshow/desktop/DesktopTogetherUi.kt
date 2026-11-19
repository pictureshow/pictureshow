/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pictureshow.core.MediaItem
import app.pictureshow.core.control.RemoteTarget
import app.pictureshow.core.control.remoteTrackSelected
import app.pictureshow.core.sync.SyncDepartureCause
import app.pictureshow.core.sync.SyncQuality
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Navigation. Host calls hop to the host thread; the page itself opens through the controller as any other.

internal fun DesktopController.watchTogether() {
    val t = together ?: return
    openPage(DesktopRoute.WatchTogether) { withContext(t.hostDispatcher) { t.sync.show() } }
}

internal fun DesktopController.remoteControl() {
    if (together == null) return
    openPage(DesktopRoute.Remote)
}

/** The words for a group notice, as Android's panel says them. */
internal fun syncNoticeText(notice: SyncNotice): String = when (notice.kind) {
    SyncNoticeKind.ACCESS_FAILED -> "Could not check access. Refresh to try again."
    SyncNoticeKind.GROUPS_FAILED -> "Could not load groups. Refresh to try again."
    SyncNoticeKind.REJOIN, SyncNoticeKind.CLOCK -> if (notice.tone == SyncNoticeTone.WARNING) {
        "This device is out of sync with the group. Rejoin to catch up."
    } else {
        "Lost sync with the group. Rejoin to catch up."
    }
    SyncNoticeKind.PREPARING -> "Preparing playback…"
    SyncNoticeKind.LIBRARY_DENIED -> "This title is unavailable to your account. Leave the group to keep watching on your own."
    SyncNoticeKind.CONNECTION -> "Waiting for a server connection. Try again when connected."
    SyncNoticeKind.SOURCE_UNAVAILABLE -> "Watch together needs an online movie, episode or song. Leave the group to play this source separately."
    SyncNoticeKind.CHECK_ACCESS -> "Refresh to check whether your account can join groups."
    SyncNoticeKind.ACCESS_DENIED -> "Your account cannot join groups. Ask your server admin to turn on SyncPlay for your account."
    SyncNoticeKind.REQUEST_FAILED -> "The server has not confirmed that request. Try again after refreshing the connection."
    SyncNoticeKind.SNAPSHOT -> "The group has not finished loading. Rejoin to try again."
    SyncNoticeKind.LEFT, SyncNoticeKind.DEPARTURE -> "You left the group. This device is paused."
    SyncNoticeKind.LEAVE_UNCONFIRMED -> "This device is paused. Leaving was not confirmed. Connect again and choose Leave group."
    SyncNoticeKind.SPEED -> "Speed is fixed at 1× while watching together."
    SyncNoticeKind.QUEUE_PENDING -> "Waiting for the group queue to update…"
    SyncNoticeKind.GROUP_UNAVAILABLE -> "This group is no longer available. Leave it, then choose another group."
    SyncNoticeKind.PLAYBACK_FAILED -> "Playback couldn't start on this device. Leave the group and join again to retry."
}

/** Why this device is no longer in a group (SYN departure), in Android's words. */
internal fun syncDepartureText(departure: app.pictureshow.core.sync.SyncDeparture): String {
    val group = departure.groupName?.takeUnless { it.isBlank() } ?: "the group"
    return when (departure.cause) {
        SyncDepartureCause.MANUAL_LEAVE -> "You left the group. This device is paused."
        SyncDepartureCause.PLAYBACK_STOPPED -> "You left $group when you stopped playback."
        SyncDepartureCause.PLAYBACK_ENDED -> "You left $group when playback ended."
        SyncDepartureCause.PLAYBACK_CLOSED -> "You left $group when you closed playback."
        SyncDepartureCause.EXTERNAL_ROUTE -> "You left $group to cast."
        SyncDepartureCause.MEMBERSHIP_LOST -> "You're no longer in $group."
    }
}

internal fun syncPhaseText(state: SyncPlayPresentation): String = when {
    state.busy && state.groupId == null -> "Joining the group…"
    state.phase == "Reconnecting" -> "Reconnecting…"
    state.phase == "Waiting" -> when (state.waitReason) {
        "Buffer" -> "Waiting for everyone to be ready"
        "Seek" -> "Waiting after a group seek."
        else -> "Waiting for the group…"
    }
    state.phase == "Playing" -> "Playing with the group"
    state.phase == "Paused" -> "Paused by the group"
    state.queue.isEmpty() -> "Nothing playing yet"
    else -> state.phase
}

internal fun syncQualityText(quality: SyncQuality): String = when (quality) {
    SyncQuality.UNKNOWN -> "Sync estimate unavailable"
    SyncQuality.IN_SYNC -> "Looks in sync"
    SyncQuality.CATCHING_UP -> "Catching up"
    SyncQuality.OUT_OF_SYNC -> "Out of sync · Rejoin"
}

/** SYN-01..07: groups on this server, creating and joining one, and the group itself with its shared controls. */
@Composable
internal fun DesktopWatchTogetherPage(c: DesktopController) {
    val t = c.together ?: return
    val state by t.sync.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp).probe("together-page"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Watch together", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text("People on this server can see your group and members. No public invite link.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val notice = effectiveSyncNotice(state)
        if (notice != null) item {
            Card(colors = CardDefaults.cardColors(containerColor = if (notice.tone == SyncNoticeTone.INFO) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val text = if (notice.kind == SyncNoticeKind.DEPARTURE) state.departure?.let(::syncDepartureText) ?: syncNoticeText(notice) else syncNoticeText(notice)
                    Text(text, Modifier.weight(1f).probe("together-notice"))
                    when (notice.action) {
                        SyncNoticeAction.REJOIN -> TextButton(onClick = t.sync::rejoinPlayback, modifier = Modifier.controlFocus().probe("together-rejoin")) { Text("Rejoin") }
                        SyncNoticeAction.LEAVE -> TextButton(onClick = { t.sync.action("leave", null) }, modifier = Modifier.controlFocus().probe("together-notice-leave")) { Text("Leave group") }
                        SyncNoticeAction.REFRESH -> TextButton(onClick = { t.sync.action("refresh", null) }, modifier = Modifier.controlFocus().probe("together-notice-refresh")) { Text("Refresh") }
                        null -> Unit
                    }
                }
            }
        }
        if (state.groupId != null) {
            item { DesktopTogetherGroup(t, state) }
        } else {
            item { DesktopTogetherChooser(t, state) }
            items(state.groups, key = { it.id }) { group ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(group.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (group.members == 1) "1 person" else "${group.members} people", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { t.sync.action("join", group.id) }, enabled = state.accessAllowed && !state.busy,
                        modifier = Modifier.controlFocus().heightIn(min = 48.dp).probe("together-join:${group.id}").semantics { contentDescription = "Join ${group.name}" }) { Text("Join") }
                }
            }
        }
    }
}

@Composable
private fun DesktopTogetherChooser(t: DesktopTogether, state: SyncPlayPresentation) {
    var name by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.accessNotice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.probe("together-access")) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Groups on this server", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            OutlinedButton(onClick = { t.sync.action("refresh", null) }, enabled = state.canRefresh, modifier = Modifier.controlFocus().heightIn(min = 48.dp).probe("together-refresh")) { Text("Refresh groups") }
        }
        when {
            state.groupsFailed -> Text("Couldn't reach the server, so groups can't be shown. Refresh to try again.", modifier = Modifier.probe("together-groups-failed"))
            state.groups.isEmpty() && state.accessAllowed && !state.busy -> Text("No groups are available on this server.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.probe("together-empty"))
        }
        if (state.accessAllowed) {
            if (state.policy == "CreateAndJoinGroups") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it.take(64) }, label = { Text("Group name") }, singleLine = true,
                        modifier = Modifier.widthIn(max = 360.dp).weight(1f, fill = false).probe("together-name"))
                    Button(onClick = { t.sync.action("create", name.trim()); name = "" }, enabled = name.isNotBlank() && !state.busy,
                        modifier = Modifier.controlFocus().heightIn(min = 48.dp).probe("together-create")) { Text("Create and join") }
                }
            } else {
                Text("Your account can join existing groups. Ask your server admin about creating a group.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DesktopTogetherGroup(t: DesktopTogether, state: SyncPlayPresentation) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(state.groupName ?: "Your group", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }.probe("together-group-name"))
        Text(syncPhaseText(state), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.probe("together-phase"))
        Text("${syncQualityText(state.quality)} · Sync is estimated from this device. Other devices may differ.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (state.participants.isNotEmpty()) {
            Text("People watching: ${state.participants.joinToString(", ")}", modifier = Modifier.probe("together-people"))
            Text("Jellyfin shares names only, not who is ready.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Text("This device: " + when (state.localPlayback) {
            SyncLocalPlayback.UNAVAILABLE -> "Playback status unavailable"
            SyncLocalPlayback.PREPARING -> "Preparing playback…"
            SyncLocalPlayback.BUFFERING -> "Buffering"
            SyncLocalPlayback.READY -> "Ready"
        }, modifier = Modifier.probe("together-local"))
        Text("Group playback", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text("Changes here apply to everyone. Audio, subtitles and quality stay personal.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        val usable = state.queue.isNotEmpty() && state.phase !in setOf("Error", "Reconnecting")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { t.sync.action("previous", null) }, enabled = usable, modifier = Modifier.controlFocus().heightIn(min = 48.dp).probe("together-previous")) { Text("Previous") }
            val playing = state.phase == "Playing"
            Button(onClick = { t.sync.action(if (playing) "pause" else "play", null) }, enabled = usable, modifier = Modifier.widthIn(min = 104.dp).controlFocus().heightIn(min = 48.dp).probe("together-toggle")) { Text(if (playing) "Pause" else "Play") }
            OutlinedButton(onClick = { t.sync.action("next", null) }, enabled = usable, modifier = Modifier.controlFocus().heightIn(min = 48.dp).probe("together-next")) { Text("Next") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Repeat")
            listOf("RepeatNone" to "Off", "RepeatOne" to "One", "RepeatAll" to "All").forEach { (value, label) ->
                FilterChip(state.repeat == value, { t.sync.action("repeat", value) }, { Text(label) }, modifier = Modifier.controlFocus().probe("together-repeat:$value"))
            }
            Spacer(Modifier.width(16.dp))
            FilterChip(state.shuffle == "Shuffle", { t.sync.action("shuffle", if (state.shuffle == "Shuffle") "Sorted" else "Shuffle") }, { Text("Shuffle") },
                modifier = Modifier.controlFocus().probe("together-shuffle"))
        }
        Text("Group queue", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (state.queue.isEmpty()) Text("Play a movie, episode or song while you're in this group, and it starts for everyone.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.probe("together-queue-empty"))
        state.queue.forEach { row ->
            val title = row.title ?: if (row.titleUnavailable) "Title unavailable" else "Loading…"
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text((if (row.current) "Now playing · " else "") + title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!row.current) TextButton(onClick = { t.sync.action("select-entry", row.id) }, modifier = Modifier.controlFocus().probe("together-select:${row.id}")) { Text("Play") }
                TextButton(onClick = { t.sync.action("remove-entry", row.id) }, modifier = Modifier.controlFocus().probe("together-remove:${row.id}").semantics { contentDescription = "Remove $title from the group queue" }) { Text("Remove") }
            }
        }
        Text("Your settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Wait for this device")
                Text("When off, the group does not wait for this device.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Switch(!state.ignoreWait, { wait -> t.sync.action("ignore-wait", (!wait).toString()) },
                modifier = Modifier.controlFocus().probe("together-wait").semantics { contentDescription = "Wait for this device" })
        }
        OutlinedButton(onClick = { t.sync.action("leave", null) }, modifier = Modifier.controlFocus().heightIn(min = 48.dp).probe("together-leave")) { Text("Leave group") }
        Text("Leaving pauses this device.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

/** SYN-08's player chip: in a group, the player says so, and Leave is one menu away. */
@Composable
internal fun DesktopSyncChip(c: DesktopController) {
    val t = c.together ?: return
    val state by t.sync.state.collectAsState()
    if (state.groupId == null) return
    var open by remember { mutableStateOf(false) }
    val opener = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    Box {
        AssistChip(onClick = { open = true }, label = { Text("Watching together · ${state.groupName ?: "Group"} · ${syncQualityText(state.quality)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = Modifier.focusRequester(opener).controlFocus().probe("together-chip"))
        DesktopDropdownMenu(open, { open = false }, opener, first) {
            DropdownMenuItem({ Text(if (state.ignoreWait) "Wait for this device" else "Don't wait for this device") }, { open = false; t.sync.action("ignore-wait", (!state.ignoreWait).toString()) },
                modifier = Modifier.focusRequester(first).menuItemFocus().probe("together-chip-wait"))
            DropdownMenuItem({ Text("Leave group") }, { open = false; t.sync.action("leave", null) }, modifier = Modifier.menuItemFocus().probe("together-chip-leave"))
        }
    }
}

/** CTRL-08 and SYN's stay reminder: shown under every page but the player while something needs the viewer. */
@Composable
internal fun DesktopTogetherBar(c: DesktopController) {
    val t = c.together ?: return
    val ui by t.controller.state.collectAsState()
    val reminder = t.sync.stayReminder
    val attached = ui.controller?.takeIf { it.phase !in setOf("detached", "signed-out") }?.target
    if (reminder == null && attached == null) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            reminder?.let { r ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val line = when {
                        r.leaveUnconfirmed -> r.groupName?.let { "Leaving $it wasn't confirmed" } ?: "Leaving the group wasn't confirmed"
                        else -> r.groupName?.let { "Still in $it" } ?: "Still in your Watch together group"
                    }
                    Text(line, Modifier.weight(1f).probe("together-stay"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!r.leaveUnconfirmed) TextButton(onClick = t.sync::rejoinPlayback, modifier = Modifier.controlFocus().probe("together-stay-watch")) { Text("Watch") }
                    TextButton(onClick = { t.sync.action("leave", null) }, modifier = Modifier.controlFocus().probe("together-stay-leave")) { Text("Leave") }
                }
            }
            attached?.let { target ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(remoteProblem(ui.controller?.phase) ?: remoteNowLine(target), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("remote-bar-status"))
                        Text(target.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = c::remoteControl, modifier = Modifier.controlFocus().probe("remote-bar-open")) { Text("Remote") }
                }
            }
        }
    }
}

internal fun remoteNowLine(target: RemoteTarget): String {
    val now = target.now ?: return REMOTE_IDLE
    val title = now.title ?: "Playing"
    return if (now.paused) "$title, paused" else title
}

/** REM-01/03: "Play on" another device from a title page. Opening the menu lists devices; choosing one sends Play now. */
@Composable
internal fun DesktopPlayOnButton(c: DesktopController, item: MediaItem) {
    val t = c.together ?: return
    val ui by t.controller.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    val opener = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    DisposableEffect(open) {
        t.controller.setPicker(open)
        onDispose { if (open) t.controller.setPicker(false) }
    }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.focusRequester(opener).controlFocus().heightIn(min = 48.dp).probe("play-on")) { Text("Play on…") }
        DesktopDropdownMenu(open, { open = false }, opener, first) {
            when {
                ui.listing -> DropdownMenuItem({ Text("Looking for devices…") }, {}, enabled = false, modifier = Modifier.focusRequester(first).menuItemFocus())
                ui.listFailed && ui.targets.isEmpty() -> DropdownMenuItem({ Text("Couldn't list devices. Try again.") }, {}, enabled = false, modifier = Modifier.focusRequester(first).menuItemFocus())
                ui.targets.isEmpty() -> DropdownMenuItem({ Text("No other devices. Open Pictureshow on the TV, signed in to this server.") }, {}, enabled = false,
                    modifier = Modifier.focusRequester(first).menuItemFocus().probe("play-on-none"))
                else -> ui.targets.forEachIndexed { i, target ->
                    DropdownMenuItem(
                        { Column {
                            Text(target.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            target.viewerName?.takeIf { !target.sameViewer }?.let { Text("Signed in as $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        } },
                        {
                            open = false
                            t.controller.attach(target)
                            t.controller.playNow(listOf(item.id))
                            c.remoteControl()
                        },
                        modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).menuItemFocus().probe("play-on:${target.session}")
                    )
                }
            }
        }
    }
}

/** REM-04/06: the remote for the attached device. Controls keep fixed places; status has its own line. */
@Composable
internal fun DesktopRemotePage(c: DesktopController) {
    val t = c.together ?: return
    val ui by t.controller.state.collectAsState()
    val state = ui.controller
    val target = state?.target?.takeIf { state.phase !in setOf("detached", "signed-out") }
    var now by remember { mutableStateOf(t.controller.now()) }
    LaunchedEffect(Unit) { while (true) { delay(500); now = t.controller.now() } }
    DisposableEffect(target == null) {
        // Nothing attached: the page offers the list, so keep it fresh while it shows.
        t.controller.setPicker(target == null)
        onDispose { t.controller.setPicker(false) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp).probe("remote-page"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Remote", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        if (target == null) {
            Text(remoteProblem(state?.phase) ?: "Control Pictureshow on a TV from this computer. The TV needs to be on and signed in to this server.")
            ui.targets.forEach { candidate ->
                OutlinedButton(onClick = { t.controller.attach(candidate) }, modifier = Modifier.controlFocus().heightIn(min = 48.dp).probe("remote-choose:${candidate.session}")) {
                    Text(candidate.name + (candidate.viewerName?.takeIf { !candidate.sameViewer }?.let { " · Signed in as $it" } ?: ""))
                }
            }
            if (ui.targets.isEmpty() && !ui.listing) Text("No other devices are available right now.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.probe("remote-none"))
            return@Column
        }
        Text(target.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("remote-target"))
        target.viewerName?.takeIf { !target.sameViewer }?.let { Text("Signed in as $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        // The status line keeps its height whatever it says, so no control moves when it changes (REM-04).
        val status = remoteProblem(state.phase) ?: remoteCommandStatus(state, now, ui.capped) ?: if (state.phase == "attached") remoteNowLine(target) else REMOTE_CHECKING
        Text(status, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.probe("remote-status"))
        val playing = target.now
        val usable = state.phase == "attached"
        playing?.takeIf { it.durationTicks > 0 }?.let { n ->
            Text("${formatTicks(n.positionTicks)} / ${formatTicks(n.durationTicks)}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.probe("remote-position"))
        }
        val canSeek = usable && playing?.canSeek == true
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = t.controller::previous, enabled = usable && playing != null, modifier = Modifier.width(112.dp).controlFocus().heightIn(min = 48.dp).probe("remote-previous")) { Text("Previous") }
            OutlinedButton(onClick = { t.controller.seekBy(-REMOTE_STEP_TICKS) }, enabled = canSeek, modifier = Modifier.width(96.dp).controlFocus().heightIn(min = 48.dp).probe("remote-back")) { Text("−30 s") }
            val paused = playing?.paused == true
            Button(onClick = { if (paused) t.controller.play() else t.controller.pause() }, enabled = usable && playing != null,
                modifier = Modifier.width(104.dp).controlFocus().heightIn(min = 48.dp).probe("remote-toggle").semantics { stateDescription = if (paused) "Paused" else "Playing" }) { Text(if (paused) "Play" else "Pause") }
            OutlinedButton(onClick = { t.controller.seekBy(REMOTE_STEP_TICKS) }, enabled = canSeek, modifier = Modifier.width(96.dp).controlFocus().heightIn(min = 48.dp).probe("remote-forward")) { Text("+30 s") }
            OutlinedButton(onClick = t.controller::next, enabled = usable && playing != null, modifier = Modifier.width(112.dp).controlFocus().heightIn(min = 48.dp).probe("remote-next")) { Text("Next") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = t.controller::stop, enabled = usable && playing != null, modifier = Modifier.controlFocus().heightIn(min = 48.dp).probe("remote-stop")) { Text("Stop") }
            TextButton(onClick = t.controller::detach, modifier = Modifier.controlFocus().heightIn(min = 48.dp).probe("remote-disconnect")) { Text("Disconnect") }
        }
        Text("Disconnect leaves the TV playing. Stop ends it.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        playing?.let { n ->
            listOf("Audio" to "Audio", "Subtitle" to "Subtitles").forEach { (type, label) ->
                val tracks = n.tracks.filter { it.type == type }
                if (tracks.isEmpty()) return@forEach
                val reported = if (type == "Audio") n.audio else n.subtitle
                Text(label + if (reported == null) " · $REMOTE_TRACK_UNREPORTED" else "", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val offered = if (type == "Subtitle") listOf(app.pictureshow.core.control.RemoteTrack("Subtitle", -1, "Off")) + tracks else tracks
                    offered.forEach { track ->
                        FilterChip(remoteTrackSelected(reported, track.index), {
                            if (type == "Audio") t.controller.audio(track.index) else t.controller.subtitle(track.index)
                        }, { Text(track.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }, enabled = usable, modifier = Modifier.controlFocus().probe("remote-$type:${track.index}"))
                    }
                }
            }
        }
    }
}

private const val REMOTE_STEP_TICKS = 300_000_000L

private fun formatTicks(ticks: Long): String {
    val total = ticks / 10_000_000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** The sidebar's Watch together and Remote entries. */
@Composable
internal fun DesktopTogetherNav(c: DesktopController, route: DesktopRoute) {
    SidebarItem("Watch together", route == DesktopRoute.WatchTogether, "nav:together", c::watchTogether)
    SidebarItem("Remote", route == DesktopRoute.Remote, "nav:remote", c::remoteControl)
}
