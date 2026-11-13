/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.LineBreak
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
import app.pictureshow.core.sync.syncTransportAvailable
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
    val compactHeight = !tv && isShortTouchWindow()
    val compactIme = compactHeight && WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var name by rememberSaveable(state.server, state.viewer) { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val createFocus = remember { FocusRequester() }
    val waitFocus = remember { FocusRequester() }
    val queueFocus = remember(state.server, state.viewer, state.groupId) { mutableMapOf<String, FocusRequester>() }
    val joined = state.groupId != null
    val uncertain = !joined && state.phase == "Reconnecting"
    val canCreate = !joined && !uncertain && state.accessAllowed && state.policy == "CreateAndJoinGroups"
    val usable = joined && !state.busy && state.phase !in setOf("Joining", "Reconnecting", "Leaving", "Error")
    val transport = syncTransportAvailable(usable, state.queue.size)
    val notice = effectiveSyncNotice(state)
    val recovery = notice?.action?.takeUnless { it == SyncNoticeAction.LEAVE }
    // Async chooser data does not invalidate an attached Refresh/name focus target.
    // Membership, usable transport and newly offered recovery do change the useful primary.
    val focusKey = listOf(state.groupId, uncertain, usable, transport, notice?.action)
    var removedEntry by remember(state.server, state.viewer, state.groupId) { mutableStateOf<Pair<String, Int>?>(null) }
    val action: (String, String?) -> Unit = { op, value ->
        if (op == "remove-entry" && value != null) removedEntry = value to state.queue.indexOfFirst { it.id == value }
        onAction(op, value)
    }
    LaunchedEffect(state.queue.map { it.id }) {
        val removed = removedEntry
        if (tv && removed != null && state.queue.none { it.id == removed.first }) {
            withFrameNanos { }
            val neighbour = state.queue.getOrNull(removed.second) ?: state.queue.lastOrNull()
            if (neighbour !=
                null
            ) {
                queueFocus[neighbour.id]?.requestFocus()
            } else if (usable) {
                waitFocus.requestFocus()
            } else {
                first.requestFocus()
            }
            removedEntry = null
        }
        queueFocus.keys.retainAll(state.queue.map { it.id }.toSet())
    }
    val create: @Composable (Modifier) -> Unit = { modifier ->
        if (tv) {
            androidx.tv.material3.Button(
                onClick = { action("create", name.trim()) },
                enabled = !state.busy && name.isNotBlank(),
                modifier = modifier.focusRequester(createFocus).testTag("sync:create"),
                scale = androidx.tv.material3.ButtonDefaults.scale(focusedScale = 1f),
                colors = androidx.tv.material3.ButtonDefaults.colors(
                    containerColor = TvMaterialTheme.colorScheme.primary,
                    contentColor = TvMaterialTheme.colorScheme.onPrimary,
                    focusedContainerColor = TvMaterialTheme.colorScheme.primary,
                    focusedContentColor = TvMaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = TvMaterialTheme.colorScheme.onSurface.copy(alpha = .12f),
                    disabledContentColor = TvMaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
                )
            ) { androidx.tv.material3.Text(stringResource(R.string.sync_create_join)) }
        } else {
            Button(onClick = {
                action("create", name.trim())
            }, enabled = !state.busy && name.isNotBlank(), modifier = modifier.testTag("sync:create")) {
                Text(stringResource(R.string.sync_create_join), maxLines = 2)
            }
        }
    }
    val leave: @Composable (Modifier) -> Unit = { base ->
        val modifier = base.testTag("sync:leave").then(
            if ((!usable || notice?.action == SyncNoticeAction.LEAVE) &&
                recovery == null
            ) {
                Modifier.focusRequester(first)
            } else {
                Modifier
            }
        )
        if (tv) {
            androidx.tv.material3.OutlinedButton(
                onClick = { action("leave", null) },
                modifier = modifier,
                enabled = state.phase != "Leaving",
                scale = androidx.tv.material3.ButtonDefaults.scale(focusedScale = 1f),
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
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(stringResource(R.string.sync_leave))
            }
        }
    }
    val footer: @Composable () -> Unit = {
        if (joined || uncertain) {
            if (compactHeight) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.sync_leave_explanation), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    leave(Modifier)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (tv) {
                        SyncText(stringResource(R.string.sync_leave_explanation), true, small = true)
                    } else {
                        Text(stringResource(R.string.sync_leave_explanation), style = MaterialTheme.typography.bodySmall)
                    }
                    leave(if (tv) Modifier.fillMaxWidth() else Modifier.align(Alignment.Start))
                }
            }
        } else if (canCreate) {
            create(Modifier.fillMaxWidth())
        }
    }
    val body: @Composable ColumnScope.() -> Unit = {
        if (!compactHeight) {
            fixtureLabel?.let {
                SyncText(stringResource(R.string.sync_fixture, it), tv, Modifier.testTag("sync:fixture"), small = true)
            }
        }
        if (!compactIme) {
            SyncText(
                stringResource(
                    R.string.sync_scope,
                    state.server.ifBlank { stringResource(R.string.sync_this_server) },
                    state.viewer.ifBlank { stringResource(R.string.sync_signed_in) }
                ),
                tv,
                small = true
            )
            notice?.let { SyncNoticeBanner(state, it, action, tv, if (recovery != null) first else null) }
        }
        if (joined) {
            SyncJoined(state, action, tv, first, waitFocus, queueFocus, usable, transport, notice?.action != null, notice, compactHeight)
        } else if (uncertain) {
            if (notice?.kind != SyncNoticeKind.LEAVE_UNCONFIRMED) SyncText(stringResource(R.string.sync_leave_unconfirmed), tv)
        } else {
            SyncGroups(state, action, tv, first, createFocus, name, { name = it.take(200) }, compactIme, create)
        }
    }
    if (tv) {
        app.pictureshow.ui.PictureshowTheme {
            TvSettingsPanel(
                stringResource(R.string.sync_title), onDismiss, first, modifier = Modifier.testTag("sync:panel"),
                footer = if (joined || uncertain || canCreate) footer else null, contentFocusKey = focusKey, panelWidth = 560.dp,
                footerHorizontalPadding = 12.dp, minimalFocusScroll = true, repairLostContentFocus = true, content = body
            )
        }
    } else {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            app.pictureshow.ui.PictureshowTheme {
                val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
                // Compact phones stay fullscreen. Wide windows bound the complete body and footer to600dp.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Scaffold(
                        modifier = Modifier.widthIn(max = 600.dp).fillMaxSize().imePadding()
                            .nestedScroll(scrollBehavior.nestedScrollConnection).testTag("sync:panel").semantics { paneTitle = pane },
                        topBar = {
                            TopAppBar(title = {
                                Column {
                                    Text(pane, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (compactHeight) {
                                        fixtureLabel?.let {
                                            Text(
                                                stringResource(R.string.sync_fixture, it),
                                                Modifier.testTag("sync:fixture"),
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }, navigationIcon = {
                                IconButton(onClick = onDismiss, modifier = Modifier.testTag("sync:close")) {
                                    Icon(syncIcon("close"), stringResource(R.string.sync_close))
                                }
                            }, expandedHeight = if (compactHeight) 48.dp else 64.dp, scrollBehavior = scrollBehavior)
                        },
                        bottomBar = {
                            if (!compactIme && (joined || uncertain || canCreate)) {
                                Surface(
                                    tonalElevation = 3.dp,
                                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                                ) {
                                    Box(
                                        Modifier.fillMaxWidth().windowInsetsPadding(
                                            WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                                        )
                                            .padding(horizontal = 16.dp, vertical = if (compactHeight) 4.dp else 12.dp)
                                    ) { footer() }
                                }
                            }
                        }
                    ) { insets -> SyncTouchBody(Modifier.padding(insets), compactHeight, body) }
                }
            }
        }
    }
}

/** A fading edge makes the body’s scroll range visible, including at large fonts and above the IME. */
@Composable
private fun SyncTouchBody(modifier: Modifier, compactHeight: Boolean, body: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val surface = MaterialTheme.colorScheme.surface
    val paddingPx = with(LocalDensity.current) { 20.dp.toPx() }
    val showBottomFade by remember(scroll, paddingPx) {
        derivedStateOf { scroll.maxValue - scroll.value > paddingPx }
    }
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(vertical = if (compactHeight) 4.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactHeight) 4.dp else 12.dp),
            content = body
        )
        if (scroll.canScrollBackward) {
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(16.dp)
                    .background(Brush.verticalGradient(listOf(surface, Color.Transparent))).testTag("sync:fade-top")
            )
        }
        if (showBottomFade) {
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
    createFocus: FocusRequester,
    name: String,
    onName: (String) -> Unit,
    compactEditing: Boolean,
    create: @Composable (Modifier) -> Unit
) {
    val primaryGroup = state.groups.firstOrNull()?.id.takeIf { state.accessAllowed && !state.busy }
    val namePrimary = primaryGroup == null && state.accessAllowed && !state.busy && state.policy == "CreateAndJoinGroups"
    if (!compactEditing) {
        if (state.accessAllowed) SyncText(stringResource(R.string.sync_privacy), tv, small = true)
        val accessNotice = when (state.access.notice) {
            SyncAccessNotice.CHECKING -> R.string.sync_checking
            SyncAccessNotice.UNKNOWN -> R.string.sync_check_access
            SyncAccessNotice.UNAVAILABLE -> R.string.sync_access_failed
            SyncAccessNotice.DENIED -> R.string.sync_access_denied
            SyncAccessNotice.ALLOWED -> null
        }
        accessNotice?.let {
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.busy) CircularProgressIndicator(Modifier.size(24.dp))
                if (tv) {
                    androidx.tv.material3.Text(stringResource(it), Modifier.testTag("sync:access"))
                } else {
                    Text(stringResource(it), Modifier.testTag("sync:access"))
                }
            }
            if (tv) Spacer(Modifier.height(8.dp))
        }
        if (state.accessAllowed) SyncHeading(stringResource(R.string.sync_groups), tv)
        if (tv || !state.busy) {
            SyncSecondaryAction(
                stringResource(R.string.sync_refresh),
                { action("refresh", null) },
                tv,
                Modifier.testTag("sync:refresh").then(
                    if (primaryGroup == null &&
                        !namePrimary
                    ) {
                        Modifier.focusRequester(first)
                    } else {
                        Modifier
                    }
                ),
                enabled = state.canRefresh,
                refreshIcon = true
            )
        }
        if (!state.busy && state.accessAllowed && state.groups.isEmpty()) {
            SyncText(stringResource(R.string.sync_empty), tv, Modifier.testTag("sync:empty"))
        }
        if (state.accessAllowed) {
            Column {
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
                            onClick = {
                                action("join", group.id)
                            },
                            trailing = { androidx.tv.material3.Text(stringResource(R.string.sync_join)) }
                        )
                    } else {
                        ListItem(
                            headlineContent = { Text(group.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
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
        }
    }
    if (state.accessAllowed && state.policy == "CreateAndJoinGroups") {
        if (!compactEditing) SyncHeading(stringResource(R.string.sync_create), tv)
        // Keep the field in the same composition when the IME appears; moving it to
        // another branch would discard focus and close the keyboard mid-edit.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (tv) 0.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                name,
                onName,
                label = {
                    Text(stringResource(R.string.sync_group_name))
                },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (!state.busy && name.isNotBlank()) action("create", name.trim()) }),
                modifier = Modifier.weight(1f).testTag("sync:name")
                    .then(if (namePrimary) Modifier.focusRequester(first) else Modifier)
                    .onPreviewKeyEvent { event ->
                        // Give remote users a direct path from single-line editing to the enabled pinned action.
                        if (tv &&
                            !state.busy &&
                            name.isNotBlank() &&
                            event.type == KeyEventType.KeyDown &&
                            event.key == Key.DirectionDown
                        ) {
                            createFocus.requestFocus()
                            true
                        } else {
                            false
                        }
                    }
            )
            if (compactEditing) create(Modifier)
        }
    } else if (!compactEditing && state.accessAllowed && state.policy == "JoinGroups") {
        SyncText(stringResource(R.string.sync_join_only), tv, small = true)
    } else if (tv && state.busy) {
        // Neutral placeholders retain a useful layout without implying account permission.
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(2) {
                Spacer(
                    Modifier.fillMaxWidth().height(
                        48.dp
                    ).background(TvMaterialTheme.colorScheme.surfaceVariant, TvMaterialTheme.shapes.medium)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun SyncJoined(
    state: SyncPlayPresentation,
    action: (String, String?) -> Unit,
    tv: Boolean,
    first: FocusRequester,
    waitFocus: FocusRequester,
    queueFocus: MutableMap<String, FocusRequester>,
    usable: Boolean,
    transport: Boolean,
    hasRecovery: Boolean,
    notice: SyncNotice?,
    compactHeight: Boolean
) {
    val groupName = state.groupName ?: stringResource(R.string.sync_your_group)
    if (tv) {
        androidx.tv.material3.Text(
            groupName,
            Modifier.padding(horizontal = 16.dp).testTag("sync:group").semantics { heading() },
            style = TvMaterialTheme.typography.headlineSmall.copy(lineBreak = LineBreak.Heading),
            maxLines = if (groupName.any {
                    it.isWhitespace()
                }
            ) {
                2
            } else {
                1
            },
            overflow = TextOverflow.Ellipsis
        )
    } else {
        Text(
            groupName,
            Modifier.padding(horizontal = 16.dp).testTag("sync:group").semantics { heading() },
            style = (if (compactHeight) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall).copy(
                lineBreak = LineBreak.Heading
            ),
            maxLines = if (!compactHeight && groupName.any { it.isWhitespace() }) 2 else 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (notice == null || state.phase !in setOf("Error", "Reconnecting")) {
        val phase = syncPhaseText(state)
        val status = if (state.quality == SyncQuality.UNKNOWN) phase else "$phase\u00a0·\u00a0${syncQualityText(state.quality)}"
        SyncText(status, tv, Modifier.testTag("sync:phase").semantics { liveRegion = LiveRegionMode.Polite }, small = compactHeight)
    }
    val local = stringResource(
        when (state.localPlayback) {
            SyncLocalPlayback.UNAVAILABLE -> R.string.sync_local_unavailable
            SyncLocalPlayback.PREPARING -> R.string.sync_local_preparing
            SyncLocalPlayback.BUFFERING -> R.string.sync_local_buffering
            SyncLocalPlayback.READY -> R.string.sync_local_ready
        }
    )
    SyncText(
        stringResource(R.string.sync_local_fact, stringResource(R.string.sync_this_device), local),
        tv,
        Modifier.testTag("sync:local"),
        small = compactHeight
    )
    if (!compactHeight &&
        state.quality != SyncQuality.UNKNOWN
    ) {
        SyncText(stringResource(R.string.sync_estimate_explanation), tv, small = true)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        SyncTransport("previous", stringResource(R.string.sync_previous), {
            action("previous", null)
        }, tv, Modifier.testTag("sync:previous"), transport)
        val play = state.phase != "Playing"
        SyncTransport(
            if (play) "play" else "pause",
            stringResource(if (play) R.string.sync_play else R.string.sync_pause),
            { action(if (play) "play" else "pause", null) },
            tv,
            Modifier.testTag(if (play) "sync:play" else "sync:pause").then(
                if (transport &&
                    !hasRecovery
                ) {
                    Modifier.focusRequester(first)
                } else {
                    Modifier
                }
            ),
            transport,
            primary = true
        )
        SyncTransport("next", stringResource(R.string.sync_next), { action("next", null) }, tv, Modifier.testTag("sync:next"), transport)
    }
    if (compactHeight &&
        state.quality != SyncQuality.UNKNOWN
    ) {
        SyncText(stringResource(R.string.sync_estimate_explanation), tv, small = true)
    }
    SyncToggle(
        stringResource(R.string.sync_wait_for_me),
        stringResource(R.string.sync_wait_explanation),
        !state.ignoreWait,
        { action("ignore-wait", (!state.ignoreWait).toString()) },
        tv,
        Modifier.testTag("sync:ignore-wait").focusRequester(waitFocus).then(
            if (usable &&
                !transport &&
                !hasRecovery
            ) {
                Modifier.focusRequester(first)
            } else {
                Modifier
            }
        ),
        usable
    )
    SyncText(stringResource(R.string.sync_personal), tv, small = true)
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
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
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
    Column {
        state.queue.forEach { entry ->
            key(entry.id) {
                val title =
                    entry.title ?: stringResource(if (entry.titleUnavailable) R.string.sync_title_unavailable else R.string.sync_loading)
                val current = if (entry.current) stringResource(R.string.sync_now_playing) else null
                val remove = stringResource(R.string.sync_remove_title, title)
                if (tv) {
                    val entryFocus = remember { FocusRequester() }
                    SideEffect { queueFocus[entry.id] = entryFocus }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TvSettingsRow(
                            title,
                            current,
                            Modifier.weight(1f).testTag("sync:entry:${entry.id}").focusRequester(entryFocus),
                            enabled = usable,
                            selected = entry.current,
                            titleMaxLines = 2,
                            onClick = { action("select-entry", entry.id) }
                        )
                        SyncTransport("remove", remove, {
                            action("remove-entry", entry.id)
                        }, true, Modifier.testTag("sync:remove:${entry.id}"), usable)
                    }
                } else {
                    val rowColor = if (entry.current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
                    ListItem(
                        headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = current?.let { { Text(it) } },
                        colors = ListItemDefaults.colors(containerColor = rowColor),
                        trailingContent = {
                            IconButton(onClick = {
                                action("remove-entry", entry.id)
                            }, enabled = usable, modifier = Modifier.testTag("sync:remove:${entry.id}")) {
                                Icon(syncIcon("remove"), remove)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).testTag("sync:entry:${entry.id}")
                            .clickable(enabled = usable) { action("select-entry", entry.id) }
                    )
                }
            }
        }
    }
    SyncHeading(stringResource(R.string.sync_people), tv)
    SyncText(stringResource(R.string.sync_readiness_unknown), tv, small = true)
    val people = syncPeople(state.participants)
    Column {
        people.names.forEachIndexed { index, person ->
            if (tv) {
                TvSettingsRow(person, null, Modifier.testTag("sync:participant:$index"), readOnly = true, titleMaxLines = 1, onClick = {})
            } else {
                ListItem(headlineContent = {
                    Text(person, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }, modifier = Modifier.testTag("sync:participant:$index"))
            }
        }
        val remaining = people.remaining
        if (remaining > 0) {
            val more = pluralStringResource(R.plurals.sync_more_people, remaining, syncNumber(remaining))
            if (tv) TvSettingsRow(more, null, readOnly = true, onClick = {}) else SyncText(more, false)
        }
    }
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
        androidx.tv.material3.IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.size(if (primary) 56.dp else 48.dp),
            colors = if (kind == "remove") {
                androidx.tv.material3.IconButtonDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = TvMaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                androidx.tv.material3.IconButtonDefaults.colors()
            }
        ) {
            androidx.tv.material3.Icon(syncIcon(kind), label, Modifier.size(24.dp))
        }
    } else {
        if (primary) {
            FilledIconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(56.dp)) {
                Icon(syncIcon(kind), label, Modifier.size(24.dp))
            }
        } else {
            IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
                Icon(syncIcon(kind), label, Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun SyncSecondaryAction(
    label: String,
    onClick: () -> Unit,
    tv: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    refreshIcon: Boolean = false
) {
    if (tv) {
        TvSettingsRow(
            label,
            null,
            modifier,
            enabled = enabled,
            onClick = onClick,
            leading = if (refreshIcon) ({ androidx.tv.material3.Icon(syncIcon("refresh"), null, Modifier.size(20.dp)) }) else null
        )
    } else {
        TextButton(onClick = onClick, modifier = modifier.padding(start = 4.dp), enabled = enabled) {
            Text(label)
            if (refreshIcon) {
                Spacer(Modifier.size(8.dp))
                Icon(syncIcon("refresh"), null, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SyncHeading(text: String, tv: Boolean, modifier: Modifier = Modifier) {
    if (tv) {
        TvSettingsSectionHeader(text, modifier)
    } else {
        Text(
            text,
            modifier.padding(horizontal = 16.dp).padding(top = 12.dp).semantics {
                heading()
            },
            style = MaterialTheme.typography.titleLarge
        )
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
        Text(
            text,
            modifier.padding(horizontal = 16.dp),
            style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge
        )
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

private fun effectiveSyncNotice(state: SyncPlayPresentation): SyncNotice? = state.notice ?: when {
    state.phase == "Reconnecting" -> SyncNotice(
        SyncNoticeKind.REJOIN,
        SyncNoticeTone.ERROR,
        if (state.groupId == null) SyncNoticeAction.LEAVE else SyncNoticeAction.REJOIN
    )
    state.phase == "Error" -> SyncNotice(SyncNoticeKind.PLAYBACK_FAILED, SyncNoticeTone.ERROR, SyncNoticeAction.LEAVE)
    state.quality == SyncQuality.OUT_OF_SYNC -> SyncNotice(SyncNoticeKind.REJOIN, SyncNoticeTone.WARNING, SyncNoticeAction.REJOIN)
    else -> null
}

@Composable
private fun SyncNoticeBanner(
    state: SyncPlayPresentation,
    notice: SyncNotice,
    action: (String, String?) -> Unit,
    tv: Boolean,
    first: FocusRequester?
) {
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
    Row(
        Modifier.padding(horizontal = if (tv) 0.dp else 16.dp).fillMaxWidth()
            .clip(if (tv) TvMaterialTheme.shapes.medium else MaterialTheme.shapes.medium).background(background).padding(12.dp)
            .testTag("sync:status").semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (notice.tone != SyncNoticeTone.INFO) Icon(syncIcon("warning"), null, tint = foreground, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tv) androidx.tv.material3.Text(message, color = foreground) else Text(message, color = foreground)
            notice.action?.takeUnless { it == SyncNoticeAction.LEAVE }?.let { recovery ->
                val label = stringResource(if (recovery == SyncNoticeAction.REJOIN) R.string.sync_rejoin else R.string.sync_refresh)
                val invoke = {
                    when (recovery) {
                        SyncNoticeAction.REJOIN -> if (state.groupId != null) action("join", state.groupId) else action("leave", null)
                        SyncNoticeAction.REFRESH -> action("refresh", null)
                        SyncNoticeAction.LEAVE -> Unit // The only Leave action is the pinned footer.
                    }
                }
                val target = Modifier.testTag("sync:recovery").then(if (first != null) Modifier.focusRequester(first) else Modifier)
                if (tv) {
                    androidx.tv.material3.OutlinedButton(
                        onClick = invoke,
                        modifier = target,
                        scale = androidx.tv.material3.ButtonDefaults.scale(focusedScale = 1f),
                        colors = androidx.tv.material3.ButtonDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = foreground,
                            focusedContainerColor = foreground,
                            focusedContentColor = background
                        )
                    ) { androidx.tv.material3.Text(label) }
                } else {
                    OutlinedButton(
                        onClick = invoke,
                        modifier = target,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground),
                        border = BorderStroke(1.dp, foreground)
                    ) { Text(label) }
                }
            }
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
internal fun SyncPlayerStatusChip(state: SyncPlayPresentation, onOpen: () -> Unit, modifier: Modifier = Modifier, tv: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val contextColor = if (tv) {
        if (focused) TvMaterialTheme.colorScheme.inverseOnSurface else TvMaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val count = pluralStringResource(R.plurals.sync_people_watching, state.participants.size, syncNumber(state.participants.size))
    val name = state.groupName ?: stringResource(R.string.sync_your_group)
    val phase = if (syncPhase(state.phase, state.waitReason) ==
        SyncPhase.EMPTY
    ) {
        stringResource(R.string.sync_group_empty_scoped)
    } else {
        syncPhaseText(state)
    }
    val quality = syncQualityText(state.quality)
    // Nonbreaking glue keeps a separator with its words if a large-font status needs two lines.
    val status = if (state.phase in setOf("Playing", "Paused")) "$phase\u00a0·\u00a0$quality" else phase
    val content: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            if (tv) {
                androidx.tv.material3.Icon(syncIcon("group"), null, Modifier.size(20.dp).testTag("sync:chip-icon"))
            } else {
                Icon(syncIcon("group"), null, Modifier.size(20.dp).testTag("sync:chip-icon"))
            }
            Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (tv) {
                    androidx.tv.material3.Text(
                        status,
                        style = TvMaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("sync:chip-status")
                    )
                } else {
                    Text(
                        status,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("sync:chip-status")
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (tv) {
                        androidx.tv.material3.Text(
                            name,
                            Modifier.weight(1f, fill = false).testTag("sync:chip-name"),
                            style = TvMaterialTheme.typography.bodySmall,
                            color = contextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        androidx.tv.material3.Text("·", Modifier.clearAndSetSemantics {}, color = contextColor)
                        androidx.tv.material3.Text(
                            count,
                            Modifier.testTag("sync:chip-count"),
                            style = TvMaterialTheme.typography.bodySmall,
                            color = contextColor
                        )
                    } else {
                        Text(
                            name,
                            Modifier.weight(1f, fill = false).testTag("sync:chip-name"),
                            style = MaterialTheme.typography.bodySmall,
                            color = contextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text("·", Modifier.clearAndSetSemantics {}, color = contextColor)
                        Text(count, Modifier.testTag("sync:chip-count"), style = MaterialTheme.typography.bodySmall, color = contextColor)
                    }
                }
            }
        }
    }
    if (tv) {
        androidx.tv.material3.Button(
            onClick = onOpen,
            modifier = modifier.onFocusChanged { focused = it.hasFocus }.testTag("sync:player-chip").semantics {
                liveRegion =
                    LiveRegionMode.Polite
            }
        ) { content() }
    } else {
        Surface(tonalElevation = 6.dp, shape = MaterialTheme.shapes.medium, modifier = modifier.testTag("sync:player-chip")) {
            TextButton(onClick = onOpen, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { content() }
        }
    }
}
