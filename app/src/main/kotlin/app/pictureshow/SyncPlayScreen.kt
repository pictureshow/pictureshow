/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import app.pictureshow.core.sync.SyncAccessNotice
import app.pictureshow.core.sync.SyncPhase
import app.pictureshow.core.sync.SyncQuality
import app.pictureshow.core.sync.syncChipName
import app.pictureshow.core.sync.syncChipShowsQuality
import app.pictureshow.core.sync.syncPanelQuality
import app.pictureshow.core.sync.syncPeople
import app.pictureshow.core.sync.syncPhase
import app.pictureshow.core.sync.syncShowsLocalPlayback
import app.pictureshow.core.sync.syncTransportAvailable
import java.text.NumberFormat
import kotlinx.coroutines.flow.first

/** Preserve the production three-argument API, including its trailing dismiss lambda. */
@Composable
internal fun SyncPlayPanel(state: SyncPlayPresentation, onAction: (String, String?) -> Unit, onDismiss: () -> Unit) {
    SyncPlayPanel(state, onAction, onDismiss, fixtureLabel = null)
}

/** Native role components on each surface; every action remains a server request. */
@OptIn(ExperimentalMaterial3Api::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
internal fun SyncPlayPanel(
    state: SyncPlayPresentation,
    onAction: (String, String?) -> Unit,
    onDismiss: () -> Unit,
    fixtureLabel: String?,
    onDeparturePresented: ((Long) -> Unit)? = null
) {
    val tv = usesTvInterface(LocalContext.current)
    val pane = stringResource(R.string.sync_title)
    var touchName by rememberSaveable(state.server, state.viewer) { mutableStateOf("") }
    val tvName = key(state.server, state.viewer) { rememberTextFieldState() }
    val name = if (tv) tvName.text.toString() else touchName
    var tvKeyboardRequested by remember { mutableStateOf(false) }
    var touchWindowSize by remember { mutableStateOf(IntSize.Zero) }
    val first = remember { FocusRequester() }
    val createFocus = remember { FocusRequester() }
    val nameFocus = remember { FocusRequester() }
    val refreshFocus = remember { FocusRequester() }
    val joinFocus = remember { FocusRequester() }
    val waitFocus = remember { FocusRequester() }
    val queueFocus = remember(state.server, state.viewer, state.groupId) { mutableMapOf<String, SyncQueueTarget>() }
    val joined = state.groupId != null
    val uncertain = !joined && state.phase == "Reconnecting"
    val bodyScroll = key(state.server, state.viewer, state.groupId, uncertain) { rememberScrollState() }
    val canCreate = !joined && !uncertain && state.accessAllowed && state.policy == "CreateAndJoinGroups"
    val usable = joined && !state.busy && state.phase !in setOf("Joining", "Reconnecting", "Leaving", "Error")
    val transport = syncTransportAvailable(usable, state.queue.size)
    val notice = effectiveSyncNotice(state)
    val recovery = notice?.action?.takeUnless { it == SyncNoticeAction.LEAVE }
    val departureChooser = !joined && !uncertain && (state.departure != null || notice?.kind == SyncNoticeKind.LEFT)
    var nameContract by remember(state.server, state.viewer, joined, uncertain) { mutableStateOf(false) }
    SideEffect { if (departureChooser) nameContract = true }
    var chooserUserMoved by remember(state.server, state.viewer, joined, uncertain) { mutableStateOf(false) }
    var chooserSettled by remember(state.server, state.viewer, joined, uncertain) { mutableStateOf(state.policyKnown && !state.busy) }
    var chooserFocusGeneration by remember(state.server, state.viewer, joined, uncertain) { mutableStateOf(0) }
    val initialChooserTarget = remember(state.server, state.viewer, joined, uncertain) {
        when {
            departureChooser && canCreate -> "name"
            state.busy || !state.accessAllowed -> "refresh"
            state.groups.isNotEmpty() -> "join"
            canCreate -> "name"
            else -> "refresh"
        }
    }
    LaunchedEffect(tv, state.server, state.viewer, joined, uncertain, state.policyKnown, state.busy, state.groups.isEmpty(), canCreate) {
        if (tv && !joined && !uncertain && !chooserSettled && state.policyKnown && !state.busy) {
            chooserSettled = true
            // A settled populated load keeps Refresh; only the empty chooser's new task earns one promotion.
            if (!chooserUserMoved && state.groups.isEmpty() && canCreate && initialChooserTarget == "refresh") {
                chooserFocusGeneration++
            }
        }
    }
    val chooserInput = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown &&
            event.key in setOf(
                Key.DirectionUp,
                Key.DirectionDown,
                Key.DirectionLeft,
                Key.DirectionRight,
                Key.DirectionCenter,
                Key.Enter,
                Key.NumPadEnter
            )
        ) {
            chooserUserMoved = true
        }
        false
    }.pointerInput(state.server, state.viewer, joined, uncertain) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.pressed && !it.previousPressed }) chooserUserMoved = true
            }
        }
    }
    val chooserFocus = when {
        joined || uncertain || recovery != null -> first
        (departureChooser || nameContract) && canCreate -> nameFocus
        (initialChooserTarget == "name" || chooserFocusGeneration > 0) && canCreate -> nameFocus
        initialChooserTarget == "join" && state.accessAllowed && state.groups.isNotEmpty() -> joinFocus
        else -> refreshFocus
    }
    val departureModifier = if (notice?.kind == SyncNoticeKind.DEPARTURE && onDeparturePresented != null) {
        state.departure?.let { Modifier.syncDeparturePresentation(it.id, onDeparturePresented) } ?: Modifier
    } else {
        Modifier
    }

    // Async populated data preserves focus. An untouched empty chooser gets one settled-data promotion.
    // Membership, usable transport and newly offered recovery do change the useful primary.
    val focusKey = listOf(state.groupId, uncertain, usable, transport, notice?.action, chooserFocusGeneration)
    var removedEntry by remember(state.server, state.viewer, state.groupId) { mutableStateOf<Pair<String, Int>?>(null) }
    val action: (String, String?) -> Unit = { op, value ->
        if (op == "remove-entry" && value != null) removedEntry = value to state.queue.indexOfFirst { it.id == value }
        onAction(op, value)
    }
    val queueRevision = state.queue.map { it.id }
    LaunchedEffect(state.server, state.viewer, state.groupId, queueRevision) {
        val removed = removedEntry
        if (tv && removed != null && state.queue.none { it.id == removed.first }) {
            val neighbour = state.queue.getOrNull(removed.second) ?: state.queue.lastOrNull()
            if (neighbour !=
                null
            ) {
                val target = queueFocus[neighbour.id]
                if (target != null) {
                    snapshotFlow { target.placedQueue == queueRevision }.first { it }
                    target.focus.requestFocus()
                    target.reveal.bringIntoView()
                }
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
                    containerColor = TvMaterialTheme.colorScheme.secondaryContainer,
                    contentColor = TvMaterialTheme.colorScheme.onSecondaryContainer,
                    focusedContainerColor = TvMaterialTheme.colorScheme.onSurface,
                    focusedContentColor = TvMaterialTheme.colorScheme.inverseOnSurface,
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
    val footer: @Composable (Boolean) -> Unit = { compactHeight ->
        if (joined || uncertain) {
            if (compactHeight || !tv) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.sync_leave_explanation), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    leave(Modifier)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(if (tv) 8.dp else 4.dp)) {
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
    val scope = stringResource(
        R.string.sync_scope,
        state.server.ifBlank { stringResource(R.string.sync_this_server) },
        state.viewer.ifBlank { stringResource(R.string.sync_signed_in) }
    )
    val body: @Composable ColumnScope.(Boolean, Boolean) -> Unit = { compactHeight, compactIme ->
        if (!tv && !compactHeight) {
            fixtureLabel?.let {
                SyncText(stringResource(R.string.sync_fixture, it), false, Modifier.testTag("sync:fixture"), small = true)
            }
        }
        if (!tv && !compactIme) SyncText(scope, false, small = true)
        if (!compactIme && !(tv && !joined && !uncertain)) {
            notice?.takeUnless {
                !tv && it.kind == SyncNoticeKind.SPEED && it.tone == SyncNoticeTone.INFO && it.action == null
            }?.let {
                SyncNoticeBanner(state, it, action, tv, if (recovery != null) first else null, modifier = departureModifier)
            }
        }
        if (joined) {
            SyncJoined(
                state,
                action,
                tv,
                first,
                waitFocus,
                queueFocus,
                usable,
                transport,
                recovery != null,
                notice,
                compactHeight,
                bodyScroll,
                touchWindowSize
            )
        } else if (uncertain) {
            if (notice?.kind != SyncNoticeKind.LEAVE_UNCONFIRMED) SyncText(stringResource(R.string.sync_leave_unconfirmed), tv)
        } else {
            SyncGroups(state, action, tv, first, name, {
                touchName = it.take(200)
            }, compactHeight, compactIme, create, refreshFocus, joinFocus)
        }
    }
    if (tv) {
        app.pictureshow.ui.PictureshowTheme {
            TvSettingsPanel(
                stringResource(R.string.sync_title),
                onDismiss,
                chooserFocus, modifier = Modifier.testTag("sync:panel").then(chooserInput),
                contentFocusRequestAllowed = { joined || uncertain || recovery != null || !chooserUserMoved },
                preview = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        fixtureLabel?.let {
                            androidx.tv.material3.Text(
                                stringResource(R.string.sync_fixture, it),
                                Modifier.testTag("sync:fixture"),
                                style = TvMaterialTheme.typography.bodySmall,
                                color = TvMaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        androidx.tv.material3.Text(
                            scope,
                            style = TvMaterialTheme.typography.bodyMedium,
                            color = TvMaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                pinnedNotice = if (!joined && !uncertain && notice != null) {
                    (
                        {
                            SyncNoticeBanner(
                                state,
                                notice,
                                action,
                                true,
                                if (recovery !=
                                    null
                                ) {
                                    first
                                } else {
                                    null
                                },
                                pinned = true,
                                modifier = departureModifier
                            )
                        }
                        )
                } else {
                    null
                },
                pinnedEditor = if (canCreate) {
                    (
                        {
                            Column {
                                TvSettingsSectionHeader(
                                    stringResource(R.string.sync_create),
                                    Modifier.testTag("sync:create-heading"),
                                    supportingText = stringResource(R.string.sync_privacy)
                                )
                                Spacer(Modifier.height(8.dp))
                                SyncTvNameField(
                                    state,
                                    tvName,
                                    nameFocus,
                                    createFocus,
                                    tvKeyboardRequested,
                                    { tvKeyboardRequested = it },
                                    action
                                )
                            }
                        }
                        )
                } else {
                    null
                },
                footer = if (joined || uncertain || canCreate) ({ footer(false) }) else null,
                contentFocusKey = focusKey, panelWidth = 560.dp, footerHorizontalPadding = 12.dp,
                footerBottomPadding = 28.dp, edgeToEdge = true,
                minimalFocusScroll = true, repairLostContentFocus = true, bodyScrollState = bodyScroll, footerDivider = true, content = {
                    body(false, false)
                }
            )
        }
    } else {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            app.pictureshow.ui.PictureshowTheme {
                // Resolve window size and IME inside this Dialog, where its insets are delivered.
                val compactHeight = isShortTouchWindow()
                val compactIme = compactHeight && WindowInsets.ime.getBottom(LocalDensity.current) > 0
                val scrollBehavior = if (compactHeight) {
                    TopAppBarDefaults.enterAlwaysScrollBehavior()
                } else {
                    TopAppBarDefaults.pinnedScrollBehavior()
                }
                // Backings fill the window; only readable body/footer content is bounded to600dp.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize().imePadding().onSizeChanged { touchWindowSize = it }
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
                            if (!compactIme && (joined || uncertain || (canCreate && !compactHeight))) {
                                Surface(
                                    tonalElevation = 3.dp,
                                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                                ) {
                                    Box(Modifier.fillMaxWidth().testTag("sync:touch-footer"), contentAlignment = Alignment.Center) {
                                        Box(
                                            Modifier.widthIn(max = 600.dp).fillMaxWidth().windowInsetsPadding(
                                                WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                                            )
                                                .padding(horizontal = 16.dp, vertical = if (compactHeight) 4.dp else 12.dp)
                                        ) { footer(compactHeight) }
                                        if (bodyScroll.canScrollForward) {
                                            Box(
                                                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(1.dp)
                                                    .background(MaterialTheme.colorScheme.outlineVariant)
                                                    .testTag("sync:touch-footer-divider")
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    ) { insets ->
                        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
                            SyncTouchBody(Modifier.widthIn(max = 600.dp), compactHeight, scrollState = bodyScroll) {
                                body(compactHeight, compactIme)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Drawing and focus reveal share the live viewport's actual fade boundaries. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SyncTouchBody(
    modifier: Modifier,
    compactHeight: Boolean,
    scrollState: ScrollState? = null,
    body: @Composable ColumnScope.() -> Unit
) {
    val rememberedScroll = rememberScrollState()
    val scroll = scrollState ?: rememberedScroll
    val surface = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current
    val bottomFade = 20.dp
    val topFade = 16.dp
    val endPadding = bottomFade + 8.dp
    val paddingPx = with(density) { endPadding.toPx() }
    val topClearance = with(density) { (topFade + 8.dp).toPx() }
    val bottomClearance = with(density) { endPadding.toPx() }
    val showBottomFade by remember(scroll, paddingPx) { derivedStateOf { scroll.maxValue - scroll.value > paddingPx } }
    val reveal = remember(scroll, topClearance, bottomClearance, paddingPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val top = if (scroll.canScrollBackward) topClearance else 0f
                val bottom = containerSize - if (scroll.maxValue - scroll.value > paddingPx) bottomClearance else 0f
                return when {
                    size > bottom - top -> 0f
                    offset < top -> offset - top
                    offset + size > bottom -> offset + size - bottom
                    else -> 0f
                }
            }
        }
    }
    Box(modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides reveal) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(top = if (compactHeight) 4.dp else 12.dp, bottom = endPadding),
                verticalArrangement = Arrangement.spacedBy(if (compactHeight) 4.dp else 12.dp),
                content = body
            )
        }
        if (scroll.canScrollBackward) {
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(topFade)
                    .background(Brush.verticalGradient(listOf(surface, Color.Transparent))).testTag("sync:fade-top")
            )
        }
        if (showBottomFade) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottomFade)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, surface))).testTag("sync:fade-bottom")
            )
        }
    }
}

/** A focused TV field is a navigation target until Select explicitly requests editing. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SyncTvNameField(
    state: SyncPlayPresentation,
    name: TextFieldState,
    first: FocusRequester,
    createFocus: FocusRequester,
    keyboardRequested: Boolean,
    requestKeyboard: (Boolean) -> Unit,
    action: (String, String?) -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val editorFocus = remember { FocusRequester() }
    val inputMethod = remember(view) { view.context.getSystemService(InputMethodManager::class.java) }
    val select = remember { RemoteFieldNavigation(setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) }
    // A floating TV keyboard can be visible with no overlap/bottom inset.
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            imeWasVisible = true
            requestKeyboard(true)
        } else if (imeWasVisible) {
            imeWasVisible = false
            requestKeyboard(false)
        }
    }
    LaunchedEffect(keyboardRequested) {
        if (keyboardRequested) {
            view.requestFocus()
            editorFocus.requestFocus()
            withFrameNanos { }
            keyboard?.show()
            // An explicit remote edit gesture requests the dialog's accepting Android view too.
            // The Compose controller is best-effort; keep this request after the state editor's
            // keyboard options have established its input session, never on initial focus.
            inputMethod?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        } else {
            keyboard?.hide()
        }
    }
    BackHandler(enabled = imeVisible) {
        keyboard?.hide()
        requestKeyboard(false)
    }
    OutlinedTextField(
        state = name,
        label = { Text(stringResource(R.string.sync_group_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        enabled = !state.busy || state.departure != null || state.notice?.kind == SyncNoticeKind.LEFT,
        lineLimits = TextFieldLineLimits.SingleLine,
        inputTransformation = { if (length > 200) replace(200, length, "") },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Go,
            showKeyboardOnFocus = keyboardRequested
        ),
        onKeyboardAction = {
            if (!state.busy && name.text.isNotBlank()) action("create", name.text.toString().trim())
            keyboard?.hide()
            requestKeyboard(false)
        },
        modifier = Modifier.fillMaxWidth().testTag("sync:name").focusRequester(editorFocus)
            .focusRequester(first)
            .onFocusChanged {
                if (!it.isFocused) {
                    select.clear()
                    requestKeyboard(false)
                }
            }
            .onPreviewKeyEvent { event ->
                if (select.handle(event) { requestKeyboard(true) }) {
                    true
                } else if (!keyboardRequested &&
                    !state.busy &&
                    name.text.isNotBlank() &&
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
}

@Composable
private fun SyncGroups(
    state: SyncPlayPresentation,
    action: (String, String?) -> Unit,
    tv: Boolean,
    first: FocusRequester,
    name: String,
    onName: (String) -> Unit,
    compactHeight: Boolean,
    compactEditing: Boolean,
    create: @Composable (Modifier) -> Unit,
    refreshFocus: FocusRequester,
    joinFocus: FocusRequester
) {
    val canCreate = state.accessAllowed && state.policy == "CreateAndJoinGroups"
    val primaryGroup = state.groups.firstOrNull()?.id.takeIf { state.accessAllowed && !state.busy }
    val namePrimary = primaryGroup == null && state.accessAllowed && !state.busy && canCreate
    val fieldReveal = remember { BringIntoViewRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    val imeHeight = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(fieldFocused, imeHeight) {
        if (fieldFocused) {
            withFrameNanos { }
            fieldReveal.bringIntoView()
        }
    }
    val mountedForm = remember { movableContentOf<@Composable () -> Unit> { it() } }
    val form: @Composable () -> Unit = {
        SyncHeading(stringResource(R.string.sync_create), false)
        Layout(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            content = {
                OutlinedTextField(
                    name,
                    onName,
                    label = { Text(stringResource(R.string.sync_group_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (!state.busy && name.isNotBlank()) action("create", name.trim()) }),
                    modifier = Modifier.testTag("sync:name").bringIntoViewRequester(fieldReveal)
                        .onFocusChanged { fieldFocused = it.isFocused }
                        .then(if (namePrimary) Modifier.focusRequester(first) else Modifier)
                )
                if (compactHeight) create(Modifier)
            }
        ) { children, constraints ->
            val width = constraints.maxWidth
            val gap = 8.dp.roundToPx()
            val actionWidth = children.getOrNull(1)?.maxIntrinsicWidth(Constraints.Infinity)?.coerceAtMost(width) ?: 0
            val inline = children.size > 1 && width - actionWidth - gap >= 200.dp.roundToPx()
            val editorWidth = if (inline) width - actionWidth - gap else width
            val editor = children[0].measure(Constraints.fixedWidth(editorWidth))
            val button = children.getOrNull(1)?.measure(Constraints.fixedWidth(if (inline) actionWidth else width))
            val height = if (button ==
                null
            ) {
                editor.height
            } else if (inline) {
                maxOf(editor.height, button.height)
            } else {
                editor.height + gap + button.height
            }
            layout(width, constraints.constrainHeight(height)) {
                editor.placeRelative(0, if (inline) (height - editor.height) / 2 else 0)
                button?.placeRelative(
                    if (inline) editorWidth + gap else 0,
                    if (inline) {
                        (height - button.height) / 2
                    } else {
                        editor.height +
                            gap
                    }
                )
            }
        }
    }
    // A compact chooser always has its action beside the editor, even before IME insets arrive.
    // Empty choosers lead with creation at every touch height. Never reparent on keyboard changes.
    val formFirst = !tv && canCreate && state.groups.isEmpty()
    if (formFirst) mountedForm(form)
    if (!compactEditing) {
        if (state.accessAllowed && !(tv && canCreate)) SyncText(stringResource(R.string.sync_privacy), tv, small = true)
        val accessNotice = when (state.access.notice) {
            SyncAccessNotice.CHECKING -> R.string.sync_checking
            SyncAccessNotice.UNKNOWN -> R.string.sync_check_access
            SyncAccessNotice.UNAVAILABLE -> R.string.sync_access_failed
            SyncAccessNotice.DENIED -> R.string.sync_access_denied
            SyncAccessNotice.ALLOWED -> null
        }
        accessNotice?.let {
            Row(
                Modifier.then(if (tv) Modifier.tvSettingsVisualBlock() else Modifier).padding(horizontal = 16.dp),
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
        if (state.accessAllowed && state.policy == "JoinGroups") SyncText(stringResource(R.string.sync_join_only), tv, small = true)
        if (state.accessAllowed) SyncHeading(stringResource(R.string.sync_groups), tv)
        val groupRows: @Composable () -> Unit = {
            if (tv || !state.busy) {
                SyncSecondaryAction(
                    stringResource(R.string.sync_refresh),
                    { action("refresh", null) },
                    tv,
                    Modifier.testTag("sync:refresh").then(
                        if (tv) {
                            Modifier.focusRequester(refreshFocus)
                        } else if (primaryGroup == null && !namePrimary) {
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
                if (tv) Spacer(Modifier.height(12.dp))
                SyncText(stringResource(R.string.sync_empty), tv, Modifier.testTag("sync:empty"), small = tv)
            }
            if (state.accessAllowed) {
                Column {
                    state.groups.forEach { group ->
                        val count = pluralStringResource(R.plurals.sync_people_watching, group.members, syncNumber(group.members))
                        val modifier = Modifier.testTag("sync:join:${group.id}").then(
                            if (primaryGroup == group.id) Modifier.focusRequester(if (tv) joinFocus else first) else Modifier
                        )
                        if (tv) {
                            TvSettingsRow(
                                group.name,
                                count,
                                modifier,
                                enabled = !state.busy,
                                onClick = { action("join", group.id) },
                                trailing = {
                                    androidx.tv.material3.Text(
                                        stringResource(R.string.sync_join),
                                        style = TvMaterialTheme.typography.labelLarge
                                    )
                                }
                            )
                        } else {
                            ListItem(
                                headlineContent = { Text(group.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(count) },
                                modifier = modifier,
                                trailingContent = {
                                    FilledTonalButton(onClick = { action("join", group.id) }, enabled = !state.busy) {
                                        Text(stringResource(R.string.sync_join))
                                    }
                                }
                            )
                        }
                    }
                }
            }
            if (tv && state.busy && !state.accessAllowed) {
                repeat(2) { TvSettingsRowPlaceholder(Modifier.testTag("sync:group-placeholder")) }
            }
        }
        if (tv) Column { groupRows() } else groupRows()
    }
    if (!tv && canCreate && !formFirst) mountedForm(form)
}

@OptIn(ExperimentalMaterial3Api::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun SyncJoined(
    state: SyncPlayPresentation,
    action: (String, String?) -> Unit,
    tv: Boolean,
    first: FocusRequester,
    waitFocus: FocusRequester,
    queueFocus: MutableMap<String, SyncQueueTarget>,
    usable: Boolean,
    transport: Boolean,
    hasRecovery: Boolean,
    notice: SyncNotice?,
    compactHeight: Boolean,
    bodyScroll: ScrollState,
    touchWindowSize: IntSize
) {
    val density = LocalDensity.current
    val touchReveal = remember(state.server, state.viewer, state.groupId, touchWindowSize, density.density, density.fontScale) {
        BringIntoViewRequester()
    }
    var touchTransportPlaced by remember(touchReveal) { mutableStateOf(false) }
    var initiallyRevealed by remember(touchReveal) { mutableStateOf(false) }
    val initialRevealAllowed = !tv &&
        transport &&
        !hasRecovery &&
        (notice == null || (notice.tone == SyncNoticeTone.INFO && notice.action == null))
    LaunchedEffect(touchReveal, touchTransportPlaced, initialRevealAllowed) {
        if (initialRevealAllowed && touchTransportPlaced && touchWindowSize != IntSize.Zero && !initiallyRevealed) {
            withFrameNanos { }
            touchReveal.bringIntoView()
            initiallyRevealed = true
        }
    }
    val groupName = state.groupName ?: stringResource(R.string.sync_your_group)
    if (tv) {
        androidx.tv.material3.Text(
            groupName,
            Modifier.tvSettingsVisualBlock().padding(horizontal = 16.dp).testTag("sync:group").semantics { heading() },
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
    val current = state.queue.firstOrNull { it.current }
    val currentTitle = if (state.queue.isEmpty()) {
        stringResource(R.string.sync_nothing_playing)
    } else if (current == null) {
        stringResource(R.string.sync_title_unavailable)
    } else {
        syncQueueTitle(current)
    }
    val titlePlaceholder = state.queue.isEmpty() || current == null || current.titleUnavailable
    val titleModifier = Modifier.padding(horizontal = 16.dp).testTag("sync:now-playing")
    if (tv) {
        androidx.tv.material3.Text(
            currentTitle,
            titleModifier.tvSettingsVisualBlock(),
            style = if (titlePlaceholder) TvMaterialTheme.typography.bodyLarge else TvMaterialTheme.typography.titleMedium,
            color = if (titlePlaceholder) {
                TvMaterialTheme.colorScheme.onSurfaceVariant
            } else {
                TvMaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    } else {
        Text(
            currentTitle,
            titleModifier,
            style = if (compactHeight) {
                MaterialTheme.typography.bodyMedium
            } else if (titlePlaceholder) {
                MaterialTheme.typography.bodyLarge
            } else {
                MaterialTheme.typography.titleMedium
            },
            color = if (titlePlaceholder) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = if (compactHeight) 1 else 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    val panelQuality = syncPanelQuality(state.quality, hasRecovery)
    if ((notice == null || state.phase !in setOf("Error", "Reconnecting")) && syncPhase(state.phase, state.waitReason) != SyncPhase.EMPTY) {
        val phase = syncPhaseText(state)
        val status = if (panelQuality == SyncQuality.UNKNOWN) phase else "$phase\u00a0·\u00a0${syncQualityText(panelQuality)}"
        SyncText(status, tv, Modifier.testTag("sync:phase").semantics { liveRegion = LiveRegionMode.Polite }, small = compactHeight)
    }
    if (!tv) {
        val peopleCount = state.participants.size
        SyncText(
            pluralStringResource(R.plurals.sync_people_watching, peopleCount, syncNumber(peopleCount)),
            false,
            Modifier.testTag("sync:people-count"),
            small = true
        )
    }
    if (tv && state.queue.isEmpty()) {
        SyncText(
            stringResource(R.string.sync_empty_next_step),
            true,
            Modifier.padding(top = 8.dp).testTag("sync:empty-next-step"),
            small = true
        )
    }
    if (tv && state.queue.isNotEmpty()) {
        SyncText(
            stringResource(R.string.sync_group_context),
            true,
            Modifier.padding(top = 8.dp).testTag("sync:personal"),
            small = true
        )
    }
    val local = stringResource(
        when (state.localPlayback) {
            SyncLocalPlayback.UNAVAILABLE -> R.string.sync_local_unavailable
            SyncLocalPlayback.PREPARING -> R.string.sync_local_preparing
            SyncLocalPlayback.BUFFERING -> R.string.sync_local_buffering
            SyncLocalPlayback.READY -> R.string.sync_local_ready
        }
    )
    if (syncShowsLocalPlayback(state.queue.size)) {
        SyncText(
            stringResource(R.string.sync_local_fact, stringResource(R.string.sync_this_device), local),
            tv,
            Modifier.testTag("sync:local"),
            small = compactHeight
        )
    }
    if (!compactHeight &&
        state.quality != SyncQuality.UNKNOWN
    ) {
        SyncText(stringResource(R.string.sync_estimate_explanation), tv, small = true)
    }
    var topControlFocused by remember(state.groupId, transport) { mutableStateOf(false) }
    LaunchedEffect(tv, topControlFocused, state.groupId) {
        if (tv && topControlFocused) {
            withFrameNanos { }
            bodyScroll.scrollTo(0)
        }
    }
    if (state.queue.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().then(
                if (tv) {
                    Modifier.tvSettingsVisualBlock().padding(vertical = 8.dp)
                } else {
                    Modifier.bringIntoViewRequester(touchReveal)
                        .onPlaced { touchTransportPlaced = true }
                }
            ),
            horizontalArrangement = if (tv) Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally) else Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SyncTransport("previous", stringResource(R.string.sync_previous), {
                action("previous", null)
            }, tv, Modifier.testTag("sync:previous"), transport)
            val play = state.phase != "Playing"
            SyncTransport(
                if (play) "play" else "pause",
                stringResource(if (play) R.string.sync_play else R.string.sync_pause),
                { action(if (play) "play" else "pause", null) },
                tv,
                Modifier.testTag(if (play) "sync:play" else "sync:pause").onFocusChanged {
                    if (transport) topControlFocused = it.isFocused
                }.then(
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
            SyncTransport(
                "next",
                stringResource(R.string.sync_next),
                { action("next", null) },
                tv,
                Modifier.testTag("sync:next"),
                transport
            )
        }
    }
    if (compactHeight &&
        state.quality != SyncQuality.UNKNOWN
    ) {
        SyncText(stringResource(R.string.sync_estimate_explanation), tv, small = true)
    }
    val waitSetting: @Composable () -> Unit = {
        SyncToggle(
            stringResource(R.string.sync_wait_for_me),
            stringResource(if (usable) R.string.sync_wait_explanation else R.string.sync_wait_unavailable),
            !state.ignoreWait,
            { action("ignore-wait", (!state.ignoreWait).toString()) },
            tv,
            Modifier.testTag("sync:ignore-wait").focusRequester(waitFocus).onFocusChanged {
                if (!transport &&
                    usable
                ) {
                    topControlFocused = it.hasFocus
                }
            }.then(
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
        if (!tv) SyncText(stringResource(R.string.sync_personal), false, small = true)
    }
    val playbackSettings: @Composable () -> Unit = {
        if (tv) {
            androidx.tv.material3.Text(
                stringResource(R.string.sync_repeat),
                Modifier.tvSettingsVisualBlock().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                style = TvMaterialTheme.typography.labelLarge,
                color = TvMaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            SyncHeading(stringResource(R.string.sync_repeat), false, compact = true)
        }
        val repeats = listOf(
            "RepeatNone" to R.string.sync_repeat_off,
            "RepeatOne" to R.string.sync_repeat_one,
            "RepeatAll" to R.string.sync_repeat_all
        )
        if (tv) {
            val repeatFocus = remember { List(3) { FocusRequester() } }
            Row(
                Modifier.tvSettingsVisualBlock().fillMaxWidth().focusProperties {
                    onEnter = { if (usable) repeatFocus[repeats.indexOfFirst { it.first == state.repeat }.coerceAtLeast(0)].requestFocus() }
                }.focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeats.forEach { (value, label) ->
                    androidx.tv.material3.FilterChip(
                        selected = state.repeat == value,
                        onClick = { action("repeat", value) },
                        enabled = usable,
                        scale = androidx.tv.material3.FilterChipDefaults.scale(focusedScale = 1f),
                        modifier = Modifier.testTag("sync:repeat:$value").focusRequester(
                            repeatFocus[
                                repeats.indexOfFirst {
                                    it.first ==
                                        value
                                }
                            ]
                        ),
                        leadingIcon = if (state.repeat ==
                            value
                        ) {
                            (
                                {
                                    androidx.tv.material3.Icon(touchActionIcon("check"), null, Modifier.size(18.dp))
                                }
                                )
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
    }
    val queue: @Composable () -> Unit = {
        SyncHeading(stringResource(R.string.sync_queue), tv)
        if (state.queue.isEmpty()) SyncText(stringResource(R.string.sync_queue_empty), tv)
        Column {
            state.queue.forEach { entry ->
                key(entry.id) {
                    val title = syncQueueTitle(entry)
                    val current = if (entry.current) stringResource(R.string.sync_now_playing) else null
                    val remove = stringResource(R.string.sync_remove_title, title)
                    if (tv) {
                        val entryTarget = remember { SyncQueueTarget() }
                        val placedQueue = state.queue.map { it.id }
                        SideEffect { queueFocus[entry.id] = entryTarget }
                        Row(Modifier.tvSettingsVisualBlock().fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TvSettingsRow(
                                title,
                                current,
                                Modifier.weight(1f).testTag("sync:entry:${entry.id}").focusRequester(entryTarget.focus)
                                    .bringIntoViewRequester(entryTarget.reveal).onPlaced { entryTarget.placedQueue = placedQueue },
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
                        val rowColor = if (entry.current) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
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
                            modifier = Modifier.fillMaxWidth().testTag("sync:entry:${entry.id}")
                                .clickable(enabled = usable) { action("select-entry", entry.id) }
                        )
                    }
                }
            }
        }
    }
    val roster: @Composable () -> Unit = {
        SyncHeading(stringResource(R.string.sync_people), tv)
        SyncText(stringResource(R.string.sync_readiness_unknown), tv, small = true)
        val people = syncPeople(state.participants)
        Column {
            people.names.forEachIndexed { index, person ->
                if (tv) {
                    TvSettingsRow(person, null, Modifier.testTag("sync:participant:$index"), readOnly = true, titleMaxLines = 1, onClick = {
                    })
                } else {
                    ListItem(headlineContent = {
                        Text(person, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }, modifier = Modifier.testTag("sync:participant:$index"))
                }
            }
            val remaining = people.remaining
            if (remaining > 0) {
                val more = pluralStringResource(R.plurals.sync_more_people, remaining, syncNumber(remaining))
                if (tv) TvSettingsRow(more, null, readOnly = true, onClick = {}) else SyncText(more, false, small = true)
            }
        }
    }
    if (tv) {
        // Section labels explain scope without changing Play → Wait → Repeat → Shuffle.
        TvSettingsSectionHeader(
            stringResource(R.string.sync_your_settings),
            Modifier.testTag("sync:personal-scope"),
            supportingText = stringResource(R.string.sync_wait_personal)
        )
        waitSetting()
        TvSettingsSectionHeader(
            stringResource(R.string.sync_group_playback),
            Modifier.testTag("sync:group-scope"),
            supportingText = stringResource(R.string.sync_group_playback_scope)
        )
        playbackSettings()
        queue()
        roster()
    } else {
        // Shared media leads; a large personal row must not imply the end of this scrollable page.
        queue()
        playbackSettings()
        roster()
        SyncHeading(stringResource(R.string.sync_your_settings), false)
        waitSetting()
    }
}

/** Both panel and queue use account-filtered metadata; unavailable always wins over a stale title. */
@Composable
private fun syncQueueTitle(entry: SyncPlayQueueRow): String = when {
    entry.titleUnavailable -> stringResource(R.string.sync_title_unavailable)
    !entry.title.isNullOrBlank() -> entry.title
    else -> stringResource(R.string.sync_loading)
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
        val headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f)
        val supportingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f)
        Box(modifier) {
            ListItem(
                headlineContent = { Text(title) },
                supportingContent = supporting?.let { { Text(it) } },
                colors = ListItemDefaults.colors(
                    headlineColor = headlineColor,
                    supportingColor = supportingColor
                ),
                trailingContent = {
                    Switch(checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.clearAndSetSemantics {})
                },
                modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch) { onToggle() }
            )
        }
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
            scale = androidx.tv.material3.IconButtonDefaults.scale(focusedScale = 1f),
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
private fun SyncHeading(text: String, tv: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    if (tv) {
        TvSettingsSectionHeader(text, modifier)
    } else {
        Text(
            text,
            modifier.padding(horizontal = 16.dp).padding(top = 12.dp).semantics {
                heading()
            },
            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge
        )
    }
}

@Composable
private fun SyncText(text: String, tv: Boolean, modifier: Modifier = Modifier, small: Boolean = false) {
    if (tv) {
        androidx.tv.material3.Text(
            text,
            modifier.tvSettingsVisualBlock().padding(horizontal = 16.dp),
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

@Composable
private fun SyncNoticeBanner(
    state: SyncPlayPresentation,
    notice: SyncNotice,
    action: (String, String?) -> Unit,
    tv: Boolean,
    first: FocusRequester?,
    modifier: Modifier = Modifier,
    pinned: Boolean = false
) {
    val message = if (notice.kind == SyncNoticeKind.DEPARTURE) {
        syncDepartureText(state)
    } else {
        stringResource(
            when (notice.kind) {
                SyncNoticeKind.ACCESS_FAILED -> R.string.sync_access_failed
                SyncNoticeKind.GROUPS_FAILED -> R.string.sync_groups_failed
                SyncNoticeKind.REJOIN -> if (notice.tone == SyncNoticeTone.WARNING) R.string.sync_out_of_sync else R.string.sync_lost
                SyncNoticeKind.PREPARING -> R.string.sync_local_preparing
                SyncNoticeKind.LIBRARY_DENIED -> R.string.sync_library_denied
                SyncNoticeKind.CONNECTION -> R.string.sync_connection
                SyncNoticeKind.SOURCE_UNAVAILABLE -> R.string.sync_source_unavailable
                SyncNoticeKind.CHECK_ACCESS -> R.string.sync_check_access
                SyncNoticeKind.ACCESS_DENIED -> R.string.sync_access_denied
                SyncNoticeKind.REQUEST_FAILED -> R.string.sync_request_failed
                SyncNoticeKind.SNAPSHOT -> R.string.sync_snapshot
                SyncNoticeKind.LEFT, SyncNoticeKind.DEPARTURE -> R.string.sync_left
                SyncNoticeKind.LEAVE_UNCONFIRMED -> R.string.sync_leave_unconfirmed
                SyncNoticeKind.SPEED -> R.string.sync_speed
                SyncNoticeKind.CLOCK -> if (notice.tone == SyncNoticeTone.WARNING) R.string.sync_out_of_sync else R.string.sync_lost
                SyncNoticeKind.QUEUE_PENDING -> R.string.sync_queue_pending
                SyncNoticeKind.GROUP_UNAVAILABLE -> R.string.sync_group_unavailable
                SyncNoticeKind.PLAYBACK_FAILED -> R.string.sync_playback_error
            }
        )
    }
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
        modifier.then(if (tv) Modifier.tvSettingsVisualBlock() else Modifier).padding(horizontal = if (tv) 0.dp else 16.dp)
            .padding(top = 0.dp, bottom = if (tv && !pinned) 8.dp else 0.dp).fillMaxWidth()
            .clip(
                if (tv) TvMaterialTheme.shapes.medium else MaterialTheme.shapes.medium
            ).background(background).padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("sync:status").semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (notice.tone != SyncNoticeTone.INFO) Icon(syncIcon("warning"), null, tint = foreground, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tv) androidx.tv.material3.Text(message, color = foreground) else Text(message, color = foreground)
            if (notice.kind == SyncNoticeKind.DEPARTURE &&
                state.departure?.cause == app.pictureshow.core.sync.SyncDepartureCause.MANUAL_LEAVE
            ) {
                state.departure.groupName?.let { name ->
                    val context = stringResource(R.string.sync_departure_context, name)
                    if (tv) {
                        androidx.tv.material3.Text(context, color = foreground, style = TvMaterialTheme.typography.bodyMedium)
                    } else {
                        Text(context, color = foreground, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
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
    val displayedQuality = quality.takeIf { state.phase in setOf("Playing", "Paused") && syncChipShowsQuality(state.quality) }
    val content: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tv) {
                androidx.tv.material3.Icon(syncIcon("group"), null, Modifier.size(20.dp).testTag("sync:chip-icon"))
            } else {
                Icon(syncIcon("group"), null, Modifier.size(20.dp).testTag("sync:chip-icon"))
            }
            Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SyncChipStatus(phase, displayedQuality, tv)
                SyncChipContext(name, count, tv, contextColor)
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

/** Acknowledges actual clipped placement, never composition or disposal. */
internal fun Modifier.syncDeparturePresentation(id: Long, onPresented: (Long) -> Unit): Modifier = onGloballyPositioned { coordinates ->
    if (coordinates.isAttached) {
        val visible = coordinates.boundsInWindow()
        val window = coordinates.findRootCoordinates().boundsInWindow()
        if (!visible.isEmpty && !window.isEmpty && visible.overlaps(window)) onPresented(id)
    }
}

@Composable
private fun syncDepartureText(state: SyncPlayPresentation): String {
    val departure = state.departure ?: return stringResource(R.string.sync_left)
    val group = departure.groupName ?: stringResource(R.string.sync_departure_group)
    return when (departure.cause) {
        app.pictureshow.core.sync.SyncDepartureCause.MANUAL_LEAVE -> stringResource(R.string.sync_left)
        app.pictureshow.core.sync.SyncDepartureCause.PLAYBACK_STOPPED -> stringResource(R.string.sync_departure_stopped, group)
        app.pictureshow.core.sync.SyncDepartureCause.PLAYBACK_ENDED -> stringResource(R.string.sync_departure_ended, group)
        app.pictureshow.core.sync.SyncDepartureCause.PLAYBACK_CLOSED -> stringResource(R.string.sync_departure_closed, group)
        app.pictureshow.core.sync.SyncDepartureCause.EXTERNAL_ROUTE -> stringResource(R.string.sync_departure_cast, group)
        app.pictureshow.core.sync.SyncDepartureCause.MEMBERSHIP_LOST -> stringResource(R.string.sync_departure_lost, group)
    }
}

/** One real native text layout preserves introspection while wrapping only between known phrases. */
@Composable
private fun SyncChipStatus(phase: String, quality: String?, tv: Boolean) {
    val style = if (tv) TvMaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelLarge
    val color = if (tv) androidx.tv.material3.LocalContentColor.current else MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    SubcomposeLayout { constraints ->
        val inline = if (quality == null) phase else "$phase · $quality"
        val fits =
            quality == null || measurer.measure(inline, style = style, maxLines = 1, softWrap = false).size.width <= constraints.maxWidth
        val text = if (fits) inline else "$phase\n$quality"
        val status = subcompose("status") {
            SyncChipText(text, tv, style, color, Modifier.testTag("sync:chip-status"), maxLines = 2, softWrap = quality == null)
        }.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.constrainWidth(status.width), constraints.constrainHeight(status.height)) {
            status.placeRelative(0, 0)
        }
    }
}

@Composable
private fun SyncChipText(
    text: String,
    tv: Boolean,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    softWrap: Boolean = false
) {
    if (tv) {
        androidx.tv.material3.Text(
            text,
            modifier,
            color = color,
            style = style,
            maxLines = maxLines,
            softWrap = softWrap,
            overflow = TextOverflow.Ellipsis
        )
    } else {
        Text(text, modifier, color = color, style = style, maxLines = maxLines, softWrap = softWrap, overflow = TextOverflow.Ellipsis)
    }
}

/** Reserve the count, then measure a useful name instead of exposing a weighted ellipsis box. */
@Composable
private fun SyncChipContext(name: String, count: String, tv: Boolean, color: Color) {
    val style = if (tv) TvMaterialTheme.typography.bodySmall else MaterialTheme.typography.bodySmall
    val measurer = rememberTextMeasurer()
    val boundaries = remember(name) { syncNameGraphemeEnds(name) }
    SubcomposeLayout { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val countRun = subcompose("count") {
            SyncChipText(count, tv, style, color, Modifier.testTag("sync:chip-count"))
        }.single().measure(loose)
        val separator = subcompose("separator") {
            SyncChipText("·", tv, style, color, Modifier.clearAndSetSemantics {})
        }.single().measure(loose)
        val gap = 8.dp.roundToPx()
        val budget = (constraints.maxWidth - countRun.width - separator.width - gap * 2).coerceAtLeast(0)
        fun fits(text: String): Boolean = measurer.measure(text, style = style, maxLines = 1, softWrap = false).size.width <= budget
        val shown = syncChipName(name, boundaries, ::fits)
        val nameRun = shown?.let { visible ->
            subcompose("name") {
                SyncChipText(visible, tv, style, color, Modifier.testTag("sync:chip-name"))
            }.single().measure(Constraints(maxWidth = budget))
        }
        val width = countRun.width + if (nameRun == null) 0 else nameRun.width + separator.width + gap * 2
        val height = maxOf(countRun.height, nameRun?.height ?: 0, if (nameRun == null) 0 else separator.height)
        layout(constraints.constrainWidth(width), constraints.constrainHeight(height)) {
            if (nameRun != null) {
                nameRun.placeRelative(0, (height - nameRun.height) / 2)
                separator.placeRelative(nameRun.width + gap, (height - separator.height) / 2)
            }
            countRun.placeRelative(if (nameRun == null) 0 else nameRun.width + separator.width + gap * 2, (height - countRun.height) / 2)
        }
    }
}

/** ICU supplies extended character boundaries; API23 safely omits unproven non-ASCII prefixes. */
private fun syncNameGraphemeEnds(name: String): List<Int> {
    if (android.os.Build.VERSION.SDK_INT < 24) return if (name.all { it.code in 32..126 }) (1..name.length).toList() else emptyList()
    val iterator = android.icu.text.BreakIterator.getCharacterInstance(java.util.Locale.getDefault())
    iterator.setText(name)
    val ends = mutableListOf<Int>()
    var end = iterator.next()
    while (end != android.icu.text.BreakIterator.DONE) {
        ends += end
        end = iterator.next()
    }
    return ends
}

/** A keyed native row survives recomposition; placement belongs to an authoritative queue revision. */
private class SyncQueueTarget {
    val focus = FocusRequester()
    val reveal = BringIntoViewRequester()
    var placedQueue by mutableStateOf<List<String>?>(null)
}
