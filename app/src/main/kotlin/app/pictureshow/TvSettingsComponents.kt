/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import app.pictureshow.core.hasControlSelection

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
    TvSettingsValueRow(
        title,
        label(value),
        supportingText = supportingText,
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
        onClick = { picker = true }

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

/** A value/chevron row also supports specialized pickers without duplicating its visual role. */
@Composable
internal fun TvSettingsValueRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TvSettingsRow(title, supportingText, modifier, enabled = enabled, onClick = onClick, trailing = {
        Row(
            Modifier.widthIn(max = 160.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(value, Modifier.weight(1f, fill = false), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium)
            TvSettingsChevron()
        }
    })
}

/** The indicator follows its row's contrasting foreground in both focused and resting states. */
@Composable
internal fun TvSettingsRadioIndicator(checked: Boolean) {
    val color = LocalContentColor.current
    RadioButton(
        checked,
        onClick = null,
        modifier = Modifier.clearAndSetSemantics {},
        colors = androidx.tv.material3.RadioButtonDefaults.colors(selectedColor = color, unselectedColor = color)
    )
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
                trailing = { TvSettingsRadioIndicator(option == value) }
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
    selected: Boolean? = null,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val supportingColor = if (focused) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurfaceVariant
    if (!hasControlSelection(selected)) {
        // tv-material1.1 ListItem always adds Selected semantics. Commands and value openers
        // use its non-selectable Surface primitive, retaining the same shared row layout.
        val shape = RoundedCornerShape(8.dp)
        androidx.tv.material3.Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)
                .onFocusChanged { focused = it.isFocused }
                .semantics(mergeDescendants = true) { role = Role.Button }
                .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, shape) else Modifier),
            enabled = enabled,
            interactionSource = interaction,
            shape = ClickableSurfaceDefaults.shape(shape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                leading?.invoke()
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    supportingText?.let { Text(it, color = supportingColor, style = MaterialTheme.typography.bodyMedium) }
                }
                trailing?.invoke()
            }
        }
        return
    }
    ListItem(
        selected == true, onClick,
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = supportingText?.let { text ->
            { Text(text, color = supportingColor, style = MaterialTheme.typography.bodyMedium) }
        },
        leadingContent = leading?.let { content ->
            { androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides supportingColor) { content() } }
        },
        trailingContent = trailing?.let { content ->
            { androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides supportingColor) { content() } }
        },
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).onFocusChanged { focused = it.isFocused },
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
                                    .testTag("tv-settings:fade-top")
                                    .background(Brush.verticalGradient(listOf(surface, Color.Transparent)))
                            )
                        }
                        if (lazyState?.canScrollForward == true) {
                            Box(
                                Modifier.fillMaxWidth().height(24.dp).align(Alignment.BottomCenter)
                                    .testTag("tv-settings:fade-bottom")
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, surface)))
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
