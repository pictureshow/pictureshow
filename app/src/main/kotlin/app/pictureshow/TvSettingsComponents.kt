/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.ui.window.DialogWindowProvider
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
internal fun TvSettingsSectionHeader(title: String, modifier: Modifier = Modifier, supportingText: String? = null) {
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
    pickerTitle: String = title,
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
        TvSettingsChoicePicker(pickerTitle, value, values, label, onSelect, onClose = {
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
    TvSettingsRow(title, supportingText, modifier, enabled = enabled, valueLabel = value, onClick = onClick, trailing = {
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
internal fun TvSettingsRow(
    title: String,
    supportingText: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean? = null,
    checked: Boolean? = null,
    valueLabel: String? = null,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    readOnly: Boolean = false,
    titleMaxLines: Int = Int.MAX_VALUE,
    leading: (@Composable () -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    val nativeFocus = Modifier.onFocusChanged { isFocused = it.isFocused }
    val rowFocus = remember { FocusRequester() }
    val supportingColor = if (isFocused) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurfaceVariant
    // ListItem's selectable Surface always adds Selected, including when false.
    // Only a radio option is selectable: switches must expose their real checked
    // state and ordinary choices/actions must never become unchecked checkboxes.
    val accessibilityModifier = Modifier.clearAndSetSemantics {
        this[SemanticsProperties.Text] = listOfNotNull(title, valueLabel, supportingText).map { AnnotatedString(it) }
        focused = isFocused
        if (!readOnly) role = if (checked != null) Role.Switch else Role.Button
        if (!readOnly && checked != null) {
            toggleableState = ToggleableState(checked)
            stateDescription = if (checked) "On" else "Off"
        } else if (!readOnly && valueLabel != null) {
            stateDescription = valueLabel
        }
        if (readOnly) {
            // A remote may focus and read this fact; Select is intentionally not an action.
        } else if (enabled) {
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
    // Clearing semantics only hides descendant LayoutNodes. ListItem prepends
    // Selected to its own node, so the truthful non-radio semantics must live on
    // a separate parent. Native key handling and the single focus target remain
    // on the child ListItem, including focusable disabled rows.
    if (selected == null) {
        Box(modifier.then(accessibilityModifier).fillMaxWidth()) {
            TvSettingsNativeRow(
                title, supportingText,
                Modifier.focusRequester(rowFocus).then(nativeFocus),
                enabled, false, supportingColor, interaction, if (readOnly) ({}) else onClick, trailing, leading, titleMaxLines
            )
        }
    } else {
        TvSettingsNativeRow(
            title, supportingText,
            modifier.then(
                nativeFocus
            ),
            enabled, selected, supportingColor, interaction, onClick, trailing, leading, titleMaxLines
        )
    }
}

@Composable
private fun TvSettingsNativeRow(
    title: String,
    supportingText: String?,
    modifier: Modifier,
    enabled: Boolean,
    selected: Boolean,
    supportingColor: Color,
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)?,
    leading: (@Composable () -> Unit)?,
    titleMaxLines: Int
) {
    ListItem(
        selected, onClick,
        // ListItem adds 12dp on each vertical edge. A centred 32dp minimum
        // headline gives a 56dp one-line row even when the font's glyph box is
        // shorter than its nominal line height, without top-aligning the text.
        headlineContent = {
            Box(Modifier.heightIn(min = if (supportingText == null) 32.dp else 0.dp), contentAlignment = Alignment.Center) {
                Text(
                    title,
                    Modifier.testTag("tv-settings:label:$title"),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        supportingContent = supportingText?.let { text ->
            { Text(text, color = supportingColor, style = MaterialTheme.typography.bodyMedium) }
        },
        leadingContent = leading?.let { content -> { content() } },
        trailingContent = trailing?.let { content ->
            { androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides supportingColor) { content() } }
        },
        modifier = modifier.fillMaxWidth(),
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
@OptIn(ExperimentalFoundationApi::class)
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
    contentFocusKey: Any? = Unit,
    panelWidth: androidx.compose.ui.unit.Dp = 420.dp,
    footerHorizontalPadding: androidx.compose.ui.unit.Dp = 28.dp,
    minimalFocusScroll: Boolean = false,
    repairLostContentFocus: Boolean = false,
    pinnedEditor: (@Composable () -> Unit)? = null,
    footerBottomPadding: androidx.compose.ui.unit.Dp = 12.dp,
    edgeToEdge: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var contentHasFocus by remember { mutableStateOf(false) }
    var hadContentFocus by remember { mutableStateOf(false) }
    val focusRepair = if (repairLostContentFocus) {
        Modifier.onFocusChanged {
            contentHasFocus = it.hasFocus
            if (it.hasFocus) hadContentFocus = true
        }.focusGroup()
    } else {
        Modifier
    }
    BackHandler(onBack = onClose)
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            windowTitle = title,
            decorFitsSystemWindows = !edgeToEdge
        )
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        // Compose chooses ADJUST_NOTHING for edge-to-edge dialogs on31+. Match it on older TV
        // windows so the fixed title/editor are not resized or panned behind a floating keyboard.
        val legacyFixedEditor = edgeToEdge && Build.VERSION.SDK_INT <= 30
        DisposableEffect(window, legacyFixedEditor) {
            val original = window?.attributes?.softInputMode
            onDispose { if (legacyFixedEditor && original != null) window?.setSoftInputMode(original) }
        }
        SideEffect {
            if (legacyFixedEditor && window?.attributes?.softInputMode != WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING) {
                window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            }
        }
        Box(
            Modifier.fillMaxSize().then(
                if (edgeToEdge) {
                    Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
                    )
                } else {
                    Modifier
                }
            ),
            contentAlignment = Alignment.CenterEnd
        ) {
            Column(
                modifier.then(focusRepair).widthIn(max = panelWidth).fillMaxWidth().fillMaxHeight()
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
                pinnedEditor?.let { Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) { it() } }
                Column(
                    Modifier.weight(1f).then(
                        if (edgeToEdge) {
                            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).imePadding()
                        } else {
                            Modifier
                        }
                    )
                ) {
                    if (scrollContent) {
                        TvSettingsList(Modifier.weight(1f), minimalFocusScroll = minimalFocusScroll, content = content)
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
                    footer?.let {
                        Box(
                            Modifier.fillMaxWidth().padding(
                                start = footerHorizontalPadding,
                                end = footerHorizontalPadding,
                                top = 12.dp,
                                bottom = footerBottomPadding
                            )
                        ) { it() }
                    }
                }
            }
        }
        LaunchedEffect(firstFocus, contentFocusKey) {
            withFrameNanos { }
            firstFocus.requestFocus()
        }
        LaunchedEffect(contentHasFocus) {
            if (repairLostContentFocus && hadContentFocus && !contentHasFocus) {
                // Allow an explicit queue-removal neighbour request to settle first.
                withFrameNanos { }
                withFrameNanos { }
                if (!contentHasFocus) firstFocus.requestFocus()
            }
        }
    }
}

/** The main TV page and its panels use identical row spacing and scroll affordances. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvSettingsList(
    modifier: Modifier = Modifier,
    minimalFocusScroll: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val scroll = rememberScrollState()
    val surface = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current
    val topClearance = with(density) { 40.dp.toPx() }
    val bottomClearance = with(density) { 48.dp.toPx() }
    val platformScroll = LocalBringIntoViewSpec.current
    val minimalScroll = remember(scroll, topClearance, bottomClearance) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                // The fade masks are part of the viewport. Read their live boundary states here,
                // where bring-into-view recalculates during scrolling, not in composition.
                val top = (if (scroll.canScrollBackward) topClearance else 0f).coerceAtMost(containerSize / 3f)
                val bottom = containerSize - (if (scroll.canScrollForward) bottomClearance else 0f).coerceAtMost(containerSize / 3f)
                return when {
                    size > bottom - top && offset <= top && offset + size >= bottom -> 0f
                    offset < top -> offset - top
                    offset + size > bottom -> offset + size - bottom
                    else -> 0f
                }
            }
        }
    }
    Box(modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides if (minimalFocusScroll) minimalScroll else platformScroll) {
            Column(
                Modifier.revealFocusAfterFontChange(scroll).fillMaxSize().verticalScroll(scroll)
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = if (minimalFocusScroll) 48.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content
            )
        }
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
