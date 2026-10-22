/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.episodeLabel
import app.pictureshow.core.imageUrl
import app.pictureshow.core.progress
import app.pictureshow.core.resumeLabel
import app.pictureshow.core.runtimeLabel
import coil3.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CatalogBackground = Color(0xFF10131C)
private val CatalogMuted = Color(0xFFB9C1D6)

/** Each navigation entry owns its saveable focus identity and lazy-list scroll positions. */
@Composable
fun CatalogHome(
    state: CatalogHomeState,
    account: Account,
    onOpen: (MediaItem) -> Unit,
    onRetry: () -> Unit,
    onSwitchUser: () -> Unit,
    onSignOut: () -> Unit,
    onResume: () -> Unit = {},
    onSettings: () -> Unit = {}
) {
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
    var initialNavigation by remember { mutableStateOf(false) }
    val profile = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    var selectedProfile by rememberSaveable { mutableStateOf("switch") }
    val profileTarget = if (selectedProfile == "settings") settingsFocus else profile
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
    suspend fun focusRow(rowIndex: Int, selections: Map<String, String> = rowSelections, positions: Map<String, Int> = rowPositions) {
        if (rowIndex !in rows.indices) return
        val row = rows[rowIndex]
        val itemIndex = row.items.indexOfFirst { it.id == selections[row.key] }.takeIf { it >= 0 }
            ?: (positions[row.key] ?: 0).coerceIn(0, row.items.lastIndex)
        val horizontal = rowStates.getValue(row.key)
        if (list.layoutInfo.visibleItemsInfo.none { it.index == rowIndex + 1 }) list.scrollToItem(rowIndex + 1)
        if (horizontal.layoutInfo.visibleItemsInfo.none { it.index == itemIndex }) horizontal.scrollToItem(itemIndex)
        withFrameNanos { }
        requesters.getValue("${row.key}:${row.items[itemIndex].id}").requestFocus()
    }
    fun moveRow(index: Int) {
        movement?.cancel()
        movement = scope.launch {
            when {
                index in rows.indices -> focusRow(index)
                index >= rows.size -> {
                    list.scrollToItem(rows.size + 1)
                    withFrameNanos { }
                    profileTarget.requestFocus()
                }
                state.error != null -> {
                    list.scrollToItem(0)
                    withFrameNanos { }
                    retry.requestFocus()
                }
            }
        }
    }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().background(CatalogBackground).testTag("home").onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown) initialNavigation = true
            false
        },
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CatalogHeading("Pictureshow")
                Text("${account.user.name} · ${account.server.name}", color = CatalogMuted)
                if (state.loading) Text("Loading your home…")
                CatalogError(
                    state.error,
                    onRetry,
                    Modifier.focusRequester(retry).onPreviewKeyEvent {
                        if (it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown && rows.isNotEmpty()) {
                            moveRow(0)
                            true
                        } else {
                            false
                        }
                    }
                )
                if (!state.loading && rows.isEmpty() && state.error == null) {
                    Text("There are no libraries or videos available for this user yet.")
                }
            }
        }
        rows.forEachIndexed { rowIndex, row ->
            item(key = row.key) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CatalogHeading(row.title, large = false)
                    LazyRow(
                        state = rowStates.getValue(row.key),
                        modifier = Modifier.fillMaxWidth().focusRestorer(),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        itemsIndexed(row.items, key = { _, item -> item.id }) { itemIndex, item ->
                            CatalogCard(
                                item = item,
                                account = account,
                                wide = row.wide || item.type == "Episode",
                                onClick = { onOpen(item) },
                                modifier = Modifier.focusRequester(requesters.getValue("${row.key}:${item.id}"))
                                    .testTag("home:${row.key}:${item.id}")
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            selectedRow = row.key
                                            selectedRowIndex = rowIndex
                                            selectedItem = item.id
                                            rowSelections = rowSelections + (row.key to item.id)
                                            rowPositions = rowPositions + (row.key to itemIndex)
                                        }
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
        item(key = "profile") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onSwitchUser,
                    enabled = !state.busy,
                    modifier = Modifier.focusRequester(profile).testTag("home:switch-user")
                        .onFocusChanged {
                            // During the first load these are the only attached targets. Automatic focus is
                            // not a saved user choice and must not outrank the first arriving catalog card.
                            if (it.isFocused && (restored || initialNavigation)) {
                                selectedRow = "profile"
                                selectedProfile = "switch"
                            }
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
                    modifier = Modifier.testTag("home:sign-out").onFocusChanged {
                        if (it.isFocused && (restored || initialNavigation)) {
                            selectedRow = "profile"
                            selectedProfile = "switch"
                        }
                    }.onPreviewKeyEvent {
                        if (it.key == Key.DirectionUp && it.type == KeyEventType.KeyDown && rows.isNotEmpty()) {
                            moveRow(rows.lastIndex)
                            true
                        } else {
                            false
                        }
                    }
                ) { Text(if (state.busy) "Please wait…" else "Sign out") }
                Button(
                    onClick = onSettings,
                    enabled = !state.busy,
                    modifier = Modifier.focusRequester(settingsFocus).testTag("home:settings").onFocusChanged {
                        if (it.isFocused && (restored || initialNavigation)) {
                            selectedRow = "profile"
                            selectedProfile = "settings"
                        }
                    }.onPreviewKeyEvent {
                        if (it.key == Key.DirectionUp && it.type == KeyEventType.KeyDown && rows.isNotEmpty()) {
                            moveRow(rows.lastIndex)
                            true
                        } else {
                            false
                        }
                    }
                ) { Text("Settings") }
            }
        }
    }
    LaunchedEffect(state.loading, rows.map { it.key to it.items.map(MediaItem::id) }) {
        if (!state.loading && (!restored || rows.isNotEmpty() && savedRow.isEmpty() && savedItem.isEmpty())) {
            // Empty startup content can temporarily leave only profile actions attached. Keep the first
            // card intent pending until a real row arrives, unless the user deliberately chose profile.
            withFrameNanos { }
            when {
                savedRow == "profile" -> {
                    list.scrollToItem(rows.size + 1)
                    withFrameNanos { }
                    if (!initialNavigation) profileTarget.requestFocus()
                }
                rows.isNotEmpty() -> {
                    val rowIndex = rows.indexOfFirst { it.key == savedRow }.takeIf { it >= 0 }
                        ?: savedRowIndex.coerceIn(0, rows.lastIndex)
                    if (savedItem.isNotEmpty()) rowSelections = rowSelections + (savedRow to savedItem)
                    focusRow(rowIndex, savedRowSelections, savedRowPositions)
                }
                state.error != null -> retry.requestFocus()
                else -> profileTarget.requestFocus()
            }
            restored = true
        } else if (!state.loading &&
            restored &&
            savedRow != "profile" &&
            savedItem.isNotEmpty() &&
            rows.none { row -> row.key == savedRow && row.items.any { it.id == savedItem } }
        ) {
            // A watched item can leave Continue watching while this entry is being restored.
            // Focus an existing visible card; never request the removed card's detached target.
            if (rows.isNotEmpty()) {
                focusRow(savedRowIndex.coerceAtMost(rows.lastIndex), savedRowSelections, savedRowPositions)
            } else {
                list.scrollToItem(rows.size + 1)
                withFrameNanos { }
                profileTarget.requestFocus()
            }
        }
    }
}

@Composable
fun CatalogGrid(
    state: CatalogGridState,
    onOpen: (MediaItem) -> Unit,
    onNearEnd: (Int) -> Unit,
    onSort: (String, Boolean) -> Unit,
    onRetry: () -> Unit
) {
    val grid = rememberLazyGridState()
    var selectedId by rememberSaveable { mutableStateOf("") }
    var restored by remember { mutableStateOf(false) }
    val firstSort = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val requests = remember { mutableMapOf<String, FocusRequester>() }
    Column(
        modifier = Modifier.fillMaxSize().background(CatalogBackground).padding(horizontal = 48.dp, vertical = 32.dp).testTag("grid"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CatalogHeading(state.parent.name)
        Text("${state.total} items", color = CatalogMuted, modifier = Modifier.testTag("grid:count"))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val sorts = listOf("SortName" to "Name", "DateCreated" to "Date added", "PremiereDate" to "Release date")
            sorts.forEachIndexed { index, (sort, title) ->
                Button(
                    onClick = { onSort(sort, state.descending) },
                    modifier = (if (index == 0) Modifier.focusRequester(firstSort) else Modifier).testTag("grid:sort:$sort")
                ) { Text(if (state.sortBy == sort) "$title ✓" else title) }
            }
            Button(onClick = { onSort(state.sortBy, !state.descending) }, modifier = Modifier.testTag("grid:sort-order")) {
                Text(if (state.descending) "Descending ↓" else "Ascending ↑")
            }
        }
        CatalogError(state.error, onRetry, Modifier.focusRequester(retry))
        if (state.loading) Text("Loading…")
        if (!state.loading && state.items.isEmpty() && state.error == null) Text("This library is empty.")
        LazyVerticalGrid(
            columns = GridCells.Adaptive(144.dp),
            state = grid,
            modifier = Modifier.weight(1f).fillMaxWidth().focusRestorer(),
            contentPadding = PaddingValues(8.dp),
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
    LaunchedEffect(state.items.map(MediaItem::id), state.loading) {
        if (!state.loading && !restored) {
            withFrameNanos { }
            if (state.items.isNotEmpty()) {
                val index = state.items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
                if (grid.layoutInfo.visibleItemsInfo.none { it.index == index }) grid.scrollToItem(index)
                withFrameNanos { }
                requestAttached(requests, state.items[index].id)
            } else if (state.error != null) {
                retry.requestFocus()
            } else {
                firstSort.requestFocus()
            }
            restored = true
        }
    }
    LaunchedEffect(grid, state.items.size) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.collect { index ->
            if (index != null) onNearEnd(index)
        }
    }
}

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
    val list = rememberLazyListState()
    val initial = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    var selectedSection by rememberSaveable { mutableStateOf("header") }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var restored by remember { mutableStateOf(false) }
    val requests = remember { mutableMapOf<String, FocusRequester>() }
    val item = state.item
    val sections = buildList {
        if (item?.type == "Series" && state.nextUp.isNotEmpty()) add("next" to state.nextUp)
        if (item?.type == "Series" && state.seasons.isNotEmpty()) add("seasons" to state.seasons)
        if (item?.type in listOf("Series", "Season") && state.episodes.isNotEmpty()) add("episodes" to state.episodes)
    }
    val rowStates = mutableMapOf<String, LazyListState>()
    sections.forEach { (section, _) -> key(section) { rowStates[section] = rememberLazyListState() } }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().background(CatalogBackground).testTag("detail"),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item(key = "body") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.loading && item == null) Text("Loading item…")
                CatalogError(state.error, onRetry, Modifier.focusRequester(retry))
                if (item != null) {
                    val backdrop = if (item.backdropImageTags.isNotEmpty() || item.parentBackdropImageTags.isNotEmpty()) {
                        item.copy(imageTags = emptyMap())
                    } else {
                        item
                    }
                    CatalogArtwork(backdrop, state.account, wide = true, modifier = Modifier.fillMaxWidth().height(220.dp))
                    CatalogHeading(item.name)
                    if (item.type == "Episode") {
                        Text(listOfNotNull(item.seriesName, episodeLabel(item).takeIf { it.isNotBlank() }).joinToString(" · "))
                    }
                    val metadata = listOfNotNull(
                        item.productionYear?.toString(),
                        item.officialRating,
                        item.communityRating?.let { "★ $it" },
                        item.runTimeTicks?.let(::runtimeLabel)?.takeIf(String::isNotBlank)
                    )
                    if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "), color = CatalogMuted)
                    item.overview?.takeIf(String::isNotBlank)?.let { Text(it) }
                    if (item.genres.isNotEmpty()) Text(item.genres.joinToString(" · "), color = CatalogMuted)
                    if (item.type == "Movie" || item.type == "Episode") {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Button(
                                onClick = { onPlay(item, item.userData.playbackPositionTicks > 0) },
                                modifier = Modifier.focusRequester(initial).testTag("detail:play")
                                    .onFocusChanged { if (it.isFocused) selectedSection = "header" }
                            ) {
                                Text(
                                    if (item.userData.playbackPositionTicks > 0) {
                                        resumeLabel(item.userData.playbackPositionTicks)
                                    } else {
                                        "Play"
                                    }
                                )
                            }
                            if (item.userData.playbackPositionTicks > 0) {
                                Button(onClick = { onPlay(item, false) }) { Text("Play from start") }
                            }
                            Button(onClick = onPlayed, enabled = !state.busy, modifier = Modifier.testTag("detail:played")) {
                                Text(if (item.userData.played) "Mark unwatched" else "Mark watched")
                            }
                            Button(onClick = onFavorite, enabled = !state.busy, modifier = Modifier.testTag("detail:favorite")) {
                                Text(if (item.userData.isFavorite) "Unfavorite" else "Favorite")
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
                    onFocusRested = if (section == "seasons") onSeason else null,
                    rowState = rowStates.getValue(section),
                    requests = requests,
                    onFocus = { focused ->
                        selectedSection = section
                        selectedId = focused.id
                    }
                )
            }
        }
        if (item?.type in listOf("Series", "Season") && !state.loading && state.episodes.isEmpty() && state.seasons.isEmpty()) {
            item(key = "empty") { Text("No episodes are available.") }
        }
    }
    LaunchedEffect(state.loading, item?.id, state.error, sections.map { it.first to it.second.map(MediaItem::id) }) {
        if (!state.loading && !restored) {
            withFrameNanos { }
            val savedSectionIndex = sections.indexOfFirst { it.first == selectedSection }
            val sectionIndex = if (savedSectionIndex >= 0) {
                savedSectionIndex
            } else if (selectedSection == "header") {
                if (item?.type in listOf("Movie", "Episode")) -1 else sections.indices.firstOrNull() ?: -1
            } else {
                sections.indices.firstOrNull() ?: -1
            }
            if (sectionIndex >= 0) {
                val (section, items) = sections[sectionIndex]
                val targetId = if (savedSectionIndex < 0 && section == "seasons") state.selectedSeasonId else selectedId
                val itemIndex = items.indexOfFirst { it.id == targetId }.coerceAtLeast(0)
                val horizontal = rowStates.getValue(section)
                if (list.layoutInfo.visibleItemsInfo.none { it.index == sectionIndex + 1 }) list.scrollToItem(sectionIndex + 1)
                if (horizontal.layoutInfo.visibleItemsInfo.none { it.index == itemIndex }) horizontal.scrollToItem(itemIndex)
                requestAttached(requests, "$section:${items[itemIndex].id}")
            } else if (item?.type in listOf("Movie", "Episode") || state.error != null) {
                if (list.layoutInfo.visibleItemsInfo.none { it.index == 0 }) list.scrollToItem(0)
                withFrameNanos { }
                if (state.error != null) retry.requestFocus() else initial.requestFocus()
            }
            restored = true
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CatalogHeading(title, large = false)
        LazyRow(
            state = rowState,
            modifier = Modifier.fillMaxWidth().focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(8.dp)
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
                    selected = item.id == selectedId,
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
private suspend fun requestAttached(requests: Map<String, FocusRequester>, key: String): Boolean {
    repeat(8) {
        withFrameNanos { }
        val requester = requests[key]
        if (requester != null && requester.requestFocus()) return true
    }
    return false
}

@Composable
private fun CatalogCard(
    item: MediaItem,
    account: Account,
    wide: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = if (wide) 240.dp else 144.dp,
    selected: Boolean = false
) {
    val selection = if (selected) {
        Modifier.border(2.dp, Color(0xFFB8C4FF)).semantics {
            this.selected = true
            contentDescription = "${item.name}, selected season"
        }
    } else {
        Modifier
    }
    Card(
        onClick = onClick,
        modifier = modifier.then(selection).then(
            if (width ==
                null
            ) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.width(width)
            }
        )
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 2f / 3f)) {
                CatalogArtwork(item, account, wide, Modifier.fillMaxSize())
                val badge = when {
                    item.userData.played -> "✓ Watched"
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
                if (fraction > 0f && !item.userData.played) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color(0xFF505969))) {
                        Box(Modifier.fillMaxWidth(fraction).height(5.dp).background(Color(0xFFB8C4FF)))
                    }
                }
            }
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall
                )
                if (item.type == "Episode") {
                    Text(
                        listOfNotNull(item.seriesName, episodeLabel(item)).joinToString(" · "),
                        maxLines = 1,
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
private fun CatalogArtwork(item: MediaItem, account: Account, wide: Boolean, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.background(Color(0xFF262D3D)), contentAlignment = Alignment.Center) {
        val pixels = with(LocalDensity.current) { maxWidth.roundToPx() }
        val url = imageUrl(account.server, item, pixels, wide = wide)
        var loaded by remember(url) { mutableStateOf(false) }
        if (!loaded) Text(item.name, modifier = Modifier.padding(14.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
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
private fun CatalogError(message: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (message != null) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(message, color = Color(0xFFFFCCB8))
            Button(onClick = onRetry, modifier = modifier.semantics { contentDescription = "Retry" }) { Text("Retry") }
        }
    }
}
