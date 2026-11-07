/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.ItemPage
import app.pictureshow.core.MediaItem
import app.pictureshow.core.music.ArtistRole
import app.pictureshow.core.music.MUSIC_QUEUE_LIMIT
import app.pictureshow.core.music.MusicDisc
import app.pictureshow.core.music.MusicPageState
import app.pictureshow.core.music.MusicRequest
import app.pictureshow.core.music.MusicSection
import app.pictureshow.core.music.MusicService
import app.pictureshow.core.music.PlaylistEditEvent
import app.pictureshow.core.music.PlaylistEditState
import app.pictureshow.core.music.PlaylistWrite
import app.pictureshow.core.music.albumDiscs
import app.pictureshow.core.music.mergeMusicPage
import app.pictureshow.core.music.musicAlbumTracksRequest
import app.pictureshow.core.music.musicArtistAlbumsRequest
import app.pictureshow.core.music.musicArtistTracksRequest
import app.pictureshow.core.music.musicFavoriteArtistsRequest
import app.pictureshow.core.music.musicGenreAlbumsRequest
import app.pictureshow.core.music.musicGenreTracksRequest
import app.pictureshow.core.music.musicPlaylistItemsRequest
import app.pictureshow.core.music.musicRandomSongsRequest
import app.pictureshow.core.music.musicSectionRequest
import app.pictureshow.core.music.orderTracks
import app.pictureshow.core.music.playableTracks
import app.pictureshow.core.music.reducePlaylistEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val MUSIC_ITEM_TYPES = setOf("MusicAlbum", "MusicArtist", "MusicGenre", "Audio")

/** What opens in the music screens rather than the video grid or detail. A video playlist stays where it was. */
internal fun MediaItem.isMusic(): Boolean =
    collectionType == "music" || type in MUSIC_ITEM_TYPES || (type == "Playlist" && mediaType == "Audio")

/** A playlist numbers its own order, and an artist's songs come from many albums, so neither shows a track number from its album. */
internal fun musicRowNumber(parent: MediaItem, index: Int): String = if (parent.type == "Playlist") (index + 1).toString() else ""

internal fun musicRoute(item: MediaItem, role: ArtistRole? = null, library: String? = null): String =
    "music?item=${Uri.encode(Json.encodeToString(item))}&role=${role?.name.orEmpty()}&library=${library.orEmpty()}"

internal const val MUSIC_ROUTE_PATTERN = "music?item={item}&role={role}&library={library}"
internal const val MUSIC_NOW_ROUTE = "music-now"

/** The read every music screen makes: an expired session ends the account, the same as a catalog read does. */
private suspend fun MusicService.read(account: Account, accounts: AccountStore, request: MusicRequest): ClientResult<ItemPage> {
    val result = music(account, request)
    if (result is ClientResult.Failure && result.error.kind in listOf(ErrorKind.INVALID_CREDENTIALS, ErrorKind.EXPIRED)) {
        accounts.expire(account.key, account.accessToken)
    }
    return result
}

internal data class MusicSectionState(
    val items: List<MediaItem> = emptyList(),
    val page: MusicPageState = MusicPageState(),
    val loading: Boolean = false,
    val error: ClientError? = null,
    val artistsRead: Boolean = false
)

internal data class MusicLibraryState(
    val account: Account,
    val library: MediaItem,
    val section: MusicSection = MusicSection.ALBUMS,
    val sections: Map<MusicSection, MusicSectionState> = emptyMap(),
    val shuffling: Boolean = false,
    val message: String? = null
) {
    val current: MusicSectionState get() = sections[section] ?: MusicSectionState()
}

/** Each tab keeps its own rows and place, so moving between tabs and coming Back loses nothing (MUS-01). */
internal class MusicLibraryViewModel(
    val account: Account,
    library: MediaItem,
    private val music: MusicService,
    private val accounts: AccountStore
) : ViewModel() {
    private val mutable = MutableStateFlow(MusicLibraryState(account, library))
    val state: StateFlow<MusicLibraryState> = mutable.asStateFlow()
    private val jobs = mutableMapOf<MusicSection, Job>()

    init {
        load(MusicSection.ALBUMS)
    }

    fun select(section: MusicSection) {
        mutable.update { it.copy(section = section) }
        if (state.value.current.items.isEmpty()) load(section)
    }

    fun nearEnd(index: Int) {
        val current = state.value.current
        if (index >= current.items.size - 12 && current.error == null) load(state.value.section)
    }

    fun retry() = load(state.value.section)

    fun dismissMessage() = mutable.update { it.copy(message = null) }

    fun shuffleAll(onReady: (List<MediaItem>) -> Unit) {
        if (state.value.shuffling) return
        mutable.update { it.copy(shuffling = true, message = null) }
        viewModelScope.launch {
            val answer = music.read(account, accounts, musicRandomSongsRequest(state.value.library.id))
            mutable.update {
                it.copy(
                    shuffling = false,
                    message = when (answer) {
                        is ClientResult.Failure -> answer.error.message
                        is ClientResult.Success -> if (playableTracks(answer.value.items).isEmpty()) "No songs here can play." else null
                    }
                )
            }
            if (answer is ClientResult.Success) playableTracks(answer.value.items).takeIf { it.isNotEmpty() }?.let(onReady)
        }
    }

    private fun change(section: MusicSection, edit: (MusicSectionState) -> MusicSectionState) {
        mutable.update { it.copy(sections = it.sections + (section to edit(it.sections[section] ?: MusicSectionState()))) }
    }

    private fun load(section: MusicSection) {
        val before = state.value.sections[section] ?: MusicSectionState()
        if (jobs[section]?.isActive == true || before.page.ended) return
        change(section) { it.copy(loading = true, error = null) }
        jobs[section] = viewModelScope.launch {
            val library = state.value.library.id
            var artists = emptyList<MediaItem>()
            if (section == MusicSection.FAVORITES && !before.artistsRead) {
                val answer = music.read(account, accounts, musicFavoriteArtistsRequest(library))
                if (answer is ClientResult.Failure) {
                    change(section) { it.copy(loading = false, error = answer.error) }
                    return@launch
                }
                artists = (answer as ClientResult.Success).value.items
            }
            when (val answer = music.read(account, accounts, musicSectionRequest(section, library, start = before.page.offset))) {
                is ClientResult.Failure -> change(section) { it.copy(loading = false, error = answer.error) }
                is ClientResult.Success -> change(section) {
                    val page = mergeMusicPage(it.page, answer.value.items.map(MediaItem::id), answer.value.totalRecordCount)
                    it.copy(
                        items = (it.items + artists + answer.value.items).distinctBy(MediaItem::id),
                        page = page,
                        loading = false,
                        error = null,
                        artistsRead = true
                    )
                }
            }
        }
    }
}

internal data class MusicDetailState(
    val account: Account,
    val item: MediaItem,
    val role: ArtistRole = ArtistRole.ALBUM_ARTIST,
    val library: String? = null,
    val albums: List<MediaItem> = emptyList(),
    val tracks: List<MediaItem> = emptyList(),
    val discs: List<MusicDisc> = emptyList(),
    val tracksComplete: Boolean = true,
    val loading: Boolean = true,
    val error: ClientError? = null,
    val canEdit: Boolean = false,
    val edit: PlaylistEditState = PlaylistEditState(),
    val busy: Boolean = false,
    val message: String? = null
) {
    val isPlaylist: Boolean get() = item.type == "Playlist"
    val isAlbum: Boolean get() = item.type == "MusicAlbum"
    val isArtist: Boolean get() = item.type == "MusicArtist"
    val isGenre: Boolean get() = item.type == "MusicGenre"
    val isTrack: Boolean get() = item.type == "Audio"
    val context: String get() = "${item.type}:${item.id}"
}

/** Reads back after every list write, so what the screen shows is what the server holds (MUS-09). */
internal class PlaylistEditor(
    private val account: Account,
    private val playlist: String,
    private val music: MusicService,
    private val scope: CoroutineScope,
    private val publish: (PlaylistEditState, List<MediaItem>?) -> Unit
) {
    var state = PlaylistEditState()
        private set

    private fun apply(event: PlaylistEditEvent, rows: List<MediaItem>? = null) {
        state = reducePlaylistEdit(state, event)
        publish(state, rows)
    }

    private suspend fun readAll(): List<MediaItem>? {
        val rows = mutableListOf<MediaItem>()
        var page = MusicPageState()
        while (!page.ended && rows.size < MUSIC_QUEUE_LIMIT) {
            val answer = music.music(account, musicPlaylistItemsRequest(playlist, page.offset)) as? ClientResult.Success ?: return null
            page = mergeMusicPage(page, answer.value.items.map(MediaItem::id), answer.value.totalRecordCount)
            rows += answer.value.items
        }
        return rows
    }

    private fun entries(rows: List<MediaItem>) = rows.map { it.playlistItemId ?: it.id }

    /** The first read, which every edit is compared against. Null means the list couldn't be read. */
    suspend fun load(): List<MediaItem>? = readAll()?.also { apply(PlaylistEditEvent("loaded", entries = entries(it)), it) }

    private suspend fun readBack() {
        val rows = readAll()
        if (rows == null) apply(PlaylistEditEvent("read-failed")) else apply(PlaylistEditEvent("read", entries = entries(rows)), rows)
    }

    fun readOnly() = apply(PlaylistEditEvent("read-only"))

    fun submit(name: String, write: suspend () -> PlaylistWrite, done: () -> Unit = {}) {
        apply(PlaylistEditEvent("submit", edit = name))
        when (state.effect) {
            "read-back" -> scope.launch { readBack() }
            "send" -> scope.launch {
                when (write().outcome) {
                    "ok" -> {
                        apply(PlaylistEditEvent("sent"))
                        readBack()
                        if (state.phase == "idle" && state.message == null) done()
                    }
                    "denied" -> apply(PlaylistEditEvent("denied"))
                    "failed" -> apply(PlaylistEditEvent("failed"))
                    else -> {
                        apply(PlaylistEditEvent("ambiguous"))
                        readBack()
                    }
                }
            }
        }
    }
}

internal class MusicDetailViewModel(
    val account: Account,
    item: MediaItem,
    role: ArtistRole,
    library: String?,
    private val music: MusicService,
    private val accounts: AccountStore,
    private val session: CatalogSession
) : ViewModel() {
    private val mutable = MutableStateFlow(MusicDetailState(account, item, role, library))
    val state: StateFlow<MusicDetailState> = mutable.asStateFlow()
    private var job: Job? = null
    private val editor = PlaylistEditor(account, item.id, music, viewModelScope) { edit, rows ->
        mutable.update { state ->
            state.copy(edit = edit, tracks = rows ?: state.tracks, message = edit.message ?: state.message)
        }
    }

    init {
        reload()
    }

    fun dismissMessage() = mutable.update { it.copy(message = null) }

    fun reload() {
        job?.cancel()
        mutable.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            val before = state.value
            when {
                before.isTrack -> {
                    mutable.update { it.copy(tracks = listOf(before.item), loading = false) }
                }
                before.isPlaylist -> {
                    val rows = editor.load()
                    if (rows == null) {
                        // Reads the error itself so the screen can tell failed from inaccessible.
                        val answer = music.read(account, accounts, musicPlaylistItemsRequest(before.item.id))
                        mutable.update { it.copy(loading = false, error = (answer as? ClientResult.Failure)?.error) }
                    } else {
                        val canEdit = music.playlistCanEdit(account, before.item.id)
                        if (!canEdit) editor.readOnly()
                        mutable.update { it.copy(tracks = rows, canEdit = canEdit, loading = false) }
                    }
                }
                before.isAlbum -> {
                    val (tracks, failure) = pages { start -> musicAlbumTracksRequest(before.item.id, start) }
                    val ordered = orderTracks(tracks)
                    mutable.update { it.copy(tracks = ordered, discs = albumDiscs(ordered), loading = false, error = failure) }
                }
                before.isArtist -> {
                    val albums = music.read(account, accounts, musicArtistAlbumsRequest(before.item.id, before.role, before.library))
                    val tracks = music.read(account, accounts, musicArtistTracksRequest(before.item.id, before.role, before.library))
                    mutable.update {
                        it.copy(
                            albums = (albums as? ClientResult.Success)?.value?.items.orEmpty(),
                            tracks = (tracks as? ClientResult.Success)?.value?.items.orEmpty(),
                            tracksComplete = (tracks as? ClientResult.Success)?.value?.let { page ->
                                page.items.size >= page.totalRecordCount
                            } ?: true,
                            loading = false,
                            error = (albums as? ClientResult.Failure)?.error ?: (tracks as? ClientResult.Failure)?.error
                        )
                    }
                }
                before.isGenre -> {
                    val albums = music.read(account, accounts, musicGenreAlbumsRequest(before.item.id, before.library.orEmpty()))
                    mutable.update {
                        it.copy(
                            albums = (albums as? ClientResult.Success)?.value?.items.orEmpty(),
                            tracksComplete = false,
                            loading = false,
                            error = (albums as? ClientResult.Failure)?.error
                        )
                    }
                }
                else -> mutable.update { it.copy(loading = false) }
            }
        }
    }

    private suspend fun pages(request: (Int) -> MusicRequest): Pair<List<MediaItem>, ClientError?> {
        val rows = mutableListOf<MediaItem>()
        var page = MusicPageState()
        while (!page.ended && rows.size < MUSIC_QUEUE_LIMIT) {
            when (val answer = music.read(account, accounts, request(page.offset))) {
                is ClientResult.Failure -> return rows to answer.error
                is ClientResult.Success -> {
                    page = mergeMusicPage(page, answer.value.items.map(MediaItem::id), answer.value.totalRecordCount)
                    rows += answer.value.items
                }
            }
        }
        return rows to null
    }

    /**
     * Everything Play means on a page that shows only some of it. An artist's first read covers most artists and a
     * genre reads nothing until asked, since a big genre would otherwise cost several requests just to look at it.
     */
    fun allTracks(onReady: (List<MediaItem>) -> Unit) {
        val before = state.value
        if (before.tracksComplete) return onReady(before.tracks)
        if (before.busy) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            val (tracks, failure) = pages { start ->
                if (before.isGenre) {
                    musicGenreTracksRequest(before.item.id, before.library.orEmpty(), start)
                } else {
                    musicArtistTracksRequest(before.item.id, before.role, before.library, start)
                }
            }
            mutable.update {
                it.copy(
                    busy = false,
                    tracks = if (failure == null && before.isArtist) tracks else it.tracks,
                    tracksComplete = failure == null,
                    message = failure?.message
                )
            }
            if (failure == null) onReady(tracks)
        }
    }

    fun instantMix(id: String, onReady: (List<MediaItem>) -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val answer = music.instantMix(account, id)
            val tracks = (answer as? ClientResult.Success)?.value?.items?.let(::playableTracks).orEmpty()
            mutable.update {
                it.copy(
                    busy = false,
                    message = when {
                        answer is ClientResult.Failure -> answer.error.message
                        tracks.isEmpty() -> "The server has no mix for this yet."
                        else -> null
                    }
                )
            }
            if (tracks.isNotEmpty()) onReady(tracks)
        }
    }

    fun favorite(id: String = state.value.item.id) {
        val before = state.value
        val target = if (id == before.item.id) before.item else before.tracks.firstOrNull { it.id == id } ?: return
        val value = !target.userData.isFavorite
        viewModelScope.launch {
            when (val answer = session.call(account) { favorite(account, id, value) }) {
                is ClientResult.Failure -> mutable.update { it.copy(message = answer.error.message) }
                is ClientResult.Success -> mutable.update { current ->
                    current.copy(
                        item = if (id == current.item.id) current.item.copy(userData = answer.value) else current.item,
                        tracks = current.tracks.map { if (it.id == id) it.copy(userData = answer.value) else it }
                    )
                }
            }
        }
    }

    /** The name isn't in the rows, so a rename has its own read-back, of the playlist itself. */
    fun rename(name: String) {
        val clean = name.trim()
        val before = state.value.item
        if (clean.isEmpty() || clean == before.name || state.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val write = music.renamePlaylist(account, before.id, clean)
            val read = if (write.outcome == "ok" || write.outcome == "unknown") {
                (session.call(account) { item(account, before.id) } as? ClientResult.Success)?.value
            } else {
                null
            }
            mutable.update {
                it.copy(
                    busy = false,
                    item = read?.let { fresh -> it.item.copy(name = fresh.name) } ?: it.item,
                    message = when {
                        write.outcome == "denied" -> "The server didn't allow that change."
                        write.outcome == "failed" -> "That change didn't save. Try again."
                        read == null -> "Couldn't read the playlist back. Check it before changing it again."
                        read.name != clean -> "The server didn't apply that change."
                        else -> "Renamed to $clean."
                    }
                )
            }
        }
    }

    fun removeEntry(entry: String) = editor.submit("remove", write = {
        music.removeFromPlaylist(account, state.value.item.id, listOf(entry))
    })

    fun moveEntry(entry: String, index: Int) = editor.submit("move", write = {
        music.movePlaylistEntry(account, state.value.item.id, entry, index)
    })
}

internal data class MusicPlaylistPickerState(
    val playlists: List<MediaItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val done: Boolean = false
)

/** Add to a playlist: nothing is sent twice after a silence, since it could add the songs twice (MUS-09). */
internal class MusicPlaylistPickerViewModel(
    private val account: Account,
    private val items: List<String>,
    private val music: MusicService
) : ViewModel() {
    private val mutable = MutableStateFlow(MusicPlaylistPickerState())
    val state: StateFlow<MusicPlaylistPickerState> = mutable.asStateFlow()

    init {
        load()
    }

    fun load() {
        mutable.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val answer = music.music(account, musicSectionRequest(MusicSection.PLAYLISTS, "", limit = 200))
            mutable.update {
                when (answer) {
                    is ClientResult.Failure -> it.copy(loading = false, error = answer.error.message)
                    is ClientResult.Success -> it.copy(loading = false, playlists = answer.value.items.filter { row -> row.isMusic() })
                }
            }
        }
    }

    fun add(playlist: MediaItem) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val editor = PlaylistEditor(account, playlist.id, music, this) { edit, _ ->
                edit.message?.let { text -> mutable.update { it.copy(message = text) } }
                if (edit.phase != "sending" && edit.phase != "reading") mutable.update { it.copy(busy = false) }
            }
            if (editor.load() == null) {
                mutable.update { it.copy(busy = false, message = "Couldn't read that playlist. Try again.") }
                return@launch
            }
            if (!music.playlistCanEdit(account, playlist.id)) {
                mutable.update { it.copy(busy = false, message = "You can't edit this playlist.") }
                return@launch
            }
            mutable.update { it.copy(busy = true) }
            editor.submit("append", { music.addToPlaylist(account, playlist.id, items) }) {
                mutable.update { it.copy(busy = false, done = true, message = "Added to ${playlist.name}.") }
            }
        }
    }

    fun create(name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || state.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val write = music.createPlaylist(account, clean, items)
            mutable.update {
                when (write.outcome) {
                    "ok" -> it.copy(busy = false, done = true, message = "Created $clean.")
                    "denied" -> it.copy(busy = false, message = "The server didn't allow that change.")
                    "failed" -> it.copy(busy = false, message = "That playlist didn't save. Try again.")
                    else -> it.copy(
                        busy = false,
                        done = true,
                        message = "The server didn't answer, so check Playlists before trying again."
                    )
                }
            }
        }
    }
}

/** What a play button hands the host: unavailable rows are never queued, and the start index follows what remains. */
internal fun Account.musicPlay(
    tracks: List<MediaItem>,
    start: MediaItem?,
    context: String,
    contextName: String,
    shuffle: Boolean = false
): MusicPlay? {
    val playable = playableTracks(tracks)
    if (playable.isEmpty()) return null
    val index = start?.let { chosen ->
        playable.indexOfFirst { it.id == chosen.id && it.playlistItemId == chosen.playlistItemId }
    }?.takeIf {
        it >=
            0
    }
    return MusicPlay(key, playable.map(::musicTrack), index, context, contextName, shuffle)
}

internal fun formatMusicTime(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) / 1000)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}

internal fun musicRepeatLabel(mode: String): String = when (mode) {
    "one" -> "Repeat: this track"
    "all" -> "Repeat: all"
    else -> "Repeat: off"
}

internal val musicQualityOptions = listOf(
    "auto" to "Auto (256 kbps on metered networks)",
    "original" to "Original",
    "256" to "256 kbps",
    "128" to "128 kbps"
)

internal fun musicQualityLabel(value: String): String = musicQualityOptions.firstOrNull { it.first == value }?.second ?: value
