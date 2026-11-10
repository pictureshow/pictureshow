/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.Account
import app.pictureshow.core.GRID_HOLD_CAP_MILLIS
import app.pictureshow.core.GridSorts
import app.pictureshow.core.GridVerticalMove
import app.pictureshow.core.MediaItem
import app.pictureshow.core.UNBOUNDED_TITLE_FONT_SCALE
import app.pictureshow.core.episodeLabel
import app.pictureshow.core.gridHoldRelease
import app.pictureshow.core.gridStripDetail
import app.pictureshow.core.gridVerticalMove
import app.pictureshow.core.imageUrl
import app.pictureshow.core.isEmptyContainer
import app.pictureshow.core.isWatched
import app.pictureshow.core.progress
import app.pictureshow.core.resumeLabel
import app.pictureshow.core.runtimeLabel
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val CatalogBackground = Color(0xFF10131C)
private val CatalogMuted = Color(0xFFB9C1D6)
internal val SelectedSeasonOutline = Color(0xFFB8C4FF)

/** Each navigation entry owns its saveable focus identity and lazy-list scroll positions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CatalogHome(
    state: CatalogHomeState,
    account: Account,
    onOpen: (MediaItem) -> Unit,
    onRetry: () -> Unit,
    onSwitchUser: () -> Unit,
    onSignOut: () -> Unit,
    onResume: () -> Unit = {},
    onSearch: () -> Unit = {},
    onSettings: () -> Unit = {},
    onCustomize: (() -> Unit)? = null,
    serverLabel: String = account.server.name
) {
    val homeLifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // Navigation supplies this destination's lifecycle, so Back refreshes without restarting the Activity.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onResume() }
    val rows = state.rows.filter { it.items.isNotEmpty() }
    val list = rememberLazyListState()
    var selectedRow by rememberSaveable { mutableStateOf("") }
    var selectedItem by rememberSaveable { mutableStateOf("") }
    var selectedRowIndex by rememberSaveable { mutableStateOf(0) }
    var rowSelections by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    var rowPositions by rememberSaveable { mutableStateOf(mapOf<String, Int>()) }
    var restored by remember { mutableStateOf(false) }
    val search = remember { FocusRequester() }
    var initialNavigation by remember { mutableStateOf(false) }
    val returning = remember {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "CatalogFocus",
                "home-entry saved=${selectedRow.isNotEmpty()} " +
                    "header=${selectedRow.takeIf { it in listOf("search", "settings", "customize", "profile") } ?: "card"} " +
                    "rowIndex=$selectedRowIndex rows=${rows.size} loading=${state.loading}"
            )
        }
        selectedRow.isNotEmpty()
    }
    val restoreRowIndex = rows.indexOfFirst { it.key == selectedRow }.takeIf { it >= 0 }
        ?: selectedRowIndex.coerceIn(0, rows.lastIndex.coerceAtLeast(0))
    val restoreRow = rows.getOrNull(restoreRowIndex)
    val restoreItem = restoreRow?.items?.let { items ->
        items.firstOrNull { it.id == rowSelections[restoreRow.key] }
            ?: items[(rowPositions[restoreRow.key] ?: 0).coerceIn(0, items.lastIndex)]
    }
    val restoreAction = when {
        state.busy && selectedRow in listOf("settings", "profile") -> "search"
        selectedRow in listOf("search", "settings", "customize", "profile") -> selectedRow
        rows.isEmpty() && !state.loading -> if (state.error != null) "retry" else "profile"
        else -> null
    }
    val restoreTarget = when (restoreAction) {
        "search" -> "Search"
        "settings" -> "Settings"
        "customize" -> "Customize home"
        "profile" -> "Switch user"
        "retry" -> "Retry"
        else -> restoreItem?.let(::catalogAnnouncement)
    }
    var openerHasInput by remember { mutableStateOf(false) }
    val handoff = rememberCatalogFocusHandoff(returning, restoreTarget, restored && openerHasInput, initialNavigation)
    fun target(modifier: Modifier, matches: Boolean) = modifier.catalogFocusTarget(
        exposed = !handoff || matches,
        inputReady = { !returning || (restored && !handoff) || initialNavigation || matches }
    ).onFocusChanged {
        if (matches) {
            openerHasInput = it.isFocused
            if (BuildConfig.DEBUG && returning) {
                android.util.Log.d("CatalogFocus", "home-opener-focus focused=${it.isFocused} restored=$restored viewer=$initialNavigation")
            }
            if (returning && it.isFocused && homeLifecycle == Lifecycle.State.RESUMED) restored = true
        }
    }
    fun select(rowIndex: Int, itemIndex: Int) {
        val row = rows[rowIndex]
        val item = row.items[itemIndex]
        selectedRow = row.key
        selectedRowIndex = rowIndex
        selectedItem = item.id
        rowSelections = rowSelections + (row.key to item.id)
        rowPositions = rowPositions + (row.key to itemIndex)
    }
    val profile = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val customizeFocus = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var movement by remember { mutableStateOf<Job?>(null) }
    val rowStates = mutableMapOf<String, LazyListState>()
    val requesters = mutableMapOf<String, FocusRequester>()
    rows.forEach { row ->
        key(row.key) {
            rowStates[row.key] = rememberLazyListState()
            row.items.forEach { item ->
                key(item.id) { requesters["${row.key}:${item.id}"] = remember { FocusRequester() } }
            }
        }
    }
    // Capture the intended selection before applying row changes. Automatic focus fallback can update the
    // saveable state while a removed card detaches, before the restoration coroutine reaches its next frame.
    val savedRowSelections = rowSelections
    val savedRowPositions = rowPositions
    val savedRow = selectedRow
    val savedItem = selectedItem
    val savedRowIndex = selectedRowIndex
    suspend fun focusRow(
        rowIndex: Int,
        selections: Map<String, String> = rowSelections,
        positions: Map<String, Int> = rowPositions
    ): Boolean {
        if (rowIndex !in rows.indices) return false
        val row = rows[rowIndex]
        val itemIndex = row.items.indexOfFirst { it.id == selections[row.key] }.takeIf { it >= 0 }
            ?: (positions[row.key] ?: 0).coerceIn(0, row.items.lastIndex)
        val horizontal = rowStates.getValue(row.key)
        if (rowIndex == 0) {
            // The header belongs with the first shelf, including when returning from a lower shelf.
            list.jumpTo(0)
        } else if (list.layoutInfo.visibleItemsInfo.none { it.index == rowIndex + 1 }) {
            list.jumpTo(rowIndex + 1)
        }
        val focused = withTimeoutOrNull(1_500) {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.key == row.key } }.first { it }
            if (horizontal.layoutInfo.visibleItemsInfo.none { it.index == itemIndex }) horizontal.jumpTo(itemIndex)
            snapshotFlow { horizontal.layoutInfo.visibleItemsInfo.any { it.index == itemIndex } }.first { it }
            while (true) {
                withFrameNanos { }
                if (withContext(Dispatchers.Main.immediate) {
                        requesters.getValue("${row.key}:${row.items[itemIndex].id}").requestFocus()
                    }
                ) {
                    return@withTimeoutOrNull true
                }
            }
        } == true
        if (BuildConfig.DEBUG) {
            android.util.Log.d("CatalogFocus", "home-request rowIndex=$rowIndex itemIndex=$itemIndex success=$focused")
        }
        if (focused) select(rowIndex, itemIndex)
        return focused
    }
    suspend fun focusAction(key: String, index: Int, requester: FocusRequester): Boolean {
        list.jumpTo(index)
        return withTimeoutOrNull(1_500) {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.key == key } }.first { it }
            while (true) {
                withFrameNanos { }
                if (withContext(Dispatchers.Main.immediate) { requester.requestFocus() }) return@withTimeoutOrNull true
            }
        } == true
    }
    fun moveRow(index: Int) {
        movement?.cancel()
        movement = scope.launch {
            when {
                index in rows.indices -> focusRow(index)

                index >= rows.size -> {
                    focusAction("profile", rows.size + 1, profile)
                }

                else -> {
                    focusAction("header", 0, search)
                }
            }
        }
    }
    fun headerDown(event: androidx.compose.ui.input.key.KeyEvent): Boolean =
        if (event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown && rows.isNotEmpty()) {
            moveRow(0)
            true
        } else {
            false
        }
    // Back below the first shelf returns to the top of home, where it opened; Back at the top leaves the app.
    val belowTop =
        rows.isNotEmpty() && selectedRow.isNotEmpty() && selectedRow !in listOf("search", "settings", "customize", rows.first().key)
    val pendingReturn = returning && !restored
    var entryBackSwallowed by remember { mutableStateOf(false) }
    BackHandler(enabled = if (pendingReturn) !entryBackSwallowed else belowTop && homeLifecycle == Lifecycle.State.RESUMED) {
        if (BuildConfig.DEBUG) android.util.Log.d("CatalogFocus", "home-back lifecycle=$homeLifecycle pending=$pendingReturn")
        // One physical Back can finish after the destination it reveals has attached. Absorb
        // one dispatch during arrival; another Back can still exit if loading never completes.
        if (pendingReturn) entryBackSwallowed = true else moveRow(0)
    }
    val platformBringIntoView = LocalBringIntoViewSpec.current
    val firstRowFocused by rememberUpdatedState(rows.firstOrNull()?.key == selectedRow)
    val focusMargin = with(LocalDensity.current) { FocusedCardMargin.toPx() }
    val homeBringIntoView = remember(platformBringIntoView, focusMargin) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = focusedCardScrollDistance(
                offset,
                size,
                containerSize,
                focusMargin,
                // TV's default 30% pivot would hide Search even when the whole first card already fits.
                if (firstRowFocused && offset >= 0f) 0f else platformBringIntoView.calculateScrollDistance(offset, size, containerSize)
            )
        }
    }
    val reveal = Modifier.revealFocusAfterFontChange(list)
    CompositionLocalProvider(LocalBringIntoViewSpec provides homeBringIntoView) {
        CatalogLoadingFocus(returning && !restored) {
            initialNavigation = true
            restored = true
            selectedRow = "search"
            scope.launch { focusAction("header", 0, search) }
        }
        LazyColumn(
            state = list,
            modifier = reveal.fillMaxSize().background(CatalogBackground).testTag("home").accessibilityGroup().onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown) {
                    if (BuildConfig.DEBUG && returning) {
                        android.util.Log.d("CatalogFocus", "home-key key=${it.key} lifecycle=$homeLifecycle restored=$restored")
                    }
                    // The Back that reveals this destination is not a new catalog selection.
                    if (it.key != Key.Back && it.key != Key.Escape) {
                        initialNavigation = true
                        if (returning) restored = true
                    }
                }
                false
            },
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item(key = "header") {
                Column(modifier = Modifier.accessibilityGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Settings sits beside Search, one Up from where home opens. Sign out stays alone with
                    // Switch user at the bottom, away from the buttons viewers press often.
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = {
                                selectedRow = "search"
                                onSearch()
                            },
                            modifier = target(Modifier, restoreAction == "search").focusRequester(search).testTag("home:search")
                                .staysInRow(first = true, last = false)
                                .onFocusChanged {
                                    // The first attached target during the first load, like the profile actions below.
                                    if (it.isFocused && (restored || initialNavigation)) selectedRow = "search"
                                }
                                .onPreviewKeyEvent { headerDown(it) }
                        ) { Text("Search") }
                        Button(
                            onClick = {
                                selectedRow = "settings"
                                onSettings()
                            },
                            enabled = !state.busy,
                            modifier = target(Modifier, restoreAction == "settings").focusRequester(settingsFocus).testTag("home:settings")
                                .staysInRow(first = false, last = onCustomize == null)
                                .onFocusChanged { if (it.isFocused && (restored || initialNavigation)) selectedRow = "settings" }
                                .onPreviewKeyEvent { headerDown(it) }
                        ) { Text("Settings") }
                        if (onCustomize != null) {
                            Button(
                                onClick = {
                                    selectedRow = "customize"
                                    onCustomize()
                                },
                                modifier = target(Modifier, restoreAction == "customize").focusRequester(customizeFocus)
                                    .testTag("home:customize").staysInRow(first = false, last = true)
                                    .onFocusChanged { if (it.isFocused && (restored || initialNavigation)) selectedRow = "customize" }
                                    .onPreviewKeyEvent { headerDown(it) }
                            ) { Text("Customize home") }
                        }
                    }
                    Column(Modifier.catalogFocusTarget(!handoff), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CatalogHeading("Pictureshow")
                        Text("${account.user.name} · $serverLabel", color = CatalogMuted)
                    }
                    if (state.loading) Text("Loading your home…", modifier = Modifier.catalogFocusTarget(!handoff))
                    CatalogError(
                        state.error,
                        onRetry,
                        target(Modifier, restoreAction == "retry").focusRequester(retry).onPreviewKeyEvent {
                            if (it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown && rows.isNotEmpty()) {
                                moveRow(0)
                                true
                            } else {
                                false
                            }
                        },
                        announceMessage = !handoff
                    )
                    if (!state.loading && rows.isEmpty() && state.error == null) {
                        Text(
                            "There are no libraries or videos available for this user yet.",
                            modifier = Modifier.catalogFocusTarget(!handoff)
                        )
                    }
                }
            }
            rows.forEachIndexed { rowIndex, row ->
                item(key = row.key) {
                    Column(modifier = Modifier.accessibilityGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.catalogFocusTarget(!handoff)) { CatalogHeading(row.title, large = false) }
                        // Only the outer vertical list changes its pivot; shelves retain platform scrolling.
                        CompositionLocalProvider(LocalBringIntoViewSpec provides platformBringIntoView) {
                            ShelfWithEnds(row.title, rowStates.getValue(row.key), row.items.lastIndex) { endCard, rowSemantics ->
                                LazyRow(
                                    state = rowStates.getValue(row.key),
                                    // Explicit stable-ID restoration in focusRow owns shelf entry. A focusRestorer
                                    // here can intercept its request after lazy disposal and select another child.
                                    modifier = Modifier.fillMaxWidth().then(rowSemantics),
                                    // Reserve the 1.1x focus scale and centered border inside the shelf clip.
                                    contentPadding = PaddingValues(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                                ) {
                                    itemsIndexed(row.items, key = { _, item -> item.id }) { itemIndex, item ->
                                        endCard(itemIndex) { endModifier ->
                                            CatalogCard(
                                                item = item,
                                                account = account,
                                                wide = row.wide || row.library || item.type == "Episode",
                                                onClick = {
                                                    select(rowIndex, itemIndex)
                                                    onOpen(item)
                                                },
                                                modifier = target(
                                                    Modifier,
                                                    restoreAction == null && row.key == restoreRow?.key && item.id == restoreItem?.id
                                                ).focusRequester(requesters.getValue("${row.key}:${item.id}"))
                                                    .testTag("home:${row.key}:${item.id}")
                                                    .staysInRow(itemIndex == 0, itemIndex == row.items.lastIndex)
                                                    .then(endModifier)
                                                    .onFocusChanged {
                                                        if (it.isFocused && (restored || initialNavigation)) select(rowIndex, itemIndex)
                                                    }.onPreviewKeyEvent {
                                                        if (it.type != KeyEventType.KeyDown) {
                                                            false
                                                        } else if (it.key == Key.DirectionDown) {
                                                            moveRow(rowIndex + 1)
                                                            true
                                                        } else if (it.key == Key.DirectionUp) {
                                                            moveRow(rowIndex - 1)
                                                            true
                                                        } else {
                                                            false
                                                        }
                                                    }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "profile") {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = {
                            selectedRow = "profile"
                            onSwitchUser()
                        },
                        enabled = !state.busy,
                        modifier = target(Modifier, restoreAction == "profile").focusRequester(profile).testTag("home:switch-user")
                            .staysInRow(first = true, last = false)
                            .onFocusChanged {
                                // During the first load these are the only attached targets. Automatic focus is
                                // not a saved user choice and must not outrank the first arriving catalog card.
                                if (it.isFocused && BuildConfig.DEBUG) {
                                    val callers = Throwable().stackTrace.filter {
                                        it.className.startsWith("app.pictureshow") ||
                                            it.className.contains("Accessibility") ||
                                            it.className.contains("Focus")
                                    }.take(24).joinToString(" <- ") { "${it.className}.${it.methodName}" }
                                    android.util.Log.d(
                                        "CatalogFocus",
                                        "home-profile-focus restored=$restored viewer=$initialNavigation handoff=$handoff callers=$callers"
                                    )
                                }
                                if (it.isFocused && (restored || initialNavigation)) selectedRow = "profile"
                            }
                            .onPreviewKeyEvent {
                                if (it.key == Key.DirectionUp && it.type == KeyEventType.KeyDown && rows.isNotEmpty()) {
                                    moveRow(rows.lastIndex)
                                    true
                                } else {
                                    false
                                }
                            }
                    ) { Text("Switch user") }
                    Button(
                        onClick = onSignOut,
                        enabled = !state.busy,
                        modifier = target(Modifier, false).testTag("home:sign-out").staysInRow(first = false, last = true).onFocusChanged {
                            if (it.isFocused && (restored || initialNavigation)) selectedRow = "profile"
                        }.onPreviewKeyEvent {
                            if (it.key == Key.DirectionUp && it.type == KeyEventType.KeyDown && rows.isNotEmpty()) {
                                moveRow(rows.lastIndex)
                                true
                            } else {
                                false
                            }
                        }
                    ) { Text(if (state.busy) "Please wait…" else "Sign out") }
                }
            }
        }
    }
    LaunchedEffect(homeLifecycle, state.loading, state.busy, state.error, rows.map { it.key to it.items.map(MediaItem::id) }) {
        // Navigation can draw Home while its outgoing detail still owns the resumed destination.
        // Requesting focus then produces an accessibility event that TalkBack cannot use yet.
        if (homeLifecycle != Lifecycle.State.RESUMED) return@LaunchedEffect
        if (BuildConfig.DEBUG && returning) {
            android.util.Log.d(
                "CatalogFocus",
                "home-restore-effect loading=${state.loading} rows=${rows.size} restored=$restored openerInput=$openerHasInput"
            )
        }
        if ((!state.loading || restoreAction != null) && (!restored || rows.isNotEmpty() && savedRow.isEmpty() && savedItem.isEmpty())) {
            // Empty startup content can temporarily leave only profile actions attached. Keep the first
            // card intent pending until a real row arrives, unless the user deliberately chose profile.
            withFrameNanos { }
            val focused = when {
                restoreAction in listOf("search", "settings", "customize") -> {
                    focusAction(
                        "header",
                        0,
                        when (restoreAction) {
                            "settings" -> settingsFocus
                            "customize" -> customizeFocus
                            else -> search
                        }
                    )
                }

                restoreAction == "profile" -> {
                    if (!initialNavigation) focusAction("profile", rows.size + 1, profile) else false
                }

                rows.isNotEmpty() -> {
                    val rowIndex = rows.indexOfFirst { it.key == savedRow }.takeIf { it >= 0 }
                        ?: savedRowIndex.coerceIn(0, rows.lastIndex)
                    if (savedItem.isNotEmpty()) rowSelections = rowSelections + (savedRow to savedItem)
                    focusRow(rowIndex, savedRowSelections, savedRowPositions)
                }

                state.error != null -> focusAction("header", 0, retry)

                else -> focusAction("profile", rows.size + 1, profile)
            }
            restored = focused
            if (!focused && !initialNavigation) {
                // A disabled or detached opener must not leave the remote on the silent receiver.
                // Release both gates, then offer the always available Search action.
                selectedRow = "search"
                initialNavigation = true
                restored = true
                focusAction("header", 0, search)
            }
        } else if (!state.loading &&
            restored &&
            savedRow !in listOf("profile", "search", "settings", "customize") &&
            savedItem.isNotEmpty() &&
            rows.none { row -> row.key == savedRow && row.items.any { it.id == savedItem } }
        ) {
            // A watched item can leave Continue watching while this entry is being restored.
            // Focus an existing visible card; never request the removed card's detached target.
            if (rows.isNotEmpty()) {
                focusRow(savedRowIndex.coerceAtMost(rows.lastIndex), savedRowSelections, savedRowPositions)
            } else {
                focusAction("profile", rows.size + 1, profile)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun CatalogGrid(
    state: CatalogGridState,
    onOpen: (MediaItem) -> Unit,
    onNearEnd: (Int) -> Unit,
    onSort: (String, Boolean) -> Unit,
    onRetry: () -> Unit,
    showSort: Boolean = true,
    stripTitle: String? = state.parent.name,
    pinStrip: Boolean = false,
    head: (@Composable () -> Unit)? = null,
    headFocus: FocusRequester? = null,
    revealKey: Any? = null,
    grid: LazyGridState = rememberLazyGridState(),
    onFilters: (() -> Unit)? = null,
    onDisplayList: ((Boolean) -> Unit)? = null
) {
    var selectedId by rememberSaveable { mutableStateOf("") }
    var restored by remember { mutableStateOf(false) }
    var retryFocusRequest by remember { mutableIntStateOf(0) }
    val firstSort = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val requests = remember { mutableMapOf<String, FocusRequester>() }
    val scope = rememberCoroutineScope()
    var moving by remember { mutableStateOf<Job?>(null) }
    var movingDown by remember { mutableStateOf(false) }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    var headerFocused by remember { mutableStateOf(false) }
    val sortBottoms = remember { mutableMapOf<Int, Float>() }
    var focusedSort by remember { mutableIntStateOf(-1) }
    val sortRequests = remember { mutableMapOf<String, FocusRequester>() }
    // The card a Down is waiting on the next page from. Released by the page, a failure, an empty page or the cap; dropped
    // by a newer key, a sort change or focus moving.
    var heldDownFrom by remember { mutableIntStateOf(-1) }
    // The card a footer Retry was pressed from, until that retry has landed or failed.
    var retryingFrom by remember { mutableIntStateOf(-1) }
    val focusManager = LocalFocusManager.current
    val items by rememberUpdatedState(state.items)
    val loadingMore by rememberUpdatedState(state.loading)
    val pageFailed by rememberUpdatedState(state.error != null && state.items.isNotEmpty())
    val scrolled by remember { derivedStateOf { grid.firstVisibleItemIndex > 0 } }

    // There is never a second job to replace: a key cancels the last one first, and a hold only starts with none running.
    fun startMove(down: Boolean, block: suspend () -> Unit) {
        movingDown = down
        moving = scope.launch { block() }
    }

    // A newer press replaces whatever the last one left waiting: the viewer gets the press they just made.
    fun dropWaitingMoves() {
        moving?.cancel()
        heldDownFrom = -1
    }

    // The footer is the grid's last item, and may be further down than anything composed.
    fun focusPageRetry() {
        startMove(down = true) {
            val position = items.size + GRID_HEADER_ITEMS
            val layout = grid.layoutInfo
            if (layout.visibleItemsInfo.none { it.index == position }) {
                grid.requestScrollToItem(position, -(layout.viewportSize.height * GRID_ARRIVAL_FRACTION).toInt())
            }
            requestAttached(requests, GRID_PAGE_RETRY)
        }
    }

    // The header is the grid's item 0, so a card's grid index is its position in the list plus one.
    fun focusCard(index: Int, direction: FocusDirection): Boolean {
        val item = items.getOrNull(index) ?: return false
        val position = index + GRID_HEADER_ITEMS
        val layout = grid.layoutInfo
        val visible = layout.visibleItemsInfo.any { it.index == position }
        if (visible) {
            // No frame to wait for, and a held key repeats faster than a frame can be spared.
            requests[item.id]?.let { return it.requestFocus() }
        }
        startMove(direction == FocusDirection.Down) {
            // A row that isn't composed can't be a focus target. The bring-into-view spec settles the rest. Requested
            // rather than scrollToItem, which a bring-into-view still running would cancel, losing the move.
            if (!visible) grid.requestScrollToItem(position, -(layout.viewportSize.height * GRID_ARRIVAL_FRACTION).toInt())
            landOnCard(
                card = item.id,
                holdingThePlaceNow = { items.getOrNull(index)?.id },
                request = { requestAttached(requests, it) },
                lookAround = { focusManager.moveFocus(direction) }
            )
        }
        return true
    }

    // Up from the first row lands on what's selected, wherever the card was.
    fun focusSelectedSort(): Boolean {
        // A failed first load has no cards to come from, and a failed later page shows after the last card. A refresh error's
        // Retry is in the header, next above the first row, and is what the viewer has to act on.
        if (state.refreshError != null) return false
        if (!showSort) {
            val above = headFocus ?: return false
            startMove(down = false) {
                if (grid.layoutInfo.visibleItemsInfo.none { it.index == 0 }) grid.requestScrollToItem(0)
                if (!awaitCatalogFocus(above)) focusManager.moveFocus(FocusDirection.Up)
            }
            return true
        }
        startMove(down = false) {
            if (grid.layoutInfo.visibleItemsInfo.none { it.index == 0 }) grid.requestScrollToItem(0)
            if (!requestAttached(sortRequests, state.sortBy)) focusManager.moveFocus(FocusDirection.Up)
        }
        return true
    }

    /**
     * Compose's focus search only sees composed cards. The row below a focused card usually isn't composed when the card
     * fills the viewport, and the grid composes one more item at a time to look for a target, so the first card of the
     * next row won: Down from column 4 landed in column 1. Moving by index holds the column whatever is composed.
     */
    fun moveVertically(index: Int, down: Boolean): Boolean {
        // A repeat of the key being carried out is part of that move. The other direction replaces it.
        if (moving?.isActive == true && movingDown == down) return true
        moving?.cancel()
        if (!down) heldDownFrom = -1
        return when (val move = gridVerticalMove(index, grid.layoutInfo.maxSpan, items.size, down, loadingMore, pageFailed)) {
            is GridVerticalMove.Card -> focusCard(move.index, if (down) FocusDirection.Down else FocusDirection.Up)

            // The page on its way lands in this column if focus is still here. A repeat doesn't restart the cap.
            GridVerticalMove.Hold -> {
                heldDownFrom = index
                true
            }

            GridVerticalMove.Stay -> true

            GridVerticalMove.Above -> focusSelectedSort()

            GridVerticalMove.PageRetry -> {
                focusPageRetry()
                true
            }
        }
    }

    // A hold ends in a plain Down on what's loaded by then, whichever way it ended.
    fun releaseHold() {
        val from = heldDownFrom
        if (from < 0) return
        heldDownFrom = -1
        when (val landing = gridHoldRelease(from, grid.layoutInfo.maxSpan, items.size, pageFailed)) {
            is GridVerticalMove.Card -> focusCard(landing.index, FocusDirection.Down)
            GridVerticalMove.PageRetry -> focusPageRetry()
            else -> Unit
        }
    }

    // A retry that fails again leaves focus on Retry. One that succeeds is a plain Down from the card the viewer left, or a
    // return to it if nothing new came. The footer stays until focus has left it, so focus never falls out of the grid.
    fun settlePageRetry() {
        val from = retryingFrom
        if (from < 0) return
        if (state.error != null) {
            retryingFrom = -1
            return
        }
        val landing = gridHoldRelease(from, grid.layoutInfo.maxSpan, items.size) as? GridVerticalMove.Card
        focusCard(landing?.index ?: from, FocusDirection.Down)
        scope.launch {
            moving?.join()
            retryingFrom = -1
        }
    }

    val platformBringIntoView = LocalBringIntoViewSpec.current
    val focusMargin = with(LocalDensity.current) { FocusedCardMargin.toPx() }
    val stripInset = with(LocalDensity.current) { ContextStripHeight.toPx() }
    val gridBringIntoView = remember(platformBringIntoView, focusMargin, stripInset) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = focusedCardScrollDistance(
                offset,
                size,
                containerSize,
                focusMargin,
                platformBringIntoView.calculateScrollDistance(offset, size, containerSize),
                // The first row and the sort controls stay with the header while everything fits.
                scrollToTop = grid.firstVisibleItemScrollOffset.toFloat().takeIf {
                    (headerFocused || focusedIndex in 0 until grid.layoutInfo.maxSpan) && grid.firstVisibleItemIndex == 0
                },
                topInset = stripInset
            )
        }
    }
    // Grid headings/card focus provide context. A navigation destination is not a pane:
    // late pane events from this departing grid would speak its title over detail focus.
    val reveal = Modifier.revealFocusAfterFontChange(grid, ContextStripHeight, revealKey)
    Box(modifier = Modifier.fillMaxSize().background(CatalogBackground).testTag("grid").accessibilityGroup()) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides gridBringIntoView) {
            LazyVerticalGrid(
                columns = if (state.list) GridCells.Fixed(1) else GridCells.Adaptive(144.dp),
                state = grid,
                modifier = reveal.fillMaxSize().focusRestorer().testTag("grid:items").semantics { traversalIndex = 0f },
                // The grid reaches the screen's edges, so only the screen clips a focused card's 1.1x scale.
                contentPadding = PaddingValues(
                    start = 56.dp,
                    end = 56.dp,
                    top = if (pinStrip) ContextStripHeight + 8.dp else 32.dp,
                    bottom = 32.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // The header scrolls with the cards: fixed above them it left 325 dp of a 540 dp screen, less than a
                // focused card with a three-line title needs at the largest system font.
                item(key = "grid:header", span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        // Sits on the screen's 48 dp keyline, not the cards' 56 dp one.
                        Modifier.offset(x = -GridHeaderOutset).padding(bottom = 12.dp).onFocusChanged {
                            headerFocused = it.hasFocus
                            if (it.hasFocus) {
                                heldDownFrom = -1
                                // A viewer who has moved on, by key or by a reader, isn't pulled back when a retry lands.
                                retryingFrom = -1
                            }
                        },
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        head?.invoke()
                        CatalogHeading(state.parent.name)
                        Text(itemCountLabel(state.total), color = CatalogMuted, modifier = Modifier.testTag("grid:count"))
                        if (showSort) {
                            FlowRow(
                                modifier = Modifier.accessibilityGroup().semantics {
                                    traversalIndex = 1f
                                }.then(
                                    // Keep the visual/input controls while withholding their early accessibility targets.
                                    if (!restored) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier
                                ).onPreviewKeyEvent {
                                    // Down from the controls returns to the card Up left, in its column. A control on
                                    // an upper wrapped line goes to the line below it first.
                                    if (it.type != KeyEventType.KeyDown) {
                                        false
                                    } else if (it.key == Key.DirectionDown &&
                                        items.isNotEmpty() &&
                                        sortBottoms[focusedSort] == sortBottoms.values.maxOrNull()
                                    ) {
                                        moving?.isActive == true || focusCard(focusedIndex.coerceAtLeast(0), FocusDirection.Down)
                                    } else {
                                        dropWaitingMoves()
                                        false
                                    }
                                },
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                itemVerticalAlignment = Alignment.CenterVertically
                            ) {
                                fun sortControl(index: Int) = Modifier.onFocusChanged { if (it.isFocused) focusedSort = index }
                                    .onGloballyPositioned { sortBottoms[index] = it.positionInParent().y + it.size.height }
                                GridSorts.forEachIndexed { index, (sort, title) ->
                                    val requester = remember(sort) { if (index == 0) firstSort else FocusRequester() }
                                    DisposableEffect(sort) {
                                        sortRequests[sort] = requester
                                        onDispose { sortRequests.remove(sort) }
                                    }
                                    Button(
                                        onClick = { onSort(sort, state.descending) },
                                        modifier = Modifier.focusRequester(requester).then(sortControl(index))
                                            .testTag("grid:sort:$sort").semantics { selected = state.sortBy == sort }
                                    ) { SortChipContent(title, "check".takeIf { state.sortBy == sort }) }
                                }
                                if (onFilters != null) {
                                    Button(
                                        onClick = onFilters,
                                        modifier = sortControl(GridSorts.size + 1).testTag("grid:filters")
                                    ) { Text(if (state.filters.active) "Filters · active" else "Filters") }
                                }
                                if (onDisplayList != null) {
                                    Button(
                                        onClick = { onDisplayList(!state.list) },
                                        modifier = sortControl(GridSorts.size + 2).testTag("grid:display")
                                    ) { Text(if (state.list) "View: List" else "View: Grid") }
                                }
                                Button(
                                    onClick = { onSort(state.sortBy, !state.descending) },
                                    modifier = sortControl(GridSorts.size).testTag("grid:sort-order").semantics {
                                        stateDescription = if (state.descending) "Descending" else "Ascending"
                                    }
                                ) {
                                    Box(Modifier.clearAndSetSemantics {}) {
                                        if (state.descending) {
                                            SortChipContent("Descending", "arrow-down")
                                        } else {
                                            SortChipContent("Ascending", "arrow-up")
                                        }
                                    }
                                }
                            }
                        }
                        CatalogError(state.error.takeIf { state.items.isEmpty() } ?: state.refreshError, {
                            restored = false
                            if (state.error == null) retryFocusRequest++
                            onRetry()
                        }, Modifier.focusRequester(retry).testTag("grid:retry"))
                        if (state.loading && state.items.isEmpty()) Text("Loading…")
                        if (!state.loading && state.items.isEmpty() && state.error == null) Text(emptyGridLabel(state.parent))
                    }
                }
                gridItemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                    val requester = remember(item.id) { FocusRequester() }
                    DisposableEffect(item.id) {
                        requests[item.id] = requester
                        onDispose { requests.remove(item.id) }
                    }
                    CatalogCard(
                        item = item,
                        account = state.account,
                        wide = state.list || item.type == "Episode",
                        width = if (state.list) 240.dp else null,
                        onClick = { onOpen(item) },
                        modifier = Modifier.focusRequester(requester).testTag("grid:${item.id}").onFocusChanged {
                            if (it.isFocused) {
                                selectedId = item.id
                                focusedIndex = index
                                onNearEnd(index)
                                heldDownFrom = -1
                                retryingFrom = -1
                            }
                        }.onPreviewKeyEvent {
                            when {
                                it.type != KeyEventType.KeyDown -> false

                                it.key == Key.DirectionDown || it.key == Key.DirectionUp ->
                                    moveVertically(index, it.key == Key.DirectionDown)

                                else -> {
                                    dropWaitingMoves()
                                    false
                                }
                            }
                        }
                    )
                }
                if (pageFailed || retryingFrom >= 0) {
                    item(key = GRID_PAGE_RETRY, span = { GridItemSpan(maxLineSpan) }) {
                        val requester = remember { FocusRequester() }
                        DisposableEffect(Unit) {
                            requests[GRID_PAGE_RETRY] = requester
                            onDispose { requests.remove(GRID_PAGE_RETRY) }
                        }
                        GridPageFailure(
                            state.error,
                            onRetry = {
                                if (state.error != null) {
                                    retryingFrom = focusedIndex.coerceAtLeast(0)
                                    onRetry()
                                }
                            },
                            modifier = Modifier.focusRequester(requester).testTag("grid:page-retry").onPreviewKeyEvent {
                                when {
                                    it.type != KeyEventType.KeyDown -> false

                                    it.key == Key.DirectionUp -> {
                                        retryingFrom = -1
                                        moving?.cancel()
                                        focusCard(focusedIndex.coerceAtLeast(0), FocusDirection.Up)
                                    }

                                    // Nothing is below, and a platform search must not wrap anywhere.
                                    it.key == Key.DirectionDown -> true

                                    else -> false
                                }
                            }
                        )
                    }
                }
            }
        }
        if (stripTitle != null && (pinStrip || scrolled)) {
            ContextStrip(stripTitle, gridStripDetail(state.sortBy, state.descending).takeIf { showSort }, Modifier.testTag("grid:strip"))
        }
    }
    // Keyed on the state rather than the chips' clicks, so a reader's click and anything else that changes the sort count.
    LaunchedEffect(state.sortBy, state.descending, state.filters, state.list) {
        dropWaitingMoves()
        retryingFrom = -1
    }
    // The error is a key too: an instant answer can take a retry from failed back to not loading within one frame.
    LaunchedEffect(state.loading, state.items.size, state.error) {
        if (!state.loading) {
            releaseHold()
            settlePageRetry()
        }
    }
    LaunchedEffect(heldDownFrom) {
        if (heldDownFrom >= 0) {
            // In frames, so a repeat of the key can't starve it and it stops with the screen.
            val pressed = withFrameMillis { it }
            do {
                val now = withFrameMillis { it }
            } while (now - pressed < GRID_HOLD_CAP_MILLIS)
            releaseHold()
        }
    }
    LaunchedEffect(state.items.map(MediaItem::id), state.loading, retryFocusRequest) {
        if (!state.loading && !restored) {
            withFrameNanos { }
            val focused = if (state.items.isNotEmpty()) {
                val index = state.items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
                val position = index + GRID_HEADER_ITEMS
                if (grid.layoutInfo.visibleItemsInfo.none { it.index == position }) grid.requestScrollToItem(position)
                withFrameNanos { }
                requestAttached(requests, state.items[index].id)
            } else if (state.error != null) {
                retry.requestFocus()
            } else {
                if (showSort) firstSort.requestFocus() else true
            }
            // A failed card request uses the existing sort fallback instead of leaving controls hidden. The header is an
            // item now, and is detached when the restored card is far down the grid.
            if (!focused && showSort && grid.layoutInfo.visibleItemsInfo.none { it.index == 0 }) {
                grid.requestScrollToItem(0)
                withFrameNanos { }
            }
            restored = focused || (showSort && firstSort.requestFocus())
        }
    }
    LaunchedEffect(grid, state.items.size) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.collect { index ->
            if (index != null && index >= GRID_HEADER_ITEMS) onNearEnd(index - GRID_HEADER_ITEMS)
        }
    }
}

/** The grid's items before its first card: the header. */
private const val GRID_HEADER_ITEMS = 1

/** The key of the footer shown after the last card when the next page failed, and of its Retry's focus requester. */
private const val GRID_PAGE_RETRY = "grid:page-failure"

/**
 * Above the focused card's lifted top at the 30% pivot, so a strip over the content never covers it. The same at every font
 * size, so its text clears the overscan margin (space.tv.safeTop) with [ContextStripTopPadding] above it.
 */
internal val ContextStripHeight = 56.dp

private val ContextStripTopPadding = 20.dp

/**
 * One line saying what a scrolled grid or a collapsed head took off the screen. The name gives way to the [detail] when
 * there's no room for both. Marked as not for a screen reader, since the viewer hears where they are from the card, but TV
 * TalkBack still stops on it when nothing is above it. So a fixed head doesn't collapse under a reader (fixedHeadCollapses).
 */
@Composable
internal fun ContextStrip(title: String, detail: String?, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(ContextStripHeight).background(CatalogBackground)
            .semantics(mergeDescendants = true) { hideFromAccessibility() }
            .padding(start = 48.dp, end = 48.dp, top = ContextStripTopPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail != null) Text(" · $detail", color = CatalogMuted, maxLines = 1, softWrap = false)
    }
}

/**
 * Text and a mark drawn as a shape. As characters, the check and arrows come from a fallback font whose taller line made
 * those chips higher than their neighbours at large sizes.
 */
@Composable
private fun SortChipContent(title: String, icon: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title)
        if (icon != null) {
            val size = with(LocalDensity.current) { LocalTextStyle.current.fontSize.toDp() }
            Icon(touchActionIcon(icon), contentDescription = null, modifier = Modifier.size(size))
        }
    }
}

/** How far down the viewport a card lands when a move has to scroll to reach it; the TV pivot's 30%. */
private const val GRID_ARRIVAL_FRACTION = .3f

/** The grid's horizontal content padding beyond the screen's 48 dp keyline. */
private val GridHeaderOutset = 8.dp

/** Clear space kept outside a focused card's scaled edge and its border. */
internal val FocusedCardMargin = 8.dp

/**
 * Moves a fixed header off the screen, still composed and focusable, so the content below it gets its height. A card
 * whose title has unbounded lines (see CatalogCard above 1.15) would not fit under the header otherwise.
 */
internal fun Modifier.collapsedOffScreen(collapsed: Boolean): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val height = if (collapsed) 0 else placeable.height
    layout(placeable.width, height) {
        placeable.placeRelative(0, if (collapsed) -placeable.height - CollapsedHeaderClearance.roundToPx() else 0)
    }
}

/**
 * Spreads a strip over the screen padding around it, up to the screen's top. It reports only the height that reaches past
 * [topPadding] and [gapBelow], so whatever follows after that gap starts exactly at the strip's bottom.
 */
internal fun Modifier.pinnedToScreenTop(horizontalPadding: Dp, topPadding: Dp, gapBelow: Dp): Modifier = layout { measurable, constraints ->
    val horizontal = horizontalPadding.roundToPx()
    val width = constraints.maxWidth + 2 * horizontal
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    val reported = (placeable.height - topPadding.roundToPx() - gapBelow.roundToPx()).coerceAtLeast(0)
    layout(constraints.maxWidth, reported) { placeable.placeRelative(-horizontal, -topPadding.roundToPx()) }
}

/** More than any screen padding above a collapsed header, so no sliver of it shows. */
private val CollapsedHeaderClearance = 64.dp

/** Tracks the scroller's viewport and its focused descendant. Layout data, so no recomposition when they change. */
private class FocusedInScroller {
    var viewport: LayoutCoordinates? = null
    var focused: LayoutCoordinates? = null
}

/**
 * For a TV scroller's modifier. A font change keeps the activity (fontScale is in configChanges), and the platform only
 * re-reveals a focused child when the viewport shrinks, not when the child or what's above it grows, so a focused control
 * low on the page would end up below the screen.
 *
 * onFocusedBoundsChanged is deprecated with a note that it will become a no-op. Its replacement is on
 * FocusTargetModifierNode, which would add a focus level to every scroller. If it does go, the inPlaceFontChange tests fail.
 * A change of [key] reveals the same way: the caller's way of saying a head moved in or out of the scroller.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.revealFocusAfterFontChange(scroller: ScrollableState, topInset: Dp = 0.dp, key: Any? = null): Modifier = composed {
    val density = LocalDensity.current
    val margin = with(density) { FocusedCardMargin.toPx() }
    val inset = with(density) { topInset.toPx() }
    val tracked = remember { FocusedInScroller() }
    var revealedFor by remember { mutableStateOf(density.fontScale to key) }
    LaunchedEffect(density.fontScale, key) {
        if ((density.fontScale to key) == revealedFor) return@LaunchedEffect
        revealedFor = density.fontScale to key
        // The grown layout is in place after the frame this composition belongs to.
        repeat(2) { withFrameNanos { } }
        val viewport = tracked.viewport?.takeIf { it.isAttached } ?: return@LaunchedEffect
        val focused = tracked.focused?.takeIf { it.isAttached } ?: return@LaunchedEffect
        val bounds = viewport.localBoundingBoxOf(focused, clipBounds = false)
        val viewportHeight = viewport.size.height.toFloat()
        scroller.scrollBy(focusedCardScrollDistance(bounds.top, bounds.height, viewportHeight, margin, 0f, topInset = inset))
    }
    Modifier.onGloballyPositioned { tracked.viewport = it }.onFocusedBoundsChanged { tracked.focused = it }
}

/** tv-material draws a focused card at 1.1x about its centre, so 5% of its height lies past each layout edge. */
private const val FOCUS_SCALE_OVERHANG = .05f

/**
 * Scroll distance that shows the whole focused card, its focus scale included, keeping [wanted], the platform's distance,
 * when that does. The platform counts only the layout bounds: on TV it puts a card that doesn't fit below its 30% pivot
 * flush with the viewport's end, so the scale's overhang was cut there. A card that can't fit shows from its top.
 * [scrollToTop] is the current scroll when the focus belongs with the header: the distance back to the top if the card
 * fits there. [topInset] is what's pinned over the top of the viewport.
 */
internal fun focusedCardScrollDistance(
    offset: Float,
    size: Float,
    containerSize: Float,
    margin: Float,
    wanted: Float,
    scrollToTop: Float? = null,
    topInset: Float = 0f
): Float {
    val overhang = size * FOCUS_SCALE_OVERHANG + margin
    if (size + 2 * overhang + topInset >= containerSize) return offset - overhang - topInset
    if (scrollToTop != null && offset + scrollToTop + size + overhang <= containerSize) return -scrollToTop
    val top = offset - wanted
    return when {
        top < overhang + topInset -> offset - overhang - topInset
        top + size + overhang > containerSize -> offset + size + overhang - containerSize
        else -> wanted
    }
}

/**
 * A shelf's section title sits [above] the card's top and ends [gap] above it. After [distance], the title is whole in the list
 * or wholly out of it, never cut by the top edge into half a word. It comes into view with the card when both fit; when they
 * don't, it goes wholly under the edge, unless the card's own scaled edge would follow it, which only a card taller than
 * the screen can do.
 */
internal fun sectionTitleScrollDistance(
    distance: Float,
    offset: Float,
    size: Float,
    containerSize: Float,
    margin: Float,
    above: Float,
    gap: Float
): Float {
    val top = offset - distance
    if (top >= above || top <= gap) return distance
    if (above + size + size * FOCUS_SCALE_OVERHANG + margin <= containerSize) return offset - above
    return offset - maxOf(gap, size * FOCUS_SCALE_OVERHANG)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun CatalogDetail(
    state: CatalogDetailState,
    onOpen: (MediaItem) -> Unit,
    onSeason: (MediaItem) -> Unit,
    onPlayed: () -> Unit,
    onFavorite: () -> Unit,
    onRetry: () -> Unit,
    onPlay: (MediaItem, Boolean) -> Unit = { _, _ -> },
    onFocusRestored: (String, Boolean) -> Unit = { _, _ -> },
    onPendingAction: ((() -> Unit) -> Boolean)? = null,
    onPendingFocus: () -> Unit = {},
    origin: MediaItem? = null,
    onPendingControlsReady: (Boolean) -> Unit = {},
    onPendingQueueNeeded: () -> Unit = {},
    onPendingQueueExpired: (() -> Boolean)? = null,
    onPlayedRequest: () -> Unit = {}
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    DisposableEffect(Unit) {
        detailTiming("composition-first")
        onDispose { }
    }
    val reportFocus by rememberUpdatedState(onFocusRestored)
    val focusHost = LocalView.current
    val list = rememberLazyListState()
    val initial = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    var selectedSection by rememberSaveable { mutableStateOf("header") }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var restored by remember { mutableStateOf(false) }
    var arrivalPending by remember { mutableStateOf(true) }
    var playbackReturn by remember { mutableStateOf(state.playbackPending) }
    var playbackLayout by rememberSaveable { mutableStateOf(state.playbackPending) }
    var playbackHeader by remember { mutableStateOf<MediaItem?>(null) }
    var pendingFailed by remember { mutableStateOf(false) }
    var commandNotice by remember { mutableStateOf<String?>(null) }
    var pendingExpired by remember { mutableStateOf(false) }
    var expiryReported by remember { mutableStateOf(false) }
    var recoveryMessage by remember { mutableStateOf<String?>(null) }
    var retryWorking by remember { mutableStateOf(false) }
    var retryCueBaseline by remember { mutableStateOf<Long?>(null) }
    var recoveryFocusOwned by remember { mutableStateOf(false) }
    var recoveryTarget by remember { mutableStateOf<String?>(null) }
    var recoveryTargetInputRevision by remember { mutableIntStateOf(-1) }
    var retryFocused by remember { mutableStateOf(false) }
    var recoverySelectKey by remember { mutableStateOf<Key?>(null) }
    var mutationDataReady by remember {
        mutableStateOf(!state.loading && !state.playbackPending && state.error == null && state.item != null)
    }
    // Report acknowledgement can precede this model's authoritative read. Keep its
    // existing header live until loading and item readiness agree on the fresh state.
    val coldWaiting = app.pictureshow.core.detailNeedsPendingControls(
        state.loading,
        arrivalPending,
        onPendingAction != null,
        playbackReturn || state.playbackPending
    )
    val playbackWaiting = state.playbackPending ||
        coldWaiting ||
        (playbackReturn && state.error == null && state.loading)
    var semanticsRevealed by remember { mutableStateOf(false) }
    var pendingSemanticsRevealed by remember { mutableStateOf(false) }
    val pendingPresentationVisible = app.pictureshow.core.detailPendingPresentationVisible(
        playbackWaiting,
        lifecycleState == Lifecycle.State.RESUMED,
        pendingSemanticsRevealed
    )
    var presentHeldLabels by remember(pendingPresentationVisible) { mutableStateOf(false) }
    LaunchedEffect(pendingPresentationVisible) {
        if (pendingPresentationVisible) {
            delay(app.pictureshow.core.DETAIL_PENDING_LABEL_GRACE_MILLIS)
            presentHeldLabels = true
        }
    }
    var pendingFocusRestored by remember { mutableStateOf(false) }
    val pendingFocus = remember { FocusRequester() }
    val playedFocus = remember { FocusRequester() }
    val favoriteFocus = remember { FocusRequester() }
    val fromStartFocus = remember { FocusRequester() }
    var pendingHeaderTarget by remember { mutableStateOf("primary") }
    var headerInputTarget by remember { mutableStateOf<String?>(null) }
    var pendingViewerMoved by remember { mutableStateOf(false) }
    val readiness = remember { app.pictureshow.core.DetailReadyFeedback() }
    val pendingFeedback = remember { app.pictureshow.core.DetailPendingFeedback() }
    var readyDeliveryTarget by remember { mutableStateOf<String?>(null) }
    var readyDeliveryLabel by remember { mutableStateOf<String?>(null) }
    // TalkBack keeps one waiting content-change utterance: a newer one replaces it. Give a
    // pending cue queued behind arrival speech its turn before Ready can replace it.
    var pendingCueRevision by remember { mutableIntStateOf(0) }
    var pendingCueQueued by remember { mutableStateOf(false) }
    LaunchedEffect(pendingCueRevision) {
        if (pendingCueRevision > 0) {
            delay(app.pictureshow.core.DETAIL_READY_AFTER_PENDING_CUE_MILLIS)
            pendingCueQueued = false
        }
    }
    val feedbackChannel =
        rememberDetailFeedbackChannel(playbackWaiting && pendingSemanticsRevealed && lifecycleState == Lifecycle.State.RESUMED) { target ->
            if (target == headerInputTarget) readiness.presented(target)
        }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        retryCueBaseline = null
        feedbackChannel.clearReaderFocus()
    }
    val readerMovement = remember { PlaybackReaderMovement() }
    val reportLive by rememberUpdatedState(onPendingControlsReady)
    DisposableEffect(Unit) {
        onDispose {
            reportLive(false)
            recoverySelectKey = null
        }
    }
    // A failed authoritative read (unlike a failed write) cannot expose stale commands.
    // The action budget ends in this same recovery state, before the transport timer fires.
    val readFailure = app.pictureshow.core.detailReadHasFailure(state.readFailed, state.error, mutationDataReady)
    val readSettled = !state.loading &&
        state.error == null &&
        !state.readFailed &&
        (!state.playbackPending || state.readSucceeded)
    // Derive settlement in this composition: an effect with the previous recovery value
    // must not re-arm pendingFailed after the successful read's SideEffect clears it.
    val recoveryActive = !readSettled && (recoveryMessage != null || readFailure || (pendingExpired && playbackWaiting))
    // A second refresh may start while focus is being acknowledged. Retain the
    // same recovery node until that read settles too; readiness still uses the model.
    val recoveryHandoff = !recoveryActive && recoveryFocusOwned && state.error == null && !state.readFailed
    val showRecovery = recoveryActive || recoveryHandoff
    DetailFeedbackEvents(recoveryActive && !retryFocused && lifecycleState == Lifecycle.State.RESUMED)
    val pendingControlsLive = !recoveryActive &&
        playbackWaiting &&
        lifecycleState == Lifecycle.State.RESUMED &&
        pendingSemanticsRevealed &&
        pendingFocusRestored &&
        headerInputTarget != null &&
        state.error == null
    SideEffect {
        arrivalPending = app.pictureshow.core.detailArrivalStillPending(arrivalPending, restored)
        if (!showRecovery) retryFocused = false
        if (readFailure && state.error != null) recoveryMessage = state.error
        if (readSettled) {
            if (pendingExpired && recoveryMessage != null) commandNotice = null
            recoveryMessage = null
            pendingExpired = false
            pendingFailed = false
        }
        // A write error can be retried against the last fresh item; a failed playback
        // reload cannot. Loading invalidates this marker until an error-free read lands.
        if (state.loading || playbackWaiting || state.readFailed) {
            mutationDataReady = false
        } else if (state.error == null && state.item != null) {
            mutationDataReady = true
            if (commandNotice == "Changes will be available when loading finishes.") commandNotice = null
        }
        if (state.playbackPending && state.error == null && !pendingFailed) playbackReturn = true
        if (playbackWaiting) {
            playbackLayout = true
            if (!coldWaiting && playbackHeader == null) playbackHeader = state.item ?: origin
        }
        if (playbackWaiting && !recoveryActive && !pendingFailed && state.error == null && lifecycleState == Lifecycle.State.RESUMED) {
            onPendingQueueNeeded()
        }
        reportLive(pendingControlsLive)
    }
    val cueAccessibility = focusHost.context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
        as android.view.accessibility.AccessibilityManager
    DisposableEffect(cueAccessibility) {
        val listener = android.view.accessibility.AccessibilityManager.AccessibilityStateChangeListener { enabled ->
            if (!enabled) {
                readiness.cancel()
                readyDeliveryTarget = null
            }
        }
        cueAccessibility.addAccessibilityStateChangeListener(listener)
        onDispose { cueAccessibility.removeAccessibilityStateChangeListener(listener) }
    }
    val reportPendingFocus by rememberUpdatedState(onPendingFocus)
    var focusInputRevision by remember { mutableIntStateOf(0) }
    val requests = remember { mutableMapOf<String, FocusRequester>() }
    val scope = rememberCoroutineScope()
    var movement by remember { mutableStateOf<Job?>(null) }
    var rowSelections by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    val item = state.item ?: origin.takeIf { playbackWaiting || recoveryActive }
    // Route cards can have less metadata than the authoritative item. During a return,
    // keep the published presentation; only fresh command data may change underneath it.
    val headerItem = playbackHeader ?: item
    val playItem = if (playbackWaiting) {
        null
    } else {
        when (item?.type) {
            "Series" -> state.nextUp.firstOrNull() ?: state.episodes.firstOrNull { !it.userData.played } ?: state.episodes.firstOrNull()
            "Season" -> state.episodes.firstOrNull { !it.userData.played } ?: state.episodes.firstOrNull()
            "Movie", "Episode", "Video" -> item
            else -> null
        }
    }
    // A new returning model deliberately clears stale data. The route still identifies its
    // capabilities; queued actions below continue to require the authoritative fresh state.
    val pendingItem = state.item ?: origin
    val pendingCanPlay = when (pendingItem?.type) {
        "Movie", "Episode", "Video" -> true
        "Series" -> state.nextUp.isNotEmpty() || state.episodes.isNotEmpty() || pendingItem?.isEmptyContainer == false
        "Season" -> state.episodes.isNotEmpty() || pendingItem?.isEmptyContainer == false
        else -> false
    }
    val latestPlaybackReady by rememberUpdatedState(!recoveryActive && !state.loading && !playbackWaiting && state.error == null)
    val latestPlayItem by rememberUpdatedState(playItem)
    val latestOnPlay by rememberUpdatedState(onPlay)
    val latestItem by rememberUpdatedState(state.item)
    val latestBusy by rememberUpdatedState(state.busy)
    val latestOnPlayed by rememberUpdatedState(onPlayed)
    val reportPlayedRequest by rememberUpdatedState(onPlayedRequest)
    val latestOnFavorite by rememberUpdatedState(onFavorite)
    val queuePendingAction by rememberUpdatedState(onPendingAction)
    // The handoff releases intents in order. Mutations can remain busy across frames;
    // serialize them here so a following intent cannot be silently dropped by the VM.
    // This composition owns the consumer, so navigation cancels any work not yet started.
    var commandGeneration by remember { mutableIntStateOf(0) }
    var readyQueuedTargets by remember { mutableStateOf(emptySet<String>()) }
    var requestCueTarget by remember { mutableStateOf<String?>(null) }
    var requestCueVersion by remember { mutableStateOf(0L) }
    val requestFeedback = remember { app.pictureshow.core.DetailRequestFeedback() }
    val freshCommands = remember { kotlinx.coroutines.channels.Channel<DetailCommand>(32) }
    val latestError by rememberUpdatedState(state.error)
    val latestLifecycle by rememberUpdatedState(lifecycleState)
    val latestMutationReady by rememberUpdatedState(
        !recoveryActive && !state.readFailed && mutationDataReady && !state.loading && !playbackWaiting && state.item != null
    )
    LaunchedEffect(Unit) {
        for ((generation, mutationTarget, command) in freshCommands) {
            androidx.compose.runtime.snapshotFlow {
                generation != commandGeneration ||
                    (latestError != null && (mutationTarget == null || !latestMutationReady)) ||
                    (
                        (if (mutationTarget != null) latestMutationReady else latestPlaybackReady) &&
                            !latestBusy &&
                            latestLifecycle == Lifecycle.State.RESUMED
                        )
            }.first { it }
            if (generation == commandGeneration) {
                if (latestError == null || (mutationTarget != null && latestMutationReady)) {
                    command()
                } else {
                    commandNotice = "The request could not be saved. Reload the item and try again."
                }
                mutationTarget?.let { readyQueuedTargets = readyQueuedTargets - it }
            }
            // Give optimistic busy/loading state a composition before taking another command.
            repeat(2) { withFrameNanos { } }
        }
    }
    var waitHadRequest by remember { mutableStateOf(false) }
    val rawRecoveryReason = (state.error ?: recoveryMessage ?: "The server has not finished loading. Retry to reload the item.") +
        if (waitHadRequest && pendingExpired) " The previous request expired and will not run." else ""
    var previousFailure by remember { mutableStateOf<app.pictureshow.core.DetailFailurePresentation?>(null) }
    val failureKey = app.pictureshow.core.detailFailureKey(
        readRecovery = recoveryActive,
        retrying = retryWorking,
        writeAttempt = state.writeAttempt.takeIf { !showRecovery && state.error != null }
    )
    val presentedFailure = app.pictureshow.core.presentDetailFailure(
        previousFailure,
        failureKey,
        if (showRecovery) rawRecoveryReason else state.error
    )
    SideEffect { previousFailure = presentedFailure }
    val recoveryReason = presentedFailure?.message ?: rawRecoveryReason
    SideEffect {
        if (app.pictureshow.core.detailRetryAttemptSucceeded(
                retryCueBaseline,
                state.readAttempt,
                state.loading,
                readFailure || state.error != null
            )
        ) {
            retryCueBaseline = null
        }
    }
    val writeFailure = presentedFailure?.message?.takeIf { failureKey?.startsWith("write:") == true }
    fun requestReadRetry() {
        if (!retryWorking && !recoveryHandoff) {
            previousFailure = null
            retryCueBaseline = state.readAttempt
            retryWorking = true
            onRetry()
        }
    }
    LaunchedEffect(retryCueBaseline, state.readAttempt, state.loading, readFailure, recoveryReason, lifecycleState) {
        if (app.pictureshow.core.detailRetryFailureNeedsCue(
                retryCueBaseline,
                state.readAttempt,
                state.loading,
                readFailure,
                lifecycleState == Lifecycle.State.RESUMED
            )
        ) {
            val baseline = retryCueBaseline
            val started = android.os.SystemClock.uptimeMillis()
            val reason = recoveryReason.trim().trimEnd('.')
            try {
                while (app.pictureshow.core.detailRetryCueCanWait(
                        feedbackChannel.accessibilityEnabled,
                        android.os.SystemClock.uptimeMillis() - started
                    )
                ) {
                    if (feedbackChannel.sendReadFailure("Loading failed. $reason. Retry is available.")) break
                    delay(50)
                }
            } finally {
                // Timeout, disabled accessibility and cancellation also end this Retry episode.
                if (retryCueBaseline == baseline) retryCueBaseline = null
            }
        }
    }
    fun queueFresh(target: String, command: () -> Unit): Boolean {
        val generation = commandGeneration
        val accepted = !pendingExpired &&
            !expiryReported &&
            queuePendingAction?.invoke {
                if (generation == commandGeneration && freshCommands.trySend(DetailCommand(generation, null, command)).isFailure) {
                    commandNotice = "The request could not be saved. Try again."
                }
            } == true
        if (accepted) {
            waitHadRequest = true
            requestCueVersion++
            requestCueTarget = target
        }
        if (!accepted) {
            commandNotice = "The request expired. Try again when the item loads."
            pendingExpired = true
        }
        return accepted
    }

    // Returning controls keep focus while an accepted mutation is busy. A fresh press
    // joins the same ordered consumer with the desired state its current label offers.
    fun queueReadyMutation(watched: Boolean) {
        if (!mutationDataReady) {
            commandNotice = if (state.error != null) {
                "Reload the item before changing it."
            } else {
                "Changes will be available when loading finishes."
            }
            return
        }
        val offered = state.item ?: return
        val target = if (watched) "played" else "favorite"
        if (target in readyQueuedTargets) return
        val desired = if (watched) !offered.isWatched else !offered.userData.isFavorite
        val generation = commandGeneration
        val command: () -> Unit = {
            val fresh = latestItem
            if (fresh != null) {
                if (watched && !fresh.isEmptyContainer) {
                    val notice = app.pictureshow.core.detailPlayedRequestNotice(desired, fresh.isWatched)
                    if (notice == null) latestOnPlayed() else commandNotice = notice
                }
                if (!watched && fresh.userData.isFavorite != desired) latestOnFavorite()
            }
        }
        if (freshCommands.trySend(DetailCommand(generation, target, command)).isFailure) {
            commandNotice = "The request could not be saved. Try again."
        } else {
            readyQueuedTargets = readyQueuedTargets + target
            if (watched) reportPlayedRequest()
            if (latestBusy) {
                requestCueVersion++
                requestCueTarget = target
            }
            commandNotice = null
        }
    }
    var playQueued by remember { mutableStateOf<String?>(null) }
    var pendingQueuedTargets by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(playbackWaiting) {
        if (playbackWaiting) {
            commandGeneration++
            readyQueuedTargets = emptySet()
            waitHadRequest = false
            commandNotice = null
            pendingExpired = false
            expiryReported = false
            if (!pendingFailed && !coldWaiting) playbackReturn = true
            pendingSemanticsRevealed = false
            readerMovement.reset()
            pendingFocusRestored = false
            pendingHeaderTarget = "primary"
            pendingViewerMoved = false
        } else {
            playQueued = null
            pendingQueuedTargets = emptySet()
        }
    }
    val latestHasRequest by rememberUpdatedState(waitHadRequest)
    fun expirePending() {
        // Invalidate even commands already handed to this composition's ready-state gate.
        // Standalone consumers without an owner getter use the same local request budget.
        commandGeneration++
        readyQueuedTargets = emptySet()
        expiryReported = true
        playQueued = null
        pendingQueuedTargets = emptySet()
        pendingExpired = true
        recoveryMessage = "The server has not finished loading. Retry to reload the item."
        commandNotice = if (latestHasRequest) {
            "The request expired. Try again when the item loads."
        } else {
            "Actions will be available when the item loads."
        }
    }
    SideEffect {
        if (playbackWaiting && !expiryReported && onPendingQueueExpired?.invoke() == true) expirePending()
    }
    LaunchedEffect(playbackWaiting) {
        if (playbackWaiting) {
            delay(app.pictureshow.core.ApiTimeouts.REQUEST_MILLIS)
            // A destination may become RESUMED after composition. Its owner is the
            // deadline authority; do not label still-acceptable actions unavailable.
            while (latestError == null) {
                val expired = onPendingQueueExpired?.invoke() ?: true
                if (expired) {
                    if (!expiryReported) expirePending()
                    break
                }
                delay(250)
            }
        }
    }
    LaunchedEffect(state.loading, state.error, state.readAttempt) {
        if (!state.loading || state.error != null) retryWorking = false
    }
    LaunchedEffect(commandNotice) {
        if (commandNotice != null) {
            delay(6_000)
            commandNotice = null
        }
    }
    LaunchedEffect(state.error) {
        if (state.error == null &&
            commandNotice in setOf(
                "The request could not be saved. Reload the item and try again.",
                "Reload the item before changing it."
            )
        ) {
            commandNotice = null
        }
    }
    fun pendingTarget(target: String) {
        if (BuildConfig.DEBUG) PlaybackDiagnostics.write("detail-pending-focus target=$target")
        pendingHeaderTarget = target
        if (target != "primary") {
            pendingViewerMoved = true
            reportPendingFocus()
        }
    }
    val pendingEnabled =
        !recoveryActive && playbackWaiting && !pendingFailed && state.error == null && pendingCanPlay && queuePendingAction != null
    val pendingActions = if (pendingEnabled) {
        PendingDetailActions(
            available = !pendingExpired && !expiryReported,
            requestedTargets = pendingQueuedTargets + if (playQueued != null) setOf("primary", "from-start") else emptySet(),
            playLabel = when {
                pendingExpired || expiryReported -> app.pictureshow.core.detailUnavailableActionLabel("primary")
                playQueued == "resume" -> app.pictureshow.core.detailRequestedActionLabel("primary", requestCueTarget == "primary")
                playQueued == "start" -> "Start already requested"
                else -> "Play when ready"
            },
            fromStartLabel = when {
                pendingExpired || expiryReported -> app.pictureshow.core.detailUnavailableActionLabel("from-start")
                playQueued == "start" -> app.pictureshow.core.detailRequestedActionLabel("from-start", requestCueTarget == "from-start")
                playQueued == "resume" -> "Play already requested"
                else -> "Play from start when ready"
            },
            playedLabel = if (pendingExpired || expiryReported) {
                app.pictureshow.core.detailUnavailableActionLabel("played")
            } else if ("played" in pendingQueuedTargets) {
                app.pictureshow.core.detailRequestedActionLabel("played", requestCueTarget == "played")
            } else if (pendingItem?.isWatched == true) {
                "Mark unwatched when ready"
            } else {
                "Mark watched when ready"
            },
            favoriteLabel = if (pendingExpired || expiryReported) {
                app.pictureshow.core.detailUnavailableActionLabel("favorite")
            } else if ("favorite" in pendingQueuedTargets) {
                app.pictureshow.core.detailRequestedActionLabel("favorite", requestCueTarget == "favorite")
            } else if (pendingItem?.userData?.isFavorite == true) {
                "Unfavorite when ready"
            } else {
                "Favorite when ready"
            },
            onPlay = {
                pendingViewerMoved = true
                if (playQueued == null) {
                    val accepted = queueFresh("primary") {
                        if (latestPlaybackReady) {
                            val fresh = latestPlayItem
                            if (fresh != null) {
                                latestOnPlay(fresh, fresh.userData.playbackPositionTicks > 0)
                            } else {
                                commandNotice = "No playable episodes are available."
                            }
                        }
                    }
                    if (accepted) playQueued = "resume"
                }
            },
            onFromStart = {
                pendingViewerMoved = true
                if (playQueued == null) {
                    val accepted = queueFresh("from-start") {
                        val fresh = latestPlayItem
                        if (latestPlaybackReady && fresh != null) {
                            latestOnPlay(fresh, false)
                        } else if (latestPlaybackReady) {
                            commandNotice = "No playable episodes are available."
                        }
                    }
                    if (accepted) playQueued = "start"
                }
            },
            onPlayed = {
                pendingViewerMoved = true
                if ("played" !in pendingQueuedTargets) {
                    val wanted = pendingItem?.isWatched != true
                    if (queueFresh("played") {
                            val fresh = latestItem
                            if (latestPlaybackReady &&
                                !latestBusy &&
                                fresh != null &&
                                !fresh.isEmptyContainer
                            ) {
                                val notice = app.pictureshow.core.detailPlayedRequestNotice(wanted, fresh.isWatched)
                                if (notice == null) latestOnPlayed() else commandNotice = notice
                            }
                        }
                    ) {
                        pendingQueuedTargets = pendingQueuedTargets + "played"
                        reportPlayedRequest()
                    }
                }
            },
            onFavorite = {
                pendingViewerMoved = true
                if ("favorite" !in pendingQueuedTargets) {
                    val wanted = pendingItem?.userData?.isFavorite != true
                    if (queueFresh("favorite") {
                            val fresh = latestItem
                            if (latestPlaybackReady &&
                                !latestBusy &&
                                fresh != null &&
                                fresh.userData.isFavorite != wanted
                            ) {
                                latestOnFavorite()
                            }
                        }
                    ) {
                        pendingQueuedTargets = pendingQueuedTargets + "favorite"
                    }
                }
            }
        ).let { actions ->
            fun label(target: String, pending: String) = app.pictureshow.core.detailPendingPresentationLabel(
                presentHeldLabels,
                target in actions.requestedTargets,
                actions.available,
                app.pictureshow.core.detailPendingPlainLabel(
                    target,
                    state.item != null,
                    pendingItem?.isWatched == true,
                    pendingItem?.userData?.isFavorite == true
                ),
                pending
            )
            actions.copy(
                playLabel = label("primary", actions.playLabel),
                fromStartLabel = label("from-start", actions.fromStartLabel),
                playedLabel = label("played", actions.playedLabel),
                favoriteLabel = label("favorite", actions.favoriteLabel)
            )
        }
    } else {
        null
    }
    val requestedCueTargets = readyQueuedTargets + pendingActions?.requestedTargets.orEmpty()
    val cueRequested = requestCueTarget?.let { it in requestedCueTargets } == true
    SideEffect {
        // Consumption ends this acceptance episode even when input and reader focus stay put.
        if (!app.pictureshow.core.detailRequestCueRemainsActive(requestCueTarget != null, false, cueRequested)) {
            requestCueTarget = null
        }
    }
    fun headerFocus(target: String, focused: Boolean) {
        if (!focused && requestCueTarget == target) requestCueTarget = null
        if (focused) {
            headerInputTarget = target
            pendingFeedback.update(
                playbackWaiting,
                presentHeldLabels,
                target,
                lifecycleState == Lifecycle.State.RESUMED && cueAccessibility.isEnabled,
                recoveryActive || state.error != null || waitHadRequest
            )
            if (playbackWaiting) pendingTarget(target)
        } else if (headerInputTarget == target) {
            headerInputTarget = null
        }
    }
    val latestPendingActions by rememberUpdatedState(pendingActions)
    val latestHeldLabels by rememberUpdatedState(presentHeldLabels)
    // Reader navigation can select static text without changing Compose input focus.
    LaunchedEffect(playbackWaiting, pendingSemanticsRevealed, lifecycleState, requestCueTarget, requestCueVersion, readyDeliveryTarget) {
        val accessibility = focusHost.context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
        val observingPendingFocus = playbackWaiting && pendingSemanticsRevealed
        pendingFeedback.update(
            observingPendingFocus,
            latestHeldLabels,
            headerInputTarget,
            lifecycleState == Lifecycle.State.RESUMED && accessibility.isEnabled,
            latestError != null || waitHadRequest
        )
        if ((observingPendingFocus || requestCueTarget != null || readyDeliveryTarget != null) &&
            lifecycleState == Lifecycle.State.RESUMED &&
            accessibility.isEnabled
        ) {
            val started = android.os.SystemClock.uptimeMillis()
            var requestPresented = false
            while (android.os.SystemClock.uptimeMillis() - started < app.pictureshow.core.ApiTimeouts.REQUEST_MILLIS &&
                (observingPendingFocus || requestCueTarget != null || readyDeliveryTarget != null) &&
                accessibility.isEnabled
            ) {
                val readerTarget = playbackReaderTarget(focusHost)
                val pendingTarget = pendingFeedback.update(
                    observingPendingFocus,
                    latestHeldLabels,
                    headerInputTarget,
                    true,
                    latestError != null ||
                        waitHadRequest ||
                        readerTarget != PlaybackReaderTarget.ACTION ||
                        !detailReaderFocusMatches(focusHost, headerInputTarget)
                )
                if (pendingTarget != null) {
                    val actions = latestPendingActions
                    val label = when (pendingTarget) {
                        "primary" -> actions?.playLabel
                        "from-start" -> actions?.fromStartLabel
                        "played" -> actions?.playedLabel
                        else -> actions?.favoriteLabel
                    }
                    if (label != null && feedbackChannel.send(pendingTarget, label)) {
                        pendingFeedback.submitted()
                        readiness.presented(pendingTarget)
                        pendingCueQueued = true
                        pendingCueRevision++
                    }
                }
                if (readerTarget == PlaybackReaderTarget.ACTION && detailReaderFocusMatches(focusHost, requestCueTarget)) {
                    requestPresented = true
                    val target = requestCueTarget
                    if (target != null &&
                        latestError == null &&
                        requestFeedback.shouldSend(requestCueVersion, true) &&
                        feedbackChannel.send(target, app.pictureshow.core.detailRequestedActionLabel(target, true))
                    ) {
                        requestFeedback.submitted(requestCueVersion)
                    }
                }
                readyDeliveryTarget?.let { target ->
                    if (headerInputTarget != target ||
                        latestError != null ||
                        waitHadRequest ||
                        readyQueuedTargets.isNotEmpty() ||
                        !detailReaderFocusMatches(focusHost, target)
                    ) {
                        readiness.cancel()
                        readyDeliveryTarget = null
                    } else if (!pendingCueQueued &&
                        feedbackChannel.send(
                            target,
                            app.pictureshow.core.detailReadyCue(
                                readyDeliveryLabel ?: app.pictureshow.core.detailHeldActionLabel(target)
                            )
                        )
                    ) {
                        readiness.submitted()
                        readyDeliveryTarget = null
                    }
                }
                if (!app.pictureshow.core.detailRequestCueRemainsActive(
                        requestCueTarget != null,
                        (
                            readerTarget in setOf(PlaybackReaderTarget.TEXT, PlaybackReaderTarget.OTHER_ACTION) ||
                                (readerTarget == PlaybackReaderTarget.ACTION && !detailReaderFocusMatches(focusHost, requestCueTarget))
                            ),
                        presented = requestPresented
                    )
                ) {
                    requestCueTarget = null
                }
                if (observingPendingFocus &&
                    (
                        readerTarget in setOf(PlaybackReaderTarget.TEXT, PlaybackReaderTarget.OTHER_ACTION) ||
                            (readerTarget == PlaybackReaderTarget.ACTION && !detailReaderFocusMatches(focusHost, headerInputTarget))
                        )
                ) {
                    readiness.cancel()
                }
                if (observingPendingFocus && readerMovement.observe(readerTarget)) {
                    pendingViewerMoved = true
                    reportPendingFocus()
                }
                delay(50)
            }
        }
    }
    val focusManager = LocalFocusManager.current
    val hasActions = item?.type in listOf("Movie", "Episode", "Video", "Series", "Season")
    // Removing pending shelves can send a final focus callback from their previous
    // composition. Judge it against current readiness, not that disposed row's state.
    val trackSelection by rememberUpdatedState(!state.loading && !playbackWaiting && restored)
    val sections = buildList {
        if (!playbackWaiting && item?.type == "Series" && state.nextUp.isNotEmpty()) add("next" to state.nextUp)
        if (!playbackWaiting && item?.type == "Series" && state.seasons.isNotEmpty()) add("seasons" to state.seasons)
        if (!playbackWaiting &&
            item?.type in listOf("Series", "Season") &&
            state.episodes.isNotEmpty()
        ) {
            add("episodes" to state.episodes)
        }
    }
    val rowStates = mutableMapOf<String, LazyListState>()
    sections.forEach { (section, _) -> key(section) { rowStates[section] = rememberLazyListState() } }
    fun moveSection(index: Int) {
        movement?.cancel()
        movement = scope.launch {
            if (index < 0) {
                list.jumpTo(0)
                selectedSection = "header"
                restored = initial.requestFocus()
            } else if (index in sections.indices) {
                val (section, items) = sections[index]
                val target = rowSelections[section] ?: state.selectedSeasonId.takeIf { section == "seasons" }
                val itemIndex = items.indexOfFirst { it.id == target }.coerceAtLeast(0)
                // A visible shelf heading does not mean its lazy cards are attached.
                list.jumpTo(index + 1)
                val horizontal = rowStates.getValue(section)
                if (horizontal.layoutInfo.visibleItemsInfo.none { it.index == itemIndex }) horizontal.jumpTo(itemIndex)
                selectedSection = section
                selectedId = items[itemIndex].id
                rowSelections = rowSelections + (section to selectedId)
                restored = requestAttached(requests, "$section:$selectedId")
            }
        }
    }
    val platformBringIntoView = LocalBringIntoViewSpec.current
    val headerFocused by rememberUpdatedState(selectedSection == "header")
    val cardFocusMargin = with(LocalDensity.current) { FocusedCardMargin.toPx() }
    val reveal = Modifier.revealFocusAfterFontChange(list)
    val detailBringIntoView = remember(platformBringIntoView, cardFocusMargin) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                // An already-visible header action must not pivot the hero/title above the screen.
                if (headerFocused && offset >= 0f && offset + size <= containerSize) {
                    0f
                } else if (headerFocused) {
                    platformBringIntoView.calculateScrollDistance(offset, size, containerSize)
                } else {
                    focusedCardScrollDistance(
                        offset,
                        size,
                        containerSize,
                        cardFocusMargin,
                        platformBringIntoView.calculateScrollDistance(offset, size, containerSize)
                    )
                }
        }
    }
    val arrivalMetadataHidden = app.pictureshow.core.detailMetadataAwaitsHandoff(
        hasActions,
        restored || pendingFocusRestored || !arrivalPending,
        playbackReturn || state.playbackPending,
        recoveryActive || state.error != null || state.readFailed
    )
    val arrivalMetadataSemantics = if (arrivalMetadataHidden) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier
    PlaybackExitFocusEvents(
        app.pictureshow.core.detailSuppressesEmptyHostFocus(
            hasActions && arrivalPending,
            restored,
            playbackReturn || playbackWaiting,
            recoveryHandoff,
            recoveryActive || state.error != null
        )
    )
    CompositionLocalProvider(LocalBringIntoViewSpec provides detailBringIntoView) {
        if (!restored) {
            // Hold input away from hidden semantics. focusTarget adds no accessibility node.
            Box(
                Modifier.width(0.dp).height(0.dp).focusRequester(pendingFocus).onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown) {
                        focusInputRevision++
                        if (semanticsRevealed) restored = true
                    }
                    false
                }.focusTarget()
            )
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = list,
                modifier = reveal.fillMaxSize().background(CatalogBackground).testTag("detail").semantics { testTagsAsResourceId = true }
                    // A returning page must expose its restored input target before TalkBack can
                    // choose a heading or overview as a replacement for the departing player.
                    .then(
                        if (lifecycleState != Lifecycle.State.RESUMED ||
                            (
                                !recoveryActive &&
                                    state.error == null &&
                                    (
                                        app.pictureshow.core.detailArrivalAwaitsAction(
                                            state.loading,
                                            semanticsRevealed || pendingSemanticsRevealed,
                                            playbackWaiting
                                        ) ||
                                            (playItem != null && !semanticsRevealed && !pendingSemanticsRevealed) ||
                                            (pendingEnabled && !pendingSemanticsRevealed)
                                        )
                                )
                        ) {
                            Modifier.clearAndSetSemantics { hideFromAccessibility() }
                        } else {
                            Modifier
                        }
                    ).accessibilityGroup().onPreviewKeyEvent {
                        if (it.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                            // Own the complete Select gesture that started on Retry. Moving
                            // input focus must not deliver its repeats/UP to the fresh Play.
                            if (it.type == KeyEventType.KeyDown &&
                                it.nativeKeyEvent.repeatCount == 0 &&
                                !(retryFocused && showRecovery)
                            ) {
                                recoverySelectKey = null
                            }
                            if ((retryFocused && showRecovery) || recoverySelectKey == it.key) {
                                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0 && recoverySelectKey == null) {
                                    recoverySelectKey = it.key
                                    requestReadRetry()
                                } else if (it.type == KeyEventType.KeyUp && recoverySelectKey == it.key) {
                                    recoverySelectKey = null
                                }
                                return@onPreviewKeyEvent true
                            }
                        }
                        if (it.type == KeyEventType.KeyDown) {
                            focusInputRevision++
                            if (!playbackWaiting) commandNotice = null
                            if (playbackWaiting &&
                                it.key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)
                            ) {
                                pendingViewerMoved = true
                            }
                            // A viewer's first move owns focus even during the brief visible handoff.
                            if (semanticsRevealed) restored = true
                        }
                        if (it.key in listOf(Key.MediaPlay, Key.MediaPlayPause) && pendingActions != null) {
                            if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) pendingActions.onPlay()
                            true
                        } else if (it.key in listOf(Key.MediaPlay, Key.MediaPlayPause) &&
                            !recoveryActive &&
                            state.error == null &&
                            !state.loading &&
                            playItem != null
                        ) {
                            if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) {
                                onPlay(playItem, playItem.userData.playbackPositionTicks > 0)
                            }
                            true
                        } else if (it.type == KeyEventType.KeyDown &&
                            it.key in listOf(Key.DirectionUp, Key.DirectionDown) &&
                            hasActions &&
                            !playbackWaiting &&
                            !recoveryActive
                        ) {
                            val index = sections.indexOfFirst { section -> section.first == selectedSection }
                            val target = index + if (it.key == Key.DirectionUp) -1 else 1
                            if (headerFocused &&
                                focusManager.moveFocus(if (it.key == Key.DirectionDown) FocusDirection.Down else FocusDirection.Up)
                            ) {
                                true
                            } else if (target < sections.size) {
                                moveSection(target)
                                true
                            } else {
                                false
                            }
                        } else {
                            false
                        }
                    },
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                item(key = "body") {
                    Box(Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.accessibilityGroup(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (!playbackWaiting && state.loading && item == null) Text("Loading item…")
                            if (item != null && headerItem != null) {
                                val backdrop = if (headerItem.backdropImageTags.isNotEmpty() ||
                                    headerItem.parentBackdropImageTags.isNotEmpty()
                                ) {
                                    headerItem.copy(imageTags = emptyMap())
                                } else {
                                    headerItem
                                }
                                CatalogArtwork(
                                    backdrop,
                                    state.account,
                                    wide = true,
                                    modifier = Modifier.fillMaxWidth().height(220.dp).testTag("detail:hero"),
                                    alignment = BiasAlignment(0f, -.5f)
                                )
                                CatalogHeading(headerItem.name, modifier = arrivalMetadataSemantics.testTag("detail:title"))
                                if (headerItem.type == "Episode") {
                                    Text(
                                        listOfNotNull(
                                            headerItem.seriesName,
                                            episodeLabel(headerItem).takeIf {
                                                it.isNotBlank()
                                            }
                                        ).joinToString(" · "),
                                        modifier = arrivalMetadataSemantics
                                    )
                                }
                                val metadata = listOfNotNull(
                                    headerItem.productionYear?.toString(),
                                    headerItem.officialRating,
                                    headerItem.communityRating?.let(::communityRatingLabel),
                                    headerItem.runTimeTicks?.let(::runtimeLabel)?.takeIf(String::isNotBlank)
                                )
                                if (metadata.isNotEmpty()) {
                                    Text(
                                        metadata.joinToString(" · "),
                                        color = CatalogMuted,
                                        modifier = arrivalMetadataSemantics
                                    )
                                }
                                val container = item.type in listOf("Series", "Season")
                                if (hasActions && container && (!playbackWaiting || pendingEnabled || recoveryActive || recoveryHandoff)) {
                                    DetailActions(
                                        if (recoveryActive) headerItem else item, playItem, state.busy, initial,
                                        if (playbackLayout) {
                                            { queueReadyMutation(true) }
                                        } else {
                                            onPlayed
                                        },
                                        if (playbackLayout) {
                                            { queueReadyMutation(false) }
                                        } else {
                                            onFavorite
                                        },
                                        onPlay,
                                        playedFocus, favoriteFocus, pendingActions, ::headerFocus,
                                        stableLayout =
                                        playbackLayout || playbackWaiting,
                                        readyQueuedTargets = readyQueuedTargets,
                                        requestCueTarget = requestCueTarget.takeIf { it == headerInputTarget },
                                        readiness = readiness,
                                        onReadyCue = { target, label ->
                                            readyDeliveryTarget = target
                                            readyDeliveryLabel = label
                                        },
                                        readyCancelled =
                                        state.error != null || waitHadRequest || readyQueuedTargets.isNotEmpty() || state.busy,
                                        fromStartFocus = fromStartFocus,
                                        blocked = recoveryActive || (playbackWaiting && pendingActions == null)
                                    ) {
                                        if (trackSelection) selectedSection = "header"
                                    }
                                }
                                headerItem.overview?.takeIf(String::isNotBlank)?.let { Text(it, modifier = arrivalMetadataSemantics) }
                                if (headerItem.genres.isNotEmpty()) {
                                    Text(
                                        headerItem.genres.joinToString(" · "),
                                        color = CatalogMuted,
                                        modifier = arrivalMetadataSemantics
                                    )
                                }
                                if (hasActions && !container && (!playbackWaiting || pendingEnabled || recoveryActive || recoveryHandoff)) {
                                    DetailActions(
                                        if (recoveryActive) headerItem else item, playItem, state.busy, initial,
                                        if (playbackLayout) {
                                            { queueReadyMutation(true) }
                                        } else {
                                            onPlayed
                                        },
                                        if (playbackLayout) {
                                            { queueReadyMutation(false) }
                                        } else {
                                            onFavorite
                                        },
                                        onPlay,
                                        playedFocus, favoriteFocus, pendingActions, ::headerFocus,
                                        stableLayout =
                                        playbackLayout || playbackWaiting,
                                        readyQueuedTargets = readyQueuedTargets,
                                        requestCueTarget = requestCueTarget.takeIf { it == headerInputTarget },
                                        readiness = readiness,
                                        onReadyCue = { target, label ->
                                            readyDeliveryTarget = target
                                            readyDeliveryLabel = label
                                        },
                                        readyCancelled =
                                        state.error != null || waitHadRequest || readyQueuedTargets.isNotEmpty() || state.busy,
                                        fromStartFocus = fromStartFocus,
                                        blocked = recoveryActive || (playbackWaiting && pendingActions == null)
                                    ) {
                                        if (trackSelection) selectedSection = "header"
                                    }
                                }
                                // Add authoritative metadata below the published header instead of
                                // inserting lines above its focused buttons or reader text nodes.
                                if (!playbackWaiting && playbackHeader != null) {
                                    val additions = buildList {
                                        if (item.name != headerItem.name) add(item.name)
                                        if (item.productionYear != headerItem.productionYear) {
                                            item.productionYear?.let { add(it.toString()) }
                                        }
                                        if (item.officialRating != headerItem.officialRating) item.officialRating?.let { add(it) }
                                        if (item.communityRating != headerItem.communityRating) {
                                            item.communityRating?.let { add(communityRatingLabel(it)) }
                                        }
                                        if (item.runTimeTicks != headerItem.runTimeTicks) {
                                            item.runTimeTicks?.let(::runtimeLabel)?.takeIf(String::isNotBlank)?.let { add(it) }
                                        }
                                        if (item.overview != headerItem.overview) {
                                            item.overview?.takeIf(String::isNotBlank)?.let { add(it) }
                                        }
                                        if (item.genres != headerItem.genres && item.genres.isNotEmpty()) {
                                            add(item.genres.joinToString(" · "))
                                        }
                                    }
                                    if (additions.isNotEmpty()) {
                                        Text(
                                            "More details",
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.semantics { heading() }
                                        )
                                        additions.forEach { Text(it) }
                                    }
                                }
                                if (state.loading &&
                                    !playbackWaiting
                                ) {
                                    Text(
                                        if (item.type in
                                            listOf("Series", "Season")
                                        ) {
                                            "Loading episodes…"
                                        } else {
                                            "Loading item…"
                                        }
                                    )
                                }
                            }
                        }
                        // An overlay has no layout footprint: status publication, changed wording and
                        // removal cannot move the hero or the reader's selected action underneath it.
                        if (showRecovery) {
                            Column(
                                Modifier.align(Alignment.TopEnd).width(420.dp).background(CatalogBackground).padding(12.dp)
                                    .accessibilityGroup(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (showRecovery) {
                                    Button(
                                        onClick = ::requestReadRetry,
                                        modifier = Modifier.width(200.dp).heightIn(min = 72.dp).focusRequester(retry)
                                            .testTag("detail:retry").onFocusChanged { retryFocused = it.isFocused }
                                            .detailFocusDiagnostic("retry").semantics {
                                                // TalkBack TV speaks state before the button label. Lead with
                                                // Retry so a long reason cannot defer the usable action cue.
                                                // Focus/state events suffice; a live region queues duplicate
                                                // loading cues behind which a fresh-action announcement waits.
                                                stateDescription = if (retryWorking || recoveryHandoff) {
                                                    "Loading item. Please wait."
                                                } else {
                                                    app.pictureshow.core.detailRetryDescription(recoveryReason)
                                                }
                                            }
                                    ) { Text(if (retryWorking || recoveryHandoff) "Retrying…" else "Retry") }
                                    Text(
                                        app.pictureshow.core.detailReadBandText(retryWorking || recoveryHandoff, recoveryReason),
                                        color = Color(0xFFFFCCB8),
                                        modifier = Modifier.testTag("detail:read-error")
                                    )
                                }
                            }
                        } else if (commandNotice != null || (playbackWaiting && state.error == null)) {
                            val status = commandNotice ?: when {
                                state.readSucceeded && !state.loading && state.playbackPending && !state.playbackReportFinished ->
                                    "Item loaded. Saving progress…"

                                !state.playbackPending || state.playbackReportFinished -> "Loading item…"

                                else -> "Saving progress…"
                            }
                            Box(Modifier.align(Alignment.TopEnd).background(CatalogBackground).padding(8.dp)) {
                                if (commandNotice == null) {
                                    // Passive phases remain readable, without queuing speech ahead
                                    // of the focused request, failure or Ready state.
                                    // Before the read succeeds, preserve the existing hidden status;
                                    // after it succeeds, retain its readable focus surface and geometry.
                                    if (pendingEnabled && !state.readSucceeded) {
                                        Text(status, modifier = Modifier.clearAndSetSemantics {})
                                    } else {
                                        StatusLine(status, announce = false)
                                    }
                                } else {
                                    StatusLine(status)
                                }
                            }
                        }
                    }
                }
                sections.forEach { (section, items) ->
                    item(key = section) {
                        DetailRow(
                            title = when (section) {
                                "next" -> "Next up"
                                "seasons" -> "Seasons"
                                else -> "Episodes"
                            },
                            section = section,
                            items = items,
                            account = state.account,
                            onOpen = if (section == "seasons") onSeason else onOpen,
                            wide = section != "seasons",
                            selectedId = if (section == "seasons") state.selectedSeasonId else null,
                            onFocusRested = if (section == "seasons") {
                                { season -> if (trackSelection) onSeason(season) }
                            } else {
                                null
                            },
                            rowState = rowStates.getValue(section),
                            requests = requests,
                            onFocus = { focused ->
                                if (trackSelection) {
                                    selectedSection = section
                                    selectedId = focused.id
                                    rowSelections = rowSelections + (section to focused.id)
                                }
                            }
                        )
                    }
                }
                if (app.pictureshow.core.showDetailEmptyState(
                        item?.type,
                        state.loading,
                        state.readSucceeded,
                        state.readFailed,
                        state.episodes.size,
                        state.seasons.size
                    )
                ) {
                    item(key = "empty") { Text("No episodes are available.") }
                }
            }
            if (writeFailure != null) {
                Column(
                    Modifier.align(Alignment.TopEnd).padding(horizontal = 48.dp, vertical = 32.dp)
                        .width(420.dp).background(CatalogBackground).padding(12.dp)
                        .then(
                            if (lifecycleState != Lifecycle.State.RESUMED) {
                                Modifier.clearAndSetSemantics { hideFromAccessibility() }
                            } else {
                                Modifier
                            }
                        ).accessibilityGroup(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // This one failure event reaches the reader even when a lower action owns
                    // focus. Keep the visible reason and spoken reason on the same stable node.
                    Text(
                        "Change failed. $writeFailure",
                        color = Color(0xFFFFCCB8),
                        modifier = Modifier.testTag("detail:write-error").semantics {
                            contentDescription = "Change failed. $writeFailure Retry is available."
                            liveRegion = LiveRegionMode.Assertive
                        }
                    )
                    Button(onClick = onRetry, modifier = Modifier.focusRequester(retry).testTag("detail:write-retry")) { Text("Retry") }
                }
            }
        }
    }
    LaunchedEffect(
        lifecycleState,
        state.loading,
        state.readSucceeded,
        semanticsRevealed,
        pendingSemanticsRevealed,
        pendingFocusRestored,
        restored
    ) {
        val fields = "loading=${state.loading} succeeded=${state.readSucceeded} revealed=$semanticsRevealed " +
            "pendingRevealed=$pendingSemanticsRevealed pendingFocused=$pendingFocusRestored restored=$restored " +
            "metadataHidden=$arrivalMetadataHidden lifecycle=$lifecycleState"
        // Release builds have no timing sink; the device timeline reads this one line per change
        // (debug builds get the same line, so it is not also sent to the sink).
        PlaybackDiagnostics.write("detail-handoff phase=publication uptime=${android.os.SystemClock.uptimeMillis()} $fields")
    }
    LaunchedEffect(
        lifecycleState,
        state.loading,
        playbackWaiting,
        pendingCanPlay,
        pendingEnabled,
        onPendingAction != null,
        item?.id,
        state.error,
        recoveryActive,
        sections.map {
            it.first to
                it.second.map(MediaItem::id)
        }
    ) {
        PlaybackDiagnostics.write(
            "detail-handoff phase=restore-start uptime=${android.os.SystemClock.uptimeMillis()} " +
                "lifecycle=$lifecycleState loading=${state.loading} waiting=$playbackWaiting " +
                "pendingPublished=$pendingSemanticsRevealed focused=${headerInputTarget != null}"
        )
        // Navigation composes the returning page before its transition has finished. A focus
        // event then is hidden from TalkBack, which falls back to the heading/overview instead.
        if (lifecycleState != Lifecycle.State.RESUMED) {
            restored = false
            semanticsRevealed = false
            pendingSemanticsRevealed = false
            readerMovement.reset()
            pendingFocusRestored = false
            recoverySelectKey = null
            return@LaunchedEffect
        }
        if (recoveryActive) {
            recoveryTarget = null
            selectedSection = "header"
            commandGeneration++
            readyQueuedTargets = emptySet()
            pendingFailed = true
            pendingSemanticsRevealed = false
            readerMovement.reset()
            pendingFocusRestored = false
            playbackReturn = false
            semanticsRevealed = true
            restored = false
            if (list.firstVisibleItemIndex != 0 || list.firstVisibleItemScrollOffset != 0) list.requestScrollToItem(0)
            repeat(2) { withFrameNanos { } }
            restored = retry.requestFocus()
            recoveryFocusOwned = restored
            reportFocus("retry", restored)
            return@LaunchedEffect
        }
        if (recoveryFocusOwned) {
            pendingFailed = false
            if (state.error == null && !state.readFailed) {
                // Clearing the error publishes pending controls in the next composition.
                // Do not focus the temporary disabled fallback underneath their slot.
                if (playbackWaiting && pendingCanPlay && queuePendingAction != null && !pendingEnabled) return@LaunchedEffect
                // Retry remains a real visible target while the fresh actions publish.
                // Do not park focus or scroll back to a previously selected shelf here.
                semanticsRevealed = true
                // A successful read can publish guarded pending controls before reporting
                // finishes. Reveal that same target before the reader acknowledges focus.
                if (pendingEnabled) pendingSemanticsRevealed = true
                restored = true
                fun freshControlHasFocus() = !retryFocused &&
                    (headerInputTarget != null || playbackContentFocused(focusHost) == true)
                if (!readSettled && !freshControlHasFocus()) {
                    // A viewer can leave Retry after this second read has begun.
                    // Observe that choice without waiting for another model emission.
                    snapshotFlow { freshControlHasFocus() }.first { it }
                }
                repeat(2) { withFrameNanos { } }
                val freshControlFocused = freshControlHasFocus()
                if (!readSettled && !freshControlFocused) return@LaunchedEffect
                val targetAlreadyOwned = recoveryTarget != null && recoveryTargetInputRevision == focusInputRevision && freshControlFocused
                val viewerChoseControl = freshControlFocused && !targetAlreadyOwned
                val focused = freshControlFocused || runCatching { initial.requestFocus() }.getOrDefault(false)
                if (focused) {
                    if (!freshControlFocused) {
                        selectedSection = "header"
                        recoveryTarget = when {
                            playItem != null || pendingEnabled -> "primary"
                            item?.isEmptyContainer == true -> "favorite"
                            else -> "played"
                        }
                        recoveryTargetInputRevision = focusInputRevision
                    }
                    // Input focus must leave Retry even if the reader selected text:
                    // the error text disappears with this overlay. Static text alone
                    // cannot acknowledge that the new action has become selectable.
                    repeat(2) { withFrameNanos { } }
                    awaitPlaybackReaderInputFocus(focusHost)
                    recoveryFocusOwned = false
                    pendingFocusRestored = playbackWaiting
                    restored = true
                    playbackReturn = false
                    pendingHeaderTarget = "primary"
                    pendingViewerMoved = false
                    reportFocus(
                        if (viewerChoseControl ||
                            recoveryTargetInputRevision != focusInputRevision
                        ) {
                            "viewer"
                        } else {
                            recoveryTarget ?: "viewer"
                        },
                        true
                    )
                    recoveryTarget = null
                    return@LaunchedEffect
                }
            }
            // A detached target or an intervening write error must release the overlay
            // and use the normal restore in this same effect; no key change is needed.
            recoveryFocusOwned = false
            recoveryTarget = null
            restored = false
        }
        if (playbackWaiting) {
            restored = false
            semanticsRevealed = false
            if (state.error != null) {
                playbackReturn = false
                pendingFailed = true
                pendingSemanticsRevealed = false
                readerMovement.reset()
                pendingFocusRestored = false
                list.jumpTo(0)
                val focused = retry.requestFocus()
                reportFocus("retry", focused)
            } else if (pendingEnabled) {
                if (!pendingFocusRestored) {
                    list.jumpTo(0)
                    if (!pendingSemanticsRevealed) {
                        withFrameNanos { }
                        // An already-focused hidden control emits no new Focused change on publish.
                        // Use the same unfocused publication sequence as the fresh return below.
                        pendingFocus.requestFocus()
                        pendingSemanticsRevealed = true
                        PlaybackDiagnostics.write("detail-handoff phase=pending-publish uptime=${android.os.SystemClock.uptimeMillis()}")
                    }
                    // A loading-phase update can cancel this effect between publication and focus.
                    repeat(2) { withFrameNanos { } }
                    PlaybackDiagnostics.write("detail-handoff phase=pending-request uptime=${android.os.SystemClock.uptimeMillis()}")
                    pendingFocusRestored = when (pendingHeaderTarget) {
                        "played" -> playedFocus
                        "favorite" -> favoriteFocus
                        "from-start" -> fromStartFocus
                        else -> initial
                    }.requestFocus()
                    PlaybackDiagnostics.write(
                        "detail-handoff phase=pending-result uptime=${android.os.SystemClock.uptimeMillis()} focused=$pendingFocusRestored"
                    )
                }
            } else {
                pendingSemanticsRevealed = false
                readerMovement.reset()
                pendingFocusRestored = false
            }
            return@LaunchedEffect
        }
        // A season chosen in the Seasons row reloads only the Episodes shelf under it: the focused season card stays, so
        // the page isn't an arrival. Hiding it from TalkBack for the read dropped the reader's focus from the card, and
        // TalkBack put it on Next up; the viewer's next Right then left the row (rc33, fast walks across 32 seasons).
        // A full refresh or item replacement can remove that card. Then reset and restore normally; a remembered
        // section name alone is not evidence that its focus target survived the load.
        val seasonSwitchInPlace = app.pictureshow.core.detailRetainsSeasonFocus(
            selectedSection,
            restored,
            semanticsRevealed,
            selectedId,
            state.seasons.map(MediaItem::id)
        )
        if (state.loading && selectedSection != "header" && !seasonSwitchInPlace) {
            restored = false
            semanticsRevealed = false
        }
        if (!state.loading && !restored) {
            val readerMovedToText = pendingSemanticsRevealed && readerMovement.observe(playbackReaderTarget(focusHost))
            if (readerMovedToText) {
                pendingViewerMoved = true
                reportPendingFocus()
            }
            val headerValid = when (headerInputTarget) {
                "primary", "from-start" -> playItem != null
                "played" -> (playbackLayout || !state.busy) && item?.isEmptyContainer == false
                "favorite" -> (playbackLayout || !state.busy) && item != null
                else -> false
            }
            if (state.error == null &&
                pendingSemanticsRevealed &&
                pendingFocusRestored &&
                headerValid &&
                (selectedSection == "header" || pendingViewerMoved)
            ) {
                // These are the same text/actions, already published and focused. Clearing
                // and refocusing would override a reader's Up move onto the static metadata.
                semanticsRevealed = true
                restored = true
                playbackReturn = false
                if (pendingFailed) commandNotice = null
                pendingExpired = false
                pendingFailed = false
                pendingSemanticsRevealed = false
                readerMovement.reset()
                pendingFocusRestored = false
                val target = if (readerMovedToText) "viewer" else headerInputTarget ?: "none"
                selectedSection = "header"
                pendingHeaderTarget = "primary"
                pendingViewerMoved = false
                reportFocus(target, true)
                return@LaunchedEffect
            }
            pendingSemanticsRevealed = false
            readerMovement.reset()
            pendingFocusRestored = false
            val inputBeforeRestore = focusInputRevision
            withFrameNanos { }
            pendingFocus.requestFocus()
            val savedSectionIndex = sections.indexOfFirst { it.first == selectedSection }
            val sectionIndex = if (pendingViewerMoved || pendingHeaderTarget != "primary") {
                // The viewer explicitly chose a header action while the saved shelf was hidden.
                -1
            } else if (savedSectionIndex >= 0) {
                savedSectionIndex
            } else if (selectedSection == "header") {
                -1
            } else {
                sections.indices.firstOrNull() ?: -1
            }
            var targetKey: String? = null
            var target: FocusRequester? = null
            var targetSection: String? = null
            var targetId: String? = null
            var headerTarget: String? = null
            if (sectionIndex >= 0) {
                val (section, items) = sections[sectionIndex]
                val savedId = if (savedSectionIndex < 0 && section == "seasons") state.selectedSeasonId else selectedId
                val itemIndex = items.indexOfFirst { it.id == savedId }.coerceAtLeast(0)
                val horizontal = rowStates.getValue(section)
                list.jumpTo(sectionIndex + 1)
                if (horizontal.layoutInfo.visibleItemsInfo.none { it.index == itemIndex }) horizontal.jumpTo(itemIndex)
                targetKey = "$section:${items[itemIndex].id}"
                targetSection = section
                targetId = items[itemIndex].id
            } else if (hasActions || state.error != null) {
                list.jumpTo(0)
                target = when {
                    state.error != null -> retry

                    pendingHeaderTarget == "from-start" && playItem != null -> {
                        headerTarget = "from-start"
                        fromStartFocus
                    }

                    pendingHeaderTarget == "played" && (playbackLayout || !state.busy) && item?.isEmptyContainer == false -> {
                        headerTarget = "played"
                        playedFocus
                    }

                    pendingHeaderTarget == "favorite" && (playbackLayout || !state.busy) -> {
                        headerTarget = "favorite"
                        favoriteFocus
                    }

                    else -> initial
                }
            }
            // Publish the target unfocused before requesting it. Focusing a hidden/new node
            // produces no Focused property change for Compose's accessibility delegate. Clearing
            // owner focus cannot help on TV: View.clearFocus synchronously refocuses the first item.
            semanticsRevealed = true
            detailTiming("focus-publish")
            PlaybackDiagnostics.write("detail-handoff phase=fresh-publish uptime=${android.os.SystemClock.uptimeMillis()}")
            repeat(2) { withFrameNanos { } }
            detailTiming("focus-request")
            PlaybackDiagnostics.write("detail-handoff phase=fresh-request uptime=${android.os.SystemClock.uptimeMillis()}")
            var handoffTarget = "viewer"
            var handoffFocused = playbackContentFocused(focusHost) == true
            if (focusInputRevision == inputBeforeRestore) {
                handoffTarget = targetSection ?: headerTarget ?: if (state.error != null) {
                    "retry"
                } else if (target != null) {
                    when {
                        playItem != null -> "primary"
                        item?.isEmptyContainer == true -> "favorite"
                        else -> "played"
                    }
                } else {
                    "none"
                }
                val key = targetKey
                val focused = if (key != null) {
                    requestAttached(requests, key) { focusInputRevision == inputBeforeRestore }
                } else {
                    target?.requestFocus() ?: false
                }
                handoffFocused = focused
                if (focused) {
                    selectedSection = targetSection ?: "header"
                    targetId?.let {
                        selectedId = it
                        rowSelections = rowSelections + (selectedSection to it)
                    }
                }
            }
            // Failure must still reveal the page and re-enable ordinary selection tracking.
            // Removing the temporary target also prevents an invisible input-focus trap.
            restored = true
            playbackReturn = false
            if (state.error == null) {
                if (pendingFailed) commandNotice = null
                pendingExpired = false
                pendingFailed = false
            }
            // This choice belongs to this pending return, not later lifecycle/shelf restores.
            pendingHeaderTarget = "primary"
            pendingViewerMoved = false
            reportFocus(handoffTarget, handoffFocused)
        }
    }
}

private fun Modifier.detailFocusDiagnostic(target: String): Modifier = onFocusChanged {
    if (BuildConfig.DEBUG && it.isFocused) PlaybackDiagnostics.write("detail-focus target=$target")
}

private data class DetailCommand(val generation: Int, val mutationTarget: String?, val action: () -> Unit)

private data class PendingDetailActions(
    val available: Boolean,
    val requestedTargets: Set<String>,
    val playLabel: String,
    val fromStartLabel: String,
    val playedLabel: String,
    val favoriteLabel: String,
    val onPlay: () -> Unit,
    val onFromStart: () -> Unit,
    val onPlayed: () -> Unit,
    val onFavorite: () -> Unit
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailActions(
    item: MediaItem,
    playItem: MediaItem?,
    busy: Boolean,
    initial: FocusRequester,
    onPlayed: () -> Unit,
    onFavorite: () -> Unit,
    onPlay: (MediaItem, Boolean) -> Unit,
    playedFocus: FocusRequester? = null,
    favoriteFocus: FocusRequester? = null,
    pending: PendingDetailActions? = null,
    onHeaderFocus: (String, Boolean) -> Unit = { _, _ -> },
    stableLayout: Boolean = false,
    readyQueuedTargets: Set<String> = emptySet(),
    requestCueTarget: String? = null,
    readiness: app.pictureshow.core.DetailReadyFeedback,
    onReadyCue: (String?, String?) -> Unit,
    readyCancelled: Boolean = false,
    fromStartFocus: FocusRequester? = null,
    blocked: Boolean = false,
    onFocus: () -> Unit
) {
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val feedbackHost = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
    var focusedTarget by remember { mutableStateOf<String?>(null) }
    val position = playItem?.userData?.playbackPositionTicks ?: 0
    val labels = mapOf(
        "primary" to (pending?.playLabel ?: if (position > 0) resumeLabel(position) else "Play"),
        "from-start" to (pending?.fromStartLabel ?: "Play from start"),
        "played" to (
            pending?.playedLabel ?: if (blocked) {
                app.pictureshow.core.detailUnavailableActionLabel("played")
            } else if ("played" in readyQueuedTargets) {
                app.pictureshow.core.detailRequestedActionLabel("played", requestCueTarget == "played")
            } else if (item.isWatched) {
                "Mark unwatched"
            } else {
                "Mark watched"
            }
            ),
        "favorite" to (
            pending?.favoriteLabel ?: if (blocked) {
                app.pictureshow.core.detailUnavailableActionLabel("favorite")
            } else if ("favorite" in readyQueuedTargets) {
                app.pictureshow.core.detailRequestedActionLabel("favorite", requestCueTarget == "favorite")
            } else if (item.userData.isFavorite) {
                "Unfavorite"
            } else {
                "Favorite"
            }
            )
    )
    // Observe selection during composition: reads made only inside SideEffect do not
    // subscribe this group to the focus callback's state changes.
    val readyTarget = focusedTarget
    val readyUsable = when (readyTarget) {
        "primary", "from-start" -> playItem != null
        "played" -> !item.isEmptyContainer && (stableLayout || !busy)
        "favorite" -> stableLayout || !busy
        else -> false
    }
    val accessibility = feedbackHost.context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
        as android.view.accessibility.AccessibilityManager
    SideEffect {
        val active = lifecycle == Lifecycle.State.RESUMED && accessibility.isEnabled
        val cue = readiness.update(
            blocked || pending != null,
            readyTarget,
            active,
            readyUsable,
            cancelled = readyCancelled
        )
        if (cue != null && readyTarget != null && detailReaderFocusMatches(feedbackHost, readyTarget)) {
            onReadyCue(readyTarget, labels[readyTarget])
        } else {
            if (cue != null) readiness.cancel()
            onReadyCue(null, null)
        }
    }
    var focused by remember { mutableStateOf(false) }
    var scrolling by remember { mutableStateOf<Job?>(null) }
    val focusMargin = with(LocalDensity.current) { 8.dp.toPx() }
    val childBringIntoViews = remember {
        listOf("primary", "played", "favorite", "from-start").associateWith { BringIntoViewRequester() }
    }
    val focusedChildren = remember { mutableSetOf<String>() }
    val firstLayoutRecorded = remember { booleanArrayOf(false) }
    fun revealFocusedBounds(coordinates: LayoutCoordinates, requester: BringIntoViewRequester) {
        if (scrolling?.isActive == true) return
        val top = coordinates.positionInRoot().y
        val bottom = top + coordinates.size.height
        val viewport = coordinates.findRootCoordinates().size.height
        if (top < focusMargin || bottom > viewport - focusMargin) {
            scrolling = scope.launch {
                requester.bringIntoView(
                    Rect(-focusMargin, -focusMargin, coordinates.size.width + focusMargin, coordinates.size.height + focusMargin)
                )
            }
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.bringIntoViewRequester(bringIntoView).onFocusChanged {
            focused = it.hasFocus
            if (it.hasFocus) onFocus()
        }.onGloballyPositioned { coordinates ->
            if (!firstLayoutRecorded[0]) {
                firstLayoutRecorded[0] = true
                detailTiming("actions-first-layout")
            }
            // Ordinary headers may gain metadata after focus. Returning headers freeze it and
            // reserve slots, so check their focused child instead: an unfocused second row must
            // not scroll an already-visible primary upward. Child checks also cover later errors
            // or label wrapping that move the focused button without another focus event.
            if (!stableLayout && focused) revealFocusedBounds(coordinates, bringIntoView)
        }.focusProperties {
            // Let the parent route a shelf exit to its remembered card, after spatial navigation
            // has exhausted the wrapped action rows. Programmatic requests remain unrestricted.
            onExit = {
                if (requestedFocusDirection == FocusDirection.Up || requestedFocusDirection == FocusDirection.Down) cancelFocusChange()
            }
        }.focusGroup()
    ) {
        val suffix = if (pending != null) "-pending" else ""

        val requestedTargets = readyQueuedTargets + pending?.requestedTargets.orEmpty()
        val activeRequestCue = requestCueTarget?.takeIf { it in requestedTargets }

        // The parent supplies the plain action name and native disabled property.
        // Keep this wording visible without also merging it into TalkBack's label.
        fun labelSemantics(target: String): Modifier =
            if (blocked || pending?.available == false || target in requestedTargets) Modifier.clearAndSetSemantics {} else Modifier

        fun actionModifier(target: String, tag: String, click: () -> Unit): Modifier {
            val childBringIntoView = childBringIntoViews.getValue(target)
            return Modifier
                .then(
                    if (stableLayout) {
                        Modifier.bringIntoViewRequester(childBringIntoView).onGloballyPositioned { coordinates ->
                            if (target in focusedChildren) revealFocusedBounds(coordinates, childBringIntoView)
                        }.width(detailStableWidth(target)).heightIn(min = DETAIL_STABLE_HEIGHT)
                    } else {
                        Modifier
                    }
                )
                .onFocusChanged {
                    if (it.isFocused) {
                        focusedChildren.add(target)
                        focusedTarget = target
                    } else {
                        focusedChildren.remove(target)
                        if (focusedTarget == target) focusedTarget = null
                    }
                    onHeaderFocus(target, it.isFocused)
                    if (BuildConfig.DEBUG && it.isFocused) PlaybackDiagnostics.write("detail-focus target=$target")
                    if (it.isFocused) detailTiming("focus-acquired", "target=$target")
                }.testTag("detail:$tag$suffix").semantics {
                    // Identify this virtual action independently of an outgoing player node.
                    testTagsAsResourceId = true
                    // Keep Material input focus/geometry while exposing the real availability
                    // to services. Compose omits click actions for disabled semantics.
                    if (pending?.available == false) disabled()
                    val available = !blocked && pending?.available != false
                    if (!available) {
                        contentDescription = app.pictureshow.core.detailHeldActionLabel(target)
                        role = Role.Button
                    }
                    val currentState = when (target) {
                        "played" -> if (item.isWatched) "Watched" else "Unwatched"
                        "favorite" -> if (item.userData.isFavorite) "Favorite" else "Not favorite"
                        else -> null
                    }
                    val requested = target in requestedTargets
                    // Acceptance is a one-shot event. Rediscovery exposes the outstanding request.
                    val requestedCue = requested
                    when {
                        requestedCue -> {
                            contentDescription = app.pictureshow.core.detailRequestedActionLabel(target, false)
                            role = Role.Button
                            if (activeRequestCue != target && available) currentState?.let { stateDescription = it }
                        }

                        else -> app.pictureshow.core.detailActionDescription(
                            currentState,
                            available && activeRequestCue != target
                        )?.let { stateDescription = it }
                    }
                }.onPreviewKeyEvent {
                    // Handle pending Select at DOWN, before a fresh callback can replace it.
                    // Activity's handoff owns the matching UP/repeats, even across completion.
                    if (!blocked &&
                        pending != null &&
                        it.type == KeyEventType.KeyDown &&
                        it.nativeKeyEvent.repeatCount == 0 &&
                        it.nativeKeyEvent.keyCode in listOf(
                            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                            android.view.KeyEvent.KEYCODE_ENTER,
                            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                        )
                    ) {
                        click()
                        true
                    } else {
                        false
                    }
                }
        }
        // TV Material raises the focused surface. Stable parents keep native traversal order
        // and node identities unchanged as pending labels become authoritative actions.
        key("primary") {
            Box {
                val click: () -> Unit = pending?.onPlay ?: {
                    playItem?.let { onPlay(it, position > 0) }
                    Unit
                }
                Button(
                    onClick = { if (!blocked) click() },
                    enabled = !blocked && (pending != null || playItem != null),
                    modifier = (if (pending != null || playItem != null) Modifier.focusRequester(initial) else Modifier)
                        .then(actionModifier("primary", "play", click))
                        .then(
                            if (pending == null && playItem == null && item.type in listOf("Series", "Season")) {
                                Modifier.clearAndSetSemantics { hideFromAccessibility() }
                            } else {
                                Modifier
                            }
                        )
                ) {
                    DetailActionLabel(
                        labels.getValue("primary"),
                        if (stableLayout) "primary" else null,
                        Modifier.testTag("detail:play$suffix-label").then(labelSemantics("primary"))
                    )
                }
            }
        }
        if (!stableLayout && position > 0) {
            key("from-start") {
                Box {
                    Button(
                        onClick = { if (!blocked) playItem?.let { onPlay(it, false) } },
                        enabled = !blocked,
                        modifier = Modifier.detailFocusDiagnostic("from-start")
                    ) { Text("Play from start") }
                }
            }
        }
        key("played") {
            Box {
                val click = pending?.onPlayed ?: onPlayed
                Button(
                    onClick = { if (!blocked) click() },
                    enabled = !blocked && (pending != null || (!item.isEmptyContainer && (stableLayout || !busy))),
                    modifier = (
                        if (pending == null &&
                            playItem == null &&
                            !item.isEmptyContainer
                        ) {
                            Modifier.focusRequester(initial)
                        } else {
                            Modifier
                        }
                        )
                        .then(playedFocus?.let { Modifier.focusRequester(it) } ?: Modifier)
                        .then(actionModifier("played", "played", click)).semantics {
                            if (!blocked && pending?.available != false && activeRequestCue != "played") {
                                toggleableState = ToggleableState(item.isWatched)
                            }
                        }
                ) {
                    DetailActionLabel(
                        labels.getValue("played"),
                        if (stableLayout) "played" else null,
                        Modifier.testTag("detail:played$suffix-label").then(labelSemantics("played"))
                    )
                }
            }
        }
        key("favorite") {
            Box {
                val click = pending?.onFavorite ?: onFavorite
                Button(
                    onClick = { if (!blocked) click() },
                    enabled = !blocked && (pending != null || stableLayout || !busy),
                    modifier = (
                        if (pending == null &&
                            playItem == null &&
                            item.isEmptyContainer
                        ) {
                            Modifier.focusRequester(initial)
                        } else {
                            Modifier
                        }
                        )
                        .then(favoriteFocus?.let { Modifier.focusRequester(it) } ?: Modifier)
                        .then(actionModifier("favorite", "favorite", click)).semantics {
                            if (!blocked && pending?.available != false && activeRequestCue != "favorite") {
                                toggleableState = ToggleableState(item.userData.isFavorite)
                            }
                        }
                ) {
                    DetailActionLabel(
                        labels.getValue("favorite"),
                        if (stableLayout) "favorite" else null,
                        Modifier.testTag("detail:favorite$suffix-label").then(labelSemantics("favorite"))
                    )
                }
            }
        }
        if (stableLayout) {
            key("from-start") {
                Box {
                    val click = pending?.onFromStart ?: {
                        playItem?.let { onPlay(it, false) }
                        Unit
                    }
                    Button(
                        onClick = { if (!blocked) click() },
                        enabled = !blocked && (pending != null || playItem != null),
                        modifier = (fromStartFocus?.let { Modifier.focusRequester(it) } ?: Modifier)
                            .then(actionModifier("from-start", "from-start", click))
                    ) {
                        DetailActionLabel(
                            labels.getValue("from-start"),
                            if (stableLayout) "from-start" else null,
                            Modifier.testTag("detail:from-start$suffix-label").then(labelSemantics("from-start"))
                        )
                    }
                }
            }
        }
    }
}

private val DETAIL_STABLE_HEIGHT = 72.dp

private fun detailStableWidth(target: String) = if (target == "primary" || target == "from-start") 200.dp else 260.dp

/**
 * After playback the pills keep a fixed size so nothing moves as labels change. TV Material's Button leaves its content at
 * the surface's top start, so the wording has to fill the room inside the button's padding to sit in the middle.
 */
@Composable
private fun DetailActionLabel(label: String, stableTarget: String?, modifier: Modifier) {
    if (stableTarget == null) {
        Text(label, modifier = modifier)
    } else {
        Box(
            Modifier.width(detailStableWidth(stableTarget) - 32.dp).heightIn(min = DETAIL_STABLE_HEIGHT - 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(label, modifier = modifier, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DetailRow(
    title: String,
    section: String,
    items: List<MediaItem>,
    account: Account,
    onOpen: (MediaItem) -> Unit,
    wide: Boolean,
    rowState: LazyListState,
    requests: MutableMap<String, FocusRequester>,
    onFocus: (MediaItem) -> Unit,
    selectedId: String? = null,
    onFocusRested: ((MediaItem) -> Unit)? = null
) {
    Column(modifier = Modifier.accessibilityGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CatalogHeading(title, large = false)
        ShelfWithEnds(title, rowState, items.lastIndex) { endCard, rowSemantics ->
            LazyRow(
                state = rowState,
                modifier = Modifier.fillMaxWidth().then(rowSemantics).focusRestorer(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    val requester = remember(item.id) { FocusRequester() }
                    val focusKey = "$section:${item.id}"
                    var focused by remember(item.id) { mutableStateOf(false) }
                    val selectOnRest by rememberUpdatedState(onFocusRested)
                    LaunchedEffect(focused, selectedId) {
                        if (focused && item.id != selectedId && selectOnRest != null) {
                            delay(350)
                            selectOnRest?.invoke(item)
                        }
                    }
                    DisposableEffect(focusKey) {
                        requests[focusKey] = requester
                        onDispose { requests.remove(focusKey) }
                    }
                    endCard(index) { endModifier ->
                        CatalogCard(
                            item,
                            account,
                            wide,
                            onClick = { onOpen(item) },
                            selected = selectedId?.let { item.id == it },
                            modifier = Modifier.focusRequester(requester).testTag("detail:$focusKey")
                                .staysInRow(index == 0, index == items.lastIndex)
                                .then(endModifier)
                                .onFocusChanged {
                                    focused = it.isFocused
                                    if (it.isFocused) onFocus(item)
                                }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Puts [index] at the viewport's start, for code about to move focus there. Not scrollToItem: a bring-into-view left running
 * by the last focus change holds the list's scroll mutex at a priority that makes scrollToItem throw CancellationException,
 * and `launch` swallows that, so everything after it, the focus request included, never runs and the press is lost.
 */
internal suspend fun LazyListState.jumpTo(index: Int) {
    // Already there: no frame to spend, which a reader's forward open into Detail counts.
    if (firstVisibleItemIndex == index && firstVisibleItemScrollOffset == 0) return
    requestScrollToItem(index)
    withFrameNanos { }
}

/**
 * Focus for a move's card once it's composed. The list may have changed under the move, so take what holds that place now,
 * and only then look around: asking the same card again would repeat frames it has already refused.
 */
internal suspend fun landOnCard(
    card: String,
    holdingThePlaceNow: () -> String?,
    request: suspend (String) -> Boolean,
    lookAround: () -> Unit
) {
    if (request(card)) return
    val now = holdingThePlaceNow()
    if (now == null || now == card || !request(now)) lookAround()
}

/** Lazy targets are registered only while attached, including across navigation restoration. */
private suspend fun requestAttached(requests: Map<String, FocusRequester>, key: String, stillWanted: () -> Boolean = { true }): Boolean {
    repeat(8) {
        withFrameNanos { }
        if (!stillWanted()) return false
        val requester = requests[key]
        if (requester != null && requester.requestFocus()) return true
    }
    return false
}

@Composable
internal fun CatalogCard(
    item: MediaItem,
    account: Account,
    wide: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = if (wide) 240.dp else 144.dp,
    selected: Boolean? = null
) {
    var focused by remember { mutableStateOf(false) }
    val selection = Modifier.semantics {
        role = Role.Button
        if (selected != null) this.selected = selected
        contentDescription = catalogAnnouncement(item)
        onClick(
            label = when (item.type) {
                "Season" -> "Open season"
                "CollectionFolder", "Folder" -> "Open library"
                "BoxSet" -> "Open collection"
                else -> "Open details"
            },
            action = null
        )
    }
    // The selected outline is the card's own resting border, so it scales with the card and the focused border
    // replaces it. As a separate modifier it stayed at the unscaled bounds and drew a second rectangle through the
    // focused card's badge and under its label.
    Card(
        onClick = onClick,
        border = if (selected == true) {
            // tv-material's card shape is 8dp rounded corners; the resting outline follows it.
            CardDefaults.border(border = Border(BorderStroke(2.dp, SelectedSeasonOutline), shape = RoundedCornerShape(8.dp)))
        } else {
            CardDefaults.border()
        },
        modifier = modifier.onFocusChanged { focused = it.isFocused }.then(selection).then(
            if (width ==
                null
            ) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.width(width)
            }
        )
    ) {
        // The inset keyline keeps poster pixels away from the outline's inside edge. The card owns
        // the outline and scale, so there is still one focus border at the original geometry.
        Column(
            Modifier.clearAndSetSemantics {}.then(
                if (focused ||
                    selected == true
                ) {
                    Modifier.border(3.dp, CatalogBackground, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                }
            )
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 2f / 3f)) {
                CatalogArtwork(item, account, wide, Modifier.fillMaxSize())
                val badge = when {
                    item.isEmptyContainer -> null
                    item.isWatched -> "✓ Watched"
                    item.userData.unplayedItemCount != null -> "${item.userData.unplayedItemCount} unplayed"
                    else -> null
                }
                if (badge != null) {
                    Text(
                        badge,
                        modifier = Modifier.align(Alignment.TopEnd).background(Color(0xDD202635)).padding(5.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (item.userData.isFavorite) {
                    Text("♥", modifier = Modifier.align(Alignment.TopStart).background(Color(0xDD202635)).padding(5.dp))
                }
                val fraction = progress(item).coerceIn(0f, 1f)
                if (fraction > 0f && !item.isWatched && !item.isEmptyContainer) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color(0xFF505969))) {
                        Box(Modifier.fillMaxWidth(fraction).height(5.dp).background(Color(0xFFB8C4FF)))
                    }
                }
            }
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.name,
                    maxLines = if (LocalDensity.current.fontScale > UNBOUNDED_TITLE_FONT_SCALE) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall
                )
                if (item.type == "Episode") {
                    Text(
                        listOfNotNull(item.seriesName, episodeLabel(item)).joinToString(" · "),
                        maxLines = if (LocalDensity.current.fontScale > UNBOUNDED_TITLE_FONT_SCALE) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                        color = CatalogMuted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
internal fun CatalogArtwork(
    item: MediaItem,
    account: Account,
    wide: Boolean,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center
) {
    BoxWithConstraints(modifier.clearAndSetSemantics {}.background(Color(0xFF262D3D)), contentAlignment = Alignment.Center) {
        val pixels = with(LocalDensity.current) { maxWidth.roundToPx() }
        val url = imageUrl(account.server, item, pixels, wide = wide)
        var loaded by remember(url) { mutableStateOf(false) }
        if (!loaded) Text(item.name, modifier = Modifier.padding(14.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = alignment,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { loaded = true },
                onError = { loaded = false }
            )
        }
    }
}

@Composable
private fun CatalogHeading(title: String, modifier: Modifier = Modifier, large: Boolean = true) {
    Text(
        title,
        style = if (large) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall,
        modifier = modifier.semantics { heading() }
    )
}

/** After the last card when the next page failed. It stays through a retry, so Retry never loses focus to the loading. */
@Composable
private fun GridPageFailure(message: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        Modifier.offset(x = -GridHeaderOutset).padding(top = 8.dp).accessibilityGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            message ?: "Loading…",
            color = if (message != null) Color(0xFFFFCCB8) else CatalogMuted,
            modifier = Modifier.testTag("grid:page-failure").semantics { liveRegion = LiveRegionMode.Polite }
        )
        Button(onClick = onRetry, modifier = modifier) { Text("Retry") }
    }
}

@Composable
private fun CatalogError(message: String?, onRetry: () -> Unit, modifier: Modifier = Modifier, announceMessage: Boolean = true) {
    if (message != null) {
        Column(modifier = Modifier.accessibilityGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(message, color = Color(0xFFFFCCB8), modifier = Modifier.catalogFocusTarget(announceMessage))
            Button(onClick = onRetry, modifier = modifier) { Text("Retry") }
        }
    }
}
