/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.pictureshow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.pictureshow.core.Account
import app.pictureshow.core.HomeRow
import app.pictureshow.core.MediaItem
import app.pictureshow.core.MediaPerson
import app.pictureshow.core.constraintKeys
import app.pictureshow.core.detailReadBandText
import app.pictureshow.core.detailRetryDescription
import app.pictureshow.core.episodeLabel
import app.pictureshow.core.imageUrl
import app.pictureshow.core.isWatched
import app.pictureshow.core.progress
import app.pictureshow.core.runtimeLabel
import app.pictureshow.core.showDetailEmptyState
import app.pictureshow.core.sortDirectionLabel
import coil3.compose.AsyncImage
import kotlin.math.ceil

/** TOUCH-06 and CAT-04: the same three sorts as TV, with touch's word for the first. */
internal val TOUCH_LIBRARY_SORTS = listOf("SortName" to "Title", "DateCreated" to "Date added", "PremiereDate" to "Release date")

@Composable
internal fun TouchCatalogHome(
    state: CatalogHomeState,
    account: Account,
    onOpen: (MediaItem) -> Unit,
    onRetry: () -> Unit,
    onResume: () -> Unit = {},
    librariesOnly: Boolean = false,
    onCustomize: (() -> Unit)? = null,
    onWatchDownloads: (() -> Unit)? = null
) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onResume() }
    val shortWindow = isShortTouchWindow()
    val rows = state.rows.filter { it.items.isNotEmpty() && (!librariesOnly || it.library) }
    if (librariesOnly) {
        TouchLibrariesGrid(state, state.libraries, account, onOpen, onRetry)
        return
    }
    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize().testTag("touch:home"),
        contentPadding = PaddingValues(vertical = if (shortWindow) 12.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(if (shortWindow) 16.dp else 24.dp)
    ) {
        item(key = "heading") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                var homeMenu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TouchCatalogHeading("Pictureshow", Modifier.weight(1f))
                    if (onCustomize != null) {
                        Box {
                            IconButton(
                                enabled = app.pictureshow.core.canOpenHomeEditor(state.preferencesReady),
                                onClick = { if (app.pictureshow.core.canOpenHomeEditor(state.preferencesReady)) homeMenu = true },
                                modifier = Modifier.testTag("touch:home-menu")
                            ) {
                                Icon(touchActionIcon("more"), contentDescription = "Home options")
                            }
                            DropdownMenu(homeMenu, { homeMenu = false }) {
                                DropdownMenuItem(
                                    enabled = app.pictureshow.core.canOpenHomeEditor(state.preferencesReady),
                                    text = { Text("Customize home") },
                                    onClick = {
                                        homeMenu = false
                                        if (app.pictureshow.core.canOpenHomeEditor(state.preferencesReady)) onCustomize()
                                    },
                                    modifier = Modifier.testTag("touch:customize")
                                )
                            }
                        }
                    }
                }
                if (!shortWindow) Text("What would you like to watch?", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.loading) TouchCatalogLoading("Loading your library…")
                if (onWatchDownloads != null) {
                    TouchDownloadsOffer(onWatchDownloads, onRetry)
                } else {
                    TouchCatalogError(state.error, onRetry)
                }
                if (!state.loading && rows.isEmpty() && state.error == null) {
                    Text("Your library is ready for something new.")
                    TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh") }
                }
            }
        }
        items(rows, key = HomeRow::key) { row ->
            TouchCatalogShelf(row.title, row.items, account, row.wide || row.library, !row.library, onOpen)
        }
    }
}

/** The tab's one job: every library in view, with no sideways scrolling to find the last one. */
@Composable
private fun TouchLibrariesGrid(
    state: CatalogHomeState,
    libraries: List<MediaItem>,
    account: Account,
    onOpen: (MediaItem) -> Unit,
    onRetry: () -> Unit
) {
    val shortWindow = isShortTouchWindow()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        state = rememberLazyGridState(),
        modifier = Modifier.fillMaxSize().testTag("touch:home"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = if (shortWindow) 12.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TouchCatalogHeading("Libraries")
                if (state.loading) TouchCatalogLoading("Loading your library…")
                TouchCatalogError(state.error, onRetry)
                if (!state.loading && libraries.isEmpty() && state.error == null) {
                    Text("No libraries are available for this account.")
                    TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh") }
                }
            }
        }
        items(libraries, key = MediaItem::id) { library ->
            TouchCatalogCard(library, account, true, { onOpen(library) }, Modifier.fillMaxWidth(), reserveLines = false)
        }
    }
}

/** Preserve the existing catalog call surface for screens and regression harnesses. */
@Composable
internal fun TouchCatalogGrid(
    state: CatalogGridState,
    onOpen: (MediaItem) -> Unit,
    onNearEnd: (Int) -> Unit,
    onSort: (String, Boolean) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    TouchMediaGrid(state, onOpen, onNearEnd, onRetry, onBack, onSort = onSort)
}

@Composable
internal fun TouchCatalogGrid(
    state: CatalogGridState,
    onOpen: (MediaItem) -> Unit,
    onNearEnd: (Int) -> Unit,
    onSort: (String, Boolean) -> Unit,
    onRetry: () -> Unit,
    onFilters: () -> Unit,
    onDisplayList: (Boolean) -> Unit,
    onClearFilters: (() -> Unit)? = null,
    onRemoveFilter: ((String) -> Unit)? = null,
    onBack: () -> Unit
) {
    TouchMediaGrid(
        state, onOpen, onNearEnd, onRetry, onBack, onSort = onSort, onFilters = onFilters, onDisplayList = onDisplayList,
        onClearFilters = onClearFilters, onRemoveFilter = onRemoveFilter
    )
}

@Composable
internal fun TouchPersonScreen(
    state: PersonState,
    onOpen: (MediaItem) -> Unit,
    onNearEnd: (Int) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    TouchMediaGrid(state.grid.copy(parent = state.person), onOpen, onNearEnd, onRetry, onBack, person = state.person)
}

@Composable
private fun TouchMediaGrid(
    state: CatalogGridState,
    onOpen: (MediaItem) -> Unit,
    onNearEnd: (Int) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onSort: ((String, Boolean) -> Unit)? = null,
    person: MediaItem? = null,
    onFilters: (() -> Unit)? = null,
    onDisplayList: ((Boolean) -> Unit)? = null,
    onClearFilters: (() -> Unit)? = null,
    onRemoveFilter: ((String) -> Unit)? = null
) {
    val shortWindow = isShortTouchWindow()
    val grid = rememberLazyGridState()
    val currentNearEnd by androidx.compose.runtime.rememberUpdatedState(onNearEnd)
    val cellMinimum = if (shortWindow) 160.dp else 132.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // One artwork height per grid: columns round to different pixels, and an aspect ratio would inherit that wobble.
        val artHeight = touchGridArtHeight(maxWidth, cellMinimum, if (shortWindow) 9f / 16f else 3f / 2f)
        Column(Modifier.fillMaxSize()) {
            TopAppBar(modifier = Modifier.testTag("touch:library-appbar"), title = {
                TouchCatalogHeading(state.parent.name, section = true)
            }, navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.testTag("touch:back")) { Icon(touchActionIcon("back"), "Back") }
            })
            LazyVerticalGrid(
                columns = if (state.list) GridCells.Fixed(1) else GridCells.Adaptive(cellMinimum),
                state = grid,
                modifier = Modifier.weight(1f).testTag("touch:grid"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = if (shortWindow) 12.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(if (shortWindow) 12.dp else 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(if (shortWindow) 8.dp else 12.dp)) {
                        if (person != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                TouchCatalogArtwork(
                                    person,
                                    state.account,
                                    false,
                                    Modifier.width(if (shortWindow) 48.dp else 112.dp).aspectRatio(if (shortWindow) 1f else 2f / 3f)
                                )
                                Text(
                                    person.overview?.takeIf(String::isNotBlank) ?: "Titles featuring ${person.name}",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            itemVerticalAlignment = Alignment.CenterVertically
                        ) {
                            if (onSort != null) TouchCatalogSort(state, onSort)
                            if (onFilters != null) {
                                FilterChip(
                                    selected = state.filters.active,
                                    onClick = onFilters,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("touch:filters"),
                                    label = { Text(filterChipLabel(state.filters)) }
                                )
                            }
                            if (onDisplayList != null) {
                                IconToggleButton(
                                    checked = state.list,
                                    onCheckedChange = onDisplayList,
                                    modifier = Modifier.testTag("touch:display")
                                ) {
                                    Icon(
                                        touchActionIcon(if (state.list) "list" else "grid"),
                                        contentDescription = if (state.list) "List view. Switch to grid" else "Grid view. Switch to list"
                                    )
                                }
                            }
                        }
                        if (onRemoveFilter != null && state.filters.active) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                state.filters.constraintKeys().forEach { key ->
                                    InputChip(
                                        selected = true,
                                        onClick = { onRemoveFilter(key) },
                                        label = { Text(filterConstraintLabel(state.filters, key)) },
                                        trailingIcon = {
                                            Icon(
                                                touchActionIcon("close"),
                                                "Remove ${filterConstraintLabel(state.filters, key)}",
                                                Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                            }
                        }
                        state.preferenceStatus?.let { Text(it) }
                        TouchCatalogError(state.error ?: state.refreshError, onRetry)
                        if (state.loading && state.items.isEmpty()) TouchCatalogLoading("Loading titles…")
                        if (!state.loading && state.items.isEmpty() && state.error == null) {
                            Text(emptyGridLabel(state.parent, state.filters))
                            if (state.filters.active && onClearFilters != null) {
                                Button(
                                    onClick = onClearFilters,
                                    modifier = Modifier.testTag("touch:clear-filters")
                                ) { Text("Clear filters") }
                            }
                        }
                    }
                }
                itemsIndexed(state.items, key = { _, item -> item.id }) { _, item ->
                    if (state.list) {
                        TouchCatalogListRow(item, state.account, { onOpen(item) })
                    } else {
                        TouchCatalogCard(item, state.account, shortWindow, {
                            onOpen(item)
                        }, Modifier.fillMaxWidth(), artHeight = artHeight)
                    }
                }
                if (state.loading && state.items.isNotEmpty()) {
                    item(key = "progress", span = { GridItemSpan(maxLineSpan) }) { TouchCatalogLoading("Loading more titles…") }
                }
            }
        }
    }
    LaunchedEffect(grid, state.items.size) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.collect { index ->
            if (index != null && state.items.isNotEmpty()) currentNearEnd((index - 1).coerceAtLeast(0))
        }
    }
}

/** The same cell count GridCells.Adaptive arrives at, under the grid's 20 dp margins and 12 dp gaps. */
internal fun touchGridArtHeight(gridWidth: Dp, cellMinimum: Dp, heightPerWidth: Float): Dp {
    val content = gridWidth - 40.dp
    val columns = ((content + 12.dp) / (cellMinimum + 12.dp)).toInt().coerceAtLeast(1)
    val cell = (content - 12.dp * (columns - 1)) / columns
    return ceil((cell * heightPerWidth).value).dp
}

@Composable
private fun TouchCatalogSort(state: CatalogGridState, onSort: (String, Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = TOUCH_LIBRARY_SORTS
    val label = options.firstOrNull { it.first == state.sortBy }?.second ?: "Title"
    // Sort and its direction are equal in rank, so they match; the menu shows its current choice to everyone.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            AssistChip(
                onClick = { expanded = true },
                modifier = Modifier.heightIn(min = 48.dp).testTag("touch:sort")
                    .touchMenuButton(expanded, "Show sort options", sortDirectionLabel(state.descending)) { expanded = true },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(label)
                        Icon(touchActionIcon(if (state.descending) "arrow-down" else "arrow-up"), null, Modifier.size(18.dp))
                    }
                },
                trailingIcon = { Icon(touchActionIcon("chevron-down"), contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (key, title) ->
                    val current = state.sortBy == key
                    DropdownMenuItem(
                        text = { Text(title) },
                        onClick = {
                            expanded = false
                            onSort(key, state.descending)
                        },
                        trailingIcon = if (current) {
                            { Icon(touchActionIcon("check"), contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = if (current) "Selected" else "" }
                    )
                }
                listOf(false to "Ascending", true to "Descending").forEach { (descending, title) ->
                    DropdownMenuItem(
                        text = { Text(title) },
                        onClick = {
                            expanded = false
                            onSort(state.sortBy, descending)
                        },
                        trailingIcon = { if (descending == state.descending) Icon(touchActionIcon("check"), null) }
                    )
                }
            }
        }
    }
}

/** TV's playback choice, withholding a stale target until the report and every dependent read settle. */
internal fun touchDetailPlayItem(state: CatalogDetailState): MediaItem? {
    if (state.playbackPending || state.loading || state.error != null && state.item == null) return null
    return when (val item = state.item) {
        null -> null
        else -> when (item.type) {
            "Series" -> state.nextUp.firstOrNull() ?: state.episodes.firstOrNull { !it.userData.played } ?: state.episodes.firstOrNull()
            "Season" -> state.episodes.firstOrNull { !it.userData.played } ?: state.episodes.firstOrNull()
            "Movie", "Episode", "Video" -> item
            else -> null
        }
    }
}

@Composable
internal fun TouchCatalogDetail(
    state: CatalogDetailState,
    onOpen: (MediaItem) -> Unit,
    onSeason: (MediaItem) -> Unit,
    onPlayed: () -> Unit,
    onFavorite: () -> Unit,
    onRetry: () -> Unit,
    onPlay: (MediaItem, Boolean) -> Unit,
    onBack: () -> Unit,
    onPlayOnTv: (MediaItem, Boolean) -> Unit = { _, _ -> },
    onPlayNext: (MediaItem) -> Unit = {},
    onAddToQueue: (MediaItem) -> Unit = {},
    origin: MediaItem? = null,
    onPlayedRequest: () -> Unit = {},
    onDownload: ((MediaItem, String?, String?) -> Unit)? = null,
    offerTvQueue: Boolean = true
) {
    val page = rememberTouchDetailPresentation(
        state,
        origin,
        onPlayed,
        onFavorite,
        onRetry,
        onPlay,
        onPlayOnTv,
        onPlayNext,
        onAddToQueue,
        onPlayedRequest,
        offerTvQueue
    )
    // Back abandons at once, before the navigation transition has released this composition.
    val leave = {
        page.abandon()
        onBack()
    }
    BackHandler(onBack = leave)
    val item = page.header
    val shortWindow = isShortTouchWindow()
    val density = LocalDensity.current
    var bandHeight by remember { mutableIntStateOf(0) }
    // The same top as a library page, so Back doesn't shift between screens.
    val pageTop = if (shortWindow) 12.dp else 20.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageHeight = maxHeight
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize().testTag("touch:detail"),
            // The bottom band covers the page without moving it; this keeps the page's end reachable above it.
            contentPadding = PaddingValues(top = pageTop, bottom = 16.dp + with(density) { bandHeight.toDp() }),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item(key = "body") {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TouchBack(leave)
                    if (item != null) {
                        BoxWithConstraints {
                            val information: @Composable () -> Unit = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    TouchCatalogHeading(item.name)
                                    if (item.type == "Episode") Text(listOfNotNull(item.seriesName, episodeLabel(item)).joinToString(" · "))
                                    val metadata = listOfNotNull(
                                        item.productionYear?.toString(),
                                        item.officialRating,
                                        item.communityRating?.let(::communityRatingLabel),
                                        runtimeLabel(item.runTimeTicks).takeIf(String::isNotBlank)
                                    )
                                    if (metadata.isNotEmpty()) {
                                        Text(
                                            metadata.joinToString(" · "),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (item.type in listOf("Movie", "Episode", "Video", "Series", "Season")) {
                                        TouchDetailActionGrid(page.actions)
                                        // Under the grid, so it moves none of the actions the grid holds still.
                                        if (onDownload != null) {
                                            OutlinedButton(
                                                onClick = {
                                                    val season = if (item.type == "Season") {
                                                        item
                                                    } else {
                                                        state.seasons.firstOrNull { it.id == state.selectedSeasonId }
                                                    }
                                                    onDownload(item, season?.id, season?.name)
                                                },
                                                enabled = !state.busy && !state.loading,
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("touch:detail:download")
                                            ) { Text("Download") }
                                        }
                                    }
                                    item.overview?.takeIf(String::isNotBlank)?.let { Text(it, Modifier.widthIn(max = 640.dp)) }
                                    if (item.genres.isNotEmpty()) {
                                        Text(
                                            item.genres.joinToString(" · "),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (page.additions.isNotEmpty()) {
                                        Text(
                                            "More details",
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.semantics { heading() }
                                        )
                                        page.additions.forEach { Text(it) }
                                    }
                                }
                            }
                            if (maxWidth >= 720.dp || shortWindow && maxWidth >= 520.dp) {
                                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                    TouchCatalogArtwork(
                                        item,
                                        state.account,
                                        false,
                                        Modifier.width(if (shortWindow) 140.dp else 200.dp).aspectRatio(2f / 3f)
                                            .clip(MaterialTheme.shapes.medium)
                                    )
                                    Box(Modifier.weight(1f)) { information() }
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                    val backdrop = item.takeIf { it.backdropImageTags.isEmpty() && it.parentBackdropImageTags.isEmpty() }
                                        ?: item.copy(imageTags = emptyMap())
                                    TouchCatalogArtwork(
                                        backdrop,
                                        state.account,
                                        true,
                                        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium)
                                    )
                                    information()
                                }
                            }
                        }
                    }
                }
            }
            if (item?.type == "Series" && state.nextUp.isNotEmpty()) {
                item(key = "next") { TouchCatalogShelf("Next up", state.nextUp, state.account, true, onOpen = onOpen) }
            }
            if (item?.type == "Series" && state.seasons.isNotEmpty()) {
                item(key = "seasons") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TouchCatalogHeading("Seasons", Modifier.padding(horizontal = 20.dp), section = true)
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.seasons, key = MediaItem::id) { season ->
                                FilterChip(
                                    selected = season.id == state.selectedSeasonId,
                                    onClick = { onSeason(season) },
                                    label = { Text(season.name) },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                )
                            }
                        }
                    }
                }
            }
            if (item?.type in listOf("Series", "Season") && state.episodes.isNotEmpty()) {
                item(key = "episodes") { TouchCatalogShelf("Episodes", state.episodes, state.account, true, onOpen = onOpen) }
            }
            if (
                showDetailEmptyState(
                    item?.type,
                    state.loading,
                    state.readSucceeded,
                    state.readFailed,
                    state.episodes.size,
                    state.seasons.size
                )
            ) {
                item(key = "empty") { Text("No episodes are available.", modifier = Modifier.padding(horizontal = 20.dp)) }
            }
            // Episodes come first on every width: on a series page they are what the viewer came for.
            // The frozen header can be a route card, which has no credits: the fresh read's credits still appear.
            val people = (state.item ?: item)?.people.orEmpty().filter { it.id.isNotBlank() }.distinctBy(MediaPerson::id)
            if (people.isNotEmpty()) {
                item(key = "people") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TouchCatalogHeading("Cast & crew", Modifier.padding(horizontal = 20.dp), section = true)
                        LazyRow(
                            state = rememberLazyListState(),
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(people, key = MediaPerson::id) { person ->
                                val linkedItem = person.touchPersonItem()
                                val credit = person.role?.takeIf(String::isNotBlank) ?: person.type?.takeIf(String::isNotBlank)
                                Card(
                                    onClick = { onOpen(linkedItem) },
                                    modifier = Modifier.width(136.dp).heightIn(min = 48.dp)
                                        .testTag("touch:person:${person.id}").semantics(mergeDescendants = true) {
                                            contentDescription = listOfNotNull(person.name, credit).joinToString(", ")
                                        }
                                ) {
                                    TouchCatalogArtwork(linkedItem, state.account, false, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                                    Column(
                                        Modifier.padding(12.dp).clearAndSetSemantics {},
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            person.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (credit != null) {
                                            Text(
                                                credit,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // Both overlays have no layout footprint: status and failure cannot move the action under a finger.
        page.status?.let { status ->
            // Level with Back, whatever the wrap, so it reads as the page's state and not as a stray pill.
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = pageTop, end = 20.dp).heightIn(min = 48.dp).widthIn(max = maxWidth * 0.6f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    status,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 8.dp).testTag("touch:detail:status")
                )
            }
        }
        val failure = page.failure ?: page.writeFailure?.let { "Change failed. $it" }
        if (failure != null) {
            val retrying = page.recovery && page.retryWorking
            val retryTag = if (page.recovery) "touch:detail:retry" else "touch:detail:write-retry"
            // A floating card: where it overlaps the page it reads as deliberate. Retry stays pinned while a long reason scrolls.
            Surface(
                Modifier.align(Alignment.BottomCenter).widthIn(max = 720.dp).fillMaxWidth().onSizeChanged { bandHeight = it.height }
                    .padding(12.dp).heightIn(max = pageHeight * 0.5f).testTag("touch:detail:recovery"),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        if (page.recovery) detailReadBandText(retrying, failure) else failure,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                            .testTag(if (page.recovery) "touch:detail:read-error" else "touch:detail:write-error")
                    )
                    val retryModifier = Modifier.heightIn(min = 48.dp).testTag(retryTag).semantics {
                        if (page.recovery) {
                            stateDescription = if (retrying) "Loading item. Please wait." else detailRetryDescription(failure)
                        }
                    }
                    val retryLabel = if (retrying) "Retrying…" else "Retry"
                    // A read failure has disabled Play, so Retry is the page's one primary; a write failure has Play beside it.
                    if (page.recovery) {
                        Button(onClick = page.onRetry, modifier = retryModifier) { Text(retryLabel) }
                    } else {
                        FilledTonalButton(onClick = page.onRetry, modifier = retryModifier) { Text(retryLabel) }
                    }
                }
            }
        } else {
            SideEffect { bandHeight = 0 }
        }
        // Spoken through one hidden, always-present polite region: a new node would not announce its first text.
        Text(
            page.cue,
            modifier = Modifier.align(Alignment.TopStart).size(1.dp).alpha(0f).testTag("touch:detail:cue").semantics {
                liveRegion = LiveRegionMode.Polite
            }
        )
    }
}

/**
 * One primary, the pair that changes this title's state, and the TV's actions gathered behind one control, so the
 * title and overview stay near the top. Every cell is as tall as its longest wording: pending, requested and fresh
 * labels never reflow it.
 */
@Composable
private fun TouchDetailActionGrid(actions: List<TouchDetailAction>) {
    val primary = actions.first { it.target == "primary" }
    val fromStart = actions.firstOrNull { it.target == "from-start" }?.takeIf(TouchDetailAction::visible)
    val played = actions.first { it.target == "played" }
    val favorite = actions.first { it.target == "favorite" }
    val tv = actions.filter { it.target in TOUCH_TV_ACTIONS }
    val cell = Modifier.fillMaxHeight()
    val shortWindow = isShortTouchWindow()
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("touch:detail:actions")) {
        // Five cells only with the room to hold "Resume from 0:35" on a line; a portrait tablet's 640 dp doesn't. A short
        // window keeps them anyway, since three rows would take most of its height.
        if (maxWidth >= 720.dp || shortWindow && maxWidth >= 600.dp) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(2f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TouchDetailActionButton(primary, cell.weight(1f))
                    fromStart?.let { TouchDetailActionButton(it, cell.weight(1f)) }
                }
                TouchDetailActionButton(played, cell.weight(1f))
                TouchDetailActionButton(favorite, cell.weight(1f))
                TouchDetailTvActions(tv, cell.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TouchDetailActionButton(primary, cell.weight(1f))
                    fromStart?.let { TouchDetailActionButton(it, cell.weight(1f)) }
                }
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TouchDetailActionButton(played, cell.weight(1f))
                    TouchDetailActionButton(favorite, cell.weight(1f))
                }
                TouchDetailTvActions(tv, Modifier.fillMaxWidth(), centerOnButton = true)
            }
        }
    }
}

private val TOUCH_TV_ACTIONS = setOf("on-tv", "play-next", "add-to-queue", "on-tv-start")

/** State on its own quiet line, so a whole grid of "when ready" matches. A reader gets the one wording, unchanged. */
@Composable
private fun TouchDetailWording(wording: String, modifier: Modifier = Modifier, spoken: Boolean = true) {
    val name = wording.removeSuffix(" when ready")
    Column(
        modifier.clearAndSetSemantics { if (spoken) text = AnnotatedString(wording) },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(name, textAlign = TextAlign.Center)
        if (name != wording) {
            Text(
                "when ready",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = LocalContentColor.current.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun TouchDetailReservedWording(label: String, reserve: List<String>) {
    Box(contentAlignment = Alignment.Center) {
        reserve.forEach { wording -> TouchDetailWording(wording, Modifier.alpha(0f), spoken = false) }
        TouchDetailWording(label)
    }
}

@Composable
private fun TouchDetailActionButton(action: TouchDetailAction, modifier: Modifier) {
    val icon = action.icon ?: "check".takeIf { action.requested }
    val content: @Composable RowScope.() -> Unit = {
        // The reserve is measured beside an icon's width, so a check arriving cannot wrap the label and move the row.
        Box(contentAlignment = Alignment.Center) {
            action.reserve.forEach { wording ->
                TouchDetailWording(wording, Modifier.alpha(0f).padding(start = 24.dp), spoken = false)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(touchActionIcon(icon), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                TouchDetailWording(action.label)
            }
        }
    }
    val sized = modifier.heightIn(min = 48.dp).testTag("touch:detail:${action.target}").semantics {
        action.state?.let { stateDescription = it }
    }
    val padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    val disabledLabel = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    if (action.target == "primary") {
        Button(
            onClick = action.onClick,
            enabled = action.enabled,
            modifier = sized,
            contentPadding = padding,
            colors = ButtonDefaults.buttonColors(disabledContentColor = disabledLabel),
            content = content
        )
    } else {
        OutlinedButton(
            onClick = action.onClick,
            enabled = action.enabled,
            modifier = sized,
            contentPadding = padding,
            border = touchOutlineBorder(action.enabled),
            colors = ButtonDefaults.outlinedButtonColors(disabledContentColor = disabledLabel),
            content = content
        )
    }
}

private val TOUCH_CHEVRON_END = 16.dp

/** The chevron and the gap before it. */
private val TOUCH_CHEVRON_ROOM = 22.dp

/** A button that opens a menu says so: a reader hears whether it is open and what a double tap will do. */
private fun Modifier.touchMenuButton(expanded: Boolean, openLabel: String, statePrefix: String? = null, open: () -> Unit) = semantics {
    stateDescription = listOfNotNull(statePrefix, if (expanded) "Expanded" else "Collapsed").joinToString(", ")
    onClick(label = openLabel) {
        open()
        true
    }
}

/** The TV's four actions behind one control. Its wording follows whichever request was accepted. */
@Composable
private fun TouchDetailTvActions(actions: List<TouchDetailAction>, modifier: Modifier, centerOnButton: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val lead = actions.firstOrNull(TouchDetailAction::requested) ?: actions.first()
    val enabled = actions.any(TouchDetailAction::enabled)
    val density = LocalDensity.current
    var buttonWidth by remember { mutableStateOf(0.dp) }
    Box(modifier.onSizeChanged { buttonWidth = with(density) { it.width.toDp() } }) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().fillMaxHeight().heightIn(min = 48.dp).testTag("touch:detail:tv")
                .touchMenuButton(expanded, "Show TV actions") { expanded = true },
            // On a line of its own the label balances the chevron, so it shares a centre with Play above. A narrow cell can't spare that.
            contentPadding = PaddingValues(
                start = if (centerOnButton) TOUCH_CHEVRON_END + TOUCH_CHEVRON_ROOM else 12.dp,
                end = TOUCH_CHEVRON_END,
                top = 8.dp,
                bottom = 8.dp
            ),
            border = touchOutlineBorder(enabled),
            colors = ButtonDefaults.outlinedButtonColors(disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        ) {
            // The wording takes what's left, so the chevron stays at the button's end whatever it says.
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                // Room for "from start" too: that action arrives with a resume point, and must not grow the button then.
                val fromStart = "Play on TV from start"
                TouchDetailReservedWording(
                    lead.label,
                    actions.flatMap(TouchDetailAction::reserve) + listOf("$fromStart when ready", "$fromStart requested")
                )
            }
            Spacer(Modifier.width(TOUCH_CHEVRON_ROOM - 18.dp))
            Icon(
                touchActionIcon("chevron-down"),
                contentDescription = null,
                modifier = Modifier.size(18.dp).testTag("touch:detail:tv:chevron")
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.width(buttonWidth)) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    enabled = action.enabled,
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                    trailingIcon = if (action.requested) {
                        { Icon(touchActionIcon("check"), contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("touch:detail:${action.target}").semantics {
                        action.state?.let { stateDescription = it }
                    }
                )
            }
        }
    }
}

/** A credit kind such as Actor or Director must open a Person route. */
internal fun MediaPerson.touchPersonItem() = MediaItem(
    id = id,
    name = name,
    type = "Person",
    imageTags = primaryImageTag?.takeIf(String::isNotBlank)?.let { mapOf("Primary" to it) }.orEmpty()
)

@Composable
internal fun TouchCatalogShelf(
    title: String,
    items: List<MediaItem>,
    account: Account,
    wide: Boolean,
    reserveLines: Boolean = true,
    onOpen: (MediaItem) -> Unit
) {
    val shortWindow = isShortTouchWindow()
    Column(verticalArrangement = Arrangement.spacedBy(if (shortWindow) 8.dp else 12.dp)) {
        TouchCatalogHeading(title, Modifier.padding(horizontal = 20.dp), section = true)
        LazyRow(
            state = rememberLazyListState(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = MediaItem::id) { item ->
                val width = if (shortWindow) {
                    if (wide) 200.dp else 116.dp
                } else {
                    if (wide) 244.dp else 136.dp
                }
                TouchCatalogCard(item, account, wide, { onOpen(item) }, Modifier.width(width), reserveLines = reserveLines)
            }
        }
    }
}

@Composable
internal fun TouchCatalogListRow(item: MediaItem, account: Account, open: () -> Unit) {
    Card(
        onClick = open,
        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp).testTag("touch:card:${item.id}")
            .semantics(mergeDescendants = true) { contentDescription = catalogAnnouncement(item) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TouchCatalogArtwork(item, account, false, Modifier.width(56.dp).height(84.dp))
            Column(Modifier.weight(1f).clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        item.productionYear?.toString(),
                        runtimeLabel(item.runTimeTicks).takeIf(String::isNotBlank),
                        item.officialRating
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun TouchCatalogCard(
    item: MediaItem,
    account: Account,
    wide: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artHeight: Dp? = null,
    reserveLines: Boolean = true
) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).testTag("touch:card:${item.id}").semantics(mergeDescendants = true) {
            contentDescription = catalogAnnouncement(item)
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        val poster = !wide && item.type != "Episode"
        TouchCatalogArtwork(
            item,
            account,
            wide,
            // An episode in a poster grid keeps its own shape; everything else takes the grid's one height.
            if (artHeight != null && (poster || wide)) {
                Modifier.fillMaxWidth().height(artHeight)
            } else {
                Modifier.fillMaxWidth().aspectRatio(if (poster) 2f / 3f else 16f / 9f)
            }
        )
        // Always 4 dp tall, so progress on one card doesn't step it out of its row.
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (progress(item) > 0 && !item.isWatched) {
                LinearProgressIndicator(progress = { progress(item) }, modifier = Modifier.fillMaxWidth().clearAndSetSemantics {})
            }
        }
        Column(Modifier.padding(12.dp).clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Two title lines and a subtitle line are always reserved: cards in a row share one height whatever the data.
            Text(
                item.name,
                style = MaterialTheme.typography.titleSmall,
                minLines = if (reserveLines) 2 else 1,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = if (item.type == "Episode") {
                listOfNotNull(item.seriesName, episodeLabel(item)).joinToString(" · ")
            } else {
                listOfNotNull(item.productionYear?.toString(), if (item.isWatched) "Watched" else null).joinToString(" · ")
            }
            if (reserveLines || subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun TouchCatalogArtwork(
    item: MediaItem,
    account: Account,
    wide: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val url = imageUrl(account.server, item, 720, wide = wide)
        var failed by remember(url) { mutableStateOf(false) }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { failed = false },
                onError = { failed = true }
            )
        }
        if (url == null || failed) {
            Text(
                item.name.take(1).uppercase(),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clearAndSetSemantics {
                }
            )
        }
    }
}

@Composable
internal fun TouchCatalogHeading(title: String, modifier: Modifier = Modifier, section: Boolean = false) {
    Text(
        title,
        modifier.semantics {
            heading()
        },
        style = if (section) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium
    )
}

@Composable
internal fun TouchCatalogLoading(label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Home's error when the server is out of reach and saved films aren't: the way to them comes first. */
@Composable
private fun TouchDownloadsOffer(onWatchDownloads: () -> Unit, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            OFFLINE_UNREACHABLE_TEXT,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        Button(onClick = onWatchDownloads, modifier = Modifier.heightIn(min = 48.dp).testTag("home:watch-downloads")) {
            Text("Watch downloads")
        }
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("home:retry")) { Text("Retry") }
    }
}

@Composable
internal fun TouchCatalogError(message: String?, onRetry: () -> Unit) {
    if (message != null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
        }
    }
}
