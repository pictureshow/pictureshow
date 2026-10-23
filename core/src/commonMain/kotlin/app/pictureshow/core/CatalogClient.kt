/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

interface CatalogService {
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

    override suspend fun views(account: Account): ClientResult<ItemPage> = request(account, "UserViews")

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

    override suspend fun latest(account: Account, parentId: String): ClientResult<List<MediaItem>> = request(
        account,
        "Items/Latest",
        mapOf("parentId" to parentId, "limit" to "24", "fields" to CARD_FIELDS)
    )

    override suspend fun items(account: Account, query: BrowseQuery): ClientResult<ItemPage> = request(
        account,
        "Items",
        mapOf(
            "parentId" to query.parentId,
            "recursive" to query.recursive.toString(),
            "includeItemTypes" to query.includeItemTypes,
            "sortBy" to query.sortBy,
            "sortOrder" to if (query.descending) "Descending" else "Ascending",
            "startIndex" to query.startIndex.coerceAtLeast(0).toString(),
            "limit" to query.limit.coerceIn(1, 200).toString(),
            "enableTotalRecordCount" to "true",
            "fields" to CARD_FIELDS
        )
    )

    override suspend fun item(account: Account, id: String): ClientResult<MediaItem> {
        val detail = request<MediaItem>(account, "Items/${id.encodeURLPathPart()}")
        if (detail !is ClientResult.Success || detail.value.type !in listOf("Movie", "Episode", "Video")) return detail
        // Jellyfin 12 can project a different UserData key for a single item. Use the same batch
        // projection as Continue watching, scoped to this item (without its 24-item row limit).
        return when (val batch = request<ItemPage>(account, "Items", mapOf("ids" to id))) {
            is ClientResult.Failure -> if (batch.error.kind == ErrorKind.EXPIRED) batch else detail
            is ClientResult.Success -> ClientResult.Success(
                batch.value.items.firstOrNull { it.id == id }?.let { detail.value.copy(userData = it.userData) } ?: detail.value
            )
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
        method: HttpMethod = HttpMethod.Get
    ): ClientResult<T> = try {
        withTimeoutOrNull(ApiTimeouts.REQUEST_MILLIS) {
            val response = client.request("${account.server.url.trimEnd('/')}/$path") {
                this.method = method
                headers.append(HttpHeaders.Authorization, AuthorizationHeader.build(identity, account.accessToken))
                url.parameters.append("userId", account.user.id)
                parameters.forEach { (name, value) -> if (value != null) url.parameters.append(name, value) }
            }
            when (response.status.value) {
                in 200..299 -> ClientResult.Success(json.decodeFromString<T>(response.bodyAsText()))
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
        const val CARD_FIELDS = "Overview,Genres,PrimaryImageAspectRatio"
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
    if (item.seriesId != null && !item.seriesPrimaryImageTag.isNullOrBlank()) {
        return ItemImage(item.seriesId, "Primary", item.seriesPrimaryImageTag)
    }
    return backdrop() ?: own("Thumb")
}
