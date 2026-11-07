/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Wait for the screen reader's initial target, rather than guessing when its queued window events finish. */
internal suspend fun awaitInitialPlayerPanelFocus(view: View, targetId: String? = null): Boolean {
    var acknowledged = false
    kotlinx.coroutines.withTimeoutOrNull(1_500) {
        while (!acknowledged) {
            val node = view.accessibilityNodeProvider?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            val focused = node?.let {
                it.isVisibleToUser &&
                    it.isFocusable &&
                    it.isClickable &&
                    (targetId == null || it.viewIdResourceName == targetId)
            } == true
            @Suppress("DEPRECATION")
            node?.recycle()
            acknowledged = focused
            if (!acknowledged) androidx.compose.runtime.withFrameNanos { }
        }
    }
    return acknowledged
}

/** Focus can be taken by a system overlay; wait for an event without rendering a busy loop. */
internal suspend fun awaitPlayerActivityFocus(host: View): Boolean = withTimeoutOrNull(3_000) {
    if (host.hasWindowFocus()) return@withTimeoutOrNull true
    suspendCancellableCoroutine { continuation ->
        val observer = host.viewTreeObserver
        lateinit var listener: ViewTreeObserver.OnWindowFocusChangeListener
        listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused && continuation.isActive) {
                if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
                continuation.resume(true)
            }
        }
        observer.addOnWindowFocusChangeListener(listener)
        continuation.invokeOnCancellation { if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener) }
        if (host.hasWindowFocus() && continuation.isActive) {
            observer.removeOnWindowFocusChangeListener(listener)
            continuation.resume(true)
        }
    }
} ?: false

internal class PlayerPanelScope(private val requestClose: (() -> Unit, () -> Unit) -> Unit) {
    /** Keep the window alive until input focus has safely returned to the activity. */
    fun close(action: () -> Unit) = requestClose({}, action)

    /** Commit the viewer's selection immediately; only window removal waits for the handoff. */
    fun select(action: () -> Unit, onClosed: () -> Unit) = requestClose(action, onClosed)
}

/** Same-window Seek is a sibling of the hidden player, never a descendant of that hidden tree. */
@Composable
internal fun PlayerSeekHost(open: Boolean, player: @Composable () -> Unit, seek: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (open) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier)) { player() }
        // A sibling modal owns the whole hit-test surface, including outside its sheet.
        // Child gestures consume their taps first; uncovered taps stop at this shield.
        if (open) Box(Modifier.fillMaxSize().playerTouchClick { }) { seek() }
    }
}

/** Track/chapter Dialogs supply a native modal boundary; same-window Seek supplies an explicit focus boundary. */
@Composable
internal fun PlayerPanel(
    title: String,
    onDismiss: () -> Unit,
    scrollable: Boolean = true,
    onHandoff: () -> Unit = {},
    inActivityWindow: Boolean = false,
    content: @Composable PlayerPanelScope.() -> Unit
) {
    val activityHost = LocalView.current
    val layoutReceipt = remember(title, inActivityWindow) { booleanArrayOf(false) }
    val firstLayout = Modifier.onGloballyPositioned {
        if (!layoutReceipt[0]) {
            layoutReceipt[0] = true
            PlaybackDiagnostics.write("panel-open title=$title inActivityWindow=$inActivityWindow")
        }
    }
    var closingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val panelScope = remember {
        PlayerPanelScope { before, after ->
            if (closingAction == null) {
                closingAction = after
                before()
            }
        }
    }
    if (inActivityWindow) {
        // Seek stays in this window so Done/Back speaks the position without an app-title
        // announcement. Its caller hides the underlying player; this group traps remote focus.
        BackHandler { panelScope.close(onDismiss) }
        LaunchedEffect(closingAction) {
            closingAction?.let {
                onHandoff()
                it()
            }
        }
        val paneHost = LocalView.current
        PlayerPanelContent(
            title,
            scrollable,
            firstLayout.focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
            announcePane = title != "Seek",
            paneTitleText = remember(paneHost, title) { nextInWindowPaneTitle(paneHost, title) }
        ) { panelScope.content() }
        return
    }
    Dialog(onDismissRequest = {
        panelScope.close(onDismiss)
    }, properties = DialogProperties(usePlatformDefaultWidth = false, windowTitle = title)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            val original = window?.callback
            val bridge = original?.let { PanelMediaKeyCallback(it) }
            if (bridge != null) window?.callback = bridge
            onDispose { if (window?.callback === bridge) window?.callback = original }
        }
        LaunchedEffect(closingAction) {
            val action = closingAction ?: return@LaunchedEffect
            onHandoff()
            // Let the return target join the activity's layout before its native focus event.
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            // Transfer input focus while this window and its callback are still alive. Keys
            // already addressed here use the same session bridge as keys in the activity.
            val started = SystemClock.uptimeMillis()
            window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            val focused = awaitPlayerActivityFocus(activityHost)
            PlaybackDiagnostics.write("panel-close title=$title waitMs=${SystemClock.uptimeMillis() - started} focused=$focused")
            action()
        }
        SideEffect {
            // Keep the original right-hand panel shading; the modal must not dim the whole player.
            window?.let {
                val attributes = it.attributes
                if (attributes.dimAmount != 0f || attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                    attributes.dimAmount = 0f
                    attributes.flags = attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
                    it.attributes = attributes
                }
            }
        }
        PlayerPanelContent(title, scrollable, firstLayout) { panelScope.content() }
    }
}

@Composable
internal fun PlayerPanelContent(
    title: String,
    scrollable: Boolean,
    modifier: Modifier = Modifier,
    announcePane: Boolean = true,
    paneTitleText: String = title,
    content: @Composable () -> Unit
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val information = title == "Playback info"
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.widthIn(max = 680.dp).fillMaxSize(.85f).testTag("player:panel")
                .then(
                    if (scrollable) {
                        Modifier.background(
                            if (information) Color(0xFF10131C) else Color(0xF510131C)
                        ).onPreviewKeyEvent { event ->
                            val key = event.nativeKeyEvent
                            if (information && key.keyCode in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP)) {
                                if (key.action == KeyEvent.ACTION_DOWN) {
                                    scope.launch {
                                        scroll.scrollBy(if (key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 180f else -180f)
                                    }
                                }
                                true
                            } else {
                                false
                            }
                        }.verticalScroll(scroll).padding(32.dp)
                    } else {
                        Modifier
                    }
                )
                .semantics {
                    isTraversalGroup = true
                    if (announcePane) paneTitle = paneTitleText
                    testTagsAsResourceId = true
                },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) { content() }
    }
}

internal class PanelMediaKeyCallback(private val original: Window.Callback) : Window.Callback by original {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        PlaybackMediaSession.dispatchKeyEvent(event) || original.dispatchKeyEvent(event)
}

/**
 * Own the selected arrival row before a key can reach the panel. Android can immediately re-enter
 * during a forced owner-focus clear, followed by another parent request while the group is already
 * active; that second request skips onEnter. Keep only the preferred row eligible during entry.
 * User input opens all rows synchronously, before key search, pointer handling or accessibility
 * focus actions. Window/a11y acknowledgment only opens eligibility; it never requests focus later.
 */
internal class PlayerPanelEntryFocus {
    private val rows = linkedMapOf<Int, FocusRequester>()
    private var focusedIndex: Int? = null
    private var initialIndex: Int? = null
    private var entered = false
    private var entryPending = true

    @Composable
    fun Modifier.row(index: Int): Modifier {
        val requester = remember(index) { FocusRequester() }
        DisposableEffect(index, requester) {
            rows[index] = requester
            onDispose { if (rows[index] === requester) rows.remove(index) }
        }
        return this.focusProperties { canFocus = !entryPending || index == preferredIndex() }
            .focusRequester(requester).onFocusChanged {
                if (it.isFocused) {
                    focusedIndex = index
                    entered = true
                }
            }.semantics {
                requestFocus {
                    allowNavigation()
                    requester.requestFocus()
                }
            }
    }

    private fun preferredIndex(): Int? = focusedIndex?.takeIf(rows::containsKey)
        // Do not latch another row while the selected row is still joining the first layout.
        ?: initialIndex?.takeIf { !entered || rows.containsKey(it) }
        ?: rows.keys.firstOrNull()

    fun requestInitial(index: Int?) {
        index?.let(rows::get)?.requestFocus()
    }

    fun allowNavigation() {
        entryPending = false
    }

    fun Modifier.entry(initialIndex: Int?): Modifier {
        this@PlayerPanelEntryFocus.initialIndex = initialIndex
        return this.onPreviewKeyEvent {
            allowNavigation()
            false
        }.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) allowNavigation()
                }
            }
        }.focusProperties {
            onEnter = { preferredIndex()?.let(rows::get)?.requestFocus() }
        }.onFocusChanged {
            if (!it.hasFocus) entryPending = true
        }.focusGroup().onGloballyPositioned { if (!entered) requestInitial(initialIndex) }
    }
}

@Composable
internal fun TrackChoices(
    choices: List<Pair<Int, String>>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    title: String? = null,
    touchEnabled: Boolean = false,
    rowDescription: (Int, String) -> String? = { _, _ -> null },
    footer: (@Composable () -> Unit)? = null
) {
    val entry = remember { PlayerPanelEntryFocus() }
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val speedRowHeight = if (windowWidth >= 600.dp) 40.dp else 48.dp
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val host = LocalView.current
    val accessible = rememberAssistiveServiceInUse()
    var initialFocusReady by remember(accessible) { mutableStateOf(!accessible) }
    val initialOffset = choices.indexOfFirst { it.first == selectedIndex }.coerceAtLeast(0)
    val initialIndex = choices.getOrNull(initialOffset)?.first
    val list = rememberLazyListState(initialFirstVisibleItemIndex = initialOffset)
    // Keep the selected row composed even in a long list. Other targets join the
    // accessibility tree once the screen reader has acknowledged this initial target.
    Column(
        (
            if (title != null) {
                (if (touchEnabled) Modifier.fillMaxWidth() else Modifier)
                    .background(if (touchEnabled) Color(0xFF10131C) else Color(0xF510131C)).padding(32.dp)
            } else {
                Modifier
            }
            )
            .then(with(entry) { Modifier.entry(initialIndex) }),
        verticalArrangement = Arrangement.spacedBy(if (touchEnabled) 8.dp else 12.dp)
    ) {
        if (title !=
            null
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                // The window/pane already names the modal. Keeping this duplicate title
                // in the tree lets TV TalkBack choose it instead of the selected row.
                modifier = Modifier.clearAndSetSemantics { hideFromAccessibility() }
            )
        }
        if (touchEnabled && footer != null) {
            Column(
                modifier = if (!initialFocusReady) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) { footer() }
        }
        LazyColumn(
            state = list,
            modifier = (
                if (touchEnabled) {
                    Modifier.weight(1f).fillMaxWidth().drawWithContent {
                        drawContent()
                        val fade = 24.dp.toPx().coerceAtMost(size.height / 2)
                        val surface = Color(0xFF10131C)
                        if (list.canScrollBackward) {
                            drawRect(
                                Brush.verticalGradient(listOf(surface, Color.Transparent), endY = fade),
                                size = Size(size.width, fade)
                            )
                        }
                        if (list.canScrollForward) {
                            drawRect(
                                Brush.verticalGradient(listOf(Color.Transparent, surface), startY = size.height - fade, endY = size.height),
                                topLeft = Offset(0f, size.height - fade),
                                size = Size(size.width, fade)
                            )
                        }
                    }
                } else {
                    Modifier
                }
                ).semantics {
                isTraversalGroup = true
                traversalIndex = 0f
            },
            verticalArrangement = Arrangement.spacedBy(if (touchEnabled) 4.dp else 12.dp)
        ) {
            itemsIndexed(choices, key = { _, choice -> choice.first }) { _, (index, label) ->
                Button(
                    scale = if (touchEnabled) ButtonDefaults.scale(focusedScale = 1f) else playerButtonScale(),
                    onClick = { onSelect(index) },
                    modifier = (
                        if (touchEnabled) {
                            Modifier.fillMaxWidth().height(speedRowHeight).playerTouchClick {
                                onSelect(index)
                            }
                        } else {
                            Modifier
                        }
                        ).testTag("track:$index").semantics {
                        selected = index == selectedIndex
                        traversalIndex = if (index == initialIndex) -1f else 0f
                        // A row may be the panel's only focus stop; it then carries what the footer explains.
                        rowDescription(index, label)?.let { contentDescription = it }
                    }.then(with(entry) { Modifier.row(index) })
                        .then(
                            if (!initialFocusReady && index != initialIndex) {
                                Modifier.clearAndSetSemantics { hideFromAccessibility() }
                            } else {
                                Modifier
                            }
                        )
                ) {
                    if (touchEnabled) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, fontWeight = if (index == selectedIndex) FontWeight.SemiBold else FontWeight.Normal)
                            val marker = LocalContentColor.current
                            Canvas(Modifier.size(24.dp).clearAndSetSemantics { }) {
                                drawCircle(marker, radius = 10.dp.toPx(), style = Stroke(2.dp.toPx()))
                                if (index == selectedIndex) drawCircle(marker, radius = 5.dp.toPx())
                            }
                        }
                    } else {
                        Text(
                            if (index == selectedIndex) "$label · selected" else label,
                            modifier = Modifier.clearAndSetSemantics { contentDescription = label }
                        )
                    }
                }
            }
            if (footer != null && !touchEnabled) {
                item {
                    Column(
                        modifier = if (!initialFocusReady) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) { footer() }
                }
            }
        }
    }
    LaunchedEffect(windowFocused, accessible) {
        if (windowFocused) {
            if (!initialFocusReady) {
                awaitInitialPlayerPanelFocus(host)
                initialFocusReady = true
                entry.allowNavigation()
            }
        }
    }
}

@Composable
internal fun SeekPanel(positionMs: Long, durationMs: Long, onSeekBy: (Long) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val host = LocalView.current
    val latestPosition = rememberUpdatedState(spokenPlaybackPosition(positionMs, durationMs))
    CurrentPositionAccessibility(setOf("seek:position", "seek:back", "seek:forward")) { latestPosition.value }
    var step by remember { mutableStateOf(0) }
    val position = remember(step) { spokenPlaybackPosition(positionMs, durationMs) }
    var previousPosition by remember { mutableStateOf<String?>(null) }
    fun seek(delta: Long) {
        previousPosition = position
        onSeekBy(delta)
        step++
    }
    LaunchedEffect(step) {
        // A clamp can move within the same displayed second. Repeated clamps likewise have
        // no semantics change; speak the resulting clock once when that feedback is absent.
        if (step > 0 && previousPosition == position) {
            @Suppress("DEPRECATION")
            host.announceForAccessibility(position)
        }
    }
    Text("Seek", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    Text(
        spokenPlaybackPosition(positionMs, durationMs),
        modifier = Modifier.testTag("seek:position").clearAndSetSemantics {
            contentDescription = "Playback position"
            stateDescription = position
        }
    )
    Button(
        scale = playerButtonScale(),
        onClick = { seek(-10_000) },
        modifier = Modifier.focusRequester(first).testTag("seek:back")
            .semantics { stateDescription = position }
    ) {
        Text("Back 10 seconds", modifier = Modifier.clearAndSetSemantics { contentDescription = "Seek. Back 10 seconds" })
    }
    Button(
        scale = playerButtonScale(),
        onClick = { seek(10_000) },
        modifier = Modifier.testTag("seek:forward")
            .semantics { stateDescription = position }
    ) { Text("Forward 10 seconds") }
    Button(scale = playerButtonScale(), onClick = onDismiss) { Text("Done") }
    LaunchedEffect(windowFocused) {
        if (windowFocused) {
            androidx.compose.runtime.withFrameNanos { }
            first.requestFocus()
        }
    }
}
