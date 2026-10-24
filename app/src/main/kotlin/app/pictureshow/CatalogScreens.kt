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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.state.ToggleableState
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.episodeLabel
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
    onSettings: () -> Unit = {}
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
                    "header=${selectedRow.takeIf { it in listOf("search", "settings", "profile") } ?: "card"} " +
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
        selectedRow in listOf("search", "settings", "profile") -> selectedRow
        rows.isEmpty() && !state.loading -> if (state.error != null) "retry" else "profile"
        else -> null
    }
    val restoreTarget = when (restoreAction) {
        "search" -> "Search"
        "settings" -> "Settings"
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
            list.scrollToItem(0)
        } else if (list.layoutInfo.visibleItemsInfo.none { it.index == rowIndex + 1 }) {
            list.scrollToItem(rowIndex + 1)
        }
        val focused = withTimeoutOrNull(1_500) {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.key == row.key } }.first { it }
            if (horizontal.layoutInfo.visibleItemsInfo.none { it.index == itemIndex }) horizontal.scrollToItem(itemIndex)
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
        list.scrollToItem(index)
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
    val belowTop = rows.isNotEmpty() && selectedRow.isNotEmpty() && selectedRow !in listOf("search", "settings", rows.first().key)
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
    val homeBringIntoView = remember(platformBringIntoView) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                // TV's default 30% pivot would hide Search even when the whole first card already fits.
                if (firstRowFocused && offset >= 0f && offset + size <= containerSize) {
                    0f
                } else {
                    platformBringIntoView.calculateScrollDistance(offset, size, containerSize)
                }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides homeBringIntoView) {
        CatalogLoadingFocus(returning && !restored) {
            initialNavigation = true
            restored = true
            selectedRow = "search"
            scope.launch { focusAction("header", 0, search) }
        }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().background(CatalogBackground).testTag("home").accessibilityGroup().onPreviewKeyEvent {
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
                                .onFocusChanged { if (it.isFocused && (restored || initialNavigation)) selectedRow = "settings" }
                                .onPreviewKeyEvent { headerDown(it) }
                        ) { Text("Settings") }
                    }
                    Column(Modifier.catalogFocusTarget(!handoff), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CatalogHeading("Pictureshow")
                        Text("${account.user.name} · ${account.server.name}", color = CatalogMuted)
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
                            LazyRow(
                                state = rowStates.getValue(row.key),
                                // Explicit stable-ID restoration in focusRow owns shelf entry. A focusRestorer
                                // here can intercept its request after lazy disposal and select another child.
                                modifier = Modifier.fillMaxWidth(),
                                // Reserve the 1.1x focus scale and centered border inside the shelf clip.
                                contentPadding = PaddingValues(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(18.dp)
                            ) {
                                itemsIndexed(row.items, key = { _, item -> item.id }) { itemIndex, item ->
                                    CatalogCard(
                                        item = item,
                                        account = account,
                                        wide = row.wide || item.type == "Episode",
                                        onClick = {
                                            select(rowIndex, itemIndex)
                                            onOpen(item)
                                        },
                                        modifier = target(
                                            Modifier,
                                            restoreAction == null && row.key == restoreRow?.key && item.id == restoreItem?.id
                                        ).focusRequester(requesters.getValue("${row.key}:${item.id}"))
                                            .testTag("home:${row.key}:${item.id}")
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
            item(key = "profile") {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = {
                            selectedRow = "profile"
                            onSwitchUser()
                        },
                        enabled = !state.busy,
                        modifier = target(Modifier, restoreAction == "profile").focusRequester(profile).testTag("home:switch-user")
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
                        modifier = target(Modifier, false).testTag("home:sign-out").onFocusChanged {
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
                restoreAction == "search" || restoreAction == "settings" -> {
                    focusAction("header", 0, if (restoreAction == "settings") settingsFocus else search)
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
            savedRow !in listOf("profile", "search", "settings") &&
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatalogGrid(
    state: CatalogGridState,
    onOpen: (MediaItem) -> Unit,
    onNearEnd: (Int) -> Unit,
    onSort: (String, Boolean) -> Unit,
    onRetry: () -> Unit,
    showSort: Boolean = true
) {
    val grid = rememberLazyGridState()
    var selectedId by rememberSaveable { mutableStateOf("") }
    var restored by remember { mutableStateOf(false) }
    var retryFocusRequest by remember { mutableIntStateOf(0) }
    val firstSort = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val requests = remember { mutableMapOf<String, FocusRequester>() }
    Column(
        modifier = Modifier.fillMaxSize().background(
            CatalogBackground
        ).padding(horizontal = 48.dp, vertical = 32.dp).testTag("grid").accessibilityGroup().semantics {
            if (restored) paneTitle = state.parent.name
        },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CatalogHeading(state.parent.name)
        Text(itemCountLabel(state.total), color = CatalogMuted, modifier = Modifier.testTag("grid:count"))
        if (showSort) {
            FlowRow(
                modifier = Modifier.accessibilityGroup().semantics {
                    traversalIndex = 1f
                }.then(
                    // Keep the visual/input controls while withholding their early accessibility targets.
                    if (!restored) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier
                ),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val sorts = listOf("SortName" to "Name", "DateCreated" to "Date added", "PremiereDate" to "Release date")
                sorts.forEachIndexed { index, (sort, title) ->
                    Button(
                        onClick = { onSort(sort, state.descending) },
                        modifier = (
                            if (index ==
                                0
                            ) {
                                Modifier.focusRequester(firstSort)
                            } else {
                                Modifier
                            }
                            ).testTag("grid:sort:$sort").semantics {
                            selected =
                                state.sortBy == sort
                        }
                    ) { Text(if (state.sortBy == sort) "$title ✓" else title) }
                }
                Button(
                    onClick = { onSort(state.sortBy, !state.descending) },
                    modifier = Modifier.testTag("grid:sort-order").semantics {
                        stateDescription = if (state.descending) "Descending" else "Ascending"
                    }
                ) {
                    Text(if (state.descending) "Descending ↓" else "Ascending ↑", modifier = Modifier.clearAndSetSemantics {})
                }
            }
        }
        CatalogError(state.error ?: state.refreshError, {
            restored = false
            if (state.error == null) retryFocusRequest++
            onRetry()
        }, Modifier.focusRequester(retry))
        if (state.loading) Text("Loading…")
        if (!state.loading && state.items.isEmpty() && state.error == null) Text(emptyGridLabel(state.parent))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(144.dp),
            state = grid,
            modifier = Modifier.weight(1f).fillMaxWidth().focusRestorer().testTag("grid:items").semantics { traversalIndex = 0f },
            // Poster height grows more than width; the grid clips along its vertical axis.
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            gridItemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                val requester = remember(item.id) { FocusRequester() }
                DisposableEffect(item.id) {
                    requests[item.id] = requester
                    onDispose { requests.remove(item.id) }
                }
                CatalogCard(
                    item = item,
                    account = state.account,
                    wide = item.type == "Episode",
                    width = null,
                    onClick = { onOpen(item) },
                    modifier = Modifier.focusRequester(requester).testTag("grid:${item.id}").onFocusChanged {
                        if (it.isFocused) {
                            selectedId = item.id
                            onNearEnd(index)
                        }
                    }
                )
            }
        }
    }
    LaunchedEffect(state.items.map(MediaItem::id), state.loading, retryFocusRequest) {
        if (!state.loading && !restored) {
            withFrameNanos { }
            val focused = if (state.items.isNotEmpty()) {
                val index = state.items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
                if (grid.layoutInfo.visibleItemsInfo.none { it.index == index }) grid.scrollToItem(index)
                withFrameNanos { }
                requestAttached(requests, state.items[index].id)
            } else if (state.error != null) {
                retry.requestFocus()
            } else {
                if (showSort) firstSort.requestFocus() else true
            }
            // A failed card request uses the existing sort fallback instead of leaving controls hidden.
            restored = focused || (showSort && firstSort.requestFocus())
        }
    }
    LaunchedEffect(grid, state.items.size) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.collect { index ->
            if (index != null) onNearEnd(index)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CatalogDetail(
    state: CatalogDetailState,
    onOpen: (MediaItem) -> Unit,
    onSeason: (MediaItem) -> Unit,
    onPlayed: () -> Unit,
    onFavorite: () -> Unit,
    onRetry: () -> Unit,
    onPlay: (MediaItem, Boolean) -> Unit = { _, _ -> }
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val list = rememberLazyListState()
    val initial = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    var selectedSection by rememberSaveable { mutableStateOf("header") }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var restored by remember { mutableStateOf(false) }
    var semanticsRevealed by remember { mutableStateOf(false) }
    val pendingFocus = remember { FocusRequester() }
    var focusInputRevision by remember { mutableIntStateOf(0) }
    val requests = remember { mutableMapOf<String, FocusRequester>() }
    val scope = rememberCoroutineScope()
    var movement by remember { mutableStateOf<Job?>(null) }
    var rowSelections by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    val item = state.item.takeUnless { state.playbackPending }
    val playItem = when (item?.type) {
        "Series" -> state.nextUp.firstOrNull() ?: state.episodes.firstOrNull { !it.userData.played } ?: state.episodes.firstOrNull()
        "Season" -> state.episodes.firstOrNull { !it.userData.played } ?: state.episodes.firstOrNull()
        "Movie", "Episode", "Video" -> item
        else -> null
    }
    val focusManager = LocalFocusManager.current
    val hasActions = item?.type in listOf("Movie", "Episode", "Video", "Series", "Season")
    val sections = buildList {
        if (item?.type == "Series" && state.nextUp.isNotEmpty()) add("next" to state.nextUp)
        if (item?.type == "Series" && state.seasons.isNotEmpty()) add("seasons" to state.seasons)
        if (item?.type in listOf("Series", "Season") && state.episodes.isNotEmpty()) add("episodes" to state.episodes)
    }
    val rowStates = mutableMapOf<String, LazyListState>()
    sections.forEach { (section, _) -> key(section) { rowStates[section] = rememberLazyListState() } }
    fun moveSection(index: Int) {
        movement?.cancel()
        movement = scope.launch {
            if (index < 0) {
                list.scrollToItem(0)
                withFrameNanos { }
                selectedSection = "header"
                restored = initial.requestFocus()
            } else if (index in sections.indices) {
                val (section, items) = sections[index]
                val target = rowSelections[section] ?: state.selectedSeasonId.takeIf { section == "seasons" }
                val itemIndex = items.indexOfFirst { it.id == target }.coerceAtLeast(0)
                // A visible shelf heading does not mean its lazy cards are attached.
                list.scrollToItem(index + 1)
                val horizontal = rowStates.getValue(section)
                if (horizontal.layoutInfo.visibleItemsInfo.none { it.index == itemIndex }) horizontal.scrollToItem(itemIndex)
                selectedSection = section
                selectedId = items[itemIndex].id
                rowSelections = rowSelections + (section to selectedId)
                restored = requestAttached(requests, "$section:$selectedId")
            }
        }
    }
    val platformBringIntoView = LocalBringIntoViewSpec.current
    val headerFocused by rememberUpdatedState(selectedSection == "header")
    val detailBringIntoView = remember(platformBringIntoView) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                // An already-visible header action must not pivot the hero/title above the screen.
                if (headerFocused && offset >= 0f && offset + size <= containerSize) {
                    0f
                } else {
                    platformBringIntoView.calculateScrollDistance(offset, size, containerSize)
                }
        }
    }
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
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().background(CatalogBackground).testTag("detail")
                // A returning page must expose its restored input target before TalkBack can
                // choose a heading or overview as a replacement for the departing player.
                .then(
                    if (lifecycleState != Lifecycle.State.RESUMED || (playItem != null && !semanticsRevealed && state.error == null)) {
                        Modifier.clearAndSetSemantics { hideFromAccessibility() }
                    } else {
                        Modifier
                    }
                ).accessibilityGroup().onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown) {
                        focusInputRevision++
                        // A viewer's first move owns focus even during the brief visible handoff.
                        if (semanticsRevealed) restored = true
                    }
                    if (it.key in listOf(Key.MediaPlay, Key.MediaPlayPause) && !state.loading && playItem != null) {
                        if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) {
                            onPlay(playItem, playItem.userData.playbackPositionTicks > 0)
                        }
                        true
                    } else if (it.type == KeyEventType.KeyDown && it.key in listOf(Key.DirectionUp, Key.DirectionDown) && hasActions) {
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
                Column(modifier = Modifier.accessibilityGroup(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (state.playbackPending) {
                        if (state.error == null) {
                            StatusLine(if (state.playbackReportFinished) "Loading item…" else "Saving progress…")
                        }
                    } else if (state.loading && item == null) {
                        Text("Loading item…")
                    }
                    CatalogError(state.error, onRetry, Modifier.focusRequester(retry))
                    if (item != null) {
                        val backdrop = if (item.backdropImageTags.isNotEmpty() || item.parentBackdropImageTags.isNotEmpty()) {
                            item.copy(imageTags = emptyMap())
                        } else {
                            item
                        }
                        CatalogArtwork(
                            backdrop,
                            state.account,
                            wide = true,
                            modifier = Modifier.fillMaxWidth().height(220.dp).testTag("detail:hero"),
                            alignment = BiasAlignment(0f, -.5f)
                        )
                        CatalogHeading(item.name)
                        if (item.type == "Episode") {
                            Text(listOfNotNull(item.seriesName, episodeLabel(item).takeIf { it.isNotBlank() }).joinToString(" · "))
                        }
                        val metadata = listOfNotNull(
                            item.productionYear?.toString(),
                            item.officialRating,
                            item.communityRating?.let(::communityRatingLabel),
                            item.runTimeTicks?.let(::runtimeLabel)?.takeIf(String::isNotBlank)
                        )
                        if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "), color = CatalogMuted)
                        val container = item.type in listOf("Series", "Season")
                        if (hasActions && container) {
                            DetailActions(item, playItem, state.busy, initial, onPlayed, onFavorite, onPlay) {
                                if (!state.loading &&
                                    restored
                                ) {
                                    selectedSection = "header"
                                }
                            }
                        }
                        item.overview?.takeIf(String::isNotBlank)?.let { Text(it) }
                        if (item.genres.isNotEmpty()) Text(item.genres.joinToString(" · "), color = CatalogMuted)
                        if (hasActions && !container) {
                            DetailActions(item, playItem, state.busy, initial, onPlayed, onFavorite, onPlay) {
                                if (!state.loading &&
                                    restored
                                ) {
                                    selectedSection = "header"
                                }
                            }
                        }
                        if (state.loading) Text("Loading episodes…")
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
                            { season -> if (!state.loading && restored) onSeason(season) }
                        } else {
                            null
                        },
                        rowState = rowStates.getValue(section),
                        requests = requests,
                        onFocus = { focused ->
                            if (!state.loading && restored) {
                                selectedSection = section
                                selectedId = focused.id
                                rowSelections = rowSelections + (section to focused.id)
                            }
                        }
                    )
                }
            }
            if (item?.type in listOf("Series", "Season") && !state.loading && state.episodes.isEmpty() && state.seasons.isEmpty()) {
                item(key = "empty") { Text("No episodes are available.") }
            }
        }
    }
    LaunchedEffect(
        lifecycleState,
        state.loading,
        state.playbackPending,
        item?.id,
        state.error,
        sections.map {
            it.first to
                it.second.map(MediaItem::id)
        }
    ) {
        // Navigation composes the returning page before its transition has finished. A focus
        // event then is hidden from TalkBack, which falls back to the heading/overview instead.
        if (lifecycleState != Lifecycle.State.RESUMED) {
            restored = false
            semanticsRevealed = false
            return@LaunchedEffect
        }
        if (state.playbackPending) {
            restored = false
            semanticsRevealed = false
            if (state.error != null) {
                list.scrollToItem(0)
                withFrameNanos { }
                retry.requestFocus()
            }
            return@LaunchedEffect
        }
        if (state.loading && selectedSection != "header") {
            restored = false
            semanticsRevealed = false
        }
        if (!state.loading && !restored) {
            val inputBeforeRestore = focusInputRevision
            withFrameNanos { }
            pendingFocus.requestFocus()
            val savedSectionIndex = sections.indexOfFirst { it.first == selectedSection }
            val sectionIndex = if (savedSectionIndex >= 0) {
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
            if (sectionIndex >= 0) {
                val (section, items) = sections[sectionIndex]
                val savedId = if (savedSectionIndex < 0 && section == "seasons") state.selectedSeasonId else selectedId
                val itemIndex = items.indexOfFirst { it.id == savedId }.coerceAtLeast(0)
                val horizontal = rowStates.getValue(section)
                list.scrollToItem(sectionIndex + 1)
                if (horizontal.layoutInfo.visibleItemsInfo.none { it.index == itemIndex }) horizontal.scrollToItem(itemIndex)
                targetKey = "$section:${items[itemIndex].id}"
                targetSection = section
                targetId = items[itemIndex].id
            } else if (hasActions || state.error != null) {
                list.scrollToItem(0)
                target = if (state.error != null) retry else initial
            }
            // Publish the target unfocused before requesting it. Focusing a hidden/new node
            // produces no Focused property change for Compose's accessibility delegate. Clearing
            // owner focus cannot help on TV: View.clearFocus synchronously refocuses the first item.
            semanticsRevealed = true
            repeat(2) { withFrameNanos { } }
            if (focusInputRevision == inputBeforeRestore) {
                val key = targetKey
                val focused = if (key != null) {
                    requestAttached(requests, key) { focusInputRevision == inputBeforeRestore }
                } else {
                    target?.requestFocus() ?: false
                }
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
        }
    }
}

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
    onFocus: () -> Unit
) {
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    var scrolling by remember { mutableStateOf<Job?>(null) }
    val focusMargin = with(LocalDensity.current) { 8.dp.toPx() }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.bringIntoViewRequester(bringIntoView).onFocusChanged {
            focused = it.hasFocus
            if (it.hasFocus) onFocus()
        }.onGloballyPositioned { coordinates ->
            // Metadata can arrive after focus, moving the same action below the viewport without
            // another focus event. Recheck its un-clipped position after layout in that case.
            if (focused && scrolling?.isActive != true) {
                val top = coordinates.positionInRoot().y
                val bottom = top + coordinates.size.height
                val viewport = coordinates.findRootCoordinates().size.height
                if (top < focusMargin || bottom > viewport - focusMargin) {
                    scrolling = scope.launch {
                        bringIntoView.bringIntoView(
                            Rect(-focusMargin, -focusMargin, coordinates.size.width + focusMargin, coordinates.size.height + focusMargin)
                        )
                    }
                }
            }
        }.focusProperties {
            // Let the parent route a shelf exit to its remembered card, after spatial navigation
            // has exhausted the wrapped action rows. Programmatic requests remain unrestricted.
            onExit = {
                if (requestedFocusDirection == FocusDirection.Up || requestedFocusDirection == FocusDirection.Down) cancelFocusChange()
            }
        }.focusGroup()
    ) {
        val position = playItem?.userData?.playbackPositionTicks ?: 0
        Button(
            onClick = { playItem?.let { onPlay(it, position > 0) } },
            enabled = playItem != null,
            modifier = (if (playItem != null) Modifier.focusRequester(initial) else Modifier).testTag("detail:play")
                .then(
                    if (playItem == null &&
                        item.type in listOf("Series", "Season")
                    ) {
                        Modifier.clearAndSetSemantics { hideFromAccessibility() }
                    } else {
                        Modifier
                    }
                )
        ) { Text(if (position > 0) resumeLabel(position) else "Play") }
        if (position > 0) {
            Button(onClick = { playItem?.let { onPlay(it, false) } }) { Text("Play from start") }
        }
        Button(
            onClick = onPlayed,
            enabled = !busy && !item.isEmptyContainer,
            modifier = (
                if (playItem == null &&
                    !item.isEmptyContainer
                ) {
                    Modifier.focusRequester(initial)
                } else {
                    Modifier
                }
                ).testTag("detail:played").semantics {
                toggleableState = ToggleableState(item.isWatched)
                stateDescription = if (item.isWatched) "Watched" else "Unwatched"
            }
        ) { Text(if (item.isWatched) "Mark unwatched" else "Mark watched") }
        Button(
            onClick = onFavorite,
            enabled = !busy,
            modifier = (if (playItem == null && item.isEmptyContainer) Modifier.focusRequester(initial) else Modifier)
                .testTag("detail:favorite").semantics {
                    toggleableState = ToggleableState(item.userData.isFavorite)
                    stateDescription = if (item.userData.isFavorite) "Favorite" else "Not favorite"
                }
        ) {
            Text(if (item.userData.isFavorite) "Unfavorite" else "Favorite")
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
        LazyRow(
            state = rowState,
            modifier = Modifier.fillMaxWidth().focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
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
                CatalogCard(
                    item,
                    account,
                    wide,
                    onClick = { onOpen(item) },
                    selected = selectedId?.let { item.id == it },
                    modifier = Modifier.focusRequester(requester).testTag("detail:$focusKey")
                        .onFocusChanged {
                            focused = it.isFocused
                            if (it.isFocused) onFocus(item)
                        }
                )
            }
        }
    }
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
                    maxLines = if (LocalDensity.current.fontScale > 1.15f) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall
                )
                if (item.type == "Episode") {
                    Text(
                        listOfNotNull(item.seriesName, episodeLabel(item)).joinToString(" · "),
                        maxLines = if (LocalDensity.current.fontScale > 1.15f) Int.MAX_VALUE else 1,
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
private fun CatalogHeading(title: String, large: Boolean = true) {
    Text(
        title,
        style = if (large) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() }
    )
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
