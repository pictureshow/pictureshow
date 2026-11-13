/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import app.pictureshow.core.sync.SyncAccessNotice
import app.pictureshow.core.sync.SyncPhase
import app.pictureshow.core.sync.SyncQuality
import app.pictureshow.core.sync.syncPeople
import app.pictureshow.core.sync.syncPhase
import java.text.NumberFormat

/** Preserve the production three-argument API, including its trailing dismiss lambda. */
@Composable
internal fun SyncPlayPanel(state: SyncPlayPresentation, onAction: (String, String?) -> Unit, onDismiss: () -> Unit) {
    SyncPlayPanel(state, onAction, onDismiss, fixtureLabel = null)
}

/** Native role components on each surface; every action remains a server request. */
@OptIn(ExperimentalMaterial3Api::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
internal fun SyncPlayPanel(state: SyncPlayPresentation, onAction: (String, String?) -> Unit, onDismiss: () -> Unit, fixtureLabel: String?) {
    val tv = usesTvInterface(LocalContext.current)
    val pane = stringResource(R.string.sync_title)
    var name by rememberSaveable(state.server, state.viewer) { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val joined = state.groupId != null
    val uncertain = !joined && state.phase == "Reconnecting"
    val canCreate = !joined && !uncertain && state.accessAllowed && state.policy == "CreateAndJoinGroups"
    val usable = joined && !state.busy && state.phase !in setOf("Joining", "Reconnecting", "Leaving", "Error")
    val focusKey =
        listOf(state.groupId, uncertain, state.accessAllowed, state.policyKnown, state.busy, state.groups.firstOrNull()?.id, usable)
    var removedEntry by remember { mutableStateOf<String?>(null) }
    val action: (String, String?) -> Unit = { op, value ->
        if (op == "remove-entry") removedEntry = value
        onAction(op, value)
    }
    LaunchedEffect(state.queue.map { it.id }) {
        if (tv && removedEntry != null && state.queue.none { it.id == removedEntry }) {
            withFrameNanos { }
            first.requestFocus()
            removedEntry = null
        }
    }
    val footer: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (joined || uncertain) {
                SyncText(stringResource(R.string.sync_leave_explanation), tv, small = true)
                val modifier = Modifier.fillMaxWidth().testTag("sync:leave").then(if (!usable) Modifier.focusRequester(first) else Modifier)
                if (tv) {
                    androidx.tv.material3.OutlinedButton(
                        onClick = { action("leave", null) },
                        modifier = modifier,
                        enabled = state.phase != "Leaving",
                        colors = androidx.tv.material3.ButtonDefaults.colors(
                            contentColor = TvMaterialTheme.colorScheme.error,
                            focusedContentColor = TvMaterialTheme.colorScheme.onError,
                            focusedContainerColor = TvMaterialTheme.colorScheme.error
                        )
                    ) { androidx.tv.material3.Text(stringResource(R.string.sync_leave)) }
                } else {
                    TextButton(
                        onClick = { action("leave", null) },
                        modifier = modifier,
                        enabled = state.phase != "Leaving",
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.sync_leave))
                    }
                }
            } else if (canCreate) {
                if (tv) {
                    androidx.tv.material3.Button(
                        onClick = { action("create", name.trim()) },
                        enabled = !state.busy && name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("sync:create")
                    ) {
                        androidx.tv.material3.Text(stringResource(R.string.sync_create_join))
                    }
                } else {
                    Button(
                        onClick = { action("create", name.trim()) },
                        enabled = !state.busy && name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("sync:create")
                    ) { Text(stringResource(R.string.sync_create_join)) }
                }
            }
        }
    }
    val body: @Composable ColumnScope.() -> Unit = {
        fixtureLabel?.let { SyncText(stringResource(R.string.sync_fixture, it), tv, Modifier.testTag("sync:fixture")) }
        SyncText(
            stringResource(
                R.string.sync_scope,
                state.server.ifBlank { stringResource(R.string.sync_this_server) },
                state.viewer.ifBlank { stringResource(R.string.sync_signed_in) }
            ),
            tv,
            small = true
        )
        SyncNoticeBanner(state, action, tv)
        if (joined) {
            SyncJoined(state, action, tv, first, usable)
        } else if (uncertain) {
            SyncText(stringResource(R.string.sync_leave_unconfirmed), tv)
        } else {
            SyncGroups(state, action, tv, first, name, { name = it.take(200) })
        }
    }
    if (tv) {
        app.pictureshow.ui.PictureshowTheme {
            TvSettingsPanel(
                stringResource(R.string.sync_title),
                onDismiss,
                first,
                modifier = Modifier.testTag("sync:panel"),
                footer = footer,
                contentFocusKey = focusKey,
                panelWidth = 560.dp,
                content = body
            )
        }
    } else {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            app.pictureshow.ui.PictureshowTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize().imePadding().testTag("sync:panel").semantics { paneTitle = pane },
                    topBar = {
                        TopAppBar(title = { Text(stringResource(R.string.sync_title)) }, navigationIcon = {
                            IconButton(onClick = onDismiss, modifier = Modifier.testTag("sync:close")) {
                                Icon(syncIcon("close"), stringResource(R.string.sync_close))
                            }
                        })
                    },
                    bottomBar = {
                        Surface(tonalElevation = 3.dp) {
                            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) { footer() }
                        }
                    }
                ) { insets ->
                    SyncTouchBody(Modifier.padding(insets), body)
                }
            }
        }
    }
}

/** A fading edge makes the body’s scroll range visible, including at large fonts and above the IME. */
@Composable
private fun SyncTouchBody(modifier: Modifier, body: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = body
        )
        if (scroll.canScrollBackward) {
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(16.dp)
                    .background(Brush.verticalGradient(listOf(surface, Color.Transparent))).testTag("sync:fade-top")
            )
        }
        if (scroll.canScrollForward) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(20.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, surface))).testTag("sync:fade-bottom")
            )
        }
    }
}

@Composable
private fun SyncGroups(
    state: SyncPlayPresentation,
    action: (String, String?) -> Unit,
    tv: Boolean,
    first: FocusRequester,
    name: String,
    onName: (String) -> Unit
) {
    SyncText(stringResource(R.string.sync_privacy), tv, small = true)
    val notice = when (state.access.notice) {
        SyncAccessNotice.CHECKING -> R.string.sync_checking
        SyncAccessNotice.UNKNOWN -> R.string.sync_check_access
        SyncAccessNotice.UNAVAILABLE -> R.string.sync_access_failed
        SyncAccessNotice.DENIED -> R.string.sync_access_denied
        SyncAccessNotice.ALLOWED -> null
    }
    notice?.let {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy) CircularProgressIndicator(Modifier.size(24.dp))
            SyncText(stringResource(it), tv, Modifier.testTag("sync:access"))
        }
    }
    val primaryGroup = state.groups.firstOrNull()?.id.takeIf { state.accessAllowed && !state.busy }
    val namePrimary = primaryGroup == null && state.accessAllowed && !state.busy && state.policy == "CreateAndJoinGroups"
    if (tv || !state.busy) {
        SyncSecondaryAction(
            stringResource(R.string.sync_refresh),
            { action("refresh", null) },
            tv,
            Modifier.testTag("sync:refresh").then(if (primaryGroup == null && !namePrimary) Modifier.focusRequester(first) else Modifier),
            enabled = state.canRefresh
        )
    }
    if (!state.busy &&
        state.accessAllowed &&
        state.groups.isEmpty()
    ) {
        SyncText(stringResource(R.string.sync_empty), tv, Modifier.testTag("sync:empty"))
    }
    if (state.accessAllowed) {
        state.groups.forEach { group ->
            val count = pluralStringResource(R.plurals.sync_people_watching, group.members, syncNumber(group.members))
            val modifier = Modifier.testTag("sync:join:${group.id}").then(
                if (primaryGroup ==
                    group.id
                ) {
                    Modifier.focusRequester(first)
                } else {
                    Modifier
                }
            )
            if (tv) {
                TvSettingsRow(
                    group.name,
                    count,
                    modifier,
                    enabled = !state.busy,
                    onClick = { action("join", group.id) },
                    trailing = { androidx.tv.material3.Text(stringResource(R.string.sync_join)) }
                )
            } else {
                ListItem(
                    headlineContent = { Text(group.name) },
                    supportingContent = { Text(count) },
                    modifier = modifier,
                    trailingContent = {
                        FilledTonalButton(onClick = {
                            action("join", group.id)
                        }, enabled = !state.busy) { Text(stringResource(R.string.sync_join)) }
                    }
                )
            }
        }
    }
    if (state.accessAllowed && state.policy == "CreateAndJoinGroups") {
        SyncHeading(stringResource(R.string.sync_create), tv)
        OutlinedTextField(
            name,
            onName,
            label = {
                Text(stringResource(R.string.sync_group_name))
            },
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().testTag("sync:name").then(if (namePrimary) Modifier.focusRequester(first) else Modifier)
        )
    } else if (state.accessAllowed && state.policy == "JoinGroups") {
        SyncText(stringResource(R.string.sync_join_only), tv, small = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun SyncJoined(
    state: SyncPlayPresentation,
    action: (String, String?) -> Unit,
    tv: Boolean,
    first: FocusRequester,
    usable: Boolean
) {
    val groupName = state.groupName ?: stringResource(R.string.sync_your_group)
    if (tv) {
        androidx.tv.material3.Text(
            groupName,
            Modifier.padding(horizontal = 16.dp).testTag("sync:group").semantics { heading() },
            style = TvMaterialTheme.typography.headlineSmall
        )
    } else {
        Text(groupName, Modifier.testTag("sync:group").semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
    }
    SyncText(syncPhaseText(state), tv, Modifier.testTag("sync:phase").semantics { liveRegion = LiveRegionMode.Polite })
    SyncText(syncQualityText(state.quality), tv, Modifier.testTag("sync:quality"))
    SyncText(stringResource(R.string.sync_estimate_explanation), tv, small = true)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        SyncTransport("previous", stringResource(R.string.sync_previous), {
            action("previous", null)
        }, tv, Modifier.testTag("sync:previous"), usable)
        val play = state.phase != "Playing"
        SyncTransport(
            if (play) "play" else "pause",
            stringResource(if (play) R.string.sync_play else R.string.sync_pause),
            { action(if (play) "play" else "pause", null) },
            tv,
            Modifier.testTag(if (play) "sync:play" else "sync:pause").then(if (usable) Modifier.focusRequester(first) else Modifier),
            usable,
            primary = true
        )
        SyncTransport("next", stringResource(R.string.sync_next), { action("next", null) }, tv, Modifier.testTag("sync:next"), usable)
    }
    SyncToggle(
        stringResource(R.string.sync_wait_for_me),
        stringResource(R.string.sync_wait_explanation),
        !state.ignoreWait,
        { action("ignore-wait", (!state.ignoreWait).toString()) },
        tv,
        Modifier.testTag("sync:ignore-wait"),
        usable
    )
    SyncHeading(stringResource(R.string.sync_repeat), tv)
    val repeats = listOf(
        "RepeatNone" to R.string.sync_repeat_off,
        "RepeatOne" to R.string.sync_repeat_one,
        "RepeatAll" to R.string.sync_repeat_all
    )
    if (tv) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeats.forEach { (value, label) ->
                androidx.tv.material3.FilterChip(
                    selected = state.repeat == value,
                    onClick = { action("repeat", value) },
                    enabled = usable,
                    modifier = Modifier.testTag("sync:repeat:$value"),
                    leadingIcon = if (state.repeat ==
                        value
                    ) {
                        ({ androidx.tv.material3.Icon(touchActionIcon("check"), null, Modifier.size(18.dp)) })
                    } else {
                        null
                    }
                ) {
                    androidx.tv.material3.Text(stringResource(label))
                }
            }
        }
    } else {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            repeats.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = state.repeat == value,
                    onClick = { action("repeat", value) },
                    enabled = usable,
                    shape = SegmentedButtonDefaults.itemShape(index, repeats.size),
                    modifier = Modifier.testTag("sync:repeat:$value")
                ) {
                    Text(stringResource(label))
                }
            }
        }
    }
    SyncToggle(
        stringResource(R.string.sync_shuffle),
        null,
        state.shuffle == "Shuffle",
        { action("shuffle", if (state.shuffle == "Shuffle") "Sorted" else "Shuffle") },
        tv,
        Modifier.testTag("sync:shuffle"),
        usable
    )
    SyncHeading(stringResource(R.string.sync_queue), tv)
    if (state.queue.isEmpty()) SyncText(stringResource(R.string.sync_queue_empty), tv)
    state.queue.forEach { entry ->
        val title = entry.title ?: stringResource(if (entry.titleUnavailable) R.string.sync_title_unavailable else R.string.sync_loading)
        val current = if (entry.current) stringResource(R.string.sync_now_playing) else null
        val remove = stringResource(R.string.sync_remove_title, title)
        if (tv) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TvSettingsRow(
                    title,
                    current,
                    Modifier.weight(1f).testTag("sync:entry:${entry.id}"),
                    enabled = usable,
                    selected = entry.current,
                    onClick = { action("select-entry", entry.id) },
                    leading = if (entry.current) ({ androidx.tv.material3.Icon(syncIcon("play"), null, Modifier.size(20.dp)) }) else null
                )
                SyncTransport("remove", remove, {
                    action("remove-entry", entry.id)
                }, true, Modifier.testTag("sync:remove:${entry.id}"), usable)
            }
        } else {
            ListItem(
                headlineContent = { Text(title) },
                supportingContent = current?.let { { Text(it) } },
                leadingContent = if (entry.current) ({ Icon(syncIcon("play"), null) }) else null,
                trailingContent = {
                    IconButton(onClick = {
                        action("remove-entry", entry.id)
                    }, enabled = usable, modifier = Modifier.testTag("sync:remove:${entry.id}")) { Icon(syncIcon("remove"), remove) }
                },
                modifier = Modifier.fillMaxWidth().testTag("sync:entry:${entry.id}").clickable(enabled = usable) {
                    action("select-entry", entry.id)
                }
            )
        }
    }
    SyncHeading(stringResource(R.string.sync_people), tv)
    val people = syncPeople(state.participants)
    people.names.forEachIndexed { index, person ->
        if (tv) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("sync:participant:$index")) {
                SyncText(person, true)
                SyncText(stringResource(R.string.sync_readiness_unknown), true, small = true)
            }
        } else {
            ListItem(headlineContent = {
                Text(person)
            }, supportingContent = {
                Text(stringResource(R.string.sync_readiness_unknown))
            }, modifier = Modifier.testTag("sync:participant:$index"))
        }
    }
    val remaining = people.remaining
    if (remaining > 0) SyncText(pluralStringResource(R.plurals.sync_more_people, remaining, syncNumber(remaining)), tv)
    SyncHeading(stringResource(R.string.sync_this_device), tv)
    SyncText(
        stringResource(
            when (state.localPlayback) {
                SyncLocalPlayback.UNAVAILABLE -> R.string.sync_local_unavailable
                SyncLocalPlayback.PREPARING -> R.string.sync_local_preparing
                SyncLocalPlayback.BUFFERING -> R.string.sync_local_buffering
                SyncLocalPlayback.READY -> R.string.sync_local_ready
            }
        ),
        tv,
        Modifier.testTag("sync:local")
    )
    SyncText(stringResource(R.string.sync_personal), tv, small = true)
}

@Composable
private fun SyncToggle(
    title: String,
    supporting: String?,
    checked: Boolean,
    onToggle: () -> Unit,
    tv: Boolean,
    modifier: Modifier,
    enabled: Boolean
) {
    if (tv) {
        TvSettingsToggleRow(title, checked, modifier, supporting, enabled) { onToggle() }
    } else {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = supporting?.let { { Text(it) } },
            trailingContent = { Switch(checked, onCheckedChange = { onToggle() }, enabled = enabled) },
            modifier = modifier
        )
    }
}

@Composable
private fun SyncTransport(
    kind: String,
    label: String,
    onClick: () -> Unit,
    tv: Boolean,
    modifier: Modifier,
    enabled: Boolean,
    primary: Boolean = false
) {
    if (tv) {
        androidx.tv.material3.IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(if (primary) 56.dp else 48.dp)) {
            androidx.tv.material3.Icon(syncIcon(kind), label, Modifier.size(24.dp))
        }
    } else {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(if (primary) 56.dp else 48.dp)) {
            Icon(syncIcon(kind), label, Modifier.size(24.dp))
        }
    }
}

@Composable
private fun SyncSecondaryAction(label: String, onClick: () -> Unit, tv: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    if (tv) {
        TvSettingsActionRow(label, modifier, enabled = enabled, onClick = onClick)
    } else {
        TextButton(onClick = onClick, modifier = modifier, enabled = enabled) { Text(label) }
    }
}

@Composable
private fun SyncHeading(text: String, tv: Boolean, modifier: Modifier = Modifier) {
    if (tv) {
        TvSettingsSectionHeader(text, modifier)
    } else {
        Text(text, modifier.padding(top = 12.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun SyncText(text: String, tv: Boolean, modifier: Modifier = Modifier, small: Boolean = false) {
    if (tv) {
        androidx.tv.material3.Text(
            text,
            modifier.padding(horizontal = 16.dp),
            style = if (small) TvMaterialTheme.typography.bodyMedium else TvMaterialTheme.typography.bodyLarge,
            color = if (small) TvMaterialTheme.colorScheme.onSurfaceVariant else TvMaterialTheme.colorScheme.onSurface
        )
    } else {
        Text(text, modifier, style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun syncPhaseText(state: SyncPlayPresentation): String = stringResource(
    when (syncPhase(state.phase, state.waitReason)) {
        SyncPhase.JOINING -> R.string.sync_joining
        SyncPhase.WAITING_BUFFER -> R.string.sync_waiting_buffer
        SyncPhase.WAITING_SEEK -> R.string.sync_waiting_seek
        SyncPhase.WAITING -> R.string.sync_waiting
        SyncPhase.PLAYING -> R.string.sync_group_playing
        SyncPhase.PAUSED -> R.string.sync_group_paused
        SyncPhase.RECONNECTING -> R.string.sync_reconnecting
        SyncPhase.LEAVING -> R.string.sync_leaving
        SyncPhase.ERROR -> R.string.sync_playback_error
        SyncPhase.EMPTY -> R.string.sync_nothing_playing
    }
)

@Composable
internal fun syncQualityText(quality: SyncQuality): String = stringResource(
    when (quality) {
        SyncQuality.UNKNOWN -> R.string.sync_quality_unknown
        SyncQuality.IN_SYNC -> R.string.sync_quality_in_sync
        SyncQuality.CATCHING_UP -> R.string.sync_quality_catching_up
        SyncQuality.OUT_OF_SYNC -> R.string.sync_quality_out
    }
)

private fun syncNumber(number: Int): String = NumberFormat.getIntegerInstance().format(number)

@Composable
private fun SyncNoticeBanner(state: SyncPlayPresentation, action: (String, String?) -> Unit, tv: Boolean) {
    val notice = state.notice ?: when {
        state.phase == "Reconnecting" -> SyncNotice(
            SyncNoticeKind.REJOIN,
            SyncNoticeTone.ERROR,
            if (state.groupId == null) SyncNoticeAction.LEAVE else SyncNoticeAction.REJOIN
        )
        state.phase == "Error" -> SyncNotice(SyncNoticeKind.PLAYBACK_FAILED, SyncNoticeTone.ERROR, SyncNoticeAction.LEAVE)
        state.quality == SyncQuality.OUT_OF_SYNC -> SyncNotice(SyncNoticeKind.REJOIN, SyncNoticeTone.WARNING, SyncNoticeAction.REJOIN)
        else -> return
    }
    val message = stringResource(
        when (notice.kind) {
            SyncNoticeKind.ACCESS_FAILED -> R.string.sync_access_failed
            SyncNoticeKind.GROUPS_FAILED -> R.string.sync_groups_failed
            SyncNoticeKind.REJOIN -> R.string.sync_lost
            SyncNoticeKind.PREPARING -> R.string.sync_local_preparing
            SyncNoticeKind.LIBRARY_DENIED -> R.string.sync_library_denied
            SyncNoticeKind.CONNECTION -> R.string.sync_connection
            SyncNoticeKind.SOURCE_UNAVAILABLE -> R.string.sync_source_unavailable
            SyncNoticeKind.CHECK_ACCESS -> R.string.sync_check_access
            SyncNoticeKind.ACCESS_DENIED -> R.string.sync_access_denied
            SyncNoticeKind.REQUEST_FAILED -> R.string.sync_request_failed
            SyncNoticeKind.SNAPSHOT -> R.string.sync_snapshot
            SyncNoticeKind.LEFT -> R.string.sync_left
            SyncNoticeKind.LEAVE_UNCONFIRMED -> R.string.sync_leave_unconfirmed
            SyncNoticeKind.SPEED -> R.string.sync_speed
            SyncNoticeKind.CLOCK -> R.string.sync_lost
            SyncNoticeKind.QUEUE_PENDING -> R.string.sync_queue_pending
            SyncNoticeKind.GROUP_UNAVAILABLE -> R.string.sync_group_unavailable
            SyncNoticeKind.PLAYBACK_FAILED -> R.string.sync_playback_error
        }
    )
    val isError = notice.tone == SyncNoticeTone.ERROR
    val background = if (tv) {
        if (isError) TvMaterialTheme.colorScheme.errorContainer else TvMaterialTheme.colorScheme.surfaceVariant
    } else if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val foreground = if (tv) {
        if (isError) TvMaterialTheme.colorScheme.onErrorContainer else TvMaterialTheme.colorScheme.onSurface
    } else if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Column(
        Modifier.fillMaxWidth().background(background).padding(12.dp).testTag("sync:status").semantics {
            liveRegion =
                LiveRegionMode.Polite
        },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (notice.tone != SyncNoticeTone.INFO) Icon(syncIcon("warning"), null, tint = foreground, modifier = Modifier.size(24.dp))
            if (tv) androidx.tv.material3.Text(message, color = foreground) else Text(message, color = foreground)
        }
        notice.action?.let { recovery ->
            val label = stringResource(
                when (recovery) {
                    SyncNoticeAction.REJOIN -> R.string.sync_rejoin
                    SyncNoticeAction.LEAVE -> R.string.sync_leave
                    SyncNoticeAction.REFRESH -> R.string.sync_refresh
                }
            )
            SyncSecondaryAction(label, {
                when (recovery) {
                    SyncNoticeAction.REJOIN -> if (state.groupId != null) action("join", state.groupId) else action("leave", null)
                    SyncNoticeAction.LEAVE -> action("leave", null)
                    SyncNoticeAction.REFRESH -> action("refresh", null)
                }
            }, tv, Modifier.testTag("sync:recovery"))
        }
    }
}

/** Kept outside the ordinary control overlay, so group status remains visible when controls hide. */
@Composable
internal fun SyncPlayerChip(sync: SyncPlayHost?, modifier: Modifier = Modifier, tv: Boolean = false) {
    if (sync == null) return
    val state by sync.state.collectAsState()
    if (state.groupId == null && state.phase != "Reconnecting") return
    SyncPlayerStatusChip(state, sync::show, modifier, tv)
}

/** Status and context have their own rows; separators never become orphaned lines. */
@Composable
internal fun SyncPlayerStatusChip(
    state: SyncPlayPresentation,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    tv: Boolean = false
) {
    val count = pluralStringResource(R.plurals.sync_people_watching, state.participants.size, syncNumber(state.participants.size))
    val name = state.groupName ?: stringResource(R.string.sync_your_group)
    val phase = syncPhaseText(state)
    val quality = syncQualityText(state.quality)
    // Nonbreaking glue keeps a separator with its words if a large-font status needs two lines.
    val status = if (state.phase in setOf("Playing", "Paused")) "$phase\u00a0·\u00a0$quality" else phase
    val content: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            if (tv) androidx.tv.material3.Icon(syncIcon("group"), null, Modifier.size(20.dp).testTag("sync:chip-icon"))
            else Icon(syncIcon("group"), null, Modifier.size(20.dp).testTag("sync:chip-icon"))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (tv) {
                    androidx.tv.material3.Text(
                        status, style = TvMaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("sync:chip-status")
                    )
                } else {
                    Text(status, style = MaterialTheme.typography.labelLarge, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("sync:chip-status"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (tv) {
                        androidx.tv.material3.Text(name, Modifier.weight(1f).testTag("sync:chip-name"),
                            style = TvMaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        androidx.tv.material3.Text(count, Modifier.testTag("sync:chip-count"), style = TvMaterialTheme.typography.labelSmall)
                    } else {
                        Text(name, Modifier.weight(1f).testTag("sync:chip-name"), style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(count, Modifier.testTag("sync:chip-count"), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    if (tv) {
        androidx.tv.material3.Button(
            onClick = onOpen,
            modifier = modifier.testTag("sync:player-chip").semantics { liveRegion = LiveRegionMode.Polite }
        ) { content() }
    } else {
        Surface(tonalElevation = 6.dp, shape = MaterialTheme.shapes.medium, modifier = modifier.testTag("sync:player-chip")) {
            TextButton(onClick = onOpen, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { content() }
        }
    }
}
