/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.displayRuntimeMillis

/** Wait for the screen reader's initial target, rather than guessing when its queued window events finish. */
internal suspend fun awaitInitialPlayerPanelFocus(view: View) {
    kotlinx.coroutines.withTimeoutOrNull(1_500) {
        while (true) {
            val node = view.accessibilityNodeProvider?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            val focused = node?.let { it.isVisibleToUser && it.isFocusable && it.isClickable } == true
            @Suppress("DEPRECATION")
            node?.recycle()
            if (focused) return@withTimeoutOrNull
            androidx.compose.runtime.withFrameNanos { }
        }
    }
}

/** A separate window gives TV TalkBack a modal focus boundary instead of leaving it on the video surface. */
@Composable
internal fun PlayerPanel(title: String, onDismiss: () -> Unit, scrollable: Boolean = true, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, windowTitle = title)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.widthIn(min = 400.dp, max = 680.dp).fillMaxSize(.85f).testTag("player:panel")
                    .then(
                        if (scrollable) {
                            Modifier.background(
                                Color(0xF510131C)
                            ).verticalScroll(rememberScrollState()).padding(32.dp)
                        } else {
                            Modifier
                        }
                    )
                    // The Dialog owns modality. Keep the tree present from the first frame so
                    // native input-focus events can identify the selected virtual node.
                    .semantics {
                        isTraversalGroup = true
                        paneTitle = title
                    },
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) { content() }
        }
    }
}

@Composable
internal fun TrackChoices(
    choices: List<Pair<Int, String>>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    title: String? = null,
    footer: (@Composable () -> Unit)? = null
) {
    val first = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val host = LocalView.current
    val accessible = rememberAccessibilityEnabled()
    var initialFocusReady by remember(accessible) { mutableStateOf(!accessible) }
    val initialOffset = choices.indexOfFirst { it.first == selectedIndex }.coerceAtLeast(0)
    val initialIndex = choices.getOrNull(initialOffset)?.first
    val list = rememberLazyListState(initialFirstVisibleItemIndex = initialOffset)
    // Keep the selected row composed even in a long list. Other targets join the
    // accessibility tree once the screen reader has acknowledged this initial target.
    Column(
        (if (title != null) Modifier.background(Color(0xF510131C)).padding(32.dp) else Modifier)
            // Redirect the window's first input-focus entry too: TV TalkBack may follow
            // that native event before the post-window coroutine can request the row.
            .focusProperties { onEnter = { if (initialIndex != null) first.requestFocus() } }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
        LazyColumn(
            state = list,
            modifier = Modifier.semantics {
                isTraversalGroup = true
                traversalIndex = 0f
            },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(choices, key = { _, choice -> choice.first }) { _, (index, label) ->
                Button(
                    onClick = { onSelect(index) },
                    modifier = Modifier.testTag("track:$index").semantics {
                        selected = index == selectedIndex
                        traversalIndex = if (index == initialIndex) -1f else 0f
                    }.then(if (index == initialIndex) Modifier.focusRequester(first) else Modifier)
                        .then(
                            if (!initialFocusReady && index != initialIndex) {
                                Modifier.clearAndSetSemantics { hideFromAccessibility() }
                            } else {
                                Modifier
                            }
                        )
                ) {
                    // Retain the visual selected suffix, with selection spoken by the node state once.
                    Text(
                        if (index == selectedIndex) "$label · selected" else label,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = label }
                    )
                }
            }
            if (footer != null) {
                item {
                    Column(
                        modifier = if (!initialFocusReady) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) { footer() }
                }
            }
        }
    }
    LaunchedEffect(initialIndex, windowFocused, accessible) {
        if (windowFocused) {
            if (initialIndex != null) {
                list.scrollToItem(initialOffset)
                androidx.compose.runtime.withFrameNanos { }
                first.requestFocus()
            }
            if (!initialFocusReady) {
                awaitInitialPlayerPanelFocus(host)
                initialFocusReady = true
            }
        }
    }
}

@Composable
internal fun SeekPanel(positionMs: Long, durationMs: Long, onSeekBy: (Long) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val total = displayRuntimeMillis(durationMs)
    val position = if (total != null) "${playerTime(positionMs)} of ${playerTime(total)}" else playerTime(positionMs)
    Text("Seek", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    Text(position)
    Button(
        onClick = { onSeekBy(-10_000) },
        modifier = Modifier.focusRequester(first).testTag("seek:back")
            .semantics { stateDescription = position }
    ) { Text("Back 10 seconds") }
    Button(
        onClick = { onSeekBy(10_000) },
        modifier = Modifier.testTag("seek:forward")
            .semantics { stateDescription = position }
    ) { Text("Forward 10 seconds") }
    Button(onClick = onDismiss) { Text("Done") }
    LaunchedEffect(windowFocused) {
        if (windowFocused) {
            androidx.compose.runtime.withFrameNanos { }
            first.requestFocus()
        }
    }
}
