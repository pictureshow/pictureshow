/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.pictureshow.core.Account
import app.pictureshow.core.CatalogService
import app.pictureshow.core.ClientResult
import app.pictureshow.core.HomePin
import app.pictureshow.core.HomePinKind
import app.pictureshow.core.HomePreferences
import app.pictureshow.core.LibraryFacets
import app.pictureshow.core.LibraryFilters
import app.pictureshow.core.MediaItem
import app.pictureshow.core.VideoResolution
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A full-screen scrollable editor gives TV overscan and large text the same space as phone touch. */
@Composable
private fun CatalogEditor(title: String, close: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val tv = usesTvInterface(LocalContext.current)
    val first = remember { FocusRequester() }
    MaterialTheme(
        colorScheme = if (tv) {
            androidx.compose.material3.darkColorScheme(
                background = androidx.compose.ui.graphics.Color(0xFF10131C),
                surface = androidx.compose.ui.graphics.Color(0xFF10131C)
            )
        } else {
            MaterialTheme.colorScheme
        }
    ) {
        Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("catalog:editor"),
                    contentPadding = PaddingValues(horizontal = if (tv) 48.dp else 24.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        EditorAction("Back", close, Modifier.focusRequester(first))
                        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    }
                    content()
                }
            }
            LaunchedEffect(Unit) { if (tv) first.requestFocus() }
        }
    }
}

@Composable
internal fun EditorAction(label: String, action: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val tv = usesTvInterface(LocalContext.current)
    if (tv) {
        androidx.tv.material3.Button(
            onClick = action,
            enabled = enabled,
            scale = androidx.tv.material3.ButtonDefaults.scale(focusedScale = 1f),
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) {
            androidx.tv.material3.Text(label)
        }
    } else {
        OutlinedButton(onClick = action, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
    }
}

@Composable
internal fun HomeCustomization(
    state: CatalogHomeState,
    account: Account,
    service: CatalogService,
    change: ((HomePreferences) -> HomePreferences) -> Unit,
    close: () -> Unit
) {
    var picker by remember { mutableStateOf<HomePinKind?>(null) }
    val preferences = state.preferences
    val defaults = listOf(
        "resume" to "Continue watching",
        "nextup" to "Next up",
        "watching" to "Continue watching and next up",
        "libraries" to "Libraries"
    ) +
        state.libraries.filter { it.collectionType in listOf("movies", "tvshows") }.map { "latest:${it.id}" to "Latest in ${it.name}" }
    val known = (
        defaults + state.availableRows.map { it.key to it.title } +
            preferences.pins.map { it.key to "Pinned ${it.kind.name.lowercase()}" }
        )
        .distinctBy { it.first }
    val rows = known.sortedBy { (key, _) -> preferences.order.indexOf(key).takeIf { it >= 0 } ?: Int.MAX_VALUE }
    CatalogEditor("Customize home", close) {
        item {
            Text("Changes belong to ${account.user.name}. Hidden rows remain available here.")
            state.preferenceStatus?.let { Text(it) }
            if (state.saving) Text("Saving layout…")
            EditorAction("Retry server save", { change { it } }, enabled = !state.saving)
            val watchingLabel = if (preferences.mergeWatching) {
                "Watching rows: Combined · choose Separate"
            } else {
                "Watching rows: Separate · choose Combined"
            }
            EditorAction(watchingLabel, {
                change { it.copy(mergeWatching = !it.mergeWatching) }
            }, enabled = !state.saving)
        }
        item { Text("Landing view", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }
        items(
            listOf("home" to "Home", "libraries" to "Libraries") + state.libraries.map {
                "library:${it.id}" to it.name
            },
            key = { "landing:${it.first}" }
        ) { (key, label) ->
            EditorAction("${if (preferences.landing == key) "Selected: " else ""}$label", {
                change { it.copy(landing = key) }
            }, enabled = !state.saving)
        }
        item {
            Text("Rows · show, hide and reorder", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        }
        items(rows, key = { it.first }) { (key, title) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                EditorAction(if (key in preferences.hidden) "Show $title" else "Hide $title", {
                    change { it.copy(hidden = if (key in it.hidden) it.hidden - key else it.hidden + key) }
                }, enabled = !state.saving)
                val index = rows.indexOfFirst { it.first == key }
                fun move(delta: Int) {
                    val order = rows.map { it.first }.toMutableList()
                    val target = (index + delta).coerceIn(order.indices)
                    order.removeAt(index)
                    order.add(target, key)
                    change { it.copy(order = order) }
                }
                EditorAction("Move $title up", { move(-1) }, enabled = !state.saving && index > 0)
                EditorAction("Move $title down", { move(1) }, enabled = !state.saving && index < rows.lastIndex)
                if (key.startsWith("pin:")) {
                    EditorAction("Unpin $title", {
                        change {
                            it.copy(
                                pins = it.pins.filterNot { pin -> pin.key == key },
                                order = it.order - key,
                                hidden =
                                it.hidden - key
                            )
                        }
                    }, enabled = !state.saving)
                }
            }
        }
        item { Text("Add a row", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }
        items(HomePinKind.entries) { kind ->
            EditorAction("Pin ${kind.name.lowercase()}", {
                if (kind == HomePinKind.FAVORITES) {
                    change { it.copy(pins = (it.pins + HomePin(kind)).distinctBy { pin -> pin.key }) }
                } else {
                    picker = kind
                }
            }, enabled = !state.saving)
        }
        item { Text("Next up", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }
        items(
            state.availableRows.firstOrNull {
                it.key == "nextup"
            }?.items.orEmpty().filter { it.seriesId != null }.distinctBy { it.seriesId },
            key = { "hide:${it.seriesId}" }
        ) { episode ->
            val id = requireNotNull(episode.seriesId)
            val label = if (id in preferences.hiddenSeries) {
                "Restore ${episode.seriesName ?: episode.name} to Next up"
            } else {
                "Remove ${episode.seriesName ?: episode.name} from Next up"
            }
            EditorAction(label, {
                change { it.copy(hiddenSeries = if (id in it.hiddenSeries) it.hiddenSeries - id else it.hiddenSeries + id) }
            }, enabled = !state.saving)
        }
        if (preferences.hiddenSeries.isNotEmpty()) {
            item {
                EditorAction("Restore all removed series to Next up", {
                    change { it.copy(hiddenSeries = emptySet()) }
                }, enabled = !state.saving)
            }
        }
        item {
            EditorAction("Restore default home layout", { change { HomePreferences(libraries = it.libraries) } }, enabled = !state.saving)
        }
    }
    picker?.let { kind ->
        NamedCatalogPicker(
            account,
            service,
            when (kind) {
                HomePinKind.COLLECTION -> "BoxSet"
                HomePinKind.PLAYLIST -> "Playlist"
                HomePinKind.GENRE -> "Genres"
                HomePinKind.STUDIO -> "Studios"
                HomePinKind.FAVORITES -> error("Favorites needs no picker")
            },
            select = { item ->
                val pin = HomePin(kind, if (kind in listOf(HomePinKind.GENRE, HomePinKind.STUDIO)) item.name else item.id)
                change { it.copy(pins = (it.pins + pin).distinctBy { it.key }) }
                picker = null
            },
            close = { picker = null }
        )
    }
}

@Composable
internal fun NamedCatalogPicker(
    account: Account,
    service: CatalogService,
    kind: String,
    parentId: String = "",
    select: (MediaItem) -> Unit,
    close: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var requested by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf(emptyList<MediaItem>()) }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(kind, requested, page, retry) {
        loading = true
        error = null
        val result = service.namedItems(account, kind, parentId, requested, page)
        currentCoroutineContext().ensureActive()
        when (result) {
            is ClientResult.Success -> {
                currentCoroutineContext().ensureActive()
                items = result.value.items.distinctBy { it.id }
                total = result.value.totalRecordCount
            }
            is ClientResult.Failure -> {
                items = emptyList()
                error = result.error.message
            }
        }
        loading = false
    }
    CatalogEditor("Choose ${kind.lowercase()}", close) {
        item {
            OutlinedTextField(query, {
                query = it.take(200)
            }, label = { Text("Find by name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            EditorAction("Find", {
                page = 0
                items = emptyList()
                requested = query.trim()
                retry++
            })
            if (loading) Text("Loading…")
            error?.let {
                Text(it)
                EditorAction("Retry", { retry++ })
            }
            if (!loading && error == null && items.isEmpty()) Text("No choices are available for this viewer.")
        }
        items(items, key = { it.id }) { item -> EditorAction(item.name, { select(item) }, enabled = !loading) }
        if (page >
            0
        ) {
            item {
                EditorAction("Previous choices", {
                    page = (page - 60).coerceAtLeast(0)
                    items = emptyList()
                }, enabled = !loading)
            }
        }
        if (page + items.size < total && items.isNotEmpty()) {
            item {
                EditorAction("More choices", {
                    page += 60
                    items = emptyList()
                }, enabled = !loading)
            }
        }
    }
}

@Composable
internal fun LibraryFilterEditor(state: CatalogGridState, service: CatalogService, apply: (LibraryFilters) -> Unit, close: () -> Unit) {
    var draft by remember { mutableStateOf(state.filters) }
    var facets by remember { mutableStateOf(LibraryFacets()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var personPicker by remember { mutableStateOf(false) }
    var personLabel by remember { mutableStateOf("Selected person") }
    LaunchedEffect(state.parent.id, retry) {
        loading = true
        val result = service.facets(state.account, state.parent.id)
        currentCoroutineContext().ensureActive()
        when (result) {
            is ClientResult.Success -> {
                facets = result.value
                error = null
            }
            is ClientResult.Failure -> error = result.error.message
        }
        loading = false
    }
    CatalogEditor("Filter ${state.parent.name}", close) {
        item {
            Text("Filters combine with your sort. The server decides which titles this viewer can access.")
            EditorAction("Apply filters", {
                apply(draft)
                close()
            }, Modifier.testTag("filters:apply"))
            EditorAction("Clear all filters", { draft = LibraryFilters() }, Modifier.testTag("filters:clear"))
            EditorAction("Unwatched: ${if (draft.unwatched) "On" else "Off"}", {
                draft = draft.copy(unwatched = !draft.unwatched)
            }, Modifier.testTag("filters:unwatched"))
            EditorAction("Favorites: ${if (draft.favorite) "On" else "Off"}", {
                draft = draft.copy(favorite = !draft.favorite)
            }, Modifier.testTag("filters:favorites"))
            EditorAction("Resolution: ${draft.resolution.label}", {
                draft = draft.copy(resolution = VideoResolution.entries[(draft.resolution.ordinal + 1) % VideoResolution.entries.size])
            }, Modifier.testTag("filters:resolution"))
            EditorAction("Person: ${if (draft.personId == null) "Any" else personLabel}", { personPicker = true })
            if (draft.personId != null) EditorAction("Clear person", { draft = draft.copy(personId = null) })
            if (loading) Text("Loading filter choices…")
            error?.let {
                Text(it)
                EditorAction("Retry filter choices", { retry++ })
            }
        }
        item { Text("Genre: ${draft.genre ?: "Any"}", modifier = Modifier.semantics { heading() }) }
        item { EditorAction("Any genre", { draft = draft.copy(genre = null) }) }
        items(facets.genres.distinct()) { genre ->
            // A semantics callback may outlive its lazy item. Read snapshot state during
            // composition so a later draft edit cannot invalidate a detached Material node.
            val selected = draft.genre == genre
            EditorAction(
                genre,
                { draft = draft.copy(genre = genre) },
                Modifier.semantics {
                    stateDescription =
                        if (selected) "Selected" else ""
                }
            )
        }
        item { Text("Year: ${draft.year ?: "Any"}", modifier = Modifier.semantics { heading() }) }
        item { EditorAction("Any year", { draft = draft.copy(year = null) }) }
        items(facets.years.distinct().sortedDescending()) { year -> EditorAction(year.toString(), { draft = draft.copy(year = year) }) }
        item { Text("Official rating: ${draft.officialRating ?: "Any"}", modifier = Modifier.semantics { heading() }) }
        item { EditorAction("Any rating", { draft = draft.copy(officialRating = null) }) }
        items(facets.ratings.distinct()) { rating -> EditorAction(rating, { draft = draft.copy(officialRating = rating) }) }
    }
    if (personPicker) {
        NamedCatalogPicker(state.account, service, "Persons", state.parent.id, {
            draft = draft.copy(personId = it.id)
            personLabel = it.name
            personPicker = false
        }, { personPicker = false })
    }
}
