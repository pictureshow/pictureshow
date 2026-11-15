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
    val heading = remember { CatalogHeadingTarget() }
    Box(Modifier.fillMaxSize()) {
    CatalogTabScope(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().padding(end = 20.dp).catalogPage().probe("home"), state = listState, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { CatalogHeading("Home", Modifier.catalogHeadingTarget(heading).probe("home-heading")) }
        if (!state.busy && state.homeRows.isEmpty()) item { Text("Nothing to show yet. Add media in Jellyfin, then refresh."); InlineTextAction("Refresh", "home-refresh", c::home) }
        val rows = state.homeRows.sortedBy { if (it.library) 1 else 0 }
        items(rows, key = { it.key }) { row ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CatalogHeading(row.title, section = true)
                CatalogLane(row.key, row.items.firstOrNull()?.id, up = heading.takeIf { row.key == rows.first().key }, modifier = Modifier.testTag("row:${row.key}")) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(row.items, key = { it.id }) { item ->
                        CatalogCard(item, account, row.wide || row.library, { c.open(item) }, Modifier.width(if(row.wide || row.library) 232.dp else 156.dp).catalogLaneItem(item.id).focusOutline().probe("card:${row.key}:${item.id}"), announcement = catalogAnnouncement(item), tag = "card:${row.key}:${item.id}")
                    }
                }
                }
            }
        }
    }
    }
    VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/**
 * D-7 (CAT-18 on a keyboard): a Home or detail shelf is one arrow lane and one Tab stop.
 * Left and Right stop at the shelf's ends instead of leaving for the window header. Tab and Shift+Tab leave a
 * shelf in one press and land on the card focused there last (the first card before any). This is a roving Tab
 * stop: while a Tab search runs, each shelf offers exactly one candidate card. Arrows use focus properties, so
 * they hold whether they arrive through the window key handler's `moveFocus` or Compose's own key handling.
 */
internal class CatalogTabState {
    /** True only while a Tab press inside this scope runs its focus search. */
    var tabbing = false
    /** Each shelf's last-focused card, kept here because a shelf scrolled off the page is disposed. */
    val lastKeys = mutableMapOf<String, String>()
}
internal val LocalCatalogTab = compositionLocalOf<CatalogTabState?> { null }

/** Holds the page's Tab presses, so shelves inside it act as single Tab stops. */
@Composable
internal fun CatalogTabScope(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val tab = remember { CatalogTabState() }
    val focus = LocalFocusManager.current
    Box(modifier.onPreviewKeyEvent { e ->
        if (e.key != Key.Tab || e.isCtrlPressed || e.isMetaPressed || e.isAltPressed) return@onPreviewKeyEvent false
        if (e.type == KeyEventType.KeyDown) {
            tab.tabbing = true
            try { focus.moveFocus(if (e.isShiftPressed) FocusDirection.Previous else FocusDirection.Next) } finally { tab.tabbing = false }
        }
        true
    }) {
        CompositionLocalProvider(LocalCatalogTab provides tab) { content() }
    }
}

internal class CatalogLaneState(val id: String, val up: CatalogHeadingTarget?, private val tab: CatalogTabState?) {
    var hasFocus = false
    private var ownLastKey: String? = null
    var lastKey: String?
        get() = tab?.lastKeys?.get(id) ?: ownLastKey
        set(value) { ownLastKey = value; if (tab != null && value != null) tab.lastKeys[id] = value }
    val attached = mutableSetOf<String>()
    var firstKey: String? = null
}
internal val LocalCatalogLane = compositionLocalOf<CatalogLaneState?> { null }

/** A focusable page heading, so Up from the first shelf has a home above it (D-7). */
internal class CatalogHeadingTarget {
    val requester = FocusRequester()
    var attached = false
}

@Composable
internal fun Modifier.catalogHeadingTarget(target: CatalogHeadingTarget): Modifier {
    DisposableEffect(target) { target.attached = true; onDispose { target.attached = false } }
    return focusRequester(target.requester)
        // The header's Back and window icons are reached by Tab only, never by arrows from the page.
        .focusProperties { up = FocusRequester.Cancel; right = FocusRequester.Cancel }
        .controlFocus(RoundedCornerShape(8.dp))
        .focusable()
}

/** Up, Down and Right never leave a catalog page for the window header or the sidebar's foot; Left and Tab may. */
internal fun Modifier.catalogPage(): Modifier = focusProperties {
    onExit = {
        if (requestedFocusDirection == FocusDirection.Up || requestedFocusDirection == FocusDirection.Down || requestedFocusDirection == FocusDirection.Right) cancelFocusChange()
    }
}.focusGroup()

@Composable
internal fun CatalogLane(id: String, firstKey: String?, up: CatalogHeadingTarget? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val tab = LocalCatalogTab.current
    val lane = remember(id, up, tab) { CatalogLaneState(id, up, tab) }
    lane.firstKey = firstKey
    Box(
        modifier
            .onFocusChanged { lane.hasFocus = it.hasFocus }
            .focusProperties {
                onExit = {
                    if (requestedFocusDirection == FocusDirection.Left || requestedFocusDirection == FocusDirection.Right) cancelFocusChange()
                }
            }
            .focusGroup()
    ) {
        CompositionLocalProvider(LocalCatalogLane provides lane) { content() }
    }
}

/** Joins a card to the enclosing [CatalogLane]; outside one it changes nothing. */
@Composable
internal fun Modifier.catalogLaneItem(key: String): Modifier {
    val lane = LocalCatalogLane.current ?: return this
    val tab = LocalCatalogTab.current
    val self = remember { object { var focused = false } }
    // Registered during composition, not in an effect: a shelf composed by the Tab search itself (beyond the
    // visible page) must already count its cards when the same search asks which one is the candidate.
    remember(lane, key) { lane.attached += key; key }
    DisposableEffect(lane, key) { onDispose { lane.attached -= key } }
    return onFocusChanged { self.focused = it.isFocused; if (it.isFocused) lane.lastKey = key }.focusProperties {
        if (tab?.tabbing == true) {
            val candidate = when {
                lane.hasFocus -> null
                lane.lastKey != null && lane.lastKey in lane.attached -> lane.lastKey
                lane.firstKey != null && lane.firstKey in lane.attached -> lane.firstKey
                else -> key
            }
            if (if (lane.hasFocus) !self.focused else candidate != key) canFocus = false
        }
        val heading = lane.up
        if (heading != null && heading.attached) up = heading.requester
    }
}

@Composable
internal fun LibraryPage(state: DesktopState, c: DesktopController) {
    val account = state.account ?: return
    val grid = rememberLazyGridState()
    LaunchedEffect(state.library?.id, state.sortBy, state.descending) { grid.scrollToItem(0) }
    LaunchedEffect(grid, state.items.size) { snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { if(it >= state.items.size-12 && !state.busy && state.items.size < state.total) c.loadMore() } }
    if (state.library?.let(c::isLiveTvView) == true) { LiveTvComingPage(state.library); return }
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
/** D-5 interim (web W-5's copy): Live TV is its own port (spec live-tv.md, gap C34); never "This library is empty." */
@Composable
internal fun LiveTvComingPage(view: MediaItem) {
    Column(Modifier.fillMaxSize().padding(24.dp).probe("livetv-coming"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CatalogHeading(view.name)
        Column(Modifier.widthIn(max = 640.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CatalogHeading("Live TV is coming to the desktop app", section = true)
            Text(
                "This desktop app can't show your server's channels and guide yet. You can watch Live TV today in the " +
                    "Pictureshow app for Android TV, Google TV, Fire TV and Android phones.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    CatalogTabScope(Modifier.fillMaxSize()) {
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
                        LaunchedEffect(state.returnFocus, state.busy, item.id) { if (state.returnFocus == "play" && !state.busy) { withFrameNanos { }; DesktopUiProbe.requestFocus["play"]?.requestFocus(); c.returnFocusDone() } }
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
                CatalogLane("seasons", state.selectedSeason?.id ?: state.seasons.firstOrNull()?.id) { LazyRow(horizontalArrangement=Arrangement.spacedBy(10.dp)) { items(state.seasons, key={it.id}) { s -> FilterChip(selected=state.selectedSeason?.id==s.id,onClick={c.selectSeason(s)},label={Text(s.name)},modifier=Modifier.catalogLaneItem(s.id).probe("season:${s.id}")) } } }
            }
        }
        if(state.episodes.isNotEmpty()) item { CatalogHeading("Episodes",section=true) }
        items(state.episodes,key={it.id}) { episode ->
            // D-10 (CAT-02): an episode row shows and announces watched and resume state, as catalog cards do.
            OutlinedCard(onClick={c.detail(episode)},modifier=Modifier.fillMaxWidth().focusOutline().probe("episode:${episode.id}").semantics(mergeDescendants = true) { contentDescription = episodeRowAnnouncement(episode) }) {
                Row(Modifier.padding(12.dp).clearAndSetSemantics {},horizontalArrangement=Arrangement.spacedBy(20.dp),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.width(160.dp).height(90.dp).clip(RoundedCornerShape(6.dp))) {
                        CatalogArtwork(episode,account,true,Modifier.fillMaxSize())
                        CatalogWatchedBadge(episode, Modifier.align(Alignment.TopEnd).padding(6.dp))
                        CatalogResumeBar(episode, modifier = Modifier.align(Alignment.BottomStart).height(4.dp))
                    }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) { Text("${episodeLabel(episode)} · ${episode.name}",style=MaterialTheme.typography.titleMedium); Text(episode.overview.orEmpty(),maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(runtimeLabel(episode.runTimeTicks))
                }
            }
        }
    }
    }
    VerticalScrollbar(rememberScrollbarAdapter(detailState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
    }
}

/** The episode row's one announcement: the shared card announcement (identity, Watched or Resume), then runtime. */
internal fun episodeRowAnnouncement(episode: MediaItem): String =
    listOf(catalogAnnouncement(episode), runtimeLabel(episode.runTimeTicks)).filter(String::isNotBlank).joinToString(", ")
