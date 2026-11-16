/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.subtitles

import app.pictureshow.core.Account
import app.pictureshow.core.ApiTimeouts
import app.pictureshow.core.AuthorizationHeader
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.playback.MediaStream
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the server says about this viewer before a search. */
data class RemoteSubtitleViewer(val access: RemoteSubtitleAccess, val subtitleLanguage: String? = null, val audioLanguage: String? = null)

/** A server answer, or the HTTP status that refused it (null: the server wasn't reached or answered nonsense). */
sealed interface SubtitleCall<out T> {
    data class Ok<T>(val value: T) : SubtitleCall<T>

    data class Failed(val status: Int?) : SubtitleCall<Nothing>
}

/** SUB-DL wire calls. Search can take a while: the provider is asked live, so it gets the long timeout. */
class RemoteSubtitleClient(private val client: HttpClient, private val identity: ClientIdentity) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * SUB-DL-01: decide before searching; reading permission and providers needs no admin rights. Also returns the
     * viewer's server-side language preferences, which choose the first search language (SUB-DL-02).
     */
    suspend fun access(account: Account, itemType: String?, playable: Boolean): RemoteSubtitleViewer {
        if (!playable) return RemoteSubtitleViewer(RemoteSubtitleAccess.Unavailable)
        val me = (call<Me>(account, "Users/Me") as? SubtitleCall.Ok)?.value
        val contentType = if (itemType == "Episode") "tvshows" else "movies"
        val options = (
            call<LibraryOptions>(account, "Libraries/AvailableOptions?libraryContentType=$contentType") as? SubtitleCall.Ok
            )?.value
        return RemoteSubtitleViewer(
            RemoteSubtitlePolicy.access(
                true,
                me?.policy?.isAdministrator,
                me?.policy?.enableSubtitleManagement,
                options?.subtitleFetchers?.map { it.name }
            ),
            me?.configuration?.subtitleLanguage?.takeIf { it.isNotBlank() },
            me?.configuration?.audioLanguage?.takeIf { it.isNotBlank() }
        )
    }

    suspend fun cultures(account: Account): List<SubtitleCulture> =
        (call<List<SubtitleCulture>>(account, "Localization/Cultures") as? SubtitleCall.Ok)?.value.orEmpty()

    suspend fun search(account: Account, itemId: String, language: String): SubtitleCall<List<RemoteSubtitle>> {
        val result = call<List<RemoteSubtitle>>(
            account,
            "Items/${itemId.encodeURLPathPart()}/RemoteSearch/Subtitles/${language.encodeURLPathPart()}",
            timeoutMs = SEARCH_MILLIS
        )
        return when (result) {
            is SubtitleCall.Ok -> SubtitleCall.Ok(RemoteSubtitlePolicy.order(result.value))
            is SubtitleCall.Failed -> result
        }
    }

    /** The server saves the file and queues a refresh of the item; the new stream shows up a moment later. */
    suspend fun download(account: Account, itemId: String, subtitleId: String): SubtitleCall<Unit> = call(
        account,
        "Items/${itemId.encodeURLPathPart()}/RemoteSearch/Subtitles/${subtitleId.encodeURLPathPart()}",
        method = HttpMethod.Post,
        timeoutMs = SEARCH_MILLIS
    )

    /** The item's streams for [mediaSourceId] as the server lists them now. */
    suspend fun streams(account: Account, itemId: String, mediaSourceId: String?): List<MediaStream>? {
        val path = "Items?ids=${itemId.encodeURLPathPart()}&fields=MediaSources&enableImages=false&enableUserData=false"
        val item = (call<ItemsPage>(account, path) as? SubtitleCall.Ok)?.value?.items?.firstOrNull() ?: return null
        val source = item.mediaSources.firstOrNull { it.id == mediaSourceId } ?: item.mediaSources.firstOrNull()
        return source?.mediaStreams ?: item.mediaStreams
    }

    /**
     * SUB-DL-04: wait for the refreshed item to list the download, up to [timeoutMs]. Null if it never did, which the
     * panel reports instead of switching to a track that isn't there.
     */
    suspend fun awaitAdded(
        account: Account,
        itemId: String,
        mediaSourceId: String?,
        before: List<MediaStream>,
        selectedAudio: Int,
        result: RemoteSubtitle,
        timeoutMs: Long = 30_000,
        intervalMs: Long = 1_000
    ): AdoptedSubtitle? = withTimeoutOrNull(timeoutMs) {
        var adopted: AdoptedSubtitle? = null
        while (adopted == null) {
            adopted = streams(account, itemId, mediaSourceId)?.let { after ->
                RemoteSubtitlePolicy.adopt(before, after, selectedAudio, result.language, result.format)
            }
            if (adopted == null) delay(intervalMs)
        }
        adopted
    }

    private suspend inline fun <reified T> call(
        account: Account,
        path: String,
        method: HttpMethod = HttpMethod.Get,
        timeoutMs: Long = ApiTimeouts.REQUEST_MILLIS
    ): SubtitleCall<T> = try {
        withTimeoutOrNull(timeoutMs) {
            val response = client.request("${account.server.url.trimEnd('/')}/$path") {
                this.method = method
                headers.append(HttpHeaders.Authorization, AuthorizationHeader.build(identity.forAccount(account), account.accessToken))
            }
            if (response.status.value in 200..299) {
                SubtitleCall.Ok(if (T::class == Unit::class) Unit as T else json.decodeFromString<T>(response.bodyAsText()))
            } else {
                SubtitleCall.Failed(response.status.value)
            }
        } ?: SubtitleCall.Failed(null)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        SubtitleCall.Failed(null)
    }

    @Serializable
    private data class Me(
        @SerialName("Policy") val policy: Policy? = null,
        @SerialName("Configuration") val configuration: Configuration? = null
    )

    @Serializable
    private data class Configuration(
        @SerialName("SubtitleLanguagePreference") val subtitleLanguage: String? = null,
        @SerialName("AudioLanguagePreference") val audioLanguage: String? = null
    )

    @Serializable
    private data class Policy(
        @SerialName("IsAdministrator") val isAdministrator: Boolean? = null,
        @SerialName("EnableSubtitleManagement") val enableSubtitleManagement: Boolean? = null
    )

    @Serializable
    private data class LibraryOptions(@SerialName("SubtitleFetchers") val subtitleFetchers: List<Named>? = null)

    @Serializable
    private data class Named(@SerialName("Name") val name: String = "")

    @Serializable
    private data class ItemsPage(@SerialName("Items") val items: List<StreamsItem> = emptyList())

    @Serializable
    private data class StreamsItem(
        @SerialName("MediaSources") val mediaSources: List<StreamsSource> = emptyList(),
        @SerialName("MediaStreams") val mediaStreams: List<MediaStream>? = null
    )

    @Serializable
    private data class StreamsSource(
        @SerialName("Id") val id: String? = null,
        @SerialName("MediaStreams") val mediaStreams: List<MediaStream> = emptyList()
    )

    private companion object {
        const val SEARCH_MILLIS = 60_000L
    }
}
