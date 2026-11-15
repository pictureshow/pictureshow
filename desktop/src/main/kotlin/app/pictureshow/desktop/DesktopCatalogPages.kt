/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)


package app.pictureshow.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import kotlinx.coroutines.delay
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

import app.pictureshow.core.*
import app.pictureshow.core.playback.*
import app.pictureshow.ui.*
import java.util.concurrent.ConcurrentHashMap

@Composable
internal fun HomePage(state: DesktopState, c: DesktopController) {
    val account = state.account ?: return
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().padding(end = 20.dp).probe("home"), state = listState, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { CatalogHeading("Home") }
        if (!state.busy && state.homeRows.isEmpty()) item { Text("Nothing to show yet. Add media in Jellyfin, then refresh."); InlineTextAction("Refresh", "home-refresh", c::home) }
        items(state.homeRows.sortedBy { if (it.library) 1 else 0 }, key = { it.key }) { row ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CatalogHeading(row.title, section = true)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(row.items, key = { it.id }) { item ->
                        CatalogCard(item, account, row.wide || row.library, { c.open(item) }, Modifier.width(if(row.wide || row.library) 232.dp else 156.dp).focusOutline().probe("card:${row.key}:${item.id}"), announcement = catalogAnnouncement(item), tag = "card:${row.key}:${item.id}")
                    }
                }
            }
        }
    }
    VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
@Composable
internal fun LibraryPage(state: DesktopState, c: DesktopController) {
    val account = state.account ?: return
    val grid = rememberLazyGridState()
    LaunchedEffect(state.library?.id, state.sortBy, state.descending) { grid.scrollToItem(0) }
    LaunchedEffect(grid, state.items.size) { snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { if(it >= state.items.size-12 && !state.busy && state.items.size < state.total) c.loadMore() } }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CatalogHeading(state.library?.name ?: "Library", Modifier.weight(1f))
            Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Choice("Sort", listOf("SortName" to "Title", "DateCreated" to "Date added", "PremiereDate" to "Release date"), state.sortBy, "sort") { c.browse(state.library!!, it, state.descending) }
                    OutlinedButton(onClick = { c.browse(state.library!!,state.sortBy,!state.descending) }, modifier = Modifier.controlFocus().probe("sort-direction")) { Text(if(state.descending) "Descending ↓" else "Ascending ↑") }
                }
            }
        }
        Text("${state.total} items", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.items.isEmpty() && !state.busy) Text("This library is empty.")
        Box(Modifier.weight(1f)) {
        LazyVerticalGrid(GridCells.FixedSize(156.dp), state = grid, modifier = Modifier.fillMaxSize().padding(end = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(state.items, key = { it.id }) { item -> CatalogCard(item,account,false,{c.open(item)},Modifier.focusOutline().probe("grid:${item.id}"), announcement = catalogAnnouncement(item), tag = "grid:${item.id}") }
        }
        VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}
@Composable
internal fun DetailPage(state: DesktopState, c: DesktopController) {
    val item = state.detail ?: return
    val account = state.account ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Detail content width excludes the persistent sidebar and top chrome; this
    // breakpoint corresponds to a full window around 1440 dp wide.
    val expansive = maxWidth >= 1120.dp
    val detailState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().padding(end = 20.dp).probe("detail"), state = detailState, contentPadding = PaddingValues(start = if (expansive) 40.dp else 24.dp, top = if (expansive) 40.dp else 24.dp, end = if (expansive) 40.dp else 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                val isEpisode = item.type == "Episode"
                val posterWidth = when { isEpisode && expansive -> 480.dp; isEpisode -> 360.dp; expansive -> 240.dp; else -> 180.dp }
                CatalogArtwork(item,account,isEpisode,Modifier.width(posterWidth).aspectRatio(if (isEpisode) 16f / 9f else 2f / 3f))
                Column(Modifier.widthIn(min=280.dp).weight(1f),verticalArrangement=Arrangement.spacedBy(if (expansive) 18.dp else 14.dp)) {
                    Text(item.name, style = if (expansive) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    Text(listOfNotNull(item.productionYear?.toString(),item.officialRating,runtimeLabel(item.runTimeTicks).takeIf{it.isNotBlank()},episodeLabel(item).takeIf{item.type=="Episode" && it.isNotBlank()}).joinToString(" · "),color=MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={c.play(item)},enabled=!state.busy,modifier = Modifier.controlFocus().heightIn(min=48.dp).probe("play")) { Text(if(item.userData.playbackPositionTicks>0) resumeLabel(item.userData.playbackPositionTicks) else "Play") }
                        if(item.userData.playbackPositionTicks>0) OutlinedButton(onClick={c.play(item,true)},modifier = Modifier.controlFocus().heightIn(min=48.dp).probe("play-start")) { Text("Play from start") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                        DesktopTooltip(if (item.isWatched) "Mark unwatched" else "Mark watched") {
                            IconToggleButton(checked = item.isWatched, onCheckedChange = { c.toggleWatched() }, modifier = Modifier.controlFocus().probe("watched").semantics { contentDescription = "Watched"; stateDescription = if (item.isWatched) "Watched" else "Not watched" }) {
                                Icon(if (item.isWatched) DesktopGlyph.Watched else DesktopGlyph.WatchedOutline, contentDescription = null, tint = if (item.isWatched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        DesktopTooltip(if (item.userData.isFavorite) "Remove favorite" else "Add to favorites") {
                            IconToggleButton(checked = item.userData.isFavorite, onCheckedChange = { c.toggleFavorite() }, modifier = Modifier.controlFocus().probe("favorite").semantics { contentDescription = "Favorite"; stateDescription = if (item.userData.isFavorite) "Favorite" else "Not favorite" }) {
                                Icon(if (item.userData.isFavorite) DesktopGlyph.Favorite else DesktopGlyph.FavoriteOutline, contentDescription = null, tint = if (item.userData.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        }
                    }
                    item.overview?.let { Text(it, Modifier.widthIn(max = 640.dp), style=MaterialTheme.typography.bodyLarge) }
                    if(item.genres.isNotEmpty()) Text(item.genres.joinToString(" · "),color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if(state.seasons.isNotEmpty()) item {
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                CatalogHeading("Seasons",section=true)
                LazyRow(horizontalArrangement=Arrangement.spacedBy(10.dp)) { items(state.seasons, key={it.id}) { s -> FilterChip(selected=state.selectedSeason?.id==s.id,onClick={c.selectSeason(s)},label={Text(s.name)},modifier=Modifier.probe("season:${s.id}")) } }
            }
        }
        if(state.episodes.isNotEmpty()) item { CatalogHeading("Episodes",section=true) }
        items(state.episodes,key={it.id}) { episode ->
            OutlinedCard(onClick={c.detail(episode)},modifier=Modifier.fillMaxWidth().focusOutline().probe("episode:${episode.id}")) {
                Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(20.dp),verticalAlignment=Alignment.CenterVertically) {
                    CatalogArtwork(episode,account,true,Modifier.width(160.dp).height(90.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) { Text("${episodeLabel(episode)} · ${episode.name}",style=MaterialTheme.typography.titleMedium); Text(episode.overview.orEmpty(),maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(runtimeLabel(episode.runTimeTicks))
                }
            }
        }
    }
    VerticalScrollbar(rememberScrollbarAdapter(detailState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
    }
}
