/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.pictureshow.core.Account
import app.pictureshow.core.HomeRow
import app.pictureshow.core.MediaItem
import app.pictureshow.core.MediaPerson
import app.pictureshow.core.episodeLabel
import app.pictureshow.core.imageUrl
import app.pictureshow.core.isEmptyContainer
import app.pictureshow.core.isWatched
import app.pictureshow.core.progress
import app.pictureshow.core.resumeLabel
import app.pictureshow.core.runtimeLabel
import coil3.compose.AsyncImage

@Composable
internal fun TouchCatalogHome(
    state: CatalogHomeState,
    account: Account,
    onOpen: (MediaItem) -> Unit,
    onRetry: () -> Unit,
    onResume: () -> Unit = {},
    librariesOnly: Boolean = false
) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onResume() }
    val rows = state.rows.filter { it.items.isNotEmpty() && (!librariesOnly || it.library) }
    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize().testTag("touch:home"),
        contentPadding = PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item(key = "heading") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TouchCatalogHeading(if (librariesOnly) "Your libraries" else "Pictureshow")
                Text(
                    if (librariesOnly) "Explore your collection" else "What would you like to watch?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.loading) TouchCatalogLoading("Loading your library…")
                TouchCatalogError(state.error, onRetry)
                if (!state.loading && rows.isEmpty() && state.error == null) {
                    Text(if (librariesOnly) "No libraries are available for this account." else "Your library is ready for something new.")
                    TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh") }
                }
            }
        }
        items(rows, key = HomeRow::key) { row -> TouchCatalogShelf(row.title, row.items, account, row.wide || row.library, onOpen) }
    }
}

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
    person: MediaItem? = null
) {
    val grid = rememberLazyGridState()
    val currentNearEnd by androidx.compose.runtime.rememberUpdatedState(onNearEnd)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(132.dp),
        state = grid,
        modifier = Modifier.fillMaxSize().testTag("touch:grid"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TouchCatalogBack(onBack)
                TouchCatalogHeading(state.parent.name)
                if (person != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        TouchCatalogArtwork(person, state.account, false, Modifier.width(112.dp).aspectRatio(2f / 3f))
                        Text(
                            person.overview?.takeIf(String::isNotBlank) ?: "Titles featuring ${person.name}",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (onSort != null) TouchCatalogSort(state, onSort)
                TouchCatalogError(state.error ?: state.refreshError, onRetry)
                if (state.loading && state.items.isEmpty()) TouchCatalogLoading("Loading titles…")
                if (!state.loading && state.items.isEmpty() && state.error == null) Text("No titles are available.")
            }
        }
        itemsIndexed(state.items, key = { _, item -> item.id }) { _, item ->
            TouchCatalogCard(item, state.account, false, { onOpen(item) }, Modifier.fillMaxWidth())
        }
        if (state.loading && state.items.isNotEmpty()) {
            item(key = "progress", span = { GridItemSpan(maxLineSpan) }) { TouchCatalogLoading("Loading more titles…") }
        }
    }
    LaunchedEffect(grid, state.items.size) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.collect { index ->
            if (index != null && state.items.isNotEmpty()) currentNearEnd((index - 1).coerceAtLeast(0))
        }
    }
}

@Composable
private fun TouchCatalogSort(state: CatalogGridState, onSort: (String, Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("SortName" to "Title", "DateCreated" to "Date added", "ProductionYear" to "Year", "CommunityRating" to "Rating")
    val label = options.firstOrNull { it.first == state.sortBy }?.second ?: "Title"
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Sort: $label") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (key, title) ->
                    DropdownMenuItem(
                        text = { Text(title) },
                        onClick = {
                            expanded = false
                            onSort(key, state.descending)
                        },
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            stateDescription = if (state.sortBy ==
                                key
                            ) {
                                "Selected"
                            } else {
                                ""
                            }
                        }
                    )
                }
            }
        }
        TextButton(onClick = { onSort(state.sortBy, !state.descending) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (state.descending) "Descending" else "Ascending")
        }
    }
}

/** Match TV's playback choice while withholding stale progress during a report/read handoff. */
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TouchCatalogDetail(
    state: CatalogDetailState,
    onOpen: (MediaItem) -> Unit,
    onSeason: (MediaItem) -> Unit,
    onPlayed: () -> Unit,
    onFavorite: () -> Unit,
    onRetry: () -> Unit,
    onPlay: (MediaItem, Boolean) -> Unit,
    onBack: () -> Unit
) {
    val item = state.item.takeUnless { state.playbackPending }
    val playable = touchDetailPlayItem(state)
    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize().testTag("touch:detail"),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item(key = "body") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TouchCatalogBack(onBack)
                TouchCatalogError(state.error, onRetry)
                if (state.playbackPending) {
                    TouchCatalogLoading(if (state.playbackReportFinished) "Loading item…" else "Saving progress…")
                } else if (item == null && state.loading) {
                    TouchCatalogLoading("Loading item…")
                }
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
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { playable?.let { onPlay(it, it.userData.playbackPositionTicks > 0) } },
                                            enabled = playable != null && !state.busy,
                                            modifier = Modifier.heightIn(min = 48.dp).testTag("touch:detail:play")
                                        ) {
                                            Text(playable?.userData?.playbackPositionTicks?.takeIf { it > 0 }?.let(::resumeLabel) ?: "Play")
                                        }
                                        if (playable?.userData?.playbackPositionTicks?.let { it > 0 } == true) {
                                            OutlinedButton(onClick = {
                                                onPlay(playable, false)
                                            }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                                                Text("Play from start")
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = onFavorite,
                                            enabled = !state.busy && !state.loading,
                                            modifier = Modifier.heightIn(min = 48.dp).semantics {
                                                stateDescription = if (item.userData.isFavorite) "Favorite" else "Not favorite"
                                            }
                                        ) { Text(if (item.userData.isFavorite) "Remove favorite" else "Favorite") }
                                        OutlinedButton(
                                            onClick = onPlayed,
                                            enabled = !state.busy && !state.loading && !item.isEmptyContainer,
                                            modifier = Modifier.heightIn(min = 48.dp).semantics {
                                                stateDescription =
                                                    if (item.isWatched) "Watched" else "Unwatched"
                                            }
                                        ) { Text(if (item.isWatched) "Mark unwatched" else "Mark watched") }
                                    }
                                }
                                item.overview?.takeIf(String::isNotBlank)?.let { Text(it) }
                                if (item.genres.isNotEmpty()) {
                                    Text(
                                        item.genres.joinToString(" · "),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (maxWidth >= 720.dp) {
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                TouchCatalogArtwork(item, state.account, false, Modifier.width(200.dp).aspectRatio(2f / 3f))
                                Box(Modifier.weight(1f)) { information() }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                val backdrop = item.takeIf { it.backdropImageTags.isEmpty() && it.parentBackdropImageTags.isEmpty() }
                                    ?: item.copy(imageTags = emptyMap())
                                TouchCatalogArtwork(backdrop, state.account, true, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                                information()
                            }
                        }
                    }
                    if (state.loading) TouchCatalogLoading("Loading episodes…")
                }
            }
        }
        val people = item?.people.orEmpty().filter { it.id.isNotBlank() }.distinctBy(MediaPerson::id)
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
                                Column(Modifier.padding(12.dp).clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        person.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (credit !=
                                        null
                                    ) {
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
        if (item?.type == "Series" && state.nextUp.isNotEmpty()) {
            item(key = "next") { TouchCatalogShelf("Next up", state.nextUp, state.account, true, onOpen) }
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
        if (item?.type in listOf("Series", "Season")) {
            if (state.episodes.isNotEmpty()) {
                item(key = "episodes") { TouchCatalogShelf("Episodes", state.episodes, state.account, true, onOpen) }
            } else if (!state.loading && state.error == null) {
                item(key = "empty") { Text("No episodes are available.", modifier = Modifier.padding(horizontal = 20.dp)) }
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
internal fun TouchCatalogShelf(title: String, items: List<MediaItem>, account: Account, wide: Boolean, onOpen: (MediaItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TouchCatalogHeading(title, Modifier.padding(horizontal = 20.dp), section = true)
        LazyRow(
            state = rememberLazyListState(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = MediaItem::id) { item ->
                TouchCatalogCard(item, account, wide, { onOpen(item) }, Modifier.width(if (wide) 244.dp else 136.dp))
            }
        }
    }
}

@Composable
internal fun TouchCatalogCard(item: MediaItem, account: Account, wide: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).testTag("touch:card:${item.id}").semantics(mergeDescendants = true) {
            contentDescription = catalogAnnouncement(item)
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        TouchCatalogArtwork(
            item,
            account,
            wide,
            Modifier.fillMaxWidth().aspectRatio(
                if (wide ||
                    item.type == "Episode"
                ) {
                    16f / 9f
                } else {
                    2f / 3f
                }
            )
        )
        if (progress(item) > 0 && !item.isWatched) {
            LinearProgressIndicator(progress = { progress(item) }, modifier = Modifier.fillMaxWidth().clearAndSetSemantics {})
        }
        Column(Modifier.padding(12.dp).clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val subtitle = if (item.type == "Episode") {
                listOfNotNull(item.seriesName, episodeLabel(item)).joinToString(" · ")
            } else {
                listOfNotNull(item.productionYear?.toString(), if (item.isWatched) "Watched" else null).joinToString(" · ")
            }
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
internal fun TouchCatalogArtwork(item: MediaItem, account: Account, wide: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val url = imageUrl(account.server, item, 720, wide = wide)
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
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

@Composable
internal fun TouchCatalogError(message: String?, onRetry: () -> Unit) {
    if (message != null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
        }
    }
}

@Composable
private fun TouchCatalogBack(onBack: () -> Unit) {
    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("‹ Back") }
}
