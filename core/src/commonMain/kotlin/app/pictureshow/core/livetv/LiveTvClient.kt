/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.livetv

import app.pictureshow.core.Account
import app.pictureshow.core.ApiTimeouts
import app.pictureshow.core.AuthorizationHeader
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.ItemPage
import app.pictureshow.core.MediaItem
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

interface LiveTvService {
    suspend fun policy(account: Account): ClientResult<LivePolicy>
    suspend fun info(account: Account): ClientResult<LiveInfo>
    suspend fun guideInfo(account: Account): ClientResult<LiveGuideInfo>
    suspend fun channels(
        account: Account,
        startIndex: Int = 0,
        limit: Int = 100,
        search: String? = null,
        favorites: Boolean = false
    ): ClientResult<LivePage<LiveChannel>>
    suspend fun channel(account: Account, id: String): ClientResult<LiveChannel>
    suspend fun programs(
        account: Account,
        channelIds: List<String>,
        startUtc: String,
        endUtc: String,
        startIndex: Int = 0,
        limit: Int = 100
    ): ClientResult<LivePage<LiveProgram>>
    suspend fun program(account: Account, id: String): ClientResult<LiveProgram>
    suspend fun recordings(account: Account, startIndex: Int = 0, limit: Int = 100): ClientResult<ItemPage>
    suspend fun timers(account: Account, series: Boolean = false): ClientResult<List<LiveSchedule>>
    suspend fun defaults(account: Account, programId: String): ClientResult<LiveSchedule>
    suspend fun createTimer(account: Account, draft: LiveSchedule, series: Boolean = false): LiveWrite
    suspend fun updateTimer(account: Account, schedule: LiveSchedule, series: Boolean = false): LiveWrite
    suspend fun cancelTimer(account: Account, id: String, series: Boolean = false): LiveWrite
    suspend fun deleteRecording(account: Account, id: String): LiveWrite
    suspend fun favorite(account: Account, id: String, favorite: Boolean): ClientResult<LiveChannel>
}

/** All calls carry the current viewer's token and userId. No administrator credential is available to this client. */
interface LivePendingWrites {
    suspend fun contains(key: String): Boolean
    suspend fun add(key: String)
    suspend fun remove(key: String)
}

class MemoryLivePendingWrites : LivePendingWrites {
    private val keys = mutableSetOf<String>()
    override suspend fun contains(key: String): Boolean = key in keys
    override suspend fun add(key: String) {
        keys.add(key)
    }
    override suspend fun remove(key: String) {
        keys.remove(key)
    }
}

class LiveTvClient(
    private val http: HttpClient,
    private val identity: ClientIdentity,
    private val pendingWrites: LivePendingWrites = MemoryLivePendingWrites(),
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) : LiveTvService {
    private val json = Json { ignoreUnknownKeys = true }
    private val writes = Mutex()

    private suspend fun send(
        account: Account,
        path: String,
        parameters: Map<String, String> = emptyMap(),
        method: HttpMethod = HttpMethod.Get,
        body: JsonObject? = null
    ): Pair<Int, String>? = withTimeoutOrNull(ApiTimeouts.REQUEST_MILLIS) {
        val response = http.request("${account.server.url.trimEnd('/')}/$path") {
            this.method = method
            headers.append(HttpHeaders.Authorization, AuthorizationHeader.build(identity.forAccount(account), account.accessToken))
            url.parameters.append("userId", account.user.id)
            parameters.forEach { (key, value) -> url.parameters.append(key, value) }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        response.status.value to response.bodyAsText()
    }

    private suspend fun <T> read(
        account: Account,
        path: String,
        parameters: Map<String, String> = emptyMap(),
        decode: (String) -> T
    ): ClientResult<T> = try {
        val answer = send(account, path, parameters)
        when {
            answer == null -> failure(ErrorKind.NETWORK, "The server took too long to respond. Retry when connected.")
            answer.first in 200..299 -> ClientResult.Success(decode(answer.second))
            answer.first == 401 -> failure(ErrorKind.INVALID_CREDENTIALS, "This sign-in has expired. Sign in again.", 401)
            answer.first == 403 -> failure(ErrorKind.SERVER, "This account is not allowed to use this Live TV feature.", 403)
            answer.first == 404 -> failure(ErrorKind.SERVER, "This channel, program or recording is no longer available.", 404)
            else -> failure(ErrorKind.SERVER, "The server could not complete the Live TV request (${answer.first}). Retry.", answer.first)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SerializationException) {
        failure(ErrorKind.INVALID_RESPONSE, "The server returned unreadable Live TV data.")
    } catch (_: IllegalArgumentException) {
        failure(ErrorKind.INVALID_RESPONSE, "The server returned incomplete Live TV data.")
    } catch (_: Exception) {
        failure(ErrorKind.NETWORK, "Could not reach the server. Last loaded guide may be out of date. Retry when connected.")
    }

    override suspend fun policy(account: Account): ClientResult<LivePolicy> =
        read(account, "Users/${account.user.id.encodeURLPathPart()}") {
            val fields = json.parseToJsonElement(it).jsonObject["Policy"]?.jsonObject ?: JsonObject(emptyMap())
            fun granted(name: String) = (fields[name] as? JsonPrimitive)?.booleanOrNull == true
            LivePolicy(
                granted("EnableLiveTvAccess"),
                granted("EnableLiveTvManagement"),
                granted("EnableContentDeletion"),
                granted("EnableUserPreferenceAccess")
            )
        }

    override suspend fun info(account: Account): ClientResult<LiveInfo> = read(account, "LiveTv/Info") {
        json.decodeFromString<LiveInfo>(it)
    }
    override suspend fun guideInfo(account: Account): ClientResult<LiveGuideInfo> =
        read(account, "LiveTv/GuideInfo") { json.decodeFromString<LiveGuideInfo>(it) }

    override suspend fun channels(
        account: Account,
        startIndex: Int,
        limit: Int,
        search: String?,
        favorites: Boolean
    ): ClientResult<LivePage<LiveChannel>> {
        require(startIndex >= 0 && limit in 1..500)
        // Jellyfin has no channel searchTerm query. Search the authorized channel pages, then page the matches.
        if (!search.isNullOrBlank()) {
            val all = mutableListOf<LiveChannel>()
            var offset = 0
            do {
                val result = channels(account, offset, 100, null, favorites)
                if (result is ClientResult.Failure) return result
                val page = (result as ClientResult.Success).value
                all += page.items
                offset += page.items.size
                if (page.items.size < 100 || (page.totalRecordCount > 0 && offset >= page.totalRecordCount)) break
                if (offset >=
                    50_000
                ) {
                    return failure(
                        ErrorKind.INVALID_RESPONSE,
                        "Too many channel pages. Narrow the server's channel list before searching."
                    )
                }
            } while (true)
            val matching = sortChannels(all).filter {
                it.name.contains(search, ignoreCase = true) ||
                    it.number.orEmpty().contains(search, ignoreCase = true)
            }
            return ClientResult.Success(LivePage(matching.drop(startIndex).take(limit), matching.size, startIndex))
        }
        return read(
            account,
            "LiveTv/Channels",
            buildMap {
                put("startIndex", startIndex.toString())
                put("limit", limit.toString())
                put("enableUserData", "true")
                put("addCurrentProgram", "true")
                if (favorites) put("isFavorite", "true")
            }
        ) { json.decodeFromString<LivePage<LiveChannel>>(it) }
    }

    override suspend fun channel(account: Account, id: String): ClientResult<LiveChannel> =
        read(account, "LiveTv/Channels/${id.encodeURLPathPart()}") { json.decodeFromString<LiveChannel>(it) }

    override suspend fun programs(
        account: Account,
        channelIds: List<String>,
        startUtc: String,
        endUtc: String,
        startIndex: Int,
        limit: Int
    ): ClientResult<LivePage<LiveProgram>> {
        require(startIndex >= 0 && limit in 1..500)
        require(channelIds.isNotEmpty() && channelIds.size <= 100) { "Fetch bounded channel batches." }
        guidePrograms(emptyList(), channelIds.toSet(), startUtc, endUtc)
        return read(
            account,
            "LiveTv/Programs",
            mapOf(
                "channelIds" to channelIds.joinToString(","),
                "minEndDate" to startUtc,
                "maxStartDate" to endUtc,
                "startIndex" to startIndex.toString(),
                "limit" to limit.toString(),
                "enableImages" to "true",
                "enableUserData" to "true"
            )
        ) { json.decodeFromString<LivePage<LiveProgram>>(it) }
    }

    override suspend fun program(account: Account, id: String): ClientResult<LiveProgram> =
        read(account, "LiveTv/Programs/${id.encodeURLPathPart()}") { json.decodeFromString<LiveProgram>(it) }

    override suspend fun recordings(account: Account, startIndex: Int, limit: Int): ClientResult<ItemPage> {
        require(startIndex >= 0 && limit in 1..500)
        return read(
            account,
            "LiveTv/Recordings",
            mapOf("startIndex" to startIndex.toString(), "limit" to limit.toString(), "enableUserData" to "true")
        ) { json.decodeFromString<ItemPage>(it) }
    }

    private fun schedulePath(series: Boolean): String = if (series) "LiveTv/SeriesTimers" else "LiveTv/Timers"

    override suspend fun timers(account: Account, series: Boolean): ClientResult<List<LiveSchedule>> = read(account, schedulePath(series)) {
        json.parseToJsonElement(it).jsonObject["Items"]?.jsonArray.orEmpty().map { row -> LiveSchedule(row.jsonObject) }
    }

    override suspend fun defaults(account: Account, programId: String): ClientResult<LiveSchedule> =
        read(account, "LiveTv/Timers/Defaults", mapOf("programId" to programId)) { LiveSchedule(json.parseToJsonElement(it).jsonObject) }

    private suspend fun permission(account: Account, delete: Boolean = false): LiveWrite? = when (val result = policy(account)) {
        is ClientResult.Failure -> LiveWrite(
            if (result.error.status in
                setOf(401, 403)
            ) {
                "denied"
            } else {
                "failed"
            },
            message = result.error.message
        )
        is ClientResult.Success -> if (result.value.access && (if (delete) result.value.deleteContent else result.value.manage)) {
            null
        } else {
            LiveWrite("denied", message = "This account cannot ${if (delete) "delete recorded files" else "manage recording schedules"}.")
        }
    }

    private suspend fun write(account: Account, path: String, body: JsonObject? = null, method: HttpMethod = HttpMethod.Post): LiveWrite =
        try {
            val answer = send(account, path, method = method, body = body)
            when {
                answer == null -> LiveWrite("unknown", message = "The result is unknown. Refresh schedules before trying again.")
                answer.first in 200..299 -> LiveWrite("ok")
                answer.first in setOf(
                    401,
                    403,
                    404
                ) -> LiveWrite("denied", message = "Permission changed or the item was removed. Refresh Live TV.")
                answer.first >= 500 -> LiveWrite("unknown", message = "The server may have applied the change. Refresh before retrying.")
                else -> LiveWrite(
                    "failed",
                    message = "The server refused the change (${answer.first}). Your original schedule is unchanged locally."
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LiveWrite("unknown", message = "Connection lost. The change may have reached the server; refresh before retrying.")
        }

    /** Comparing all four scope fields distinguishes a different airing of the same episode. */
    private fun sameScope(a: LiveSchedule, b: LiveSchedule): Boolean = a.programId.isNotBlank() &&
        a.programId == b.programId &&
        a.channelId == b.channelId &&
        liveInstant(a.startDate) != null &&
        liveInstant(a.endDate) != null &&
        liveInstant(a.startDate) == liveInstant(b.startDate) &&
        liveInstant(a.endDate) == liveInstant(b.endDate)

    private fun confirmedCreate(draft: LiveSchedule, saved: LiveSchedule): LiveWrite {
        val editable = setOf(
            "PrePaddingSeconds", "PostPaddingSeconds", "Priority", "IsPrePaddingRequired", "IsPostPaddingRequired", "KeepUntil",
            "RecordAnyTime", "RecordAnyChannel", "RecordNewOnly", "Days", "KeepUpTo", "SkipEpisodesInLibrary"
        )
        return if (draft.fields.filterKeys { it in editable }.all { (key, value) -> saved.fields[key] == value }) {
            LiveWrite("ok", saved)
        } else {
            LiveWrite("failed", saved, "A schedule for this airing exists with different recording options. Review or edit that schedule.")
        }
    }

    override suspend fun createTimer(account: Account, draft: LiveSchedule, series: Boolean): LiveWrite = writes.withLock {
        permission(account)?.let { return@withLock it }
        val scope = "${account.key}:$series:${draft.programId}:${draft.channelId}:${draft.startDate}:${draft.endDate}"
        val before = timers(account, series)
        if (before !is ClientResult.Success) {
            return@withLock LiveWrite(
                "failed",
                message = "Could not refresh schedules. Nothing was submitted."
            )
        }
        val existing = before.value.filter { sameScope(it, draft) && it.status != "Cancelled" }
        if (existing.size == 1) {
            pendingWrites.remove(scope)
            return@withLock confirmedCreate(draft, existing.single())
        }
        if (existing.size > 1 || pendingWrites.contains(scope)) {
            return@withLock LiveWrite(
                "unknown",
                message = "An earlier request is still unresolved. Check schedules before creating another recording."
            )
        }
        val fresh = program(account, draft.programId)
        if (fresh !is ClientResult.Success) {
            return@withLock LiveWrite(
                if (fresh is ClientResult.Failure && fresh.error.status in setOf(401, 403, 404)) "denied" else "failed",
                message = "Could not refresh this program. Nothing was submitted."
            )
        }
        val airing = fresh.value
        val start = liveInstant(airing.startDate)
        val end = liveInstant(airing.endDate)
        if (airing.channelId != draft.channelId ||
            start == null ||
            start != liveInstant(draft.startDate) ||
            end == null ||
            end <= start ||
            end != liveInstant(draft.endDate) ||
            end <= nowMillis() ||
            (series && !airing.isSeries)
        ) {
            return@withLock LiveWrite(
                "failed",
                message = "This airing changed, ended, or does not support a series recording. Refresh the program."
            )
        }
        val permittedChannel = channel(account, draft.channelId)
        if (permittedChannel !is ClientResult.Success) {
            return@withLock LiveWrite(
                if (permittedChannel is ClientResult.Failure &&
                    permittedChannel.error.status in setOf(401, 403, 404)
                ) {
                    "denied"
                } else {
                    "failed"
                },
                message = "Could not confirm access to this channel. Refresh Live TV before recording."
            )
        }
        // Mark BEFORE transport: cancellation can also occur after the server accepted a request.
        pendingWrites.add(scope)
        val answer = write(account, schedulePath(series), draft.fields)
        if (answer.outcome == "denied" || answer.outcome == "failed") pendingWrites.remove(scope)
        if (answer.outcome in setOf("ok", "unknown")) {
            val after = timers(account, series)
            if (after is ClientResult.Success) {
                val matches = after.value.filter { sameScope(it, draft) && it.status != "Cancelled" }
                if (matches.size == 1) {
                    pendingWrites.remove(scope)
                    return@withLock confirmedCreate(draft, matches.single())
                }
            }
            return@withLock LiveWrite(
                "unknown",
                message = "The recording request needs confirmation. Refresh schedules; do not submit it again."
            )
        }
        answer
    }

    override suspend fun updateTimer(account: Account, schedule: LiveSchedule, series: Boolean): LiveWrite = writes.withLock {
        permission(account)?.let { return@withLock it }
        require(schedule.id.isNotBlank())
        val path = "${schedulePath(series)}/${schedule.id.encodeURLPathPart()}"
        val refreshed = read(account, path) { LiveSchedule(json.parseToJsonElement(it).jsonObject) }
        if (refreshed !is ClientResult.Success) {
            return@withLock LiveWrite(
                "failed",
                message = "Could not refresh the schedule. Nothing was submitted."
            )
        }
        val dirty = JsonObject(schedule.fields.filterKeys { it in schedule.dirtyFields })
        val updated = try {
            editSchedule(refreshed.value, dirty, series)
        } catch (_: IllegalArgumentException) {
            return@withLock LiveWrite("failed", message = "The provider no longer supports one of these edits. Refresh the form.")
        }
        if (dirty.isEmpty()) return@withLock LiveWrite("ok", refreshed.value)
        val answer = write(account, path, updated.fields)
        if (answer.outcome != "ok") return@withLock answer
        when (val after = read(account, path) { LiveSchedule(json.parseToJsonElement(it).jsonObject) }) {
            is ClientResult.Success -> if (dirty.all { (key, value) -> after.value.fields[key] == value }) {
                LiveWrite("ok", after.value)
            } else {
                LiveWrite("failed", after.value, "The server did not retain every edit. Review its saved values before retrying.")
            }
            is ClientResult.Failure -> LiveWrite(
                "unknown",
                message = "The update was accepted, but its saved values could not be read. Refresh schedules."
            )
        }
    }

    override suspend fun cancelTimer(account: Account, id: String, series: Boolean): LiveWrite = writes.withLock {
        permission(account)?.let { return@withLock it }
        val answer = write(account, "${schedulePath(series)}/${id.encodeURLPathPart()}", method = HttpMethod.Delete)
        if (answer.outcome != "ok") return@withLock answer
        val after = timers(account, series)
        if (after is ClientResult.Success && after.value.none { it.id == id && it.status != "Cancelled" }) {
            LiveWrite("ok")
        } else {
            LiveWrite("unknown", message = "Cancellation was accepted but could not be confirmed. Refresh schedules.")
        }
    }

    override suspend fun deleteRecording(account: Account, id: String): LiveWrite = writes.withLock {
        val rights = policy(account)
        if (rights !is ClientResult.Success) {
            return@withLock LiveWrite(
                "failed",
                message = "Could not refresh recording permissions. Nothing was deleted."
            )
        }
        if (!rights.value.access) return@withLock LiveWrite("denied", message = "This account cannot access Live TV recordings.")
        val path = "LiveTv/Recordings/${id.encodeURLPathPart()}"
        val recording = read(account, path) { json.decodeFromString<MediaItem>(it) }
        if (recording !is ClientResult.Success) {
            return@withLock LiveWrite(
                "failed",
                message = "Could not refresh this recording. Nothing was deleted."
            )
        }
        val canDelete = recording.value.canDelete
        if (canDelete == false || (canDelete != true && !rights.value.deleteContent)) {
            return@withLock LiveWrite("denied", message = "This account cannot delete this recorded file.")
        }
        // The Live TV DELETE endpoint only unlists metadata on supported servers.
        // Recorded-file deletion is the normal permission-checked content operation.
        val itemPath = "Items/${id.encodeURLPathPart()}"
        val answer = write(account, itemPath, method = HttpMethod.Delete)
        if (answer.outcome != "ok") return@withLock answer
        when (val after = read(account, path) { json.decodeFromString<MediaItem>(it) }) {
            is ClientResult.Failure -> if (after.error.status == 404) {
                when (val item = read(account, itemPath) { json.decodeFromString<MediaItem>(it) }) {
                    is ClientResult.Failure -> if (item.error.status == 404) {
                        LiveWrite("ok")
                    } else {
                        LiveWrite("unknown", message = "Content deletion could not be confirmed. Refresh recordings.")
                    }
                    is ClientResult.Success -> LiveWrite("unknown", message = "The recorded item is still available. Refresh recordings.")
                }
            } else {
                LiveWrite(
                    "unknown",
                    message = "Deletion was accepted but could not be confirmed. Refresh recordings."
                )
            }
            is ClientResult.Success -> LiveWrite(
                "unknown",
                message = "The recorded file is still listed. Refresh recordings before retrying."
            )
        }
    }

    override suspend fun favorite(account: Account, id: String, favorite: Boolean): ClientResult<LiveChannel> {
        val rights = policy(account)
        if (rights !is ClientResult.Success || !rights.value.access || !rights.value.userData) {
            return failure(ErrorKind.SERVER, "This account cannot change channel favorites.", 403)
        }
        val answer = write(
            account,
            "Users/${account.user.id.encodeURLPathPart()}/FavoriteItems/${id.encodeURLPathPart()}",
            method = if (favorite) HttpMethod.Post else HttpMethod.Delete
        )
        return if (answer.outcome ==
            "ok"
        ) {
            channel(account, id)
        } else {
            failure(ErrorKind.SERVER, answer.message ?: "Could not change the favorite.")
        }
    }

    private fun failure(kind: ErrorKind, message: String, status: Int? = null) = ClientResult.Failure(ClientError(kind, message, status))
}
