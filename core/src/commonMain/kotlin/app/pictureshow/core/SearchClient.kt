/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import io.ktor.client.HttpClient

const val SEARCH_QUERY_LIMIT = 200
fun normalizeSearch(query: String): String = query.trim().take(SEARCH_QUERY_LIMIT).trimEnd()

enum class SearchKind(val itemType: String, val title: String) {
    MOVIES("Movie", "Movies"),
    SHOWS("Series", "Shows"),
    EPISODES("Episode", "Episodes"),
    COLLECTIONS("BoxSet", "Collections"),
    PEOPLE("Person", "People"),

    // Music groups come after every video group, so a video library's results keep their order.
    ALBUMS("MusicAlbum", "Albums"),
    ARTISTS("MusicArtist", "Artists"),
    SONGS("Audio", "Songs"),
    PLAYLISTS("Playlist", "Playlists")
}

interface SearchService {
    suspend fun search(account: Account, query: String, kind: SearchKind): ClientResult<ItemPage>

    suspend fun personItems(account: Account, personId: String, startIndex: Int = 0, limit: Int = 60): ClientResult<ItemPage>
}

/** Separate queries prevent music and high-volume types from crowding video results out of the limit. */
internal class SearchClient(client: HttpClient, identity: ClientIdentity) : SearchService {
    private val transport = CatalogClient(client, identity)

    override suspend fun search(account: Account, query: String, kind: SearchKind): ClientResult<ItemPage> {
        val term = normalizeSearch(query)
        if (term.length < 2) return ClientResult.Success(ItemPage())
        val parameters = mutableMapOf("searchTerm" to term, "limit" to "24")
        if (kind == SearchKind.ARTISTS) {
            parameters += mapOf("enableTotalRecordCount" to "true", "fields" to SEARCH_CARD_FIELDS)
        } else if (kind != SearchKind.PEOPLE) {
            parameters += mapOf(
                "recursive" to "true",
                "includeItemTypes" to kind.itemType,
                "enableTotalRecordCount" to "true",
                "fields" to if (kind == SearchKind.SONGS) "$SEARCH_CARD_FIELDS,$MUSIC_TRACK_FIELDS" else SEARCH_CARD_FIELDS
            )
            if (kind == SearchKind.PLAYLISTS) parameters += "mediaTypes" to "Audio"
        }
        // Artists aren't library items: /Items searches them badly, and /Artists is where the browse tabs read them.
        val path = when (kind) {
            SearchKind.PEOPLE -> "Persons"
            SearchKind.ARTISTS -> "Artists"
            else -> "Items"
        }
        return transport.request(account, path, parameters)
    }

    override suspend fun personItems(account: Account, personId: String, startIndex: Int, limit: Int): ClientResult<ItemPage> =
        transport.request(
            account,
            "Items",
            mapOf(
                "personIds" to personId,
                "recursive" to "true",
                "includeItemTypes" to "Movie,Series",
                "sortBy" to "ProductionYear,SortName",
                "sortOrder" to "Descending",
                "startIndex" to startIndex.coerceAtLeast(0).toString(),
                "limit" to limit.coerceIn(1, 200).toString(),
                "enableTotalRecordCount" to "true",
                "fields" to SEARCH_CARD_FIELDS
            )
        )
}

internal const val MUSIC_TRACK_FIELDS = "SortName,ParentId"
internal const val SEARCH_CARD_FIELDS = "PrimaryImageAspectRatio,ProductionYear,Overview,Genres,ChildCount,RecursiveItemCount"

/** What a search card shows, for [searchDuplicateLabels]. */
data class SearchLabelItem(
    val id: String,
    val type: String? = null,
    val name: String? = null,
    val year: Int? = null,
    val seriesName: String? = null
)

/**
 * Which search results need their library named, because another result of the same kind shows the same card text: the
 * same title, year and (for episodes) series. Two copies of one film in two libraries are otherwise identical cards
 * (CAT-14a). People are never labelled with a library. Returned in input order.
 */
fun searchDuplicateLabels(items: List<SearchLabelItem>): List<String> {
    fun norm(s: String?) = (s ?: "").trim().replace(Regex("\\s+"), " ").lowercase()
    fun key(i: SearchLabelItem) = listOf(i.type ?: "", norm(i.name), i.year?.toString() ?: "", norm(i.seriesName)).joinToString("\u0000")
    val counts = items.filter { it.type != "Person" && norm(it.name).isNotEmpty() }.groupingBy(::key).eachCount()
    return items.filter { (counts[key(it)] ?: 0) > 1 }.map { it.id }
}
