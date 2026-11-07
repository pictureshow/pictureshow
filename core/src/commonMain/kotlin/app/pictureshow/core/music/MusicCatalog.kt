/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.music

import app.pictureshow.core.ClientError
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.MediaItem
import kotlinx.serialization.Serializable

/** The browse tabs of a music library, in the order MUS-01 names them. */
enum class MusicSection(val title: String) {
    ALBUMS("Albums"),
    ALBUM_ARTISTS("Album artists"),
    ARTISTS("Artists"),
    SONGS("Songs"),
    GENRES("Genres"),
    PLAYLISTS("Playlists"),
    FAVORITES("Favorites")
}

/** The credit an artist page reads: album artist and contributing artist stay distinct (MUS-01). */
enum class ArtistRole { ALBUM_ARTIST, ARTIST }

@Serializable
data class MusicRequest(val path: String, val parameters: Map<String, String>)

const val MUSIC_PAGE = 60
private const val MUSIC_CARD_FIELDS = "PrimaryImageAspectRatio,ProductionYear,Genres,ChildCount,RecursiveItemCount,SortName"
private const val MUSIC_TRACK_REQUEST_FIELDS = "$MUSIC_CARD_FIELDS,ParentId,MediaSources"
private const val TRACK_ORDER = "ParentIndexNumber,IndexNumber,SortName"

private fun paged(start: Int, limit: Int) = mapOf(
    "startIndex" to start.coerceAtLeast(0).toString(),
    "limit" to limit.coerceIn(1, 200).toString(),
    "enableTotalRecordCount" to "true"
)

private fun sorted(sortBy: String, descending: Boolean) = mapOf(
    "sortBy" to sortBy,
    "sortOrder" to if (descending) "Descending" else "Ascending"
)

/** Every list is scoped to its library. Playlists aren't library children, so they are scoped to audio instead. */
fun musicSectionRequest(
    section: MusicSection,
    library: String,
    sortBy: String = "SortName",
    descending: Boolean = false,
    start: Int = 0,
    limit: Int = MUSIC_PAGE
): MusicRequest {
    val common = paged(start, limit) + sorted(sortBy, descending) + ("fields" to MUSIC_CARD_FIELDS)
    fun items(type: String, extra: Map<String, String> = emptyMap()) =
        MusicRequest("Items", common + mapOf("parentId" to library, "recursive" to "true", "includeItemTypes" to type) + extra)
    return when (section) {
        MusicSection.ALBUMS -> items("MusicAlbum")
        MusicSection.ALBUM_ARTISTS -> MusicRequest("Artists/AlbumArtists", common + ("parentId" to library))
        MusicSection.ARTISTS -> MusicRequest("Artists", common + ("parentId" to library))
        MusicSection.SONGS -> items("Audio", mapOf("fields" to MUSIC_TRACK_REQUEST_FIELDS))
        MusicSection.GENRES -> MusicRequest("Genres", common + ("parentId" to library))
        MusicSection.PLAYLISTS -> MusicRequest(
            "Items",
            common + mapOf("recursive" to "true", "includeItemTypes" to "Playlist", "mediaTypes" to "Audio")
        )
        MusicSection.FAVORITES -> items("MusicAlbum,Audio", mapOf("filters" to "IsFavorite", "fields" to MUSIC_TRACK_REQUEST_FIELDS))
    }
}

/** Favorite artists aren't children of a library in /Items, so the Favorites tab reads them first from /Artists. */
fun musicFavoriteArtistsRequest(library: String, start: Int = 0, limit: Int = MUSIC_PAGE) = MusicRequest(
    "Artists",
    paged(start, limit) + sorted("SortName", false) +
        mapOf("parentId" to library, "isFavorite" to "true", "fields" to MUSIC_CARD_FIELDS)
)

fun musicAlbumTracksRequest(album: String, start: Int = 0, limit: Int = 200) = MusicRequest(
    "Items",
    paged(start, limit) + sorted(TRACK_ORDER, false) +
        mapOf("parentId" to album, "recursive" to "true", "includeItemTypes" to "Audio", "fields" to MUSIC_TRACK_REQUEST_FIELDS)
)

fun musicArtistAlbumsRequest(artist: String, role: ArtistRole, library: String?, start: Int = 0, limit: Int = MUSIC_PAGE) = MusicRequest(
    "Items",
    paged(start, limit) + sorted("ProductionYear,SortName", true) + buildMap {
        put(if (role == ArtistRole.ALBUM_ARTIST) "albumArtistIds" else "contributingArtistIds", artist)
        put("recursive", "true")
        put("includeItemTypes", "MusicAlbum")
        put("fields", MUSIC_CARD_FIELDS)
        library?.let { put("parentId", it) }
    }
)

fun musicArtistTracksRequest(artist: String, role: ArtistRole, library: String?, start: Int = 0, limit: Int = 200) = MusicRequest(
    "Items",
    paged(start, limit) + sorted("Album,$TRACK_ORDER", false) + buildMap {
        put(if (role == ArtistRole.ALBUM_ARTIST) "albumArtistIds" else "artistIds", artist)
        put("recursive", "true")
        put("includeItemTypes", "Audio")
        put("fields", MUSIC_TRACK_REQUEST_FIELDS)
        library?.let { put("parentId", it) }
    }
)

fun musicGenreAlbumsRequest(genre: String, library: String, start: Int = 0, limit: Int = MUSIC_PAGE) = MusicRequest(
    "Items",
    paged(start, limit) + sorted("SortName", false) +
        mapOf(
            "parentId" to library,
            "genreIds" to genre,
            "recursive" to "true",
            "includeItemTypes" to "MusicAlbum",
            "fields" to MUSIC_CARD_FIELDS
        )
)

fun musicGenreTracksRequest(genre: String, library: String, start: Int = 0, limit: Int = 200) = MusicRequest(
    "Items",
    paged(start, limit) + sorted("Album,$TRACK_ORDER", false) +
        mapOf(
            "parentId" to library,
            "genreIds" to genre,
            "recursive" to "true",
            "includeItemTypes" to "Audio",
            "fields" to MUSIC_TRACK_REQUEST_FIELDS
        )
)

fun musicPlaylistItemsRequest(playlist: String, start: Int = 0, limit: Int = 200) = MusicRequest(
    "Playlists/$playlist/Items",
    paged(start, limit) + ("fields" to MUSIC_TRACK_REQUEST_FIELDS)
)

fun musicInstantMixRequest(item: String, limit: Int = 100) = MusicRequest(
    "Items/$item/InstantMix",
    mapOf("limit" to limit.coerceIn(1, 200).toString(), "fields" to MUSIC_TRACK_REQUEST_FIELDS)
)

/** Shuffle all of a tab is the server's own random pick: the client never pages a whole library to shuffle it. */
fun musicRandomSongsRequest(library: String, limit: Int = MUSIC_RANDOM_LIMIT) = MusicRequest(
    "Items",
    paged(0, limit) + mapOf(
        "parentId" to library,
        "recursive" to "true",
        "includeItemTypes" to "Audio",
        "sortBy" to "Random",
        "fields" to MUSIC_TRACK_REQUEST_FIELDS
    )
)

const val MUSIC_RANDOM_LIMIT = 200

/** Most a queue holds. A context longer than this plays its first entries, and says so. */
const val MUSIC_QUEUE_LIMIT = 2000

/** Disc, then track, then name, then identity, so missing and duplicate tags still give one stable order (MUS-02). */
fun orderTracks(tracks: List<MediaItem>): List<MediaItem> = tracks.sortedWith(
    compareBy<MediaItem> { it.parentIndexNumber ?: 1 }
        .thenBy { it.indexNumber == null }
        .thenBy { it.indexNumber ?: 0 }
        .thenBy { (it.sortName ?: it.name).lowercase() }
        .thenBy { it.id }
)

@Serializable
data class MusicDisc(val disc: Int?, val tracks: List<String>)

/** Grouped by disc only when there's more than one. Nothing here reads an artist, so a compilation stays one album. */
fun albumDiscs(tracks: List<MediaItem>): List<MusicDisc> {
    val ordered = orderTracks(tracks)
    val discs = ordered.groupBy { it.parentIndexNumber ?: 1 }
    if (discs.size < 2) return if (ordered.isEmpty()) emptyList() else listOf(MusicDisc(null, ordered.map { it.id }))
    return discs.map { (disc, list) -> MusicDisc(disc, list.map { it.id }) }
}

/** A track's own credit shows when it differs from its album's, which is every track of a compilation. */
fun trackArtistLine(track: MediaItem, albumArtist: String? = null): String? {
    val names = track.artistItems.map { it.name }.filter(String::isNotBlank)
    val line = names.joinToString(", ").ifBlank { track.albumArtist.orEmpty() }
    return line.takeIf { it.isNotBlank() && it != albumArtist }
}

fun trackIsAvailable(track: MediaItem): Boolean = track.locationType != "Virtual"

/** The album's credit on a card or detail: named credits first, then the plain field. */
fun albumArtistLine(album: MediaItem): String =
    album.albumArtists.map { it.name }.filter(String::isNotBlank).joinToString(", ").ifBlank { album.albumArtist.orEmpty() }

@Serializable
data class MusicPageState(val ids: List<String> = emptyList(), val offset: Int = 0, val total: Int = 0, val ended: Boolean = false)

/**
 * Offset moves by what the server sent, and a short page isn't the end: only the total or an empty page is. A row
 * the server sends twice while its list shifts shows once.
 */
fun mergeMusicPage(state: MusicPageState, page: List<String>, total: Int): MusicPageState {
    val offset = state.offset + page.size
    return MusicPageState(
        ids = (state.ids + page).distinct(),
        offset = offset,
        total = total,
        ended = page.isEmpty() || offset >= total
    )
}

/** MUS-01: these read differently and offer different things. */
enum class MusicListState { LOADING, READY, EMPTY, INACCESSIBLE, FAILED, EXPIRED }

fun musicListState(loading: Boolean, items: Int, error: ClientError?): MusicListState = when {
    items > 0 -> MusicListState.READY
    error?.kind == ErrorKind.EXPIRED -> MusicListState.EXPIRED
    error?.status == 403 || error?.status == 404 -> MusicListState.INACCESSIBLE
    error != null -> MusicListState.FAILED
    loading -> MusicListState.LOADING
    else -> MusicListState.EMPTY
}

/** Nothing to play has no Play: an album whose tracks are all missing isn't a playable container (MUS-02). */
fun playableTracks(tracks: List<MediaItem>): List<MediaItem> = tracks.filter(::trackIsAvailable)
