/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.music.ArtistRole
import app.pictureshow.core.music.MusicListState
import app.pictureshow.core.music.MusicSection
import app.pictureshow.core.music.albumArtistLine
import app.pictureshow.core.music.musicListState
import app.pictureshow.core.music.trackArtistLine
import app.pictureshow.core.music.trackIsAvailable
import app.pictureshow.core.playback.Ticks
import app.pictureshow.ui.CatalogArtwork
import app.pictureshow.ui.CatalogCard
import app.pictureshow.ui.CatalogHeading
import app.pictureshow.core.catalogAnnouncement
import coil3.compose.AsyncImage

// The music pages (MUS-01..03, MUS-09, MUS-11). Mouse and keyboard first: every control is a focusable button with a
// probe tag, every row's extra actions sit in its own menu, and the queue reorders with Move up and Move down.

@Composable
private fun MusicIcon(glyph: ImageVector, label: String, tag: String, enabled: Boolean = true, selected: Boolean? = null, onClick: () -> Unit) {
    DesktopTooltip(label) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.controlFocus().probe(tag).semantics {
            contentDescription = label
            if (selected != null) stateDescription = if (selected) "On" else "Off"
        }) {
            Icon(glyph, contentDescription = null, modifier = Modifier.size(22.dp),
                tint = if (selected == true) MaterialTheme.colorScheme.primary else LocalContentColor.current)
        }
    }
}

@Composable
private fun MusicMessage(text: String?, tag: String, onDismiss: () -> Unit) {
    if (text == null) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer)
            TextButton(onDismiss, Modifier.controlFocus().probe(tag)) { Text("Dismiss") }
        }
    }
}

@Composable
private fun MusicListNotice(state: MusicListState, emptyText: String, onRetry: () -> Unit) {
    val (title, retry) = when (state) {
        MusicListState.LOADING -> { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
        MusicListState.READY -> return
        MusicListState.EMPTY -> emptyText to false
        MusicListState.INACCESSIBLE -> "This isn't available to you on this server." to false
        MusicListState.EXPIRED -> "This account needs to sign in again." to false
        MusicListState.FAILED -> "Couldn't load this. Check the connection and try again." to true
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (retry) OutlinedButton(onRetry, Modifier.controlFocus().probe("music:retry")) { Text("Retry") }
    }
}

/** MUS-01: the library's own page, tab by tab. */
@Composable
internal fun DesktopMusicLibraryPage(state: DesktopState, c: DesktopController) {
    val account = state.account ?: return
    val libraryItem = state.library ?: return
    val model = remember(account.key, libraryItem.id) { c.musicBrowse.library(account, libraryItem) }
    val s by model.state.collectAsState()
    val section = s.current
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CatalogHeading(libraryItem.name, Modifier.weight(1f))
            FilledTonalButton(onClick = { model.shuffleAll { tracks -> account.desktopMusicPlay(tracks, null, "MusicLibrary:${libraryItem.id}", libraryItem.name, shuffle = true)?.let(c.music::request) } },
                enabled = !s.shuffling, modifier = Modifier.controlFocus().probe("music:shuffle-all")) { Text(if (s.shuffling) "Shuffling…" else "Shuffle all") }
        }
        PrimaryScrollableTabRow(selectedTabIndex = s.section.ordinal, edgePadding = 0.dp, modifier = Modifier.fillMaxWidth()) {
            MusicSection.entries.forEach { tab ->
                Tab(selected = s.section == tab, onClick = { model.select(tab) }, text = { Text(tab.title) },
                    modifier = Modifier.controlFocus(RoundedCornerShape(6.dp), insetRing = true).probe("music:tab:${tab.name}"))
            }
        }
        MusicMessage(s.message, "music:library-message", model::dismissMessage)
        val listState = musicListState(section.loading, section.items.size, section.error)
        MusicListNotice(listState, "Nothing here yet.", model::retry)
        if (section.items.isEmpty()) return@Column
        val open: (MediaItem) -> Unit = { item ->
            val role = if (s.section == MusicSection.ARTISTS) ArtistRole.ARTIST else null
            c.musicItem(item, role, libraryItem.id)
        }
        Box(Modifier.weight(1f)) {
            if (s.section == MusicSection.SONGS) {
                val list = rememberLazyListState()
                LaunchedEffect(list, s.section) { snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect(model::nearEnd) }
                LazyColumn(Modifier.fillMaxSize().padding(end = 20.dp), state = list) {
                    itemsIndexed(section.items, key = { _, it -> it.id }) { index, track ->
                        MusicTrackRow(c, account, track, (index + 1).toString(), null,
                            onPlay = { account.desktopMusicPlay(section.items, track, "MusicLibrary:${libraryItem.id}:songs", libraryItem.name)?.let(c.music::request) })
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            } else {
                val grid = rememberLazyGridState()
                LaunchedEffect(grid, s.section) { snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect(model::nearEnd) }
                LazyVerticalGrid(GridCells.Adaptive(156.dp), state = grid, modifier = Modifier.fillMaxSize().padding(end = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(section.items, key = { it.id }) { item ->
                        if (item.type == "Audio") {
                            MusicTrackRow(c, account, item, "", null, onPlay = { account.desktopMusicPlay(listOf(item), item, "Audio:${item.id}", item.name)?.let(c.music::request) })
                        } else {
                            CatalogCard(item, account, false, { open(item) }, Modifier.focusOutline().probe("music:card:${item.id}"),
                                announcement = catalogAnnouncement(item), tag = "music:card:${item.id}",
                                subtitle = musicCardLine(item))
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

private fun musicCardLine(item: MediaItem): String = when (item.type) {
    "MusicAlbum" -> listOfNotNull(albumArtistLine(item).ifBlank { null }, item.productionYear?.toString()).joinToString(" · ")
    "MusicArtist" -> "Artist"
    "MusicGenre" -> "Genre"
    "Playlist" -> item.childCount?.let { "$it ${if (it == 1) "track" else "tracks"}" } ?: "Playlist"
    else -> ""
}

/** One song: click (or Enter) plays from here; its menu queues it, adds it to a playlist, or edits the playlist it's in. */
@Composable
private fun MusicTrackRow(
    c: DesktopController,
    account: Account,
    track: MediaItem,
    number: String,
    albumArtist: String?,
    onPlay: () -> Unit,
    playlistEdit: Boolean = false,
    index: Int = 0,
    count: Int = 0,
    onMove: (Int) -> Unit = {},
    onRemove: () -> Unit = {}
) {
    val available = trackIsAvailable(track)
    val playing = c.music.state.collectAsState().value.current?.track?.id == track.id
    var menu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    val opener = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    val tag = "music:track:${track.playlistItemId ?: track.id}"
    Row(Modifier.fillMaxWidth().height(52.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(onClick = onPlay, enabled = available, shape = RoundedCornerShape(8.dp),
            color = if (playing) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            modifier = Modifier.weight(1f).fillMaxHeight().controlFocus(RoundedCornerShape(8.dp), insetRing = true).probe(tag)
                .semantics { contentDescription = listOfNotNull(track.name, trackArtistLine(track, albumArtist), if (!available) "unavailable" else null, if (playing) "playing" else null).joinToString(", ") }) {
            Row(Modifier.padding(horizontal = 12.dp).alpha(if (available) 1f else 0.5f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(number, Modifier.width(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Column(Modifier.weight(1f)) {
                    Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (playing) FontWeight.SemiBold else null)
                    trackArtistLine(track, albumArtist)?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (track.userData.isFavorite) Icon(DesktopGlyph.Favorite, contentDescription = "Favorite", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(track.runTimeTicks?.let { formatDesktopMusicTime(it / Ticks.PER_MILLISECOND) }.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            DesktopTooltip("More for ${track.name}") {
                IconButton(onClick = { menu = true }, modifier = Modifier.focusRequester(opener).controlFocus().probe("$tag:more").semantics { contentDescription = "More for ${track.name}" }) {
                    Icon(DesktopGlyph.ChevronDown, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            DesktopDropdownMenu(menu, { menu = false }, opener, first) {
                val item = account.desktopMusicTrack(track)
                DropdownMenuItem({ Text("Play next") }, { menu = false; c.music.playNext(account, listOf(item)) }, enabled = available,
                    modifier = Modifier.focusRequester(first).menuItemFocus().probe("$tag:play-next"))
                DropdownMenuItem({ Text("Add to queue") }, { menu = false; c.music.append(account, listOf(item)) }, enabled = available,
                    modifier = Modifier.menuItemFocus().probe("$tag:append"))
                DropdownMenuItem({ Text("Add to playlist…") }, { menu = false; picker = true }, modifier = Modifier.menuItemFocus().probe("$tag:playlist"))
                if (track.albumId != null) DropdownMenuItem({ Text("Go to album") }, {
                    menu = false; c.musicItem(MediaItem(track.albumId!!, track.album.orEmpty(), "MusicAlbum"))
                }, modifier = Modifier.menuItemFocus().probe("$tag:album"))
                if (playlistEdit) {
                    DropdownMenuItem({ Text("Move up") }, { menu = false; onMove(index - 1) }, enabled = index > 0, modifier = Modifier.menuItemFocus().probe("$tag:up"))
                    DropdownMenuItem({ Text("Move down") }, { menu = false; onMove(index + 1) }, enabled = index < count - 1, modifier = Modifier.menuItemFocus().probe("$tag:down"))
                    DropdownMenuItem({ Text("Remove from playlist") }, { menu = false; onRemove() }, modifier = Modifier.menuItemFocus().probe("$tag:remove"))
                }
            }
        }
    }
    if (picker) DesktopMusicPickerDialog(c, account, listOf(track.id), opener) { picker = false }
}

/** MUS-02: an album, artist, genre, playlist or single song, with its actions. */
@Composable
internal fun DesktopMusicItemPage(state: DesktopState, c: DesktopController) {
    val account = state.account ?: return
    val item = state.detail ?: return
    val role = desktopMusicRole(state.musicRole)
    val model = remember(account.key, item.id, role, state.detailLibrary) { c.musicBrowse.detail(account, item, role, state.detailLibrary) }
    val s by model.state.collectAsState()
    var picker by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    val pickerOpener = remember { FocusRequester() }
    val renameOpener = remember { FocusRequester() }
    val head = s.item
    val albumArtist = albumArtistLine(head).ifBlank { null }
    fun play(tracks: List<MediaItem>, start: MediaItem? = null, shuffle: Boolean = false) {
        account.desktopMusicPlay(tracks, start, s.context, head.name, shuffle)?.let(c.music::request)
    }
    fun withAll(action: (List<MediaItem>) -> Unit) = model.allTracks(action)
    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 20.dp).probe("music:item"), state = list, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                FlowRow(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CatalogArtwork(head, account, false, Modifier.size(200.dp).clip(RoundedCornerShape(8.dp)))
                    Column(Modifier.widthIn(min = 280.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(when (head.type) { "MusicAlbum" -> "Album"; "MusicArtist" -> "Artist"; "MusicGenre" -> "Genre"; "Playlist" -> "Playlist"; else -> "Song" },
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(head.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                        val total = s.tracks.sumOf { it.runTimeTicks ?: 0 } / Ticks.PER_MILLISECOND
                        val facts = listOfNotNull(
                            albumArtist.takeIf { s.isAlbum },
                            head.productionYear?.toString(),
                            s.tracks.size.takeIf { it > 0 && s.tracksComplete }?.let { "$it ${if (it == 1) "track" else "tracks"}" },
                            total.takeIf { it > 0 && s.tracksComplete }?.let(::formatDesktopMusicTime),
                            head.genres.takeIf { it.isNotEmpty() && s.isAlbum }?.take(3)?.joinToString(", ")
                        )
                        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val playable = s.tracks.any(::trackIsAvailable) || !s.tracksComplete
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ withAll { play(it) } }, enabled = playable && !s.busy, modifier = Modifier.controlFocus().probe("music:play")) {
                                Icon(DesktopGlyph.Play, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Play")
                            }
                            FilledTonalButton({ withAll { play(it, shuffle = true) } }, enabled = playable && !s.busy, modifier = Modifier.controlFocus().probe("music:shuffle")) { Text("Shuffle") }
                            OutlinedButton({ withAll { tracks -> c.music.playNext(account, tracks.filter(::trackIsAvailable).map(account::desktopMusicTrack)) } },
                                enabled = playable && !s.busy, modifier = Modifier.controlFocus().probe("music:play-next")) { Text("Play next") }
                            OutlinedButton({ withAll { tracks -> c.music.append(account, tracks.filter(::trackIsAvailable).map(account::desktopMusicTrack)) } },
                                enabled = playable && !s.busy, modifier = Modifier.controlFocus().probe("music:append")) { Text("Add to queue") }
                            if (!s.isPlaylist) OutlinedButton({ picker = true }, enabled = !s.busy, modifier = Modifier.focusRequester(pickerOpener).controlFocus().probe("music:add-playlist")) { Text("Add to playlist…") }
                            if (s.isAlbum || s.isArtist || s.isGenre || s.isTrack) OutlinedButton({ model.instantMix(head.id) { play(it) } }, enabled = !s.busy,
                                modifier = Modifier.controlFocus().probe("music:mix")) { Text("Instant mix") }
                            if (s.isPlaylist && s.canEdit) OutlinedButton({ rename = true }, enabled = !s.busy, modifier = Modifier.focusRequester(renameOpener).controlFocus().probe("music:rename")) { Text("Rename…") }
                            if (!s.isGenre) MusicIcon(if (head.userData.isFavorite) DesktopGlyph.Favorite else DesktopGlyph.FavoriteOutline,
                                if (head.userData.isFavorite) "Remove from favorites" else "Add to favorites", "music:favorite", selected = head.userData.isFavorite) { model.favorite() }
                        }
                        if (s.isPlaylist && !s.loading && !s.canEdit) Text("You can play this playlist, but only its owner can change it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        head.overview?.takeIf { it.isNotBlank() && s.isArtist }?.let { Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            item { MusicMessage(s.message, "music:item-message", model::dismissMessage) }
            if (s.albums.isNotEmpty()) {
                item { CatalogHeading("Albums", Modifier.padding(top = 8.dp), section = true) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(s.albums, key = { it.id }) { album ->
                            CatalogCard(album, account, false, { c.musicItem(album, library = s.library) }, Modifier.width(156.dp).focusOutline().probe("music:album:${album.id}"),
                                announcement = catalogAnnouncement(album), tag = "music:album:${album.id}", subtitle = musicCardLine(album))
                        }
                    }
                }
            }
            val listState = musicListState(s.loading, s.tracks.size + s.albums.size, s.error)
            if (listState != MusicListState.READY) item { MusicListNotice(listState, if (s.isPlaylist) "This playlist is empty." else "No songs here.", model::reload) }
            if (s.tracks.isNotEmpty() && (s.isArtist || s.isGenre)) item { CatalogHeading("Songs", Modifier.padding(top = 8.dp), section = true) }
            if (s.isAlbum && s.discs.size > 1) {
                val byId = s.tracks.associateBy(MediaItem::id)
                s.discs.forEach { disc ->
                    item(key = "disc:${disc.disc}") { Text("Disc ${disc.disc ?: 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() }) }
                    items(disc.tracks.mapNotNull(byId::get), key = { it.id }) { track ->
                        MusicTrackRow(c, account, track, track.indexNumber?.toString().orEmpty(), albumArtist, onPlay = { play(s.tracks, track) })
                    }
                }
            } else {
                itemsIndexed(s.tracks, key = { _, it -> it.playlistItemId ?: it.id }) { index, track ->
                    val number = when { s.isPlaylist -> (index + 1).toString(); s.isAlbum -> track.indexNumber?.toString().orEmpty(); else -> "" }
                    val entry = track.playlistItemId ?: track.id
                    MusicTrackRow(c, account, track, number, albumArtist.takeIf { s.isAlbum }, onPlay = { play(s.tracks, track) },
                        playlistEdit = s.isPlaylist && s.canEdit && s.edit.phase == "idle", index = index, count = s.tracks.size,
                        onMove = { to -> model.moveEntry(entry, to) }, onRemove = { model.removeEntry(entry) })
                }
            }
            if (!s.tracksComplete && s.isArtist) item {
                TextButton({ model.allTracks { } }, enabled = !s.busy, modifier = Modifier.controlFocus().probe("music:all-songs")) { Text("Show all songs") }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
    if (picker) DesktopMusicPickerDialog(c, account, if (s.isTrack) listOf(head.id) else emptyList(), pickerOpener, fromItem = model.takeIf { !s.isTrack }) { picker = false }
    if (rename) DesktopMusicNameDialog("Rename playlist", head.name, "Rename", "music:rename", renameOpener, onConfirm = { model.rename(it); rename = false }) { rename = false }
}

/** A small text dialog: rename a playlist, or name a new one. Escape and Cancel close it; Enter confirms. */
@Composable
private fun DesktopMusicNameDialog(title: String, initial: String, confirm: String, tag: String, opener: FocusRequester, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    val keys = DesktopMenuRegistration(true, opener, onDismiss)
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.onPreviewKeyEvent(keys), title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") },
                modifier = Modifier.fillMaxWidth().focusRequester(field).probe("$tag:field"),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (name.isNotBlank()) onConfirm(name) }))
        },
        dismissButton = { TextButton(onDismiss, Modifier.controlFocus(allowInModal = true).probe("$tag:cancel")) { Text("Cancel") } },
        confirmButton = { TextButton({ onConfirm(name) }, enabled = name.isNotBlank(), modifier = Modifier.controlFocus(allowInModal = true).probe("$tag:confirm")) { Text(confirm) } })
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { field.requestFocus() } }
}

/** MUS-09: add to one of the viewer's playlists, or make a new one with these tracks. */
@Composable
private fun DesktopMusicPickerDialog(c: DesktopController, account: Account, ids: List<String>, opener: FocusRequester, fromItem: DesktopMusicDetail? = null, onDismiss: () -> Unit) {
    // An album, artist or genre adds every one of its playable songs; those may need reading first.
    var resolved by remember { mutableStateOf(ids.takeIf { it.isNotEmpty() }) }
    LaunchedEffect(fromItem) { if (resolved == null) fromItem?.allTracks { tracks -> resolved = tracks.filter(::trackIsAvailable).map(MediaItem::id) } }
    val tracks = resolved
    val keys = DesktopMenuRegistration(true, opener, onDismiss)
    if (tracks == null) {
        AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.onPreviewKeyEvent(keys), title = { Text("Add to playlist") }, text = { CircularProgressIndicator() },
            confirmButton = { TextButton(onDismiss, Modifier.controlFocus(allowInModal = true).probe("music:picker:cancel")) { Text("Cancel") } })
        return
    }
    val picker = remember(tracks) { c.musicBrowse.picker(account, tracks) }
    val s by picker.state.collectAsState()
    var naming by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val newOpener = remember { FocusRequester() }
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.onPreviewKeyEvent(keys), title = { Text("Add to playlist") },
        text = {
            Column(Modifier.widthIn(min = 320.dp).heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.message?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.primary) }
                s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (s.loading) CircularProgressIndicator()
                if (!s.done) {
                    TextButton({ naming = true }, enabled = !s.busy, modifier = Modifier.focusRequester(newOpener).focusRequester(first).controlFocus(allowInModal = true).probe("music:picker:new")) { Text("New playlist…") }
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(s.playlists, key = { it.id }) { playlist ->
                            TextButton({ picker.add(playlist) }, enabled = !s.busy, modifier = Modifier.fillMaxWidth().controlFocus(allowInModal = true).probe("music:picker:${playlist.id}")) {
                                Text(playlist.name, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss, Modifier.controlFocus(allowInModal = true).probe("music:picker:close")) { Text(if (s.done) "Done" else "Cancel") } })
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    if (naming) DesktopMusicNameDialog("New playlist", "", "Create", "music:new-playlist", newOpener, onConfirm = { picker.create(it); naming = false }) { naming = false }
}

/** MUS-03: the bar at the bottom of the window while music is queued. Click the title for Now playing. */
@Composable
internal fun DesktopMusicMiniBar(c: DesktopController) {
    val ui by c.music.state.collectAsState()
    val row = ui.current ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Music player" }) {
        Column {
            val duration = ui.durationMs ?: 0
            LinearProgressIndicator({ if (duration > 0) (ui.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth().height(3.dp))
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(onClick = c::nowPlaying, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.weight(1f).controlFocus(RoundedCornerShape(8.dp), insetRing = true).probe("music:mini:open")
                        .semantics { contentDescription = musicMiniDescription(row.track.title, row.track.artist, ui.message) }) {
                    Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MusicArt(row.track.artUrl, Modifier.size(48.dp))
                        Column(Modifier.weight(1f)) {
                            Text(row.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            // Pilot 172 D6: a failure ("None of the tracks left…") gets the line to itself, in the accent colour and
                            // announced, as on Now playing; it no longer trails the artist in grey where it was easy to miss.
                            val message = ui.message
                            if (message != null) Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.probe("music:mini:message"),
                                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            else Text(row.track.artist ?: ui.contextName.orEmpty(),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text("${formatDesktopMusicTime(ui.positionMs)} / ${formatDesktopMusicTime(duration)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MusicIcon(DesktopGlyph.TransportBack, "Previous", "music:mini:previous", onClick = c.music::previous)
                val playing = ui.outputActive && ui.queue?.paused == false
                MusicIcon(if (playing) DesktopGlyph.Pause else DesktopGlyph.Play, if (playing) "Pause" else "Play", "music:mini:toggle", onClick = c.music::toggle)
                MusicIcon(DesktopGlyph.TransportForward, "Next", "music:mini:next", onClick = c.music::next)
                if (ui.message != null) TextButton(c.music::dismissMessage, Modifier.controlFocus().probe("music:mini:dismiss")) { Text("Dismiss") }
                if (ui.canRetrySave) TextButton(c.music::retrySave, Modifier.controlFocus().probe("music:mini:retry-save")) { Text("Retry save") }
            }
        }
    }
}

@Composable
private fun MusicArt(url: String?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(DesktopGlyph.Audio, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Playing another album or playlist replaces the queue only after the viewer says so (QUE-02). */
@Composable
internal fun DesktopMusicReplacePrompt(c: DesktopController, play: DesktopMusicPlay) {
    val opener = remember { FocusRequester() }
    val cancel = remember { FocusRequester() }
    val keys = DesktopMenuRegistration(true, opener, c.music::dismissPrompt)
    val ui by c.music.state.collectAsState()
    AlertDialog(onDismissRequest = c.music::dismissPrompt, modifier = Modifier.onPreviewKeyEvent(keys), title = { Text("Replace the queue?") },
        text = { Text("Playing ${play.contextName ?: "this"} replaces what's queued" + (ui.contextName?.let { " from $it" } ?: "") + ".") },
        dismissButton = { TextButton(c.music::dismissPrompt, Modifier.focusRequester(cancel).controlFocus(allowInModal = true).probe("music:replace:cancel")) { Text("Cancel") } },
        confirmButton = { TextButton(c.music::confirm, Modifier.controlFocus(allowInModal = true).probe("music:replace:confirm")) { Text("Replace queue") } })
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { cancel.requestFocus() } }
}

/** MUS-03: Now playing, with the transport, volume, and the queue to reorder, remove from and clear. */
@Composable
internal fun DesktopMusicNowPage(c: DesktopController) {
    val ui by c.music.state.collectAsState()
    val row = ui.current
    val list = rememberLazyListState()
    if (row == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CatalogHeading("Now playing")
            Text("Nothing is queued. Play an album, playlist or song from a music library.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 20.dp), state = list, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                FlowRow(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    MusicArt(row.track.artUrl, Modifier.size(240.dp))
                    Column(Modifier.widthIn(min = 300.dp, max = 640.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Now playing", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(row.track.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                        Text(listOfNotNull(row.track.artist, row.track.album).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ui.output?.let { Text(it.label + (it.reason?.let { r -> " ($r)" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.probe("music:now:output")) }
                        val duration = (ui.durationMs ?: 0).coerceAtLeast(0)
                        var dragging by remember { mutableStateOf<Float?>(null) }
                        Slider(value = dragging ?: ui.positionMs.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                            onValueChange = { dragging = it }, onValueChangeFinished = { dragging?.let { c.music.seekTo(it.toLong()) }; dragging = null },
                            valueRange = 0f..duration.toFloat().coerceAtLeast(1f), enabled = duration > 0,
                            modifier = Modifier.fillMaxWidth().controlFocus(RoundedCornerShape(8.dp)).probe("music:now:seek").semantics { contentDescription = "Position" })
                        Row(Modifier.fillMaxWidth()) {
                            Text(formatDesktopMusicTime(dragging?.toLong() ?: ui.positionMs), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.weight(1f))
                            Text(formatDesktopMusicTime(duration), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MusicIcon(DesktopGlyph.Shuffle, if (ui.shuffled) "Shuffle: on" else "Shuffle: off", "music:now:shuffle", selected = ui.shuffled, onClick = c.music::toggleShuffle)
                            MusicIcon(DesktopGlyph.TransportBack, "Previous", "music:now:previous", onClick = c.music::previous)
                            val playing = ui.outputActive && ui.queue?.paused == false
                            FilledIconButton(c.music::toggle, Modifier.size(56.dp).controlFocus().probe("music:now:toggle").semantics { contentDescription = if (playing) "Pause" else "Play" }) {
                                Icon(if (playing) DesktopGlyph.Pause else DesktopGlyph.Play, contentDescription = null, modifier = Modifier.size(28.dp))
                            }
                            MusicIcon(DesktopGlyph.TransportForward, "Next", "music:now:next", onClick = c.music::next)
                            MusicIcon(DesktopGlyph.Repeat, desktopMusicRepeatLabel(ui.repeat), "music:now:repeat", selected = ui.repeat != "off", onClick = c.music::cycleRepeat)
                            MusicIcon(DesktopGlyph.Stop, "Stop", "music:now:stop", enabled = ui.outputActive, onClick = c.music::stop)
                        }
                        if (ui.repeat == "one") Text("Repeating this track", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(DesktopGlyph.Audio, contentDescription = null, modifier = Modifier.size(20.dp))
                            var volume by remember(ui.volume) { mutableStateOf(ui.volume.toFloat()) }
                            Slider(volume, { volume = it }, onValueChangeFinished = { c.music.setVolume(volume.toInt()) }, valueRange = 0f..100f,
                                modifier = Modifier.width(220.dp).controlFocus(RoundedCornerShape(8.dp)).probe("music:now:volume").semantics { contentDescription = "Music volume" })
                            Text("${volume.toInt()}%", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { MusicMessage(ui.message, "music:now:message", c.music::dismissMessage) }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CatalogHeading("Up next", Modifier.weight(1f), section = true)
                    OutlinedButton(c.music::clearUpcoming, enabled = ui.upNext.isNotEmpty(), modifier = Modifier.controlFocus().probe("music:now:clear-upcoming")) { Text("Clear up next") }
                    OutlinedButton(c.music::clear, modifier = Modifier.controlFocus().probe("music:now:clear")) { Text("Clear queue") }
                }
            }
            val upNext = ui.upNext
            if (upNext.isEmpty()) item { Text(if (ui.repeat == "all") "The queue starts over after this track." else "Nothing after this track.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val order = ui.queue?.order.orEmpty()
            itemsIndexed(upNext, key = { _, it -> it.entry }) { _, queued ->
                QueueRow(c, queued, order.indexOf(queued.entry), order.size, faded = false)
            }
            val played = ui.played
            if (played.isNotEmpty()) {
                item { CatalogHeading("Played", Modifier.padding(top = 16.dp), section = true) }
                items(played, key = { it.entry }) { queued -> QueueRow(c, queued, order.indexOf(queued.entry), order.size, faded = true) }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** A queue row: click jumps to it; Move up, Move down and Remove are buttons, so reordering works from the keyboard. */
@Composable
private fun QueueRow(c: DesktopController, row: DesktopMusicRow, index: Int, count: Int, faded: Boolean) {
    val tag = "music:queue:${row.entry}"
    Row(Modifier.fillMaxWidth().height(56.dp).alpha(if (faded) 0.6f else 1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(onClick = { c.music.jump(row.entry) }, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.weight(1f).fillMaxHeight().controlFocus(RoundedCornerShape(8.dp), insetRing = true).probe(tag)
                .semantics { contentDescription = "Play ${row.track.title}" + (row.track.artist?.let { ", $it" } ?: "") }) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MusicArt(row.track.artUrl, Modifier.size(40.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    row.track.artist?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text(row.track.durationTicks?.let { formatDesktopMusicTime(it / Ticks.PER_MILLISECOND) }.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!faded) {
            MusicIcon(DesktopGlyph.ArrowUp, "Move up", "$tag:up", enabled = index > 0) { c.music.move(row.entry, index - 1) }
            MusicIcon(DesktopGlyph.ArrowDown, "Move down", "$tag:down", enabled = index in 0 until count - 1) { c.music.move(row.entry, index + 1) }
        }
        MusicIcon(DesktopGlyph.Remove, "Remove from queue", "$tag:remove") { c.music.remove(row.entry) }
    }
}

/** The mini bar's spoken name: the track, then any message, so a reader hears a failure without opening Now playing. */
internal fun musicMiniDescription(title: String, artist: String?, message: String?): String =
    "Now playing: $title" + (artist?.let { ", $it" } ?: "") + (message?.let { ". $it" } ?: "")
