/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
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

/** Optional visual metadata for the measured, non-lazy SyncPlay viewport only. */
private val LocalTvSettingsVisuals = staticCompositionLocalOf<TvSettingsVisuals?> { null }

private data class TvSettingsMeasuredBounds(val bounds: Rect, val scrollOffset: Int) {
    // Bring-into-view can ask again before the next placement callback. Account for that live
    // scroll delta instead of using a rectangle from the previous frame as the current viewport.
    fun atScroll(offset: Int): Rect = bounds.translate(0f, (scrollOffset - offset).toFloat())
}

private class TvSettingsVisualItem(val owner: TvSettingsVisuals, val protectWholeItem: Boolean) {
    var bounds by mutableStateOf<TvSettingsMeasuredBounds?>(null)
    val content = mutableStateMapOf<Any, TvSettingsMeasuredBounds?>()
}

private class TvSettingsVisuals(val scroll: ScrollState) {
    var viewport by mutableStateOf<Rect?>(null)
    val items = mutableStateMapOf<TvSettingsVisualItem, Unit>()

    fun measure(coordinates: LayoutCoordinates): TvSettingsMeasuredBounds? {
        if (!coordinates.isAttached) return null
        // Clipped bounds would incorrectly report a row crossing the fold as wholly visible.
        val bounds = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = false)
        if (bounds.isEmpty || !listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all { it.isFinite() }) return null
        return TvSettingsMeasuredBounds(bounds, scroll.value)
    }

    fun bottomFadeExtent(fullExtent: Float): Float {
        val view = viewport ?: return fullExtent
        if (items.isEmpty()) return fullExtent
        val offset = scroll.value
        val visible = items.keys.map { item ->
            val bounds = item.bounds?.atScroll(offset) ?: return fullExtent
            item to bounds
        }.filter { (_, bounds) -> bounds.overlaps(view) }
        if (visible.isEmpty()) return fullExtent
        val lastBottom = visible.maxOf { it.second.bottom }
        val lastItems = visible.filter { it.second.bottom >= lastBottom - .5f }
        if (lastItems.any { (_, bounds) -> bounds.top < view.top - .5f || bounds.bottom > view.bottom + .5f }) return fullExtent

        val protectedBottom = visible.maxOf { (item, bounds) ->
            if (item.protectWholeItem) {
                bounds.bottom
            } else {
                if (item.content.isEmpty()) return fullExtent
                item.content.values.maxOf { measured ->
                    (measured ?: return fullExtent).atScroll(offset).bottom
                }
            }
        }
        // The complete native Text boxes (and passive icons/indicators) stay above the ramp.
        // One physical pixel of guard also keeps rasterized edge strokes out of the first stop.
        return (view.bottom - protectedBottom - 1f).coerceIn(0f, fullExtent)
    }
}

@Composable
private fun rememberTvSettingsVisualItem(protectWholeItem: Boolean): TvSettingsVisualItem? {
    val owner = LocalTvSettingsVisuals.current ?: return null
    val item = remember(owner, protectWholeItem) { TvSettingsVisualItem(owner, protectWholeItem) }
    DisposableEffect(owner, item) {
        owner.items[item] = Unit
        onDispose { owner.items.remove(item) }
    }
    return item
}

private fun Modifier.tvSettingsItemBounds(item: TvSettingsVisualItem?): Modifier =
    if (item == null) this else onGloballyPositioned { item.bounds = item.owner.measure(it) }

@Composable
private fun Modifier.tvSettingsContentBounds(item: TvSettingsVisualItem?): Modifier {
    if (item == null) return this
    val token = remember(item) { Any() }
    DisposableEffect(item, token) {
        item.content[token] = null
        onDispose { item.content.remove(token) }
    }
    return onGloballyPositioned { item.content[token] = item.owner.measure(it) }
}

/** Marks an existing custom block conservatively; adds no layout, semantics or focus target. */
@Composable
internal fun Modifier.tvSettingsVisualBlock(): Modifier = tvSettingsItemBounds(rememberTvSettingsVisualItem(protectWholeItem = true))

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
    val visual = rememberTvSettingsVisualItem(protectWholeItem = false)
    Column(modifier.tvSettingsItemBounds(visual).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Text(
            title,
            Modifier.tvSettingsContentBounds(visual).semantics {
                heading()
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleSmall
        )
        supportingText?.let {
            Text(
                it,
                Modifier.tvSettingsContentBounds(visual),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
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
    val visual = rememberTvSettingsVisualItem(protectWholeItem = false)
    ListItem(
        selected, onClick,
        // ListItem adds 12dp on each vertical edge. A centred 32dp minimum
        // headline gives a 56dp one-line row even when the font's glyph box is
        // shorter than its nominal line height, without top-aligning the text.
        headlineContent = {
            Box(Modifier.heightIn(min = if (supportingText == null) 32.dp else 0.dp), contentAlignment = Alignment.Center) {
                Text(
                    title,
                    Modifier.testTag("tv-settings:label:$title").tvSettingsContentBounds(visual),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        supportingContent = supportingText?.let { text ->
            { Text(text, Modifier.tvSettingsContentBounds(visual), color = supportingColor, style = MaterialTheme.typography.bodyMedium) }
        },
        leadingContent = leading?.let { content ->
            { if (visual == null) content() else Box(Modifier.tvSettingsContentBounds(visual)) { content() } }
        },
        trailingContent = trailing?.let { content ->
            {
                CompositionLocalProvider(LocalContentColor provides supportingColor) {
                    if (visual == null) content() else Box(Modifier.tvSettingsContentBounds(visual)) { content() }
                }
            }
        },
        modifier = modifier.tvSettingsItemBounds(visual).fillMaxWidth(),
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
    pinnedNotice: (@Composable () -> Unit)? = null,
    bodyScrollState: ScrollState? = null,
    footerDivider: Boolean = false,
    contentFocusRequestAllowed: () -> Boolean = { true },
    content: @Composable ColumnScope.() -> Unit
) {
    val latestContentFocusRequestAllowed by rememberUpdatedState(contentFocusRequestAllowed)
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
                pinnedNotice?.let { Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) { it() } }
                pinnedEditor?.let {
                    Box(
                        Modifier.fillMaxWidth().padding(
                            start = 12.dp,
                            end = 12.dp,
                            top = if (pinnedNotice ==
                                null
                            ) {
                                8.dp
                            } else {
                                0.dp
                            },
                            bottom = 8.dp
                        )
                    ) { it() }
                }
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
                        TvSettingsList(
                            Modifier.weight(1f),
                            minimalFocusScroll = minimalFocusScroll,
                            scrollState = bodyScrollState,
                            content = content
                        )
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
                        Box(Modifier.fillMaxWidth().testTag("tv-settings:footer")) {
                            Box(
                                Modifier.fillMaxWidth().padding(
                                    start = footerHorizontalPadding,
                                    end = footerHorizontalPadding,
                                    top = 12.dp,
                                    bottom = footerBottomPadding
                                )
                            ) { it() }
                            if (footerDivider) {
                                Box(
                                    Modifier.align(
                                        Alignment.TopCenter
                                    ).fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.borderVariant)
                                )
                            }
                        }
                    }
                }
            }
        }
        LaunchedEffect(firstFocus, contentFocusKey) {
            withFrameNanos { }
            if (latestContentFocusRequestAllowed()) firstFocus.requestFocus()
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
    scrollState: ScrollState? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val rememberedScroll = rememberScrollState()
    val scroll = scrollState ?: rememberedScroll
    val surface = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current
    val topClearance = with(density) { 40.dp.toPx() }
    val fullBottomFade = with(density) { 40.dp.toPx() }
    val edgeGap = with(density) { 8.dp.toPx() }
    val visuals = remember(scroll, minimalFocusScroll) { if (minimalFocusScroll) TvSettingsVisuals(scroll) else null }
    val bottomFadeExtent: () -> Float = remember(scroll, visuals, fullBottomFade) {
        { if (scroll.canScrollForward) visuals?.bottomFadeExtent(fullBottomFade) ?: fullBottomFade else 0f }
    }
    val platformScroll = LocalBringIntoViewSpec.current
    val minimalScroll = remember(scroll, topClearance, bottomFadeExtent, edgeGap) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                // The fade masks are part of the viewport. Read their live boundary states here,
                // where bring-into-view recalculates during scrolling, not in composition.
                val top = (if (scroll.canScrollBackward) topClearance else 0f).coerceAtMost(containerSize / 3f)
                val fade = bottomFadeExtent()
                val bottom = containerSize - (if (fade > 0f) fade + edgeGap else 0f).coerceAtMost(containerSize / 3f)
                return when {
                    size > bottom - top -> 0f
                    offset < top -> offset - top
                    offset + size > bottom -> offset + size - bottom
                    else -> 0f
                }
            }
        }
    }
    val bottomFade = bottomFadeExtent()
    Box(
        modifier.fillMaxWidth().testTag("tv-settings:viewport").then(
            if (visuals == null) Modifier else Modifier.onGloballyPositioned { visuals.viewport = visuals.measure(it)?.bounds }
        )
    ) {
        CompositionLocalProvider(
            LocalBringIntoViewSpec provides if (minimalFocusScroll) minimalScroll else platformScroll,
            LocalTvSettingsVisuals provides visuals
        ) {
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
                    .background(
                        if (minimalFocusScroll) {
                            Brush.verticalGradient(0f to surface, .25f to surface, 1f to Color.Transparent)
                        } else {
                            Brush.verticalGradient(listOf(surface, Color.Transparent))
                        }
                    )
            )
            if (minimalFocusScroll) {
                Box(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.borderVariant)
                )
            }
        }
        if (bottomFade > 0f) {
            val opaqueStart = ((bottomFade - edgeGap) / bottomFade).coerceIn(0f, 1f)
            Box(
                Modifier.fillMaxWidth().height(
                    with(density) {
                        bottomFade.toDp()
                    }
                ).align(Alignment.BottomCenter).testTag("tv-settings:fade-bottom")
                    .background(
                        if (minimalFocusScroll) {
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                opaqueStart to surface,
                                1f to surface
                            )
                        } else {
                            Brush.verticalGradient(listOf(Color.Transparent, surface))
                        }
                    )
            )
        }
        if (scroll.canScrollBackward || scroll.canScrollForward) {
            val indicator = MaterialTheme.colorScheme.onSurfaceVariant
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 4.dp, top = 8.dp, bottom = 8.dp)) {
                Canvas(Modifier.width(4.dp).fillMaxHeight().testTag("tv-settings:scroll-indicator")) {
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

/** Neutral loading footprint: same two text styles, padding and minimum as a native two-line row. */
@Composable
internal fun TvSettingsRowPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier.tvSettingsVisualBlock().fillMaxWidth().heightIn(min = 64.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Loading",
            Modifier.clearAndSetSemantics {
            },
            color = Color.Transparent,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1
        )
        Text(
            "Loading",
            Modifier.clearAndSetSemantics {
            },
            color = Color.Transparent,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1
        )
    }
}
