/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.music.ArtistRole
import app.pictureshow.core.music.MusicListState
import app.pictureshow.core.music.MusicSection
import app.pictureshow.core.music.albumArtistLine
import app.pictureshow.core.music.musicListState
import app.pictureshow.core.music.trackArtistLine
import app.pictureshow.core.music.trackIsAvailable
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/** One line above the navigation, for the whole app: what's playing, and the two controls worth a thumb's reach. */
@Composable
internal fun TouchMusicBar(host: MusicHost, onOpen: () -> Unit) {
    val ui by host.state.collectAsStateWithLifecycle()
    val row = ui.current ?: return
    Row(
        Modifier.fillMaxWidth().background(
            MaterialTheme.colorScheme.surfaceContainerHigh
        ).clickable(onClickLabel = "Open Now playing", onClick = onOpen)
            .testTag("music:bar").padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AsyncImage(
            model = row.track.artUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
        Column(Modifier.weight(1f)) {
            Text(row.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.track.artist?.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(onClick = host::toggle, modifier = Modifier.heightIn(min = 48.dp).testTag("music:bar:toggle")) {
            Text(if (ui.isPlaying) "Pause" else "Play")
        }
        TextButton(onClick = host::next, enabled = host.hasNext(), modifier = Modifier.heightIn(min = 48.dp).testTag("music:bar:next")) {
            Text("Next")
        }
    }
}

/** The host's questions and short notes, wherever the viewer happens to be. */
@Composable
internal fun TouchMusicPrompts(host: MusicHost, onDestination: (MusicPlay) -> Unit) {
    val ui by host.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(ui.message, ui.canRetrySave) {
        val message = ui.message ?: return@LaunchedEffect
        if (ui.canRetrySave) return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        host.dismissMessage()
    }
    if (ui.canRetrySave) {
        AlertDialog(
            onDismissRequest = host::dismissMessage,
            title = { Text("Queue not saved") },
            text = { Text(ui.message ?: "Couldn't save the queue on this device.") },
            confirmButton = { TextButton(onClick = host::retrySave) { Text("Try again") } },
            dismissButton = { TextButton(onClick = host::dismissMessage) { Text("Not now") } }
        )
    }
    val prompt = ui.prompt ?: return
    when (prompt.kind) {
        "replace" -> AlertDialog(
            onDismissRequest = host::dismissPrompt,
            title = { Text("Replace the queue?") },
            text = { Text("${prompt.play.contextName ?: "This"} replaces what's in the queue now.") },
            confirmButton = { TextButton(onClick = host::confirm, modifier = Modifier.testTag("music:replace")) { Text("Replace queue") } },
            dismissButton = { TextButton(onClick = host::dismissPrompt) { Text("Keep queue") } }
        )
        else -> AlertDialog(
            onDismissRequest = host::dismissPrompt,
            title = { Text("Play where?") },
            text = { Text("You're connected to ${prompt.destination ?: "another device"}.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        host.dismissPrompt()
                        onDestination(prompt.play)
                    },
                    modifier = Modifier.testTag("music:destination")
                ) { Text("Play on ${prompt.destination ?: "that device"}") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = host::dismissPrompt) { Text("Cancel") }
                    TextButton(onClick = host::confirm, modifier = Modifier.testTag("music:here")) { Text("Play here") }
                }
            }
        )
    }
}

@Composable
internal fun TouchMusicLibrary(vm: MusicLibraryViewModel, host: MusicHost, onOpen: (MediaItem, ArtistRole?) -> Unit, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.dismissMessage()
        }
    }
    val section = state.current
    val account = state.account
    val grid = rememberLazyGridState()
    val nearEnd by rememberUpdatedState(vm::nearEnd)
    Column(Modifier.fillMaxSize().testTag("music:library")) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TouchBack(onBack)
            TouchCatalogHeading(state.library.name, Modifier.weight(1f), section = true)
            TextButton(
                onClick = {
                    vm.shuffleAll { tracks ->
                        account.musicPlay(tracks, null, "shuffle:${state.library.id}", "Shuffle all", shuffle = true)?.let(host::request)
                    }
                },
                enabled = !state.shuffling,
                modifier = Modifier.heightIn(min = 48.dp).testTag("music:shuffle-all")
            ) { Text("Shuffle all") }
        }
        PrimaryScrollableTabRow(selectedTabIndex = MusicSection.entries.indexOf(state.section), edgePadding = 12.dp) {
            MusicSection.entries.forEach { entry ->
                Tab(
                    selected = entry == state.section,
                    onClick = { vm.select(entry) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("music:tab:${entry.name}"),
                    text = { Text(entry.title) }
                )
            }
        }
        val list = musicListState(section.loading, section.items.size, section.error)
        LazyVerticalGrid(
            columns = if (state.section == MusicSection.SONGS) GridCells.Fixed(1) else GridCells.Adaptive(140.dp),
            state = grid,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "status") {
                when (list) {
                    MusicListState.LOADING -> TouchCatalogLoading("Loading ${state.section.title.lowercase()}…")
                    MusicListState.EMPTY -> Text("No ${state.section.title.lowercase()} here yet.")
                    MusicListState.INACCESSIBLE -> Text("This account can't open this music.")
                    MusicListState.EXPIRED -> Text("Your session has expired. Sign in again.")
                    MusicListState.FAILED -> TouchCatalogError(section.error?.message ?: "Couldn't load this.", vm::retry)
                    MusicListState.READY -> if (section.error != null) TouchCatalogError(section.error.message, vm::retry)
                }
            }
            itemsIndexed(section.items, key = { _, item -> item.id }, span = { _, item ->
                if (item.type == "Audio") GridItemSpan(maxLineSpan) else GridItemSpan(1)
            }) { index, item ->
                if (item.type == "Audio") {
                    TouchMusicTrackRow(item, null, account, host, number = "", onPlay = {
                        val tracks = section.items.filter { it.type == "Audio" }
                        account.musicPlay(
                            tracks,
                            item,
                            "${state.section.name}:${state.library.id}",
                            state.section.title
                        )?.let(host::request)
                    }, onOpen = onOpen, onFavorite = null)
                } else {
                    TouchMusicCard(item, account) {
                        val role = when {
                            state.section == MusicSection.ARTISTS -> ArtistRole.ARTIST
                            item.type == "MusicArtist" -> ArtistRole.ALBUM_ARTIST
                            else -> null
                        }
                        onOpen(item, role)
                    }
                }
            }
        }
    }
    LaunchedEffect(grid, section.items.size) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.collect { index ->
            if (index != null && section.items.isNotEmpty()) nearEnd(index)
        }
    }
}

@Composable
private fun TouchMusicCard(item: MediaItem, account: Account, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("music:card:${item.id}").semantics(mergeDescendants = true) {
            contentDescription = catalogAnnouncement(item)
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        TouchCatalogArtwork(item, account, false, Modifier.fillMaxWidth().aspectRatio(1f))
        Column(Modifier.padding(8.dp)) {
            Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val line = albumArtistLine(item).ifBlank { item.productionYear?.toString().orEmpty() }
            if (line.isNotBlank()) {
                Text(
                    line,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** A row's own menu holds what isn't the row's one job, and a screen reader gets the same list as named actions. */
@Composable
private fun TouchMusicTrackRow(
    item: MediaItem,
    albumArtist: String?,
    account: Account,
    host: MusicHost,
    onPlay: () -> Unit,
    onOpen: (MediaItem, ArtistRole?) -> Unit,
    onFavorite: (() -> Unit)?,
    extra: List<Pair<String, () -> Unit>> = emptyList(),
    number: String = item.indexNumber?.toString().orEmpty()
) {
    val available = trackIsAvailable(item)
    var menu by remember { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val track = remember(item) { account.musicTrack(item) }
    val actions = buildList {
        add("Play next" to { host.playNext(account, listOf(track)) })
        add("Add to queue" to { host.append(account, listOf(track)) })
        add("Add to playlist" to { picking = true })
        onFavorite?.let { add((if (item.userData.isFavorite) "Remove from favorites" else "Add to favorites") to it) }
        item.albumId?.takeIf { albumArtist == null }?.let { album ->
            add(
                "Go to album" to
                    {
                        onOpen(
                            MediaItem(id = album, name = item.album.orEmpty(), type = "MusicAlbum", albumArtist = item.albumArtist),
                            null
                        )
                    }
            )
        }
        addAll(extra)
    }.filter { available || it.first == "Go to album" }
    Row(
        Modifier.fillMaxWidth().heightIn(
            min = 56.dp
        ).clickable(
            enabled = available,
            onClickLabel = "Play",
            onClick = onPlay
        ).testTag("music:track:${item.playlistItemId ?: item.id}").semantics {
            customActions = actions.map { (name, run) ->
                CustomAccessibilityAction(name) {
                    run()
                    true
                }
            }
            if (!available) stateDescription = "Not available"
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(number, Modifier.widthIn(min = 28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            val line = listOfNotNull(trackArtistLine(item, albumArtist), if (available) null else "Not available").joinToString(" · ")
            if (line.isNotBlank()) {
                Text(
                    line,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item.runTimeTicks?.takeIf { it > 0 }?.let { Text(formatMusicTime(it / 10_000), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Box {
            TextButton(onClick = {
                menu = true
            }, modifier = Modifier.heightIn(min = 48.dp), enabled = actions.isNotEmpty()) { Text("More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                actions.forEach { (name, run) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        menu = false
                        run()
                    })
                }
            }
        }
    }
    if (picking) TouchPlaylistPicker(account, listOf(item.id)) { picking = false }
}

@Composable
internal fun TouchPlaylistPicker(account: Account, items: List<String>, onClose: () -> Unit) {
    val vm: MusicPlaylistPickerViewModel =
        koinViewModel(key = "picker:${items.joinToString()}", parameters = { parametersOf(account, items) })
    val state by vm.state.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    LaunchedEffect(state.done) {
        if (state.done) {
            state.message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            onClose()
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add to playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.message?.let {
                    Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
                }
                if (state.loading) TouchCatalogLoading("Loading playlists…")
                TouchCatalogError(state.error, vm::load)
                if (creating) {
                    OutlinedTextField(name, {
                        name = it
                    }, label = {
                        Text("New playlist name")
                    }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("music:playlist-name"))
                    Text("Only you can see a new playlist.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        itemsIndexed(state.playlists, key = { _, it -> it.id }) { _, playlist ->
                            TextButton(onClick = {
                                vm.add(playlist)
                            }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(playlist.name, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(onClick = {
                    vm.create(name)
                }, enabled = !state.busy && name.isNotBlank(), modifier = Modifier.testTag("music:playlist-create")) { Text("Create") }
            } else {
                TextButton(onClick = { creating = true }, modifier = Modifier.testTag("music:playlist-new")) { Text("New playlist") }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}

@Composable
internal fun TouchMusicDetail(vm: MusicDetailViewModel, host: MusicHost, onOpen: (MediaItem, ArtistRole?) -> Unit, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val account = state.account
    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.dismissMessage()
        }
    }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    fun play(shuffle: Boolean, start: MediaItem? = null) {
        vm.allTracks { tracks ->
            account.musicPlay(tracks, start, state.context, state.item.name, shuffle)?.let(host::request)
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        modifier = Modifier.fillMaxSize().testTag("music:detail"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TouchBack(onBack)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TouchCatalogArtwork(state.item, account, false, Modifier.size(120.dp).clip(MaterialTheme.shapes.medium))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TouchCatalogHeading(state.item.name, section = true)
                        albumArtistLine(state.item).takeIf(String::isNotBlank)?.let { Text(it) }
                        val facts = listOfNotNull(
                            state.item.productionYear?.toString(),
                            state.tracks.size.takeIf { it > 0 && state.tracksComplete }?.let { if (it == 1) "1 track" else "$it tracks" }
                        ).joinToString(" · ")
                        if (facts.isNotBlank()) {
                            Text(
                                facts,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                state.item.overview?.takeIf(String::isNotBlank)?.let { Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                val canPlay = state.isGenre || state.tracks.any(::trackIsAvailable)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = {
                        play(false)
                    }, enabled = canPlay && !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("music:play")) { Text("Play") }
                    OutlinedButton(onClick = {
                        play(true)
                    }, enabled = canPlay && !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("music:shuffle")) {
                        Text("Shuffle")
                    }
                    OutlinedButton(
                        onClick = { vm.allTracks { tracks -> host.playNext(account, playableMusic(account, tracks)) } },
                        enabled = canPlay && !state.busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("music:play-next")
                    ) { Text("Play next") }
                    OutlinedButton(
                        onClick = { vm.allTracks { tracks -> host.append(account, playableMusic(account, tracks)) } },
                        enabled = canPlay && !state.busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("music:add-queue")
                    ) { Text("Add to queue") }
                    if (!state.isGenre) {
                        OutlinedButton(onClick = { vm.favorite() }, modifier = Modifier.heightIn(min = 48.dp).testTag("music:favorite")) {
                            Text(if (state.item.userData.isFavorite) "Favorited" else "Favorite")
                        }
                    }
                    if (state.tracks.isNotEmpty() && state.tracksComplete) {
                        OutlinedButton(onClick = {
                            picking = true
                        }, modifier = Modifier.heightIn(min = 48.dp).testTag("music:add-playlist")) { Text("Add to playlist") }
                    }
                    OutlinedButton(
                        onClick = {
                            vm.instantMix(state.item.id) { mix ->
                                account.musicPlay(
                                    mix,
                                    null,
                                    "mix:${state.item.id}",
                                    "Instant mix of ${state.item.name}"
                                )?.let(host::request)
                            }
                        },
                        enabled = !state.busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("music:mix")
                    ) { Text("Instant mix") }
                    if (state.isPlaylist && state.canEdit) {
                        OutlinedButton(onClick = {
                            renaming = true
                        }, modifier = Modifier.heightIn(min = 48.dp).testTag("music:rename")) { Text("Rename") }
                        OutlinedButton(onClick = {
                            editing = !editing
                        }, modifier = Modifier.heightIn(min = 48.dp).testTag("music:edit")) { Text(if (editing) "Done" else "Edit") }
                    }
                }
                if (state.isPlaylist && state.edit.phase == "unknown") {
                    OutlinedButton(onClick = { vm.reload() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check the playlist") }
                }
                if (state.loading) TouchCatalogLoading("Loading…")
                when (musicListState(state.loading, state.tracks.size + state.albums.size, state.error)) {
                    MusicListState.INACCESSIBLE -> Text("This account can't open this music.")
                    MusicListState.EXPIRED -> Text("Your session has expired. Sign in again.")
                    MusicListState.FAILED -> TouchCatalogError(state.error?.message ?: "Couldn't load this.", vm::reload)
                    MusicListState.EMPTY -> if (!state.isGenre) Text("Nothing here yet.")
                    else -> if (state.error != null) TouchCatalogError(state.error?.message, vm::reload)
                }
            }
        }
        if (state.albums.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "albums") { TouchCatalogHeading("Albums", section = true) }
            itemsIndexed(state.albums, key = { _, it ->
                "album:${it.id}"
            }) { _, album -> TouchMusicCard(album, account) { onOpen(album, null) } }
        }
        val byId = state.tracks.associateBy(MediaItem::id)
        val discs = state.discs.takeIf { state.isAlbum && it.size > 1 }
        if (discs != null) {
            discs.forEach { disc ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "disc:${disc.disc}") {
                    Text("Disc ${disc.disc}", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                }
                disc.tracks.forEach { id ->
                    val row = byId[id] ?: return@forEach
                    item(span = { GridItemSpan(maxLineSpan) }, key = "track:$id") {
                        TouchMusicTrackRow(row, albumArtistLine(state.item), account, host, {
                            play(false, row)
                        }, onOpen, { vm.favorite(row.id) })
                    }
                }
            }
        } else {
            if (state.isArtist && state.tracks.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "songs") { TouchCatalogHeading("Songs", section = true) }
            }
            state.tracks.forEachIndexed { index, row ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "track:${row.playlistItemId ?: row.id}:$index") {
                    val entry = row.playlistItemId
                    val edits = if (editing && entry != null) {
                        buildList {
                            if (index > 0) add("Move up" to { vm.moveEntry(entry, index - 1) })
                            if (index < state.tracks.lastIndex) add("Move down" to { vm.moveEntry(entry, index + 1) })
                            add("Remove from playlist" to { vm.removeEntry(entry) })
                        }
                    } else {
                        emptyList()
                    }
                    Column {
                        TouchMusicTrackRow(
                            row,
                            albumArtistLine(state.item).ifBlank {
                                null
                            },
                            account,
                            host,
                            { play(false, row) },
                            onOpen,
                            { vm.favorite(row.id) },
                            edits,
                            musicRowNumber(state.item, index)
                        )
                        // Edit shows the row's changes where they're used, as well as in its menu and to a screen reader.
                        if (edits.isNotEmpty()) {
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                edits.forEach { (name, run) ->
                                    TextButton(
                                        onClick = run,
                                        modifier = Modifier.testTag("music:edit:$name:$entry")
                                    ) { Text(name) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (renaming) {
        var name by rememberSaveable { mutableStateOf(state.item.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename playlist") },
            text = {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("music:rename-field"))
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.rename(name)
                    renaming = false
                }, enabled = name.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } }
        )
    }
    if (picking) TouchPlaylistPicker(account, state.tracks.filter(::trackIsAvailable).map(MediaItem::id)) { picking = false }
}

internal fun playableMusic(account: Account, tracks: List<MediaItem>): List<MusicTrack> =
    tracks.filter(::trackIsAvailable).map(account::musicTrack)

/** Now playing, with its queue under it on a phone and beside it on a tablet. */
@Composable
internal fun TouchMusicNow(host: MusicHost, onBack: () -> Unit) {
    val ui by host.state.collectAsStateWithLifecycle()
    val preferences by koinInject<PlaybackPreferencesStore>().values.collectAsState(null)
    LaunchedEffect(ui.queue?.current, ui.queue == null) {
        if (ui.queue != null && ui.queue?.current == null) onBack()
    }
    val row = ui.current
    if (row == null) {
        TouchPage("Now playing", onBack) { Text("Nothing is playing.") }
        return
    }
    var position by remember { mutableLongStateOf(host.positionMs()) }
    var duration by remember { mutableStateOf(host.durationMs()) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(row.entry, ui.isPlaying) {
        while (true) {
            position = host.positionMs()
            duration = host.durationMs()
            delay(500)
        }
    }
    val q = ui.queue
    val listState = rememberLazyListState()
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var picking by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize().testTag("music:now"),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "now") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth()) { TouchBack(onBack) }
                AsyncImage(
                    model = row.track.artUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth(0.72f).widthIn(max = 420.dp).aspectRatio(1f).clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        row.track.title,
                        Modifier.testTag("music:now:title"),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    listOfNotNull(
                        row.track.artist,
                        row.track.album
                    ).filter(String::isNotBlank).joinToString(" · ").takeIf(String::isNotBlank)?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                val total = duration
                if (total != null && total > 0) {
                    val shown = seeking ?: (position.toFloat() / total).coerceIn(0f, 1f)
                    Slider(
                        value = shown,
                        onValueChange = { seeking = it },
                        onValueChangeFinished = {
                            seeking?.let { host.seekTo((it * total).toLong()) }
                            seeking = null
                        },
                        modifier = Modifier.fillMaxWidth().testTag("music:seek").semantics {
                            contentDescription = "Seek"
                            stateDescription = "${formatMusicTime((shown * total).toLong())} of ${formatMusicTime(total)}"
                        }
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(formatMusicTime((shown * total).toLong()), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(formatMusicTime(total), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(formatMusicTime(position), style = MaterialTheme.typography.bodySmall)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedButton(onClick = host::previous, modifier = Modifier.heightIn(min = 48.dp).testTag("music:previous")) {
                        Text("Previous")
                    }
                    Button(onClick = host::toggle, modifier = Modifier.heightIn(min = 48.dp).testTag("music:toggle")) {
                        Text(if (ui.isPlaying) "Pause" else "Play")
                    }
                    OutlinedButton(
                        onClick = host::next,
                        enabled = host.hasNext(),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("music:next")
                    ) {
                        Text("Next")
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = host::cycleRepeat, modifier = Modifier.heightIn(min = 48.dp).testTag("music:repeat")) {
                        Text(
                            musicRepeatLabel(
                                q?.repeat ?: "off"
                            )
                        )
                    }
                    TextButton(onClick = host::toggleShuffle, modifier = Modifier.heightIn(min = 48.dp).testTag("music:shuffle-mode")) {
                        Text(if (q?.shuffled == true) "Shuffle: on" else "Shuffle: off")
                    }
                    TextButton(
                        onClick = host::stop,
                        enabled = ui.outputActive,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("music:stop")
                    ) {
                        Text("Stop")
                    }
                    TextButton(onClick = {
                        picking = true
                    }, modifier = Modifier.heightIn(min = 48.dp).testTag("music:now:playlist")) { Text("Add to playlist") }
                }
                ui.output?.let { output ->
                    Text(
                        output.label,
                        Modifier.testTag("music:output"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (preferences?.conversionNotice != false) {
                        output.reason?.let { reason ->
                            Text(
                                reason,
                                Modifier.testTag("music:conversion").semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Up next", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    TextButton(
                        onClick = host::clearUpcoming,
                        enabled = ui.upNext.isNotEmpty(),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("music:clear-upcoming")
                    ) {
                        Text("Clear")
                    }
                    TextButton(onClick = host::clear, modifier = Modifier.heightIn(min = 48.dp).testTag("music:clear")) {
                        Text("End queue")
                    }
                }
                if (ui.upNext.isEmpty()) Text("Nothing after this one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        itemsIndexed(ui.upNext, key = { _, it -> it.entry }) { index, upcoming ->
            val base = (q?.order?.indexOf(q.current) ?: 0) + 1
            val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = {
                if (it != SwipeToDismissBoxValue.Settled) host.remove(upcoming.entry)
                it != SwipeToDismissBoxValue.Settled
            })
            SwipeToDismissBox(state = dismiss, backgroundContent = {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer), contentAlignment = Alignment.CenterEnd) {
                    Text("Remove", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).heightIn(min = 56.dp)
                        .graphicsLayer { translationY = if (dragging == upcoming.entry) dragOffset else 0f }
                        .clickable(onClickLabel = "Play", onClick = { host.jump(upcoming.entry) })
                        .testTag("music:queue:${upcoming.entry}")
                        .semantics {
                            customActions = buildList {
                                if (index >
                                    0
                                ) {
                                    add(
                                        CustomAccessibilityAction("Move up") {
                                            host.move(upcoming.entry, base + index - 1)
                                            true
                                        }
                                    )
                                }
                                if (index <
                                    ui.upNext.lastIndex
                                ) {
                                    add(
                                        CustomAccessibilityAction("Move down") {
                                            host.move(
                                                upcoming.entry,
                                                base + index + 1
                                            )
                                            true
                                        }
                                    )
                                }
                                add(
                                    CustomAccessibilityAction("Remove") {
                                        host.remove(upcoming.entry)
                                        true
                                    }
                                )
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(upcoming.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        upcoming.track.artist?.takeIf(String::isNotBlank)?.let {
                            Text(
                                it,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // Holding the handle lifts the row; where it lands is worked out from how far it moved.
                    Text(
                        "Move",
                        Modifier.heightIn(
                            min = 48.dp
                        ).padding(horizontal = 12.dp, vertical = 14.dp).testTag("music:queue:handle:${upcoming.entry}")
                            .pointerInput(upcoming.entry, index, base) {
                                val rowHeight = 64.dp.toPx()
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragging = upcoming.entry
                                        dragOffset = 0f
                                    },
                                    onDragEnd = {
                                        val steps = (dragOffset / rowHeight).let { kotlin.math.round(it).toInt() }
                                        val target = (index + steps).coerceIn(0, ui.upNext.lastIndex)
                                        if (target != index) host.move(upcoming.entry, base + target)
                                        dragging = null
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        dragging = null
                                        dragOffset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                    }
                                )
                            },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
    if (picking) TouchPlaylistPicker(ui.account ?: return, listOf(row.track.id)) { picking = false }
}
