/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.sync

import app.pictureshow.core.Account
import app.pictureshow.core.ApiTimeouts
import app.pictureshow.core.AuthorizationHeader
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A request's 2xx is dispatch only. The group event, followed by rendered output, confirms it. */
data class SyncRequest(val operation: String, val body: JsonObject? = null) {
    init {
        require(
            operation in setOf(
                "New", "Join", "Leave", "SetNewQueue", "Queue", "MovePlaylistItem", "RemoveFromPlaylist", "SetPlaylistItem",
                "NextItem", "PreviousItem", "SetRepeatMode", "SetShuffleMode", "Pause", "Unpause", "Seek", "Stop", "Buffering",
                "Ready", "Ping", "SetIgnoreWait"
            )
        )
    }
}

fun syncCreate(name: String): SyncRequest {
    require(name.isNotBlank() && name.length <= 200)
    return SyncRequest("New", buildJsonObject { put("GroupName", name) })
}

fun syncJoin(groupId: String): SyncRequest {
    require(groupId.isNotBlank())
    return SyncRequest("Join", buildJsonObject { put("GroupId", groupId) })
}

fun syncTransport(command: String, positionTicks: Long? = null, playlistItemId: String? = null): SyncRequest {
    require(command in setOf("Pause", "Unpause", "Seek", "Stop", "NextItem", "PreviousItem"))
    val body = when (command) {
        "Seek" -> buildJsonObject { put("PositionTicks", requireNotNull(positionTicks).also { require(it >= 0) }) }

        "NextItem", "PreviousItem" -> buildJsonObject {
            put("PlaylistItemId", requireNotNull(playlistItemId).also { require(it.isNotBlank()) })
        }

        else -> null
    }
    return SyncRequest(command, body)
}

fun syncNewQueue(items: List<String>, currentIndex: Int = 0, startPositionTicks: Long = 0): SyncRequest {
    require(items.isNotEmpty() && items.all { it.isNotBlank() } && currentIndex in items.indices && startPositionTicks >= 0)
    return SyncRequest(
        "SetNewQueue",
        buildJsonObject {
            put("PlayingQueue", JsonArray(items.map(::JsonPrimitive)))
            put("PlayingItemPosition", currentIndex)
            put("StartPositionTicks", startPositionTicks)
        }
    )
}

fun syncEnqueue(items: List<String>, next: Boolean): SyncRequest {
    require(items.isNotEmpty() && items.all { it.isNotBlank() })
    return SyncRequest(
        "Queue",
        buildJsonObject {
            put("ItemIds", JsonArray(items.map(::JsonPrimitive)))
            put("Mode", if (next) "QueueNext" else "Queue")
        }
    )
}

fun syncMove(playlistItemId: String, index: Int): SyncRequest {
    require(playlistItemId.isNotBlank() && index >= 0)
    return SyncRequest(
        "MovePlaylistItem",
        buildJsonObject {
            put("PlaylistItemId", playlistItemId)
            put("NewIndex", index)
        }
    )
}

fun syncRemove(playlistItemIds: List<String>, clearPlaylist: Boolean = false, clearPlayingItem: Boolean = false): SyncRequest {
    require(playlistItemIds.all { it.isNotBlank() } && (playlistItemIds.isNotEmpty() || clearPlaylist))
    return SyncRequest(
        "RemoveFromPlaylist",
        buildJsonObject {
            put("PlaylistItemIds", JsonArray(playlistItemIds.map(::JsonPrimitive)))
            put("ClearPlaylist", clearPlaylist)
            put("ClearPlayingItem", clearPlayingItem)
        }
    )
}

fun syncSelect(playlistItemId: String): SyncRequest {
    require(playlistItemId.isNotBlank())
    return SyncRequest("SetPlaylistItem", buildJsonObject { put("PlaylistItemId", playlistItemId) })
}

fun syncRepeat(mode: String): SyncRequest {
    require(mode in setOf("RepeatNone", "RepeatOne", "RepeatAll"))
    return SyncRequest("SetRepeatMode", buildJsonObject { put("Mode", mode) })
}

fun syncShuffle(shuffled: Boolean): SyncRequest =
    SyncRequest("SetShuffleMode", buildJsonObject { put("Mode", if (shuffled) "Shuffle" else "Sorted") })

fun syncIgnoreWait(ignore: Boolean): SyncRequest = SyncRequest("SetIgnoreWait", buildJsonObject { put("IgnoreWait", ignore) })

fun syncPing(roundTripMs: Long): SyncRequest {
    require(roundTripMs in 0..1000)
    return SyncRequest("Ping", buildJsonObject { put("Ping", roundTripMs) })
}

/** Caller supplies the actual renderer entry, position and intent; no synthetic zero/Ready before preparation. */
fun syncReadiness(ready: Boolean, whenMs: Long, positionTicks: Long, isPlaying: Boolean, playlistItemId: String): SyncRequest {
    require(positionTicks >= 0 && playlistItemId.isNotBlank())
    return SyncRequest(
        if (ready) "Ready" else "Buffering",
        buildJsonObject {
            put("When", syncUtcTimestamp(whenMs))
            put("PositionTicks", positionTicks)
            put("IsPlaying", isPlaying)
            put("PlaylistItemId", playlistItemId)
        }
    )
}

data class SyncUtcTime(val receptionMs: Long, val transmissionMs: Long)

/** Viewer-scoped HTTP; retries are deliberately owned by deliberate Join/recovery, never by a mutation. */
class SyncClient(private val client: HttpClient, private val identity: ClientIdentity) {
    private fun authorization(account: Account) = AuthorizationHeader.build(identity.forAccount(account), account.accessToken)
    private fun url(account: Account, path: String) = "${account.server.url.trimEnd('/')}/$path"

    private suspend fun <T> get(account: Account, path: String, decode: (JsonElement) -> T): ClientResult<T> = try {
        val response = withTimeoutOrNull(ApiTimeouts.REQUEST_MILLIS) {
            client.get(url(account, path)) { header(HttpHeaders.Authorization, authorization(account)) }
        }
        when {
            response == null -> ClientResult.Failure(ClientError(ErrorKind.NETWORK, "The server took too long to respond."))

            response.status.value == 401 -> ClientResult.Failure(ClientError(ErrorKind.INVALID_CREDENTIALS, "Sign in again.", 401))

            response.status.value == 403 -> ClientResult.Failure(
                ClientError(ErrorKind.SERVER, "This viewer is not allowed to use Watch together.", 403)
            )

            response.status.value !in 200..299 -> ClientResult.Failure(
                ClientError(ErrorKind.SERVER, "The server could not load Watch together.", response.status.value)
            )

            else -> try {
                ClientResult.Success(decode(Json.parseToJsonElement(response.bodyAsText())))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ClientResult.Failure(ClientError(ErrorKind.INVALID_RESPONSE, "The server returned unreadable Watch together information."))
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Could not reach the server."))
    }

    suspend fun groups(account: Account): ClientResult<List<SyncGroup>> = get(account, "SyncPlay/List") { value ->
        (value as? JsonArray ?: error("Not a list")).map { parseSyncGroup(it as? JsonObject ?: error("Not a group")) }
    }

    suspend fun access(account: Account): ClientResult<String> = get(account, "Users/Me") { value ->
        val policy = (value as? JsonObject)?.get("Policy") as? JsonObject ?: error("No policy")
        ((policy["SyncPlayAccess"] as? JsonPrimitive)?.contentOrNull ?: error("No access")).also {
            require(it in setOf("CreateAndJoinGroups", "JoinGroups", "None"))
        }
    }

    suspend fun utcTime(account: Account): ClientResult<SyncUtcTime> = get(account, "GetUtcTime") { value ->
        val fields = value as? JsonObject ?: error("Not a clock")
        SyncUtcTime(
            requireNotNull(syncTimestampMillis((fields["RequestReceptionTime"] as? JsonPrimitive)?.contentOrNull ?: "")),
            requireNotNull(syncTimestampMillis((fields["ResponseTransmissionTime"] as? JsonPrimitive)?.contentOrNull ?: ""))
        )
    }

    suspend fun send(account: Account, request: SyncRequest): Int = try {
        withTimeoutOrNull(ApiTimeouts.REQUEST_MILLIS) {
            client.post(url(account, "SyncPlay/${request.operation}")) {
                header(HttpHeaders.Authorization, authorization(account))
                request.body?.let {
                    contentType(ContentType.Application.Json)
                    setBody(it.toString())
                }
            }.status.value
        } ?: 0
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        0
    }
}

/** UTC formatting is platform-neutral so readiness vectors also cover negative epoch and day boundaries. */
fun syncUtcTimestamp(epochMillis: Long): String {
    require(epochMillis in -62135596800000L..253402300799999L)
    val seconds = if (epochMillis >= 0) epochMillis / 1000 else (epochMillis - 999) / 1000
    val millis = (epochMillis - seconds * 1000).toInt()
    val days = if (seconds >= 0) seconds / 86400 else (seconds - 86399) / 86400
    val daySeconds = (seconds - days * 86400).toInt()
    val ordinal = days + 719162
    fun beforeYear(year: Int): Long {
        val previous = year - 1L
        return previous * 365 + previous / 4 - previous / 100 + previous / 400
    }
    var low = 1
    var high = 10000
    while (low + 1 < high) {
        val mid = (low + high) / 2
        if (beforeYear(mid) <= ordinal) low = mid else high = mid
    }
    val year = low
    var dayOfYear = (ordinal - beforeYear(year)).toInt()
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val months = listOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    var month = 0
    while (dayOfYear >= months[month]) dayOfYear -= months[month++]
    fun Int.padded(size: Int = 2) = toString().padStart(size, '0')
    return "${year.padded(4)}-${(month + 1).padded()}-${(dayOfYear + 1).padded()}T" +
        "${(daySeconds / 3600).padded()}:${(daySeconds / 60 % 60).padded()}:${(daySeconds % 60).padded()}.${millis.padded(3)}Z"
}

fun syncUtcString(epochMillis: Long): String = syncUtcTimestamp(epochMillis)
