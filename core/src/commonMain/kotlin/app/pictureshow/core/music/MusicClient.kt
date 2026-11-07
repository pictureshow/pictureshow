/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.music

import app.pictureshow.core.Account
import app.pictureshow.core.ApiTimeouts
import app.pictureshow.core.AuthorizationHeader
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.ItemPage
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class PlaylistCreated(@SerialName("Id") val id: String = "")

/** The result of a write the server may have applied even when no answer came: "ok", "denied", "failed" or "unknown". */
data class PlaylistWrite(val outcome: String, val id: String? = null)

interface MusicService {
    suspend fun music(account: Account, request: MusicRequest): ClientResult<ItemPage>

    suspend fun instantMix(account: Account, item: String): ClientResult<ItemPage>

    suspend fun playlistCanEdit(account: Account, playlist: String): Boolean

    suspend fun createPlaylist(account: Account, name: String, items: List<String>, isPublic: Boolean = false): PlaylistWrite

    suspend fun renamePlaylist(account: Account, playlist: String, name: String): PlaylistWrite

    suspend fun addToPlaylist(account: Account, playlist: String, items: List<String>): PlaylistWrite

    suspend fun removeFromPlaylist(account: Account, playlist: String, entries: List<String>): PlaylistWrite

    suspend fun movePlaylistEntry(account: Account, playlist: String, entry: String, index: Int): PlaylistWrite
}

internal class MusicClient(private val client: HttpClient, private val identity: ClientIdentity) : MusicService {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private suspend fun send(
        account: Account,
        path: String,
        parameters: Map<String, String>,
        method: HttpMethod = HttpMethod.Get,
        body: String? = null
    ): Pair<Int, String>? = withTimeoutOrNull(ApiTimeouts.REQUEST_MILLIS) {
        val response = client.request("${account.server.url.trimEnd('/')}/$path") {
            this.method = method
            headers.append(HttpHeaders.Authorization, AuthorizationHeader.build(identity.forAccount(account), account.accessToken))
            url.parameters.append("userId", account.user.id)
            parameters.forEach { (name, value) -> url.parameters.append(name, value) }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        response.status.value to response.bodyAsText()
    }

    private fun failure(kind: ErrorKind, message: String, status: Int? = null) = ClientResult.Failure(ClientError(kind, message, status))

    override suspend fun music(account: Account, request: MusicRequest): ClientResult<ItemPage> = try {
        val answer = send(account, request.path, request.parameters)
        when {
            answer == null -> failure(ErrorKind.NETWORK, "The server took too long to respond. Try again.")
            answer.first in 200..299 -> ClientResult.Success(json.decodeFromString<ItemPage>(answer.second))
            answer.first == 401 -> failure(ErrorKind.EXPIRED, "Your session has expired. Sign in again.", 401)
            answer.first == 403 || answer.first == 404 ->
                failure(ErrorKind.SERVER, "This account can't open this music.", answer.first)
            else -> failure(ErrorKind.SERVER, "The server could not complete the request (${answer.first}).", answer.first)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SerializationException) {
        failure(ErrorKind.INVALID_RESPONSE, "The server returned an unreadable response.")
    } catch (_: IllegalArgumentException) {
        failure(ErrorKind.INVALID_RESPONSE, "The server returned an incomplete response.")
    } catch (_: Exception) {
        failure(ErrorKind.NETWORK, "Could not reach the server. Check your connection and try again.")
    }

    override suspend fun instantMix(account: Account, item: String): ClientResult<ItemPage> =
        music(account, musicInstantMixRequest(item.encodeURLPathPart()))

    /** A playlist the server doesn't name this user on (or any failure) is read-only: edits are refused here, not after. */
    override suspend fun playlistCanEdit(account: Account, playlist: String): Boolean = try {
        val answer = send(account, "Playlists/${playlist.encodeURLPathPart()}/Users/${account.user.id.encodeURLPathPart()}", emptyMap())
        answer != null &&
            answer.first in 200..299 &&
            json.parseToJsonElement(answer.second).let { (it as? JsonObject)?.get("CanEdit") }
                ?.let { (it as? JsonPrimitive)?.content } == "true"
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun write(
        account: Account,
        path: String,
        parameters: Map<String, String> = emptyMap(),
        method: HttpMethod = HttpMethod.Post,
        body: String? = null
    ): PlaylistWrite = try {
        val answer = send(account, path, parameters, method, body)
        when {
            answer == null -> PlaylistWrite("unknown")
            answer.first in 200..299 -> PlaylistWrite(
                "ok",
                runCatching { json.decodeFromString<PlaylistCreated>(answer.second).id }.getOrNull()?.takeIf(String::isNotBlank)
            )
            answer.first == 401 || answer.first == 403 || answer.first == 404 -> PlaylistWrite("denied")
            answer.first >= 500 -> PlaylistWrite("unknown")
            else -> PlaylistWrite("failed")
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // The request may have reached the server before the connection went.
        PlaylistWrite("unknown")
    }

    override suspend fun createPlaylist(account: Account, name: String, items: List<String>, isPublic: Boolean): PlaylistWrite =
        write(account, "Playlists", body = createPlaylistBody(name, account.user.id, items, isPublic).toString())

    override suspend fun renamePlaylist(account: Account, playlist: String, name: String): PlaylistWrite =
        write(account, "Playlists/${playlist.encodeURLPathPart()}", body = renamePlaylistBody(name).toString())

    override suspend fun addToPlaylist(account: Account, playlist: String, items: List<String>): PlaylistWrite =
        write(account, "Playlists/${playlist.encodeURLPathPart()}/Items", mapOf("ids" to items.joinToString(",")))

    override suspend fun removeFromPlaylist(account: Account, playlist: String, entries: List<String>): PlaylistWrite =
        write(account, "Playlists/${playlist.encodeURLPathPart()}/Items", mapOf("entryIds" to entries.joinToString(",")), HttpMethod.Delete)

    override suspend fun movePlaylistEntry(account: Account, playlist: String, entry: String, index: Int): PlaylistWrite =
        write(account, "Playlists/${playlist.encodeURLPathPart()}/Items/${entry.encodeURLPathPart()}/Move/$index")
}
