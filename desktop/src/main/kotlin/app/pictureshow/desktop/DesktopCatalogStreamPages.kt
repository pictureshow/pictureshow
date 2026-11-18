/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.pictureshow.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.pictureshow.core.*
import app.pictureshow.ui.*
import kotlinx.coroutines.delay

// ---------------------------------------------------------------- Home additions (HOME-01, 09, 10, 13)

/** Home's heading row: the title, then Customize (enabled once this viewer's layout has resolved). */
@Composable
internal fun HomeHeadingRow(state: DesktopState, c: DesktopController, heading: Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CatalogHeading("Home", heading)
            OutlinedButton(onClick = c::openHomeEditor, enabled = canOpenHomeEditor(state.catalog.preferences != null),
                modifier = Modifier.controlFocus().probe("home-customize")) { Text("Customize Home") }
        }
        state.catalog.homeNotice?.let { notice ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(notice, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.probe("home-notice"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                InlineTextAction("Retry", "home-notice-retry", c::home)
            }
        }
    }
}

/** HOME-10: a Latest row's heading opens its library; every other heading is plain text. */
@Composable
internal fun HomeRowHeading(row: HomeRow, state: DesktopState, c: DesktopController) {
    val library = homeRowDestination(row.key)?.takeIf { it.startsWith("library:") }?.removePrefix("library:")
        ?.let { id -> state.sidebarLibraries().firstOrNull { it.id == id } }
    if (library == null) CatalogHeading(row.title, section = true)
    else TextButton(onClick = { c.browseRoot(library) }, contentPadding = PaddingValues(horizontal = 0.dp),
        modifier = Modifier.controlFocus(RoundedCornerShape(8.dp)).probe("row-link:${row.key}").semantics { heading() }) {
        Text("${row.title} ›", style = MaterialTheme.typography.titleLarge)
    }
}

/** HOME-12/13: the merged row's "Next · S1:E2" and a duplicate title's library name. */
internal fun homeSubtitle(row: HomeRow, item: MediaItem): String? =
    if (row.library) null else homeCardSubtitle(item, row.captions[item.id], row.libraryNames[item.id])

// ---------------------------------------------------------------- Home editor (HOME-01..05, 12)

@Composable
internal fun HomeEditorPage(state: DesktopState, c: DesktopController) {
    val prefs = state.catalog.preferences ?: run { Text("Loading your Home layout…", Modifier.padding(24.dp)); return }
    val keys = homeEditorKeys(state)
    var confirmReset by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<HomePinKind?>(null) }
    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().padding(end = 20.dp).catalogPage().probe("home-editor"), state = list, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { CatalogHeading("Customize Home") }
        item {
            val status = state.catalog.preferenceStatus
            if (state.catalog.saving) Text("Saving…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.probe("editor-saving"))
            else if (status != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.probe("editor-status"))
                if (state.catalog.retrySave) InlineTextAction("Try again", "editor-retry", { c.changeHome { it } })
            }
        }
        item {
            EditorSwitch("Combine Continue watching and Next up", "One row, with Next up titles marked Next.", prefs.mergeWatching, "editor-first", !state.catalog.saving) { on ->
                c.changeHome { it.copy(mergeWatching = on) }
            }
        }
        item { CatalogHeading("Rows", section = true) }
        itemsIndexed(keys, key = { _, k -> "row:$k" }) { index, key ->
            val shown = key !in prefs.hidden
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(homeEditorLabel(key, state), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        homeRowEditHint(shown, false, false)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Switch(shown, { on -> c.changeHome { p -> p.copy(hidden = if (on) p.hidden - key else p.hidden + key) } }, enabled = !state.catalog.saving,
                        modifier = Modifier.controlFocus().probe("editor-show:$key").semantics { contentDescription = "Show ${homeEditorLabel(key, state)}" })
                    TextButton(onClick = { c.changeHome { p -> p.copy(order = moveHomeRow(p.order, keys, key, -1)) } }, enabled = index > 0 && !state.catalog.saving,
                        modifier = Modifier.controlFocus().probe("editor-up:$key").semantics { contentDescription = "Move ${homeEditorLabel(key, state)} up" }) { Text("Up") }
                    TextButton(onClick = { c.changeHome { p -> p.copy(order = moveHomeRow(p.order, keys, key, 1)) } }, enabled = index < keys.lastIndex && !state.catalog.saving,
                        modifier = Modifier.controlFocus().probe("editor-down:$key").semantics { contentDescription = "Move ${homeEditorLabel(key, state)} down" }) { Text("Down") }
                    prefs.pins.firstOrNull { it.key == key }?.let { pin ->
                        TextButton(onClick = { c.removePin(pin) }, enabled = !state.catalog.saving, modifier = Modifier.controlFocus().probe("editor-unpin:$key")) { Text("Remove") }
                    }
                }
            }
        }
        item { CatalogHeading("Add a row", section = true) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(HomePinKind.COLLECTION to "Collection", HomePinKind.PLAYLIST to "Playlist", HomePinKind.GENRE to "Genre", HomePinKind.STUDIO to "Studio").forEach { (kind, label) ->
                    FilterChip(picking == kind, { picking = if (picking == kind) null else kind.also { c.loadPinChoices(it, "") } }, { Text(label) }, Modifier.controlFocus(RoundedCornerShape(8.dp)).probe("pin-kind:${kind.name}"))
                }
                val favorites = HomePin(HomePinKind.FAVORITES)
                OutlinedButton(onClick = { c.addPin(favorites) }, enabled = prefs.pins.none { it.key == favorites.key } && !state.catalog.saving,
                    modifier = Modifier.controlFocus().probe("pin-favorites")) { Text(if (prefs.pins.any { it.key == favorites.key }) "Favorites is on your home" else "Add Favorites") }
            }
        }
        picking?.let { kind -> item(key = "picker:${kind.name}") { PinPicker(kind, state, c) } }
        item { CatalogHeading("Next up", section = true) }
        item { NextUpSeriesEditor(state, c, prefs) }
        item { CatalogHeading("Start on", section = true) }
        item {
            val libraries = state.sidebarLibraries()
            val choices = listOf("home" to "Home") + libraries.map { "library:${it.id}" to it.name }
            val selected = prefs.landing.takeIf { landing -> choices.any { it.first == landing } } ?: "home"
            Choice("Start on", choices, selected, "landing", buttonWidth = 360.dp) { pick -> c.changeHome { it.copy(landing = pick) } }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!confirmReset) OutlinedButton(onClick = { confirmReset = true }, enabled = !state.catalog.saving, modifier = Modifier.controlFocus().probe("editor-reset")) { Text("Restore defaults") }
                else {
                    Text("Restore the default rows? Pins and removed series come back; library sort choices stay.")
                    Button(onClick = { confirmReset = false; c.changeHome { resetHomeLayout(it, true) } }, modifier = Modifier.controlFocus().probe("editor-reset-confirm")) { Text("Restore") }
                    TextButton(onClick = { confirmReset = false }, modifier = Modifier.controlFocus().probe("editor-reset-cancel")) { Text("Cancel") }
                }
            }
        }
    }
    VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun EditorSwitch(title: String, caption: String, value: Boolean, tag: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange, enabled = enabled, modifier = Modifier.controlFocus().probe(tag).semantics { contentDescription = title })
    }
}

/** HOME-02: a searchable list of this viewer's collections, playlists, genres or studios. */
@Composable
private fun PinPicker(kind: HomePinKind, state: DesktopState, c: DesktopController) {
    var term by remember(kind) { mutableStateOf("") }
    val prefs = state.catalog.preferences ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(term, { term = it; c.loadPinChoices(kind, it) }, label = { Text("Search") }, singleLine = true,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().probe("pin-search"))
        state.catalog.peopleError?.let { Text("Could not load choices. $it", color = MaterialTheme.colorScheme.error) }
        if (state.catalog.people.isEmpty() && state.catalog.peopleError == null) Text("Nothing to pin here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.catalog.people.forEach { choice ->
                // Collections and playlists pin by id; genres and studios by name (the server's own filter values).
                val pin = HomePin(kind, if (kind == HomePinKind.COLLECTION || kind == HomePinKind.PLAYLIST) choice.id else choice.name)
                val pinned = prefs.pins.any { it.key == pin.key }
                OutlinedButton(onClick = { c.addPin(pin) }, enabled = !pinned && !state.catalog.saving, modifier = Modifier.controlFocus().probe("pin-choice:${choice.id}")) {
                    Text(listOfNotNull(choice.name, homePinPickerSupport(pinned, null)).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** HOME-04: remove or restore a series from Next up. */
@Composable
private fun NextUpSeriesEditor(state: DesktopState, c: DesktopController, prefs: HomePreferences) {
    val series = state.catalog.availableRows.firstOrNull { it.key == "nextup" }?.items.orEmpty()
        .filter { it.seriesId != null }.distinctBy { it.seriesId }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (series.isEmpty() && prefs.hiddenSeries.isEmpty()) Text("Nothing is in Next up.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        series.forEach { episode ->
            val id = episode.seriesId!!
            val shown = id !in prefs.hiddenSeries
            EditorSwitch(episode.seriesName ?: episode.name, nextUpSeriesHint(shown), shown, "nextup-series:$id", !state.catalog.saving) { on ->
                c.changeHome { p -> p.copy(hiddenSeries = if (on) p.hiddenSeries - id else p.hiddenSeries + id) }
            }
        }
        if (offerRestoreAllSeries(prefs.hiddenSeries, series.mapNotNull { it.seriesId }.toSet()))
            OutlinedButton(onClick = { c.changeHome { it.copy(hiddenSeries = emptySet()) } }, enabled = !state.catalog.saving,
                modifier = Modifier.controlFocus().probe("nextup-restore-all")) { Text("Restore all series") }
    }
}

// ---------------------------------------------------------------- Library toolbar, filters, rail, list (BROWSE, HOME-06)

/** Sort, direction, Grid/List and, for a box set or Movies library, Play and Shuffle. */
@Composable
internal fun LibraryToolbar(state: DesktopState, c: DesktopController) {
    val library = state.library ?: return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (collectionPlayScope(library.type, library.collectionType) != null) {
            Button(onClick = { c.playCollection(false) }, enabled = !state.catalog.queueBusy && state.total > 0, modifier = Modifier.controlFocus().probe("queue-play")) { Text("▶ Play") }
            OutlinedButton(onClick = { c.playCollection(true) }, enabled = !state.catalog.queueBusy && state.total > 0, modifier = Modifier.controlFocus().probe("queue-shuffle")) { Text("Shuffle") }
        }
        Choice("Sort", DESKTOP_SORTS, state.sortBy, "sort", buttonWidth = 260.dp) { c.browse(library, it, state.descending) }
        OutlinedButton(onClick = { c.browse(library, state.sortBy, !state.descending) }, modifier = Modifier.controlFocus().probe("sort-direction")
            .semantics { contentDescription = "Sort direction: ${sortDirectionLabel(state.descending)}" }) { Text(if (state.descending) "Descending ↓" else "Ascending ↑") }
        Choice("View", listOf(false to "Grid", true to "List"), state.catalog.listView, "view") { c.setLibraryListView(it) }
    }
}

/** BROWSE-01..03: combined filters; each change starts a new result set. */
@Composable
internal fun LibraryFilterBar(state: DesktopState, c: DesktopController) {
    val library = state.library ?: return
    if (!libraryFiltersOffered(library)) return
    LaunchedEffect(library.id) { c.loadFacets() }
    val f = state.catalog.filters
    val facets = state.catalog.facets
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Genre", listOf("" to "Any") + facets?.genres.orEmpty().map { it to it }, f.genre.orEmpty(), "filter-genre", buttonWidth = 220.dp) { c.setLibraryFilters(f.copy(genre = it.ifBlank { null })) }
            Choice("Year", listOf(0 to "Any") + facets?.years.orEmpty().sortedDescending().map { it to it.toString() }, f.year ?: 0, "filter-year", buttonWidth = 160.dp) { c.setLibraryFilters(f.copy(year = it.takeIf { y -> y > 0 })) }
            Choice("Rating", listOf("" to "Any") + facets?.ratings.orEmpty().map { it to it }, f.officialRating.orEmpty(), "filter-rating", buttonWidth = 180.dp) { c.setLibraryFilters(f.copy(officialRating = it.ifBlank { null })) }
            Choice("Resolution", VideoResolution.entries.map { it to it.label }, f.resolution, "filter-resolution", buttonValue = "Resolution · ${f.resolution.summary()}", buttonWidth = 220.dp) { c.setLibraryFilters(f.copy(resolution = it)) }
            PersonFilter(state, c)
            FilterChip(f.unwatched, { c.setLibraryFilters(f.copy(unwatched = !f.unwatched)) }, { Text("Unwatched") }, Modifier.controlFocus(RoundedCornerShape(8.dp)).probe("filter-unwatched"))
            FilterChip(f.favorite, { c.setLibraryFilters(f.copy(favorite = !f.favorite)) }, { Text("Favorites") }, Modifier.controlFocus(RoundedCornerShape(8.dp)).probe("filter-favorite"))
        }
        state.catalog.facetsError?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        if (f.active) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            f.constraintKeys().forEach { key ->
                InputChip(true, { c.setLibraryFilters(f.withoutConstraint(key)) }, { Text(f.constraintLabel(key)) },
                    trailingIcon = { Text("×") }, modifier = Modifier.controlFocus(RoundedCornerShape(8.dp)).probe("filter-chip:$key")
                        .semantics { contentDescription = "Remove filter ${f.constraintLabel(key)}" })
            }
            TextButton(onClick = { c.setLibraryFilters(LibraryFilters()) }, modifier = Modifier.controlFocus().probe("filter-clear")) { Text("Clear filters") }
        }
    }
}

/** BROWSE-01 person: a searchable menu of people in this library. */
@Composable
private fun PersonFilter(state: DesktopState, c: DesktopController) {
    var expanded by remember { mutableStateOf(false) }
    var term by remember { mutableStateOf("") }
    val opener = remember { FocusRequester() }
    val field = remember { FocusRequester() }
    val f = state.catalog.filters
    Box {
        OutlinedButton(onClick = { expanded = true; term = ""; c.searchPeople("") }, modifier = Modifier.focusRequester(opener).controlFocus(enabled = !expanded).probe("filter-person")) {
            Text("Person · ${if (f.personId != null) f.constraintLabel("person") else "Any"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DesktopDropdownMenu(expanded, { expanded = false }, opener, field, Modifier.widthIn(min = 300.dp, max = 420.dp).probe("filter-person-menu")) {
            OutlinedTextField(term, { term = it; c.searchPeople(it) }, label = { Text("Search people") }, singleLine = true,
                modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth().focusRequester(field).probe("filter-person-search"))
            DropdownMenuItem({ Text("Any") }, { expanded = false; c.setLibraryFilters(f.copy(personId = null, personName = null)) }, Modifier.menuItemFocus().probe("filter-person:any"))
            state.catalog.peopleError?.let { Text("Could not load people. $it", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
            state.catalog.people.take(40).forEach { person ->
                DropdownMenuItem({ Text(person.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, { expanded = false; c.setLibraryFilters(f.copy(personId = person.id, personName = person.name)) },
                    Modifier.menuItemFocus().probe("filter-person:${person.id}"), leadingIcon = { RadioButton(f.personId == person.id, null) })
            }
        }
    }
}

/** BROWSE-05: # and A–Z beside a title-sorted library; letters with no title are dimmed and skipped. */
@Composable
internal fun LetterRail(state: DesktopState, c: DesktopController, modifier: Modifier = Modifier) {
    if (state.sortBy != "SortName" || state.catalog.railIds.isNullOrEmpty()) return
    val available = remember(state.catalog.railNames) { state.catalog.railNames.map(::jumpLetter).toSet() }
    // Pilot 172 D5: 27 entries at 22 dp ran past the window at 1280x800. Entries shrink to share the height the page
    // gives the rail, down to [LETTER_MIN] (still a pointer target); below that the rail scrolls, with a visible bar.
    BoxWithConstraints(modifier.width(36.dp).fillMaxHeight()) {
        val entry = letterEntryHeight(maxHeight, JUMP_LETTERS.size)
        val scroll = rememberScrollState()
        val small = entry < 18.dp
        Column(Modifier.width(32.dp).verticalScroll(scroll).probe("letter-rail"), horizontalAlignment = Alignment.CenterHorizontally) {
            JUMP_LETTERS.forEach { letter ->
                val on = letter in available
                Box(Modifier.size(28.dp, entry).clip(RoundedCornerShape(4.dp)).then(if (on) Modifier.clickable { c.jumpToLetter(letter) }.controlFocus(RoundedCornerShape(4.dp)) else Modifier)
                    .probe("letter:$letter").semantics { contentDescription = if (on) "Jump to $letter" else "$letter, no titles" }, contentAlignment = Alignment.Center) {
                    Text(letter, style = if (small) MaterialTheme.typography.labelSmall.copy(lineHeight = 12.sp) else MaterialTheme.typography.labelMedium,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                }
            }
        }
        if (scroll.maxValue > 0) VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

internal val LETTER_MIN = 14.dp
internal val LETTER_MAX = 22.dp

/** Each rail entry's height: an equal share of [available], between [LETTER_MIN] and [LETTER_MAX]. */
internal fun letterEntryHeight(available: Dp, count: Int): Dp =
    if (available == Dp.Infinity || count <= 0) LETTER_MAX else (available / count).coerceIn(LETTER_MIN, LETTER_MAX)

/** BROWSE-05: after a letter jump, the grid scrolls to the letter's first title and focuses it. */
@Composable
internal fun LibraryJumpEffect(state: DesktopState, c: DesktopController, scrollTo: suspend (Int) -> Unit) {
    val target = state.catalog.jumpTarget
    LaunchedEffect(target, state.items.size) {
        if (target == null) return@LaunchedEffect
        val index = state.items.indexOfFirst { it.id == target }
        if (index < 0) return@LaunchedEffect
        scrollTo(index); withFrameNanos { }; delay(16)
        DesktopUiProbe.requestFocus["grid:$target"]?.requestFocus()
        c.jumpDone()
    }
}

/** HOME-06: the list form of a library page; it keeps the grid's tags so focus and jumps work in both. */
@Composable
internal fun LibraryListRow(item: MediaItem, account: Account, c: DesktopController) {
    OutlinedCard(onClick = { c.open(item) }, modifier = Modifier.fillMaxWidth().focusOutline().probe("grid:${item.id}").semantics(mergeDescendants = true) { contentDescription = catalogAnnouncement(item) }) {
        Row(Modifier.padding(8.dp).clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(48.dp).height(72.dp).clip(RoundedCornerShape(4.dp))) {
                CatalogArtwork(item, account, false, Modifier.fillMaxSize())
                CatalogWatchedBadge(item, Modifier.align(Alignment.TopEnd).padding(2.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(item.productionYear?.toString(), item.officialRating, runtimeLabel(item.runTimeTicks).takeIf(String::isNotBlank),
                    item.childCount?.takeIf { item.isFolder }?.let { titleCountLabel(it) }).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** The empty line: a filtered library names the filters, others say the library is empty. */
internal fun libraryEmptyLine(state: DesktopState): String =
    if (state.catalog.filters.active) filteredEmptyLabel(state.library?.collectionType) else "This library is empty."

// ---------------------------------------------------------------- Title discovery (CAT-30..32)

@Composable
internal fun DetailDiscoveryRows(state: DesktopState, c: DesktopController) {
    val account = state.account ?: return
    val item = state.detail ?: return
    if (state.catalog.discoveryFor != item.id) return
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (state.catalog.trailers.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CatalogHeading("Trailers", section = true)
            state.catalog.trailerMessage?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.probe("trailer-message")) }
            CatalogLane("trailers", state.catalog.trailers.firstOrNull()?.key) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(state.catalog.trailers, key = { it.key }) { t ->
                        val local = t.item
                        if (local != null) CatalogCard(local, account, true, { c.openTrailer(t) }, Modifier.width(232.dp).catalogLaneItem(t.key).focusOutline().probe("trailer:${t.key}"),
                            announcement = "${t.title}, ${trailerLine(t)}", tag = "trailer:${t.key}", subtitle = trailerLine(t))
                        else OutlinedCard(onClick = { c.openTrailer(t) }, modifier = Modifier.width(232.dp).height(130.dp).catalogLaneItem(t.key).focusOutline().probe("trailer:${t.key}")
                            .semantics(mergeDescendants = true) { contentDescription = "${t.title}, ${trailerLine(t)}, opens in your browser" }) {
                            Column(Modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                                Text("▶ ${t.title}", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${trailerLine(t)} ↗", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        if (state.catalog.extras.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CatalogHeading("Extras", section = true)
            CatalogLane("extras", state.catalog.extras.firstOrNull()?.id) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(state.catalog.extras, key = { it.id }) { extra ->
                        CatalogCard(extra, account, true, { c.playExtra(extra) }, Modifier.width(232.dp).catalogLaneItem(extra.id).focusOutline().probe("extra:${extra.id}"),
                            announcement = "${extra.name}, ${extraLine(extra)}", tag = "extra:${extra.id}", subtitle = extraLine(extra))
                    }
                }
            }
        }
        if (state.catalog.similar.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CatalogHeading("More like this", section = true)
            CatalogLane("similar", state.catalog.similar.firstOrNull()?.id) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(state.catalog.similar, key = { it.id }) { other ->
                        CatalogCard(other, account, false, { c.open(other) }, Modifier.width(156.dp).catalogLaneItem(other.id).focusOutline().probe("similar:${other.id}"),
                            announcement = catalogAnnouncement(other), tag = "similar:${other.id}")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Photo viewer (PHOTO-01/02)

@Composable
internal fun PhotoViewerPage(state: DesktopState, c: DesktopController) {
    val account = state.account ?: return
    val photos = state.catalog.photos
    val photo = photos.getOrNull(state.catalog.photoIndex) ?: return
    var slideshow by remember { mutableStateOf(false) }
    var seconds by remember { mutableStateOf(SLIDESHOW_SECONDS[1]) }
    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { focus.requestFocus() } }
    LaunchedEffect(slideshow, seconds, state.catalog.photoIndex) {
        if (!slideshow) return@LaunchedEffect
        delay(seconds * 1000L)
        if (!c.stepPhoto(forward = true, slideshow = true)) slideshow = false
    }
    LaunchedEffect(touched, slideshow) { controls = true; delay(if (slideshow) 2500 else 5000); controls = false }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)
        .focusRequester(focus).probe("photo-viewer")
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            touched++
            when (e.key) {
                Key.DirectionRight -> { c.stepPhoto(true, false); true }
                Key.DirectionLeft -> { c.stepPhoto(false, false); true }
                Key.Spacebar -> { slideshow = !slideshow; true }
                else -> false
            }
        }.focusable()
        .semantics { contentDescription = "${photo.name}, photo ${state.catalog.photoIndex + 1} of ${photos.size}" }) {
        val px = with(androidx.compose.ui.platform.LocalDensity.current) { (maxWidth.toPx().toInt() to maxHeight.toPx().toInt()) }
        val url = photoImageUrl(account.server, photo, px.first, px.second)
        CatalogArtwork(photo, account, true, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, artworkUrl = url)
        if (controls) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(photoPosition(state.catalog.photoIndex, photos.size, slideshow, seconds), Modifier.weight(1f).probe("photo-position"), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            OutlinedButton(onClick = { touched++; c.stepPhoto(false, false) }, enabled = state.catalog.photoIndex > 0, modifier = Modifier.controlFocus().probe("photo-previous")) { Text("Previous") }
            OutlinedButton(onClick = { touched++; c.stepPhoto(true, false) }, enabled = state.catalog.photoIndex < photos.lastIndex, modifier = Modifier.controlFocus().probe("photo-next")) { Text("Next") }
            Button(onClick = { touched++; slideshow = !slideshow }, enabled = photos.size > 1, modifier = Modifier.controlFocus().probe("photo-slideshow")) { Text(if (slideshow) "Pause slideshow" else "Start slideshow") }
            OutlinedButton(onClick = { touched++; seconds = SLIDESHOW_SECONDS[(SLIDESHOW_SECONDS.indexOf(seconds) + 1) % SLIDESHOW_SECONDS.size] }, modifier = Modifier.controlFocus().probe("photo-speed")
                .semantics { contentDescription = "Slideshow speed, every $seconds seconds" }) { Text("Every $seconds s") }
            OutlinedButton(onClick = c::back, modifier = Modifier.controlFocus().probe("photo-close")) { Text("Close") }
        }
    }
}

/** "3 of 12", plus the slideshow's pace while it runs (PHOTO-02). */
internal fun photoPosition(index: Int, count: Int, slideshow: Boolean, seconds: Int): String =
    "${index + 1} of $count" + if (slideshow) " · Slideshow, every $seconds seconds" else ""
