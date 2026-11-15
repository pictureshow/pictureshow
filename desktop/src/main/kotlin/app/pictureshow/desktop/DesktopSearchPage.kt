/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package app.pictureshow.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.pictureshow.core.*
import app.pictureshow.ui.*
import kotlinx.coroutines.launch

/** CAT-14–16: fixed field, grouped shelves and one roving Tab stop per shelf. */
@Composable
internal fun SearchPage(state: DesktopState, c: DesktopController) {
    val account = state.account ?: return
    val search = state.search
    var fieldValue by remember { mutableStateOf(TextFieldValue(search.query)) }
    LaunchedEffect(search.query) {
        if (fieldValue.text != search.query) fieldValue = TextFieldValue(search.query, TextRange(search.query.length))
    }
    LaunchedEffect(state.returnFocus) {
        if (state.returnFocus == "search-field") fieldValue = fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
    }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val field = remember { FocusRequester() }
    val firstTag = search.rows.firstOrNull()?.let { row -> row.items.firstOrNull()?.let { "search:${row.key}:${it.id}" } }
    val firstRowIndex = (if (search.storageError != null) 1 else 0) + (if (search.completed && (search.someFailed || search.libraryError)) 1 else 0)
    val enterResults: () -> Unit = {
        firstTag?.let { tag -> scope.launch {
            list.scrollToItem(firstRowIndex)
            androidx.compose.runtime.withFrameNanos { }
            DesktopUiProbe.requestFocus[tag]?.requestFocus()
        } }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val contentWidth = (maxWidth - 48.dp).coerceAtLeast(120.dp)
    // Whole cards fill the viewport; the scrollbar reveals that more cards follow without a clipped sliver.
    fun cardWidth(target: Int): androidx.compose.ui.unit.Dp {
        val columns = kotlin.math.ceil((contentWidth.value + 16) / (target + 16)).toInt().coerceAtLeast(1)
        return (contentWidth - 16.dp * (columns - 1)) / columns
    }
    val posterWidth = cardWidth(156)
    val episodeWidth = cardWidth(232)
    CatalogTabScope(Modifier.fillMaxSize().catalogPage()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CatalogHeading("Search")
                OutlinedTextField(
                    fieldValue, { value ->
                        val text = value.text.take(SEARCH_QUERY_LIMIT)
                        val changed = text != fieldValue.text
                        fieldValue = value.copy(text = text, selection = TextRange(value.selection.start.coerceAtMost(text.length), value.selection.end.coerceAtMost(text.length)))
                        if (changed) c.searchQuery(text)
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(field).probe("search-field")
                        .onFocusChanged { if (it.isFocused) fieldValue = fieldValue.copy(selection = TextRange(0, fieldValue.text.length)) }
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && !e.isAltPressed && !e.isCtrlPressed && !e.isMetaPressed && e.key == Key.DirectionDown && firstTag != null) {
                                enterResults(); true
                            } else false
                        },
                    label = { Text("Search your libraries") }, singleLine = true,
                    placeholder = { Text("Movies, shows, people, music…") },
                    trailingIcon = { if (search.query.isNotEmpty()) TextButton(onClick = { c.searchQuery(""); field.requestFocus() }, modifier = Modifier.controlFocus().probe("search-clear")) { Text("Clear") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { c.rememberSearch(); enterResults() })
                )
                if (search.loading) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Searching" })
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().probe("search-results"), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (normalizeSearch(search.query).length < 2) {
                        item { SearchMessage("Find something to watch or listen to", if (normalizeSearch(search.query).isEmpty()) "Search titles, people and music across all your libraries." else "Enter at least 2 characters to search your libraries.") }
                        if (search.recent.isNotEmpty()) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CatalogHeading("Recent searches", section = true)
                                    search.recent.forEachIndexed { i, term -> TextButton(onClick = { c.searchQuery(term, immediate = true); field.requestFocus() }, modifier = Modifier.controlFocus().probe("search-recent:$i")) { Text(term) } }
                                    InlineTextAction("Clear recent searches", "search-clear-recent", { c.rememberSearch(clear = true); field.requestFocus() })
                                }
                            }
                        }
                    }
                    search.storageError?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } }
                    if (search.completed && (search.someFailed || search.libraryError)) item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SearchMessage(if (search.allFailed) "Search is unavailable" else "Some results are unavailable", if (search.allFailed) "Check your server or connection, then try again." else if (search.libraryError) "Some library names couldn't load. Try again to tell copies apart." else "The results that loaded are shown below. Try again for the remaining groups.")
                            InlineTextAction("Retry", "search-retry", { c.searchQuery(search.query, immediate = true); field.requestFocus() })
                        }
                    }
                    if (search.completed && !search.someFailed && search.rows.isEmpty()) item { SearchMessage("No results for “${normalizeSearch(search.query)}”", "Try another title, person or artist.") }
                    items(search.rows, key = { it.key }) { row ->
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CatalogHeading(row.title, section = true)
                            val laneScroll = rememberLazyListState()
                            val librarySlot = row.items.any { it.id in search.libraries }
                            CatalogLane("search:${row.key}", row.items.firstOrNull()?.id) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LazyRow(state = laneScroll, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    items(row.items, key = { it.id }) { item ->
                                        val tag = "search:${row.key}:${item.id}"
                                        val library = search.libraries[item.id]
                                        CatalogCard(item, account, row.wide, { c.openSearchResult(item) },
                                            Modifier.width(if (row.wide) episodeWidth else posterWidth).catalogLaneItem(item.id)
                                                .focusProperties { if (row == search.rows.first()) up = field }
                                                .focusOutline().probe(tag), tag = tag,
                                            announcement = listOfNotNull(catalogAnnouncement(item), library).joinToString(". "),
                                            caption = { SearchCaption(item, library, librarySlot) })
                                    }
                                }
                                HorizontalScrollbar(rememberScrollbarAdapter(laneScroll), Modifier.fillMaxWidth().height(8.dp))
                            }
                            }
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
    }
}

@Composable private fun SearchCaption(item: MediaItem, library: String?, librarySlot: Boolean) {
    Column(Modifier.padding(12.dp).clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.name, style = MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val meta = if (item.type == "Episode") listOfNotNull(item.seriesName, episodeLabel(item)).joinToString(" · ") else item.productionYear?.toString().orEmpty()
        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (librarySlot) Text(library.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun SearchMessage(title: String, body: String) {
    Column(Modifier.widthIn(max = 560.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Person results open their credit list; ordinary titles keep the existing detail route. */
@Composable internal fun SearchPersonPage(state: DesktopState, c: DesktopController) {
    val account = state.account ?: return
    val grid = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(columns = GridCells.Adaptive(156.dp), state = grid, modifier = Modifier.fillMaxSize().padding(end = 20.dp).catalogPage(), contentPadding = PaddingValues(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) { CatalogHeading(state.person?.name.orEmpty()) }
            if (!state.busy && state.items.isEmpty() && state.error == null) item(span = { GridItemSpan(maxLineSpan) }) { SearchMessage("No titles to show", "This person has no movies or shows in your libraries.") }
            items(state.items, key = { it.id }) { item ->
                CatalogCard(item, account, false, { c.open(item) }, Modifier.fillMaxWidth().focusOutline().probe("person:${item.id}"), announcement = catalogAnnouncement(item), tag = "person:${item.id}")
            }
            if (state.items.size < state.total) item(span = { GridItemSpan(maxLineSpan) }) { InlineTextAction("Load more", "person-more", c::morePerson, enabled = !state.busy) }
        }
        VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
