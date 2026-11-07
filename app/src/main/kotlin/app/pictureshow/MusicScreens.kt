/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
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

private val MusicBackground = Color(0xFF10131C)
private val MusicMuted = Color(0xFFC3C9D8)

/**
 * Secondary text inside a focusable row or card. It follows the row's own content color, so it stays readable when focus
 * turns the row light; the fixed [MusicMuted] is for text on the page's dark background only.
 */
internal val musicMutedOnRow: Color
    @Composable get() = LocalContentColor.current.copy(alpha = 0.78f)

/**
 * A row as wide as the page doesn't grow on focus: it would cover its own More button and leave the safe area. Its fill
 * shows focus.
 */
internal val musicRowScale: ClickableSurfaceScale
    @Composable get() = ClickableSurfaceDefaults.scale(focusedScale = 1f)
private val MusicError = Color(0xFFFFCCB8)

@Composable
private fun TvHeading(title: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Text(
        title,
        style = if (large) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall,
        modifier = modifier.semantics { heading() }
    )
}

/** One strip under every TV screen but the player: Down from a screen's last row reaches it, and its first stop opens Now playing. */
@Composable
internal fun TvMusicBar(host: MusicHost, onOpen: () -> Unit) {
    val ui by host.state.collectAsStateWithLifecycle()
    val row = ui.current ?: return
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF1B2130)).padding(horizontal = 48.dp, vertical = 8.dp).testTag("music:bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            scale = musicRowScale,
            onClick = onOpen,
            modifier = Modifier.weight(1f).testTag("music:bar:open").semantics {
                contentDescription =
                    "Now playing: ${row.track.title}"
            }
        ) {
            Row(
                Modifier.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AsyncImage(
                    row.track.artUrl,
                    null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(MaterialTheme.shapes.small).background(Color(0xFF262D3D))
                )
                Column(Modifier.weight(1f)) {
                    Text(row.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    row.track.artist?.takeIf(String::isNotBlank)?.let {
                        Text(
                            it,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = musicMutedOnRow,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        Button(onClick = host::toggle, modifier = Modifier.testTag("music:bar:toggle")) { Text(if (ui.isPlaying) "Pause" else "Play") }
        Button(onClick = host::next, enabled = host.hasNext(), modifier = Modifier.testTag("music:bar:next")) { Text("Next") }
    }
}

@Composable
private fun TvDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.background(Color(0xFF1B2130), MaterialTheme.shapes.medium).padding(24.dp).widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TvHeading(title)
            content()
        }
    }
}

@Composable
internal fun TvMusicPrompts(host: MusicHost, onDestination: (MusicPlay) -> Unit) {
    val ui by host.state.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    if (ui.canRetrySave) {
        TvDialog("Queue not saved", host::dismissMessage) {
            Text(ui.message ?: "Couldn't save the queue on this device.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = host::retrySave, modifier = Modifier.focusRequester(first)) { Text("Try again") }
                Button(onClick = host::dismissMessage) { Text("Not now") }
            }
            LaunchedEffect(Unit) { first.requestFocus() }
        }
    }
    val prompt = ui.prompt ?: return
    if (prompt.kind == "replace") {
        TvDialog("Replace the queue?", host::dismissPrompt) {
            Text("${prompt.play.contextName ?: "This"} replaces what's in the queue now.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = host::dismissPrompt, modifier = Modifier.focusRequester(first)) { Text("Keep queue") }
                Button(onClick = host::confirm, modifier = Modifier.testTag("music:replace")) { Text("Replace queue") }
            }
            LaunchedEffect(Unit) { first.requestFocus() }
        }
    } else {
        TvDialog("Play where?", host::dismissPrompt) {
            Text("You're connected to ${prompt.destination ?: "another device"}.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = host::confirm, modifier = Modifier.focusRequester(first).testTag("music:here")) { Text("Play here") }
                Button(onClick = {
                    host.dismissPrompt()
                    onDestination(prompt.play)
                }) { Text("Play on ${prompt.destination ?: "that device"}") }
                Button(onClick = host::dismissPrompt) { Text("Cancel") }
            }
            LaunchedEffect(Unit) { first.requestFocus() }
        }
    }
}

/** A short note the TV shows in place, since a toast can't be reached or reread with a remote. */
@Composable
private fun TvNote(text: String?, onDismiss: () -> Unit) {
    if (text == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite }, color = MusicError)
        Button(onClick = onDismiss) { Text("OK") }
    }
}

@Composable
private fun TvRetry(message: String?, onRetry: () -> Unit) {
    if (message == null) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, color = MusicError)
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun TvMusicCard(item: MediaItem, account: Account, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("music:card:${item.id}").semantics {
            contentDescription =
                catalogAnnouncement(item)
        }
    ) {
        Column {
            CatalogArtwork(item, account, false, Modifier.fillMaxWidth().aspectRatio(1f))
            Column(Modifier.padding(8.dp)) {
                Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val line = albumArtistLine(item).ifBlank { item.productionYear?.toString().orEmpty() }
                if (line.isNotBlank()) {
                    Text(
                        line,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = musicMutedOnRow,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/** The row plays; its More button holds the rest, so a remote reaches everything with Right then Select. */
@Composable
private fun TvMusicTrackRow(
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            scale = musicRowScale,
            onClick = onPlay,
            enabled = available,
            modifier = Modifier.weight(1f).testTag("music:track:${item.playlistItemId ?: item.id}").semantics {
                if (!available) stateDescription = "Not available"
            }
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(number, Modifier.widthIn(min = 32.dp), color = musicMutedOnRow)
                Column(Modifier.weight(1f)) {
                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val line = listOfNotNull(
                        trackArtistLine(item, albumArtist),
                        if (available) null else "Not available"
                    ).joinToString(" · ")
                    if (line.isNotBlank()) {
                        Text(
                            line,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = musicMutedOnRow,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                item.runTimeTicks?.takeIf { it > 0 }?.let { Text(formatMusicTime(it / 10_000), color = musicMutedOnRow) }
            }
        }
        Button(onClick = {
            menu = true
        }, enabled = available, modifier = Modifier.testTag("music:more:${item.playlistItemId ?: item.id}")) { Text("More") }
    }
    if (menu) {
        val first = remember { FocusRequester() }
        TvDialog(item.name, { menu = false }) {
            val actions = buildList {
                add("Play next" to { host.playNext(account, listOf(track)) })
                add("Add to queue" to { host.append(account, listOf(track)) })
                add("Add to playlist" to { picking = true })
                onFavorite?.let { add((if (item.userData.isFavorite) "Remove from favorites" else "Add to favorites") to it) }
                item.albumId?.let { album ->
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
            }
            LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(actions) { index, (name, run) ->
                    Button(onClick = {
                        menu = false
                        run()
                    }, modifier = if (index == 0) Modifier.focusRequester(first) else Modifier) { Text(name) }
                }
            }
            LaunchedEffect(Unit) { first.requestFocus() }
        }
    }
    if (picking) TvPlaylistPicker(account, listOf(item.id)) { picking = false }
}

@Composable
private fun TvTextEntry(value: String, label: String, onValue: (String) -> Unit, tag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = MusicMuted)
        BasicTextField(
            value,
            onValue,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
            cursorBrush = SolidColor(Color.White),
            modifier = Modifier.fillMaxWidth().background(Color(0xFF262D3D), MaterialTheme.shapes.small).padding(12.dp).testTag(tag)
        )
    }
}

@Composable
private fun TvPlaylistPicker(account: Account, items: List<String>, onClose: () -> Unit) {
    val vm: MusicPlaylistPickerViewModel =
        koinViewModel(key = "picker:${items.joinToString()}", parameters = { parametersOf(account, items) })
    val state by vm.state.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val first = remember { FocusRequester() }
    TvDialog("Add to playlist", onClose) {
        state.message?.let {
            Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = if (state.done) MusicMuted else MusicError)
        }
        if (state.done) {
            Button(onClick = onClose, modifier = Modifier.focusRequester(first)) { Text("OK") }
        } else if (creating) {
            TvTextEntry(name, "New playlist name", { name = it }, "music:playlist-name")
            Text("Only you can see a new playlist.", color = MusicMuted, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    vm.create(name)
                }, enabled = !state.busy && name.isNotBlank(), modifier = Modifier.testTag("music:playlist-create")) { Text("Create") }
                Button(onClick = onClose, modifier = Modifier.focusRequester(first)) { Text("Cancel") }
            }
        } else {
            if (state.loading) Text("Loading playlists…")
            TvRetry(state.error, vm::load)
            LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Button(onClick = {
                        creating = true
                    }, modifier = Modifier.focusRequester(first).testTag("music:playlist-new")) { Text("New playlist") }
                }
                items(state.playlists, key = MediaItem::id) { playlist ->
                    Button(onClick = {
                        vm.add(playlist)
                    }, enabled = !state.busy) { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Button(onClick = onClose) { Text("Cancel") }
        }
        LaunchedEffect(state.done, creating) { runCatching { first.requestFocus() } }
    }
}

@Composable
internal fun TvMusicLibrary(vm: MusicLibraryViewModel, host: MusicHost, onOpen: (MediaItem, ArtistRole?) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val section = state.current
    val account = state.account
    val grid = rememberLazyGridState()
    val nearEnd by rememberUpdatedState(vm::nearEnd)
    val firstTab = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstTab.requestFocus() } }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(168.dp),
        state = grid,
        modifier = Modifier.fillMaxSize().background(MusicBackground).testTag("music:library"),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TvHeading(state.library.name, large = true)
                TvNote(state.message, vm::dismissMessage)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(MusicSection.entries) { index, entry ->
                        Button(
                            onClick = { vm.select(entry) },
                            modifier = (
                                if (index ==
                                    0
                                ) {
                                    Modifier.focusRequester(firstTab)
                                } else {
                                    Modifier
                                }
                                ).testTag("music:tab:${entry.name}").semantics {
                                stateDescription = if (entry == state.section) "Selected" else ""
                            }
                        ) { Text(if (entry == state.section) "${entry.title} ·" else entry.title) }
                    }
                    item {
                        Button(
                            onClick = {
                                vm.shuffleAll { tracks ->
                                    account.musicPlay(
                                        tracks,
                                        null,
                                        "shuffle:${state.library.id}",
                                        "Shuffle all",
                                        shuffle = true
                                    )?.let(host::request)
                                }
                            },
                            enabled = !state.shuffling,
                            modifier = Modifier.testTag("music:shuffle-all")
                        ) { Text("Shuffle all") }
                    }
                }
                when (musicListState(section.loading, section.items.size, section.error)) {
                    MusicListState.LOADING -> Text("Loading ${state.section.title.lowercase()}…")
                    MusicListState.EMPTY -> Text("No ${state.section.title.lowercase()} here yet.")
                    MusicListState.INACCESSIBLE -> Text("This account can't open this music.")
                    MusicListState.EXPIRED -> Text("Your session has expired. Sign in again.")
                    MusicListState.FAILED -> TvRetry(section.error?.message ?: "Couldn't load this.", vm::retry)
                    MusicListState.READY -> TvRetry(section.error?.message, vm::retry)
                }
            }
        }
        itemsIndexed(section.items, key = { _, item -> item.id }, span = { _, item ->
            if (item.type == "Audio") GridItemSpan(maxLineSpan) else GridItemSpan(1)
        }) { _, item ->
            if (item.type == "Audio") {
                TvMusicTrackRow(item, null, account, host, number = "", onPlay = {
                    val tracks = section.items.filter { it.type == "Audio" }
                    account.musicPlay(tracks, item, "${state.section.name}:${state.library.id}", state.section.title)?.let(host::request)
                }, onOpen = onOpen, onFavorite = null)
            } else {
                TvMusicCard(item, account) {
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
    LaunchedEffect(grid, section.items.size) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.collect { index ->
            if (index != null && section.items.isNotEmpty()) nearEnd(index)
        }
    }
}

@Composable
internal fun TvMusicDetail(vm: MusicDetailViewModel, host: MusicHost, onOpen: (MediaItem, ArtistRole?) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val account = state.account
    var renaming by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val play = remember { FocusRequester() }
    LaunchedEffect(state.loading) { if (!state.loading) runCatching { play.requestFocus() } }
    fun start(shuffle: Boolean, from: MediaItem? = null) {
        vm.allTracks { tracks -> account.musicPlay(tracks, from, state.context, state.item.name, shuffle)?.let(host::request) }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(168.dp),
        modifier = Modifier.fillMaxSize().background(MusicBackground).testTag("music:detail"),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                CatalogArtwork(state.item, account, false, Modifier.size(180.dp).clip(MaterialTheme.shapes.medium))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvHeading(state.item.name, large = true)
                    val facts = listOfNotNull(
                        albumArtistLine(state.item).takeIf(String::isNotBlank),
                        state.item.productionYear?.toString(),
                        state.tracks.size.takeIf { it > 0 && state.tracksComplete }?.let { if (it == 1) "1 track" else "$it tracks" }
                    ).joinToString(" · ")
                    if (facts.isNotBlank()) Text(facts, color = MusicMuted)
                    state.item.overview?.takeIf(String::isNotBlank)?.let {
                        Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MusicMuted)
                    }
                    TvNote(state.message, vm::dismissMessage)
                    val canPlay = state.isGenre || state.tracks.any(::trackIsAvailable)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            start(false)
                        }, enabled = canPlay && !state.busy, modifier = Modifier.focusRequester(play).testTag("music:play")) {
                            Text("Play")
                        }
                        Button(onClick = {
                            start(true)
                        }, enabled = canPlay && !state.busy, modifier = Modifier.testTag("music:shuffle")) { Text("Shuffle") }
                        Button(onClick = {
                            vm.allTracks { host.playNext(account, playableMusic(account, it)) }
                        }, enabled = canPlay && !state.busy, modifier = Modifier.testTag("music:play-next")) { Text("Play next") }
                        Button(onClick = {
                            vm.allTracks { host.append(account, playableMusic(account, it)) }
                        }, enabled = canPlay && !state.busy, modifier = Modifier.testTag("music:add-queue")) { Text("Add to queue") }
                        if (!state.isGenre) {
                            Button(onClick = {
                                vm.favorite()
                            }, modifier = Modifier.testTag("music:favorite")) {
                                Text(if (state.item.userData.isFavorite) "Favorited" else "Favorite")
                            }
                        }
                        if (state.tracks.isNotEmpty() && state.tracksComplete) {
                            Button(onClick = {
                                picking = true
                            }, modifier = Modifier.testTag("music:add-playlist")) { Text("Add to playlist") }
                        }
                        Button(
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
                            modifier = Modifier.testTag("music:mix")
                        ) { Text("Instant mix") }
                        if (state.isPlaylist && state.canEdit) {
                            Button(onClick = { renaming = true }, modifier = Modifier.testTag("music:rename")) { Text("Rename") }
                            Button(onClick = {
                                editing = !editing
                            }, modifier = Modifier.testTag("music:edit")) { Text(if (editing) "Done" else "Edit") }
                        }
                        if (state.isPlaylist && state.edit.phase == "unknown") Button(onClick = vm::reload) { Text("Check the playlist") }
                    }
                    if (state.loading) Text("Loading…")
                    when (musicListState(state.loading, state.tracks.size + state.albums.size, state.error)) {
                        MusicListState.INACCESSIBLE -> Text("This account can't open this music.")
                        MusicListState.EXPIRED -> Text("Your session has expired. Sign in again.")
                        MusicListState.FAILED -> TvRetry(state.error?.message ?: "Couldn't load this.", vm::reload)
                        MusicListState.EMPTY -> if (!state.isGenre) Text("Nothing here yet.")
                        else -> TvRetry(state.error?.message, vm::reload)
                    }
                }
            }
        }
        if (state.albums.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "albums") { TvHeading("Albums") }
            itemsIndexed(state.albums, key = { _, it ->
                "album:${it.id}"
            }) { _, album -> TvMusicCard(album, account) { onOpen(album, null) } }
        }
        val byId = state.tracks.associateBy(MediaItem::id)
        val discs = state.discs.takeIf { state.isAlbum && it.size > 1 }
        if (discs != null) {
            discs.forEach { disc ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "disc:${disc.disc}") { TvHeading("Disc ${disc.disc}") }
                disc.tracks.forEach { id ->
                    val row = byId[id] ?: return@forEach
                    item(span = { GridItemSpan(maxLineSpan) }, key = "track:$id") {
                        TvMusicTrackRow(row, albumArtistLine(state.item), account, host, {
                            start(false, row)
                        }, onOpen, { vm.favorite(row.id) })
                    }
                }
            }
        } else {
            if (state.isArtist &&
                state.tracks.isNotEmpty()
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "songs") { TvHeading("Songs") }
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
                        TvMusicTrackRow(
                            row,
                            albumArtistLine(state.item).ifBlank {
                                null
                            },
                            account,
                            host,
                            { start(false, row) },
                            onOpen,
                            { vm.favorite(row.id) },
                            edits,
                            musicRowNumber(state.item, row, index)
                        )
                        // Edit shows the row's changes where they're used, as well as in its More list.
                        if (edits.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().padding(start = 56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                edits.forEach { (name, run) ->
                                    Button(onClick = run, modifier = Modifier.testTag("music:edit:$name:$entry")) { Text(name) }
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
        TvDialog("Rename playlist", { renaming = false }) {
            TvTextEntry(name, "Name", { name = it }, "music:rename-field")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    vm.rename(name)
                    renaming = false
                }, enabled = name.isNotBlank()) { Text("Save") }
                Button(onClick = { renaming = false }) { Text("Cancel") }
            }
        }
    }
    if (picking) TvPlaylistPicker(account, state.tracks.filter(::trackIsAvailable).map(MediaItem::id)) { picking = false }
}

/** Art and controls on the left, the queue on the right. Left and Right on the seek button move in ten-second steps. */
@Composable
internal fun TvMusicNow(host: MusicHost, onBack: () -> Unit) {
    val ui by host.state.collectAsStateWithLifecycle()
    val preferences by koinInject<PlaybackPreferencesStore>().values.collectAsState(null)
    LaunchedEffect(ui.queue?.current, ui.queue == null) {
        if (ui.queue != null && ui.queue?.current == null) onBack()
    }
    val row = ui.current
    if (row == null) {
        Box(Modifier.fillMaxSize().background(MusicBackground), contentAlignment = Alignment.Center) { Text("Nothing is playing.") }
        return
    }
    val q = ui.queue
    var position by remember { mutableLongStateOf(host.positionMs()) }
    var duration by remember { mutableStateOf(host.durationMs()) }
    LaunchedEffect(row.entry, ui.isPlaying) {
        while (true) {
            position = host.positionMs()
            duration = host.durationMs()
            delay(500)
        }
    }
    val toggle = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { toggle.requestFocus() } }
    var picking by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxSize().background(MusicBackground).padding(horizontal = 48.dp, vertical = 27.dp).testTag("music:now"),
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                AsyncImage(
                    row.track.artUrl,
                    null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(220.dp).clip(MaterialTheme.shapes.medium).background(Color(0xFF262D3D))
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TvHeading(row.track.title, Modifier.testTag("music:now:title"))
                    listOfNotNull(
                        row.track.artist,
                        row.track.album
                    ).filter(String::isNotBlank).joinToString(" · ").takeIf(String::isNotBlank)?.let {
                        Text(it, color = MusicMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item {
                val total = duration
                val line = if (total != null &&
                    total > 0
                ) {
                    "${formatMusicTime(position)} / ${formatMusicTime(total)}"
                } else {
                    formatMusicTime(position)
                }
                Surface(
                    scale = musicRowScale,
                    onClick = {},
                    modifier = Modifier.fillMaxWidth().testTag("music:seek").semantics {
                        contentDescription = "Seek. $line"
                    }.onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || total == null) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> {
                                host.seekTo((position - 10_000).coerceAtLeast(0))
                                true
                            }
                            Key.DirectionRight -> {
                                host.seekTo((position + 10_000).coerceAtMost(total))
                                true
                            }
                            else -> false
                        }
                    }
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(line)
                        if (total != null && total > 0) {
                            Box(Modifier.fillMaxWidth().background(Color(0xFF3A4254))) {
                                Box(
                                    Modifier.fillMaxWidth(
                                        (position.toFloat() / total).coerceIn(0f, 1f)
                                    ).size(height = 4.dp, width = 0.dp).background(Color.White)
                                )
                            }
                        }
                    }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = host::previous, modifier = Modifier.testTag("music:previous")) { Text("Previous") }
                    Button(onClick = host::toggle, modifier = Modifier.focusRequester(toggle).testTag("music:toggle")) {
                        Text(if (ui.isPlaying) "Pause" else "Play")
                    }
                    Button(onClick = host::next, enabled = host.hasNext(), modifier = Modifier.testTag("music:next")) { Text("Next") }
                    Button(onClick = host::stop, enabled = ui.outputActive, modifier = Modifier.testTag("music:stop")) { Text("Stop") }
                    Button(onClick = host::cycleRepeat, modifier = Modifier.testTag("music:repeat")) {
                        Text(
                            musicRepeatLabel(
                                q?.repeat ?: "off"
                            )
                        )
                    }
                    Button(onClick = host::toggleShuffle, modifier = Modifier.testTag("music:shuffle-mode")) {
                        Text(
                            if (q?.shuffled ==
                                true
                            ) {
                                "Shuffle: on"
                            } else {
                                "Shuffle: off"
                            }
                        )
                    }
                    Button(onClick = { picking = true }, modifier = Modifier.testTag("music:now:playlist")) { Text("Add to playlist") }
                }
            }
            ui.output?.let { output ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(output.label, Modifier.testTag("music:output"), color = MusicMuted, style = MaterialTheme.typography.bodySmall)
                        if (preferences?.conversionNotice != false) {
                            output.reason?.let {
                                Text(
                                    it,
                                    Modifier.testTag("music:conversion").semantics {
                                        liveRegion = LiveRegionMode.Polite
                                    },
                                    color = MusicMuted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight().testTag("music:queue"),
            state = rememberLazyListState(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvHeading("Up next", Modifier.weight(1f))
                    Button(
                        onClick = host::clearUpcoming,
                        enabled = ui.upNext.isNotEmpty(),
                        modifier = Modifier.testTag("music:clear-upcoming")
                    ) {
                        Text("Clear")
                    }
                    Button(onClick = host::clear, modifier = Modifier.testTag("music:clear")) { Text("End queue") }
                }
            }
            if (ui.upNext.isEmpty()) item { Text("Nothing after this one.", color = MusicMuted) }
            itemsIndexed(ui.upNext, key = { _, it -> it.entry }) { index, upcoming ->
                val base = (q?.order?.indexOf(q.current) ?: 0) + 1
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(onClick = {
                        host.jump(upcoming.entry)
                    }, scale = musicRowScale, modifier = Modifier.weight(1f).testTag("music:queue:${upcoming.entry}")) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(upcoming.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            upcoming.track.artist?.takeIf(String::isNotBlank)?.let {
                                Text(
                                    it,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = musicMutedOnRow,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    Button(
                        onClick = {
                            host.move(upcoming.entry, base + index - 1)
                        },
                        enabled = index > 0,
                        modifier = Modifier.width(72.dp).testTag("music:queue:up:${upcoming.entry}").semantics {
                            contentDescription =
                                "Move ${upcoming.track.title} up"
                        }
                    ) { Text("Up") }
                    Button(
                        onClick = {
                            host.move(upcoming.entry, base + index + 1)
                        },
                        enabled = index < ui.upNext.lastIndex,
                        modifier = Modifier.width(88.dp).testTag("music:queue:down:${upcoming.entry}").semantics {
                            contentDescription =
                                "Move ${upcoming.track.title} down"
                        }
                    ) { Text("Down") }
                    Button(
                        onClick = {
                            host.remove(upcoming.entry)
                        },
                        modifier = Modifier.testTag("music:queue:remove:${upcoming.entry}").semantics {
                            contentDescription =
                                "Remove ${upcoming.track.title}"
                        }
                    ) { Text("Remove") }
                }
            }
        }
    }
    if (picking) TvPlaylistPicker(ui.account ?: return, listOf(row.track.id)) { picking = false }
}
