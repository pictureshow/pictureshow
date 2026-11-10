/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

interface CatalogService {
    suspend fun displayPreferences(account: Account): ClientResult<JsonObject> =
        ClientResult.Failure(ClientError(ErrorKind.SERVER, "Server display preferences are unavailable."))

    suspend fun saveDisplayPreferences(account: Account, document: JsonObject): ClientResult<Unit> =
        ClientResult.Failure(ClientError(ErrorKind.SERVER, "Server display preferences are unavailable."))

    suspend fun facets(account: Account, parentId: String): ClientResult<LibraryFacets> = ClientResult.Success(LibraryFacets())

    suspend fun namedItems(
        account: Account,
        kind: String,
        parentId: String = "",
        term: String = "",
        startIndex: Int = 0
    ): ClientResult<ItemPage> = ClientResult.Success(ItemPage())

    suspend fun views(account: Account): ClientResult<ItemPage>

    suspend fun resume(account: Account): ClientResult<ItemPage>

    suspend fun nextUp(account: Account, seriesId: String? = null): ClientResult<ItemPage>

    suspend fun latest(account: Account, parentId: String): ClientResult<List<MediaItem>>

    suspend fun items(account: Account, query: BrowseQuery): ClientResult<ItemPage>

    suspend fun item(account: Account, id: String): ClientResult<MediaItem>

    suspend fun seasons(account: Account, seriesId: String): ClientResult<ItemPage>

    suspend fun episodes(account: Account, seriesId: String, seasonId: String): ClientResult<ItemPage>

    suspend fun played(account: Account, id: String, value: Boolean): ClientResult<ItemUserData>

    suspend fun favorite(account: Account, id: String, value: Boolean): ClientResult<ItemUserData>
}

internal class CatalogClient(private val client: HttpClient, private val identity: ClientIdentity) : CatalogService {
    // Jellyfin's optional DTO properties may be omitted or explicitly null.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override suspend fun displayPreferences(account: Account): ClientResult<JsonObject> =
        request(account, "DisplayPreferences/usersettings", mapOf("client" to "pictureshow"))

    override suspend fun saveDisplayPreferences(account: Account, document: JsonObject): ClientResult<Unit> =
        request(account, "DisplayPreferences/usersettings", mapOf("client" to "pictureshow"), HttpMethod.Post, document.toString())

    override suspend fun facets(account: Account, parentId: String): ClientResult<LibraryFacets> {
        // Metadata endpoints can enumerate a denied parent on Jellyfin 12. Check the item route first.
        val parent = request<MediaItem>(account, "Items/${parentId.encodeURLPathPart()}")
        if (parent is ClientResult.Failure) return parent
        return request(account, "Items/Filters", mapOf("parentId" to parentId))
    }

    override suspend fun namedItems(
        account: Account,
        kind: String,
        parentId: String,
        term: String,
        startIndex: Int
    ): ClientResult<ItemPage> {
        if (parentId.isNotBlank()) {
            val parent = request<MediaItem>(account, "Items/${parentId.encodeURLPathPart()}")
            if (parent is ClientResult.Failure) return parent
        }
        require(kind in setOf("Genres", "Studios", "Persons", "BoxSet", "Playlist"))
        val path = if (kind in setOf("BoxSet", "Playlist")) "Items" else kind
        return request(
            account,
            path,
            mapOf(
                "parentId" to parentId.takeIf { it.isNotBlank() },
                "searchTerm" to term.takeIf { it.isNotBlank() },
                "startIndex" to startIndex.coerceAtLeast(0).toString(),
                "limit" to "60",
                "recursive" to "true",
                "includeItemTypes" to kind.takeIf { path == "Items" },
                "sortBy" to "SortName"
            )
        )
    }

    override suspend fun views(account: Account): ClientResult<ItemPage> = request(account, "UserViews", mapOf("fields" to CARD_FIELDS))

    override suspend fun resume(account: Account): ClientResult<ItemPage> = request(
        account,
        "UserItems/Resume",
        mapOf("mediaTypes" to "Video", "limit" to "24", "fields" to CARD_FIELDS)
    )

    override suspend fun nextUp(account: Account, seriesId: String?): ClientResult<ItemPage> = request(
        account,
        "Shows/NextUp",
        mapOf("limit" to "24", "fields" to CARD_FIELDS, "seriesId" to seriesId)
    )

    override suspend fun latest(account: Account, parentId: String): ClientResult<List<MediaItem>> = when (
        // /Items/Latest can return children from an inaccessible parent on Jellyfin 12.
        // The ordinary browse query enforces parent access, tags and rating policy.
        val result =
            items(
                account,
                BrowseQuery(
                    parentId,
                    includeItemTypes = "Movie,Series",
                    recursive = true,
                    sortBy = "DateCreated",
                    descending = true,
                    limit = 24
                )
            )
    ) {
        is ClientResult.Success -> ClientResult.Success(result.value.items)
        is ClientResult.Failure -> result
    }

    override suspend fun items(account: Account, query: BrowseQuery): ClientResult<ItemPage> = request(
        account,
        "Items",
        if (query.ids.isNotEmpty()) {
            mapOf(
                "ids" to query.ids.joinToString(","),
                "limit" to query.limit.coerceIn(1, 200).toString(),
                "enableUserData" to "true",
                "fields" to "ChildCount,RecursiveItemCount"
            )
        } else {
            mapOf(
                "parentId" to query.parentId.takeIf { it.isNotBlank() },
                "recursive" to query.recursive.toString(),
                "includeItemTypes" to query.includeItemTypes,
                "sortBy" to query.sortBy,
                "sortOrder" to if (query.descending) "Descending" else "Ascending",
                "startIndex" to query.startIndex.coerceAtLeast(0).toString(),
                "limit" to query.limit.coerceIn(1, 200).toString(),
                "enableTotalRecordCount" to "true",
                "fields" to CARD_FIELDS
            ) + query.filters.parameters()
        }
    )

    override suspend fun item(account: Account, id: String): ClientResult<MediaItem> {
        val detail = request<MediaItem>(account, "Items/${id.encodeURLPathPart()}")
        if (detail !is ClientResult.Success ||
            detail.value.type !in
            listOf(
                "Movie", "Episode", "Video", "Series", "Season", "BoxSet", "Playlist", "Folder", "CollectionFolder",
                "MusicAlbum", "Audio"
            )
        ) {
            return detail
        }
        // Jellyfin 12 can project a different UserData key for a single item. Use the same batch
        // projection as catalog grids and Continue watching, including parent counts.
        // Scope it to this item without a row limit; keep the full detail metadata.
        return when (val batch = request<ItemPage>(account, "Items", mapOf("ids" to id))) {
            // Playable resume/watched state is just as sensitive as parent counts. The primary
            // projection may be ambiguous, so any failed batch read must retain the Retry barrier.
            is ClientResult.Failure -> batch
            is ClientResult.Success -> batch.value.items.firstOrNull { it.id == id }?.let {
                ClientResult.Success(detail.value.copy(userData = it.userData))
            } ?: catalogFailure(ErrorKind.INVALID_RESPONSE, "The server did not return this item's current state. Try again.")
        }
    }

    override suspend fun seasons(account: Account, seriesId: String): ClientResult<ItemPage> = request(
        account,
        "Shows/${seriesId.encodeURLPathPart()}/Seasons",
        mapOf("fields" to CARD_FIELDS)
    )

    override suspend fun episodes(account: Account, seriesId: String, seasonId: String): ClientResult<ItemPage> = request(
        account,
        "Shows/${seriesId.encodeURLPathPart()}/Episodes",
        mapOf("seasonId" to seasonId, "fields" to CARD_FIELDS)
    )

    override suspend fun played(account: Account, id: String, value: Boolean): ClientResult<ItemUserData> = request(
        account,
        "UserPlayedItems/${id.encodeURLPathPart()}",
        method = if (value) HttpMethod.Post else HttpMethod.Delete
    )

    override suspend fun favorite(account: Account, id: String, value: Boolean): ClientResult<ItemUserData> = request(
        account,
        "UserFavoriteItems/${id.encodeURLPathPart()}",
        method = if (value) HttpMethod.Post else HttpMethod.Delete
    )

    internal suspend inline fun <reified T> request(
        account: Account,
        path: String,
        parameters: Map<String, String?> = emptyMap(),
        method: HttpMethod = HttpMethod.Get,
        body: String? = null
    ): ClientResult<T> = try {
        withTimeoutOrNull(ApiTimeouts.REQUEST_MILLIS) {
            val response = client.request("${account.server.url.trimEnd('/')}/$path") {
                this.method = method
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                headers.append(HttpHeaders.Authorization, AuthorizationHeader.build(identity.forAccount(account), account.accessToken))
                url.parameters.append("userId", account.user.id)
                parameters.forEach { (name, value) -> if (value != null) url.parameters.append(name, value) }
            }
            when (response.status.value) {
                in 200..299 -> ClientResult.Success(
                    if (T::class == Unit::class) Unit as T else json.decodeFromString<T>(response.bodyAsText())
                )
                401 -> catalogFailure(ErrorKind.EXPIRED, "Your session has expired. Sign in again.")
                else -> catalogFailure(ErrorKind.SERVER, "The server could not complete the request (${response.status.value}).")
            }
        } ?: catalogFailure(ErrorKind.NETWORK, "The server took too long to respond. Try again.")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SerializationException) {
        catalogFailure(ErrorKind.INVALID_RESPONSE, "The server returned an unreadable response.")
    } catch (_: IllegalArgumentException) {
        catalogFailure(ErrorKind.INVALID_RESPONSE, "The server returned an incomplete response.")
    } catch (_: Exception) {
        catalogFailure(ErrorKind.NETWORK, "Could not reach the server. Check your connection and try again.")
    }

    private companion object {
        const val CARD_FIELDS = "Overview,Genres,PrimaryImageAspectRatio,ChildCount,RecursiveItemCount"
    }
}

private fun catalogFailure(kind: ErrorKind, message: String) = ClientResult.Failure(ClientError(kind, message))

/** Image GET is public in the server API; no access token is put in URLs or image cache keys. */
fun imageUrl(server: Server, item: MediaItem, width: Int, height: Int? = null, wide: Boolean = false): String? {
    val image = chooseImage(item, wide) ?: return null
    return URLBuilder("${server.url.trimEnd('/')}/Items/${image.id.encodeURLPathPart()}/Images/${image.type}").apply {
        parameters.append("tag", image.tag)
        parameters.append("maxWidth", width.coerceAtLeast(1).toString())
        if (height != null) parameters.append("maxHeight", height.coerceAtLeast(1).toString())
        parameters.append("quality", "85")
    }.buildString()
}

private data class ItemImage(val id: String, val type: String, val tag: String)

private fun chooseImage(item: MediaItem, wide: Boolean): ItemImage? {
    fun own(type: String) = item.imageTags[type]?.takeIf(String::isNotBlank)?.let { ItemImage(item.id, type, it) }
    fun backdrop(): ItemImage? {
        val ownTag = item.backdropImageTags.firstOrNull()?.takeIf(String::isNotBlank)
        if (ownTag != null) return ItemImage(item.id, "Backdrop", ownTag)
        val parent = item.parentBackdropItemId
        val tag = item.parentBackdropImageTags.firstOrNull()?.takeIf(String::isNotBlank)
        return if (parent != null && tag != null) ItemImage(parent, "Backdrop", tag) else null
    }
    fun parentThumb(): ItemImage? {
        val id = item.parentThumbItemId
        val tag = item.parentThumbImageTag?.takeIf(String::isNotBlank)
        return if (id != null && tag != null) ItemImage(id, "Thumb", tag) else null
    }
    val wideCard = wide || item.type == "Episode"
    if (wideCard) {
        val ownImage = own("Thumb") ?: if (item.type == "Episode") own("Primary") else null
        return ownImage ?: backdrop() ?: parentThumb() ?: own("Primary")
    }
    val primary = own("Primary")
    if (primary != null) return primary
    // A track usually has no art of its own and shows its album's.
    if (!item.albumId.isNullOrBlank() && !item.albumPrimaryImageTag.isNullOrBlank()) {
        return ItemImage(item.albumId, "Primary", item.albumPrimaryImageTag)
    }
    if (item.seriesId != null && !item.seriesPrimaryImageTag.isNullOrBlank()) {
        return ItemImage(item.seriesId, "Primary", item.seriesPrimaryImageTag)
    }
    return backdrop() ?: own("Thumb")
}
