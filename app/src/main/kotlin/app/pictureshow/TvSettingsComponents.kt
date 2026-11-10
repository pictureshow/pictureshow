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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick as semanticClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.requestFocus as semanticRequestFocus
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.RadioButtonDefaults
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
        modifier = modifier,
        checked = checked,
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
    navigation: Boolean = false,
    summary: String? = null,
    onClick: () -> Unit
) {
    TvSettingsRow(
        title,
        supportingText ?: summary,
        modifier,
        enabled = enabled,
        onClick = onClick,
        trailing = if (navigation) ({ TvSettingsChevron() }) else null
    )
}

/** Starts on the same 16dp inner text edge as a settings row. */
@Composable
internal fun TvSettingsSectionHeader(title: String, supportingText: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Text(
            title,
            Modifier.semantics {
                heading()
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleSmall
        )
        supportingText?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
    }
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
    optionSupportingText: ((T) -> String?)? = null,
    onSelect: (T) -> Unit
) {
    require(values.isNotEmpty())
    val opener = remember { FocusRequester() }
    var picker by remember { mutableStateOf(false) }
    var restoreOpener by remember { mutableStateOf(false) }
    TvSettingsRow(
        title,
        supportingText,
        modifier = modifier.focusRequester(opener).onPreviewKeyEvent { event ->
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
        valueLabel = label(value),
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
        }, supportingText = optionSupportingText)
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
                trailing = {
                    RadioButton(
                        option == value,
                        onClick = null,
                        modifier = Modifier.testTag("tv-settings:radio:$index").clearAndSetSemantics {},
                        colors = RadioButtonDefaults.colors(
                            selectedColor = LocalContentColor.current,
                            unselectedColor = LocalContentColor.current
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun TvSettingsRow(
    title: String,
    supportingText: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean? = null,
    checked: Boolean? = null,
    valueLabel: String? = null,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    val rowFocus = remember { FocusRequester() }
    val supportingColor = if (isFocused) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurfaceVariant
    // ListItem's selectable Surface always adds Selected, including when false.
    // Only a radio option is selectable: switches must expose their real checked
    // state and ordinary choices/actions must never become unchecked checkboxes.
    val rowModifier = if (selected == null) {
        modifier.focusRequester(rowFocus).clearAndSetSemantics {
            this[SemanticsProperties.Text] = listOfNotNull(title, valueLabel, supportingText).map { AnnotatedString(it) }
            focused = isFocused
            role = if (checked != null) Role.Switch else Role.Button
            if (checked != null) {
                toggleableState = ToggleableState(checked)
                stateDescription = if (checked) "On" else "Off"
            } else if (valueLabel != null) {
                stateDescription = valueLabel
            }
            if (enabled) {
                semanticClick {
                    onClick()
                    true
                }
            } else {
                disabled()
            }
            semanticRequestFocus {
                rowFocus.requestFocus()
                true
            }
        }
    } else {
        modifier
    }
    ListItem(
        selected ?: false, onClick,
        // Grow the inner content, rather than stretching a 48dp ListItem surface
        // to 56dp while leaving its text at the top of that extra eight pixels.
        headlineContent = {
            Text(
                title,
                Modifier.padding(
                    vertical = if (supportingText ==
                        null
                    ) {
                        4.dp
                    } else {
                        0.dp
                    }
                ).testTag("tv-settings:label:$title"),
                style = MaterialTheme.typography.bodyLarge
            )
        },
        supportingContent = supportingText?.let { text ->
            { Text(text, color = supportingColor, style = MaterialTheme.typography.bodyMedium) }
        },
        trailingContent = trailing?.let { content ->
            { androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides supportingColor) { content() } }
        },
        modifier = rowModifier.fillMaxWidth(),
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
                    Modifier.padding(horizontal = 28.dp, vertical = 20.dp).semantics {
                        heading()
                    },
                    style = MaterialTheme.typography.headlineSmall
                )
                preview?.let { Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp)) { it() } }
                TvSettingsList(Modifier.weight(1f), content)
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
                Modifier.fillMaxWidth().height(32.dp).align(Alignment.TopCenter).testTag("tv-settings:fade-top")
                    .background(Brush.verticalGradient(listOf(surface, Color.Transparent)))
            )
        }
        if (scroll.canScrollForward) {
            Box(
                Modifier.fillMaxWidth().height(40.dp).align(Alignment.BottomCenter).testTag("tv-settings:fade-bottom")
                    .background(Brush.verticalGradient(listOf(Color.Transparent, surface)))
            )
        }
        if (scroll.canScrollBackward || scroll.canScrollForward) {
            val indicator = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f)
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 4.dp, top = 8.dp, bottom = 8.dp)) {
                Canvas(Modifier.width(2.dp).fillMaxHeight().testTag("tv-settings:scroll-indicator")) {
                    val viewport = size.height
                    val thumb = (viewport * viewport / (viewport + scroll.maxValue)).coerceAtLeast(32.dp.toPx()).coerceAtMost(viewport)
                    val top = if (scroll.maxValue > 0) (viewport - thumb) * scroll.value / scroll.maxValue else 0f
                    drawRoundRect(
                        indicator,
                        topLeft = Offset(0f, top),
                        size = androidx.compose.ui.geometry.Size(size.width, thumb),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                            size.width / 2
                        )
                    )
                }
            }
        }
    }
}
