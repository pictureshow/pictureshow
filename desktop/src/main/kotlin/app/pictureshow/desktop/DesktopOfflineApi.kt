/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.Account
import app.pictureshow.core.AuthorizationHeader
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.control.TemporaryEvent
import app.pictureshow.core.control.TemporarySession
import app.pictureshow.core.control.TemporaryState
import app.pictureshow.core.control.reduceTemporary
import app.pictureshow.core.offline.OfflineCandidate
import app.pictureshow.core.offline.OfflinePolicy
import app.pictureshow.core.offline.OfflineRequest
import app.pictureshow.core.offline.OfflineUserData
import app.pictureshow.core.offline.OfflineUserDataWrite
import app.pictureshow.core.offline.offlineDownloadSetDevice
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
internal data class DesktopOfflineStream(
    @SerialName("Index") val index: Int = 0,
    @SerialName("Type") val type: String = "",
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("IsForced") val isForced: Boolean = false,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("IsTextSubtitleStream") val isText: Boolean = false
)

@Serializable
internal data class DesktopOfflineSource(
    @SerialName("Id") val id: String? = null,
    @SerialName("ETag") val etag: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("Bitrate") val bitrate: Int? = null,
    @SerialName("IsInfiniteStream") val isInfinite: Boolean = false,
    @SerialName("RunTimeTicks") val runtimeTicks: Long? = null,
    @SerialName("MediaStreams") val streams: List<DesktopOfflineStream> = emptyList()
)

@Serializable
internal data class DesktopOfflineUserData(
    @SerialName("PlaybackPositionTicks") val positionTicks: Long = 0,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("PlayCount") val playCount: Int = 0,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null
) {
    fun core() = OfflineUserData(positionTicks, played, playCount, lastPlayedDate)
}

/** No defaults for the two fields a held place is judged by: an answer without them isn't position 0. */
@Serializable
private data class DesktopOfflineStrictUserData(
    @SerialName("PlaybackPositionTicks") val positionTicks: Long,
    @SerialName("Played") val played: Boolean,
    @SerialName("PlayCount") val playCount: Int = 0,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null
) {
    fun core() = OfflineUserData(positionTicks, played, playCount, lastPlayedDate)
}

/** The item facts a download needs. Kind-agnostic: a music track carries its album fields, a video its series fields. */
@Serializable
internal data class DesktopOfflineItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String = "",
    @SerialName("CanDownload") val canDownload: Boolean? = null,
    @SerialName("IsInProgress") val isInProgress: Boolean = false,
    @SerialName("Status") val recordingStatus: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("ParentIndexNumber") val seasonNumber: Int? = null,
    @SerialName("IndexNumber") val episodeNumber: Int? = null,
    @SerialName("ProductionYear") val year: Int? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("RunTimeTicks") val runtimeTicks: Long? = null,
    @SerialName("UserData") val userData: DesktopOfflineUserData? = null,
    @SerialName("MediaSources") val sources: List<DesktopOfflineSource> = emptyList(),
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("Artists") val artists: List<String> = emptyList()
) {
    fun candidate(): OfflineCandidate = sources.firstOrNull().let { source ->
        OfflineCandidate(id, type, source?.isInfinite == true, source?.size, source?.bitrate, runtimeTicks ?: source?.runtimeTicks,
            canDownload, isInProgress, recordingStatus)
    }
}

@Serializable private data class DesktopOfflineItems(@SerialName("Items") val items: List<DesktopOfflineItem> = emptyList())

@Serializable
private data class DesktopOfflinePolicyDto(
    @SerialName("EnableContentDownloading") val downloads: Boolean = false,
    @SerialName("EnableMediaPlayback") val playback: Boolean = false,
    @SerialName("EnableVideoPlaybackTranscoding") val video: Boolean = false,
    @SerialName("EnableAudioPlaybackTranscoding") val audio: Boolean = false
)

@Serializable
private data class DesktopOfflineUser(@SerialName("Id") val id: String = "", @SerialName("Policy") val policy: DesktopOfflinePolicyDto = DesktopOfflinePolicyDto())

@Serializable private data class DesktopOfflineInitiate(@SerialName("Code") val code: String = "", @SerialName("Secret") val secret: String = "")

@Serializable
private data class DesktopOfflineAuth(@SerialName("AccessToken") val token: String? = null, @SerialName("User") val user: DesktopOfflineUser? = null)

/** The server answered with an error status: it was reached. */
internal class DesktopOfflineHttpException(val status: Int) : IOException("HTTP $status")

internal class DesktopOfflineHttpResponse(
    val status: Int,
    val header: (String) -> String?,
    val body: InputStream?,
    val contentLength: Long?,
    /** Anything else the response holds open; the body is always closed. */
    private val closer: () -> Unit = {}
) : AutoCloseable {
    override fun close() { runCatching { body?.close() }; closer() }
}

/**
 * A blocking call or read doesn't notice a cancelled coroutine and can sit for the whole read timeout. This ends it the moment
 * the caller is cancelled, however late the call registers (spec offline.md: "A blocked call must end when it's told to").
 */
internal suspend fun <T> desktopWithCallCancel(block: suspend CoroutineScope.((cancel: () -> Unit) -> Unit) -> T): T = coroutineScope {
    val registered = AtomicReference<() -> Unit>()
    val cancelled = AtomicBoolean(false)
    val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { cancelled.set(true); registered.get()?.invoke() }
    }
    try {
        block { cancel -> registered.set(cancel); if (cancelled.get()) cancel() }
    } finally {
        registered.set(null)
        watcher.cancel()
    }
}

/**
 * The server calls downloads make. Every call names the token it uses, because two are in play: the viewer's interactive
 * sign-in for catalog facts and progress, and the download set's own token for media bytes (OFF-10, CTRL-11).
 */
internal open class DesktopOfflineApi(private val identity: ClientIdentity, private val http: HttpClient = desktopOfflineHttpClient()) {
    private val json = Json { ignoreUnknownKeys = true }

    fun authorization(deviceId: String, token: String?): String = AuthorizationHeader.build(identity.copy(deviceId = deviceId), token)
    private fun interactive(account: Account) = AuthorizationHeader.build(identity.forAccount(account), account.accessToken)

    fun url(serverUrl: String, request: OfflineRequest): String {
        val base = serverUrl.trimEnd('/') + "/" + request.path.trimStart('/')
        if (request.query.isEmpty()) return base
        return base + "?" + request.query.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
    }
    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
    private fun server(account: Account) = account.server.url.trimEnd('/')

    /** Opens a media response; the caller reads and closes it. [onCall] gets what ends the request from another thread. */
    open fun open(url: String, headers: Map<String, String>, range: String?, onCall: (cancel: () -> Unit) -> Unit): DesktopOfflineHttpResponse {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(180)).GET().apply {
            headers.forEach { (name, value) -> header(name, value) }
            // A compressed body would make Content-Length and resume offsets lie.
            header("Accept-Encoding", "identity")
            if (range != null) header("Range", range)
        }.build()
        val future: CompletableFuture<HttpResponse<InputStream>> = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
        onCall { future.cancel(true); runCatching { future.getNow(null)?.body()?.close() } }
        val response = await(future)
        val body = response.body()
        onCall { runCatching { body.close() } }
        return DesktopOfflineHttpResponse(response.statusCode(), { name -> response.headers().firstValue(name).orElse(null) }, body,
            response.headers().firstValueAsLong("Content-Length").let { if (it.isPresent && it.asLong >= 0) it.asLong else null })
    }

    private fun <T> await(future: CompletableFuture<T>): T = try { future.get() }
    catch (e: ExecutionException) { throw (e.cause as? IOException) ?: IOException(e.cause?.javaClass?.simpleName ?: "request failed") }
    catch (e: java.util.concurrent.CancellationException) { throw IOException("cancelled") }

    protected open suspend fun call(url: String, authorization: String, body: String? = null, method: String? = null): String =
        withContext(Dispatchers.IO) {
            desktopWithCallCancel { onCall ->
                val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).header("Authorization", authorization).apply {
                    when {
                        method != null -> method(method, HttpRequest.BodyPublishers.noBody())
                        body != null -> header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
                        else -> GET()
                    }
                }.build()
                val future = http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                onCall { future.cancel(true) }
                val response = await(future)
                if (response.statusCode() !in 200..299) throw DesktopOfflineHttpException(response.statusCode())
                response.body()
            }
        }

    open suspend fun item(account: Account, itemId: String): DesktopOfflineItem =
        json.decodeFromString(call("${server(account)}/Users/${account.user.id}/Items/$itemId", interactive(account)))

    /** Episodes of one season, or of a whole series when [seasonId] is null, in the server's order, paged. */
    open suspend fun episodes(account: Account, seriesId: String, seasonId: String?): List<DesktopOfflineItem> {
        val out = mutableListOf<DesktopOfflineItem>()
        while (true) {
            val query = buildList {
                add("userId" to account.user.id)
                if (seasonId != null) add("seasonId" to seasonId)
                add("fields" to "MediaSources,MediaStreams,CanDownload,Overview")
                add("startIndex" to out.size.toString()); add("limit" to "100")
            }
            val page = json.decodeFromString<DesktopOfflineItems>(call(url(account.server.url, OfflineRequest("/Shows/$seriesId/Episodes", query)), interactive(account))).items
            out += page
            if (page.size < 100) return out
        }
    }

    /**
     * Tracks by id with what a download needs, in the order asked (the server answers in its own). At most 100 ids a request, so the
     * request line stays well inside what a server accepts; an id the server doesn't return is left out (MUS-10).
     */
    open suspend fun items(account: Account, ids: List<String>): List<DesktopOfflineItem> {
        val found = mutableMapOf<String, DesktopOfflineItem>()
        ids.distinct().chunked(100).forEach { batch ->
            val query = listOf("ids" to batch.joinToString(","), "fields" to "MediaSources,MediaStreams,CanDownload", "limit" to batch.size.toString())
            json.decodeFromString<DesktopOfflineItems>(call(url(account.server.url, OfflineRequest("/Users/${account.user.id}/Items", query)), interactive(account)))
                .items.forEach { found[it.id] = it }
        }
        return ids.distinct().mapNotNull(found::get)
    }

    open suspend fun policy(account: Account): OfflinePolicy {
        val user = json.decodeFromString<DesktopOfflineUser>(call("${server(account)}/Users/Me", interactive(account)))
        return OfflinePolicy(user.policy.downloads, user.policy.playback, user.policy.video, user.policy.audio)
    }

    /** Reconnect must prove this is still the same viewer before any write. */
    open suspend fun sameViewer(account: Account): Boolean =
        json.decodeFromString<DesktopOfflineUser>(call("${server(account)}/Users/Me", interactive(account))).id
            .replace("-", "").equals(account.user.id.replace("-", ""), ignoreCase = true)

    /** Strict: an answer without position and played throws instead of reading as position 0. */
    open suspend fun userData(account: Account, itemId: String): OfflineUserData = json.decodeFromString<DesktopOfflineStrictUserData>(
        call("${server(account)}/UserItems/$itemId/UserData?userId=${account.user.id}", interactive(account))
    ).core()

    /** Dirty fields only; a field left out stays as the server has it. */
    open suspend fun writeUserData(account: Account, itemId: String, write: OfflineUserDataWrite) {
        val body = buildJsonObject {
            write.positionTicks?.let { put("PlaybackPositionTicks", it) }
            write.played?.let { put("Played", it) }
        }
        call("${server(account)}/UserItems/$itemId/UserData?userId=${account.user.id}", interactive(account), body.toString())
    }

    /** Stops a conversion we walked away from. Best effort. */
    open suspend fun cleanup(serverUrl: String, request: OfflineRequest, deviceId: String, token: String) {
        runCatching { call(url(serverUrl, request), authorization(deviceId, token), method = "DELETE") }
    }

    /** A Wi-Fi sign-in page answers 200 too. Only this server's own two answers count. */
    open suspend fun quickConnectEnabled(serverUrl: String): Boolean =
        when (call("${serverUrl.trimEnd('/')}/QuickConnect/Enabled", authorization(identity.deviceId, null)).trim()) {
            "true" -> true
            "false" -> false
            else -> throw IOException("Not this server's answer")
        }

    /** Issues a token for [deviceId] without asking the viewer anything: their own session authorizes the code. */
    open suspend fun issueByQuickConnect(account: Account, deviceId: String): Pair<String, String> {
        val anonymous = authorization(deviceId, null)
        val start = json.decodeFromString<DesktopOfflineInitiate>(call("${server(account)}/QuickConnect/Initiate", anonymous, ""))
        call(url(account.server.url, OfflineRequest("/QuickConnect/Authorize", listOf("code" to start.code))), interactive(account), "")
        val exchange = buildJsonObject { put("Secret", start.secret) }
        return authResult(call("${server(account)}/Users/AuthenticateWithQuickConnect", anonymous, exchange.toString()))
    }

    open suspend fun issueByPassword(account: Account, deviceId: String, password: String): Pair<String, String> {
        val body = buildJsonObject { put("Username", account.user.name); put("Pw", password) }
        return authResult(call("${server(account)}/Users/AuthenticateByName", authorization(deviceId, null), body.toString()))
    }

    private fun authResult(text: String): Pair<String, String> {
        val auth = json.decodeFromString<DesktopOfflineAuth>(text)
        return (auth.token ?: throw IOException("No token")) to (auth.user?.id ?: throw IOException("No viewer"))
    }

    open suspend fun logout(serverUrl: String, deviceId: String, token: String) {
        call("${serverUrl.trimEnd('/')}/Sessions/Logout", authorization(deviceId, token), "")
    }
}

internal fun desktopOfflineHttpClient(): HttpClient = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NEVER)
    .connectTimeout(Duration.ofSeconds(15))
    .executor(java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "desktop-offline-http").apply { isDaemon = true } })
    .build()

@Serializable
internal data class DesktopOfflineSessionsFile(
    val state: TemporaryState = TemporaryState(),
    val tokens: Map<String, String> = emptyMap(),
    val servers: Map<String, String> = emptyMap()
)

internal sealed interface DesktopOfflineSetToken {
    data class Ready(val deviceId: String, val token: String) : DesktopOfflineSetToken
    /** Quick Connect is off on this server; only the viewer's password can issue a separate token. */
    data object NeedsPassword : DesktopOfflineSetToken
    data class Unavailable(val reason: String) : DesktopOfflineSetToken
}

/**
 * The download set's own device and token (OFF-10, CTRL-11): the interactive sign-in is never written into a job. The record is
 * written before the server call, so a crash after the issue still leaves something to revoke; sign-out revokes it, and a revocation
 * the server didn't hear stays pending and is retried.
 */
internal class DesktopOfflineSessions(private val file: File, private val api: DesktopOfflineApi, private val installationDeviceId: () -> String) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun load(): DesktopOfflineSessionsFile = runCatching { json.decodeFromString<DesktopOfflineSessionsFile>(file.readText()) }.getOrDefault(DesktopOfflineSessionsFile())
    private fun save(value: DesktopOfflineSessionsFile) = atomicPrivateWrite(file, json.encodeToString(DesktopOfflineSessionsFile.serializer(), value).toByteArray())

    private fun binding(account: Account, generation: Long) = TemporarySession(account.server.id, account.user.id,
        offlineDownloadSetDevice(installationDeviceId(), account.server.id, account.user.id), SENDER, generation)

    suspend fun token(account: Account, password: String? = null): DesktopOfflineSetToken = mutex.withLock {
        val wanted = binding(account, 0)
        var saved = load()
        val existing = saved.state.records.firstOrNull { it.server == wanted.server && it.viewer == wanted.viewer && it.device == wanted.device }
        if (existing?.phase == "active") saved.tokens[existing.secretRef]?.let { return DesktopOfflineSetToken.Ready(existing.device, it) }
        if (existing != null) {
            settle(existing)
            saved = load()
            if (saved.state.records.any { it.device == wanted.device }) return DesktopOfflineSetToken.Unavailable("revocation-pending")
        }
        val binding = wanted.copy(generation = (existing?.generation ?: -1) + 1)
        val creating = reduceTemporary(saved.state, TemporaryEvent("create", binding))
        if (creating.effect != "provision") return DesktopOfflineSetToken.Unavailable(creating.reason ?: "invalid")
        saved = saved.copy(state = creating, servers = saved.servers + (account.server.id to account.server.url))
        save(saved)
        val issued = runCatching {
            when {
                password != null -> api.issueByPassword(account, binding.device, password)
                api.quickConnectEnabled(account.server.url) -> api.issueByQuickConnect(account, binding.device)
                else -> null
            }
        }
        val dropRecord = { save(saved.copy(state = saved.state.copy(records = saved.state.records.filterNot { it.device == binding.device }))) }
        val pair = issued.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) { dropRecord(); throw error }
            dropRecord()
            return DesktopOfflineSetToken.Unavailable(if (error is DesktopOfflineHttpException && error.status in 401..403) "denied" else "network")
        }
        if (pair == null) { dropRecord(); return DesktopOfflineSetToken.NeedsPassword }
        val (token, viewer) = pair
        val secretRef = "dl:${binding.device}"
        val returned = if (viewer.replace("-", "").equals(account.user.id.replace("-", ""), ignoreCase = true)) account.user.id else viewer
        val next = reduceTemporary(saved.state, TemporaryEvent("issued", binding, returned, secretRef))
        saved = saved.copy(state = next, tokens = saved.tokens + (secretRef to token))
        save(saved)
        if (next.effect != "use") { finishRevocation(binding); return DesktopOfflineSetToken.Unavailable("wrong-viewer") }
        DesktopOfflineSetToken.Ready(binding.device, token)
    }

    suspend fun end(serverId: String, viewerId: String) = mutex.withLock {
        load().state.records.filter { it.server == serverId && it.viewer == viewerId }.forEach { settle(it) }
    }

    /** Any set whose account no longer owns private state here (removed, or signed out). */
    suspend fun endAllExcept(owners: Set<Pair<String, String>>) = mutex.withLock {
        load().state.records.filterNot { (it.server to it.viewer) in owners }.forEach { settle(it) }
    }

    suspend fun revocationsPending(): Boolean = mutex.withLock { load().state.records.any { it.phase in PENDING } }

    suspend fun reconnect() = mutex.withLock { load().state.records.filter { it.phase in PENDING }.forEach { settle(it) } }

    private suspend fun settle(record: TemporarySession) {
        val saved = load()
        when (record.phase) {
            "active" -> { save(saved.copy(state = reduceTemporary(saved.state, TemporaryEvent("end", record)))); finishRevocation(record) }
            "creating", "cancelled-creating" -> save(saved.copy(state = saved.state.copy(records = saved.state.records - record)))
            else -> finishRevocation(record)
        }
    }

    private suspend fun finishRevocation(binding: TemporarySession) {
        var saved = load()
        val current = saved.state.records.firstOrNull { it.device == binding.device }
        when (current?.phase) {
            "pending-persist" -> { saved = saved.copy(state = reduceTemporary(saved.state, TemporaryEvent("persisted", current))); save(saved) }
            "pending-revoke" -> { saved = saved.copy(state = reduceTemporary(saved.state, TemporaryEvent("reconnect", current))); save(saved) }
        }
        val record = saved.state.records.firstOrNull { it.device == binding.device } ?: return
        val token = saved.tokens[record.secretRef]
        val server = saved.servers[record.server]
        val ok = token == null || server == null || try { api.logout(server, record.device, token); true }
            catch (e: DesktopOfflineHttpException) { e.status == 401 } // an already-dead token is revoked
            catch (e: IOException) { false }
        val after = reduceTemporary(saved.state, TemporaryEvent(if (ok) "logout-ok" else "logout-failed", record))
        save(saved.copy(state = after, tokens = if (after.effect == "erase-secret") saved.tokens - record.secretRef.orEmpty() else saved.tokens))
    }

    private companion object {
        const val SENDER = "offline"
        val PENDING = listOf("pending-revoke", "revoking", "pending-persist")
    }
}
