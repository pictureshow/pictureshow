/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text

/** One focus target per row. The trailing control is a state indicator, never a second button. */
@Composable
internal fun TvSettingsToggleRow(
    title: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    onToggle: (Boolean) -> Unit
) {
    TvSettingsRow(
        title,
        supportingText,
        enabled = enabled,
        modifier = modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
            stateDescription = if (checked) "On" else "Off"
        },
        onClick = { onToggle(!checked) },
        trailing = { Switch(checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.clearAndSetSemantics {}) }
    )
}

@Composable
internal fun TvSettingsActionRow(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TvSettingsRow(title, supportingText, modifier, enabled = enabled, onClick = onClick)
}

/** OK opens all options; Left/Right are optional shortcuts that also work in a long main-page list. */
@Composable
internal fun <T> TvSettingsChoiceRow(
    title: String,
    value: T,
    values: List<T>,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    onSelect: (T) -> Unit
) {
    require(values.isNotEmpty())
    val opener = remember { FocusRequester() }
    var picker by remember { mutableStateOf(false) }
    var restoreOpener by remember { mutableStateOf(false) }
    TvSettingsRow(
        title,
        supportingText,
        modifier = modifier.focusRequester(opener).semantics { stateDescription = label(value) }.onPreviewKeyEvent { event ->
            if (!enabled || event.key !in listOf(Key.DirectionLeft, Key.DirectionRight)) {
                false
            } else {
                if (event.type == KeyEventType.KeyDown) {
                    val direction = if (event.key == Key.DirectionRight) 1 else -1
                    onSelect(values[(values.indexOf(value).coerceAtLeast(0) + direction).mod(values.size)])
                }
                true
            }
        },
        enabled = enabled,
        onClick = { picker = true },
        trailing = {
            Row(
                Modifier.widthIn(max = 160.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    label(value),
                    Modifier.weight(1f, fill = false),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodyMedium
                )
                TvSettingsChevron()
            }
        }
    )
    if (picker) {
        TvSettingsChoicePicker(title, value, values, label, onSelect, onClose = {
            picker = false
            restoreOpener = true
        })
    }
    LaunchedEffect(picker, restoreOpener) {
        if (!picker && restoreOpener) {
            withFrameNanos { }
            opener.requestFocus()
            restoreOpener = false
        }
    }
}

@Composable
internal fun <T> TvSettingsChoicePicker(
    title: String,
    value: T,
    values: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onClose: () -> Unit,
    supportingText: ((T) -> String?)? = null
) {
    require(values.isNotEmpty())
    val first = remember { FocusRequester() }
    val initial = remember { values.indexOf(value).coerceAtLeast(0) }
    TvSettingsPanel(title, onClose, first) {
        values.forEachIndexed { index, option ->
            TvSettingsRow(
                label(option),
                supportingText?.invoke(option),
                selected = option == value,
                modifier = (if (index == initial) Modifier.focusRequester(first) else Modifier)
                    .testTag("tv-settings:option:$index").semantics { role = Role.RadioButton },
                onClick = {
                    onSelect(option)
                    onClose()
                },
                trailing = { RadioButton(option == value, onClick = null, modifier = Modifier.clearAndSetSemantics {}) }
            )
        }
    }
}

@Composable
internal fun TvSettingsRow(
    title: String,
    supportingText: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val supportingColor = if (focused) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        selected, onClick,
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = supportingText?.let { text ->
            { Text(text, color = supportingColor, style = MaterialTheme.typography.bodyMedium) }
        },
        leadingContent = leading,
        trailingContent = trailing?.let { content ->
            { androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides supportingColor) { content() } }
        },
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        enabled = enabled,
        scale = ListItemDefaults.scale(focusedScale = 1f),
        interactionSource = interaction
    )
}

@Composable
private fun TvSettingsChevron() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(20.dp).clearAndSetSemantics {}) {
        val stroke = 2.dp.toPx()
        drawLine(
            color,
            Offset(size.width * .38f, size.height * .25f),
            Offset(size.width * .65f, size.height * .5f),
            stroke,
            StrokeCap.Round
        )
        drawLine(
            color,
            Offset(size.width * .65f, size.height * .5f),
            Offset(size.width * .38f, size.height * .75f),
            stroke,
            StrokeCap.Round
        )
    }
}

/** Full-height TV panel: fixed title/preview, with every explanation scrolling inside its setting row. */
@Composable
internal fun TvSettingsPanel(
    title: String,
    onClose: () -> Unit,
    firstFocus: FocusRequester,
    modifier: Modifier = Modifier,
    preview: (@Composable () -> Unit)? = null,
    scrollContent: Boolean = true,
    footer: (@Composable () -> Unit)? = null,
    lazyState: LazyListState? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    BackHandler(onBack = onClose)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, windowTitle = title)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                modifier.widthIn(max = 420.dp).fillMaxWidth().fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .testTag("tv-settings:panel").semantics {
                        paneTitle = title
                        testTagsAsResourceId = true
                    }
            ) {
                Text(
                    title,
                    Modifier.padding(horizontal = 24.dp, vertical = 20.dp).semantics {
                        heading()
                    },
                    style = MaterialTheme.typography.headlineSmall
                )
                preview?.let { Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) { it() } }
                if (scrollContent) {
                    TvSettingsList(Modifier.weight(1f), content)
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Column(Modifier.fillMaxSize(), content = content)
                        val surface = MaterialTheme.colorScheme.surface
                        if (lazyState?.canScrollBackward == true) {
                            Box(
                                Modifier.fillMaxWidth().height(16.dp).align(Alignment.TopCenter)
                                    .testTag("tv-settings:fade-top").background(Brush.verticalGradient(listOf(surface, Color.Transparent)))
                            )
                        }
                        if (lazyState?.canScrollForward == true) {
                            Box(
                                Modifier.fillMaxWidth().height(24.dp).align(Alignment.BottomCenter)
                                    .testTag(
                                        "tv-settings:fade-bottom"
                                    ).background(Brush.verticalGradient(listOf(Color.Transparent, surface)))
                            )
                        }
                    }
                }
                footer?.let { Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) { it() } }
            }
        }
        LaunchedEffect(firstFocus) {
            withFrameNanos { }
            firstFocus.requestFocus()
        }
    }
}

/** The main TV page and its panels use identical row spacing and scroll affordances. */
@Composable
internal fun TvSettingsList(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier.revealFocusAfterFontChange(scroll).fillMaxSize().verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content
        )
        if (scroll.canScrollBackward) {
            Box(
                Modifier.fillMaxWidth().height(16.dp).align(Alignment.TopCenter).testTag("tv-settings:fade-top")
                    .background(Brush.verticalGradient(listOf(surface, Color.Transparent)))
            )
        }
        if (scroll.canScrollForward) {
            Box(
                Modifier.fillMaxWidth().height(24.dp).align(Alignment.BottomCenter).testTag("tv-settings:fade-bottom")
                    .background(Brush.verticalGradient(listOf(Color.Transparent, surface)))
            )
        }
    }
}
