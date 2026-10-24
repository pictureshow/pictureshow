/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import io.ktor.client.HttpClient
import io.ktor.http.URLBuilder
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Optional Jellyfin playback metadata, independent of playback negotiation and device profiles. */
class PlaybackExtrasClient(client: HttpClient, identity: ClientIdentity, private val diagnostic: (String) -> Unit = {}) {
    private val catalog = CatalogClient(client, identity)

    suspend fun item(account: Account, itemId: String): ClientResult<MediaItem> = catalog.request(
        account,
        "Items/${itemId.encodeURLPathPart()}",
        mapOf("fields" to "Chapters,Trickplay")
    )

    suspend fun segments(account: Account, itemId: String): ClientResult<List<MediaSegment>> =
        when (val result = catalog.request<SegmentPage>(account, "MediaSegments/${itemId.encodeURLPathPart()}")) {
            is ClientResult.Success -> ClientResult.Success(result.value.items)
            is ClientResult.Failure -> result
        }

    /**
     * Ask for available episodes before the server computes adjacency. Jellyfin includes missing
     * placeholders for API keys and users who display missing episodes; those cannot be played.
     * Keep the server's aired order, including inserted specials and season boundaries.
     */
    suspend fun nextEpisode(account: Account, item: MediaItem): ClientResult<MediaItem?> {
        if (item.type != "Episode" || item.seriesId.isNullOrBlank()) return ClientResult.Success(null)
        val path = "Shows/${item.seriesId.encodeURLPathPart()}/Episodes"
        val parameters = mapOf(
            "adjacentTo" to item.id,
            "isMissing" to "false",
            "enableUserData" to "true",
            "fields" to "Overview"
        )
        // Log only this endpoint's path/query and episode IDs. Omit the host and automatic userId.
        val request = "$path?" + parameters.entries.joinToString("&") { (key, value) -> "$key=${value.encodeURLParameter()}" }
        return when (val result = catalog.request<ItemPage>(account, path, parameters)) {
            is ClientResult.Failure -> {
                diagnostic(
                    "nextEpisode current=${item.id.encodeURLParameter()} request=GET $request " +
                        "returned=unavailable chosen=none error=${result.error.kind}"
                )
                result
            }
            is ClientResult.Success -> {
                val items = result.value.items
                val index = items.indexOfFirst { it.id == item.id }
                val next = if (index >= 0) {
                    items.getOrNull(index + 1)?.takeIf { it.id != item.id && it.type == "Episode" }
                } else {
                    null
                }
                val returned = items.joinToString(",", "[", "]") { it.id.encodeURLParameter() }
                diagnostic(
                    "nextEpisode current=${item.id.encodeURLParameter()} request=GET $request " +
                        "returned=$returned chosen=${next?.id?.encodeURLParameter() ?: "none"}"
                )
                ClientResult.Success(next)
            }
        }
    }
}

@Serializable
private data class SegmentPage(@SerialName("Items") val items: List<MediaSegment> = emptyList())

fun trickplaySheetUrl(server: Server, itemId: String, mediaSourceId: String, width: Int, sheet: Int): String =
    URLBuilder("${server.url.trimEnd('/')}/Videos/${itemId.encodeURLPathPart()}/Trickplay/$width/$sheet.jpg").apply {
        parameters.append("mediaSourceId", mediaSourceId)
    }.buildString()

fun chapterImageUrl(server: Server, itemId: String, index: Int, tag: String): String =
    URLBuilder("${server.url.trimEnd('/')}/Items/${itemId.encodeURLPathPart()}/Images/Chapter/$index").apply {
        parameters.append("tag", tag)
        parameters.append("maxWidth", "320")
    }.buildString()
