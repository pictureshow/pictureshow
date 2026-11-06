/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.AuthorizationHeader
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.offline.OfflineCandidate
import app.pictureshow.core.offline.OfflinePolicy
import app.pictureshow.core.offline.OfflineRequest
import app.pictureshow.core.offline.OfflineUserData
import app.pictureshow.core.offline.OfflineUserDataWrite
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
internal data class OfflineApiStream(
    @SerialName("Index") val index: Int = 0,
    @SerialName("Type") val type: String = "",
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("IsForced") val isForced: Boolean = false,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("IsTextSubtitleStream") val isText: Boolean = false,
    @SerialName("Channels") val channels: Int? = null
)

@Serializable
internal data class OfflineApiSource(
    @SerialName("Id") val id: String? = null,
    @SerialName("ETag") val etag: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("Bitrate") val bitrate: Int? = null,
    @SerialName("IsInfiniteStream") val isInfinite: Boolean = false,
    @SerialName("RunTimeTicks") val runtimeTicks: Long? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = true,
    @SerialName("MediaStreams") val streams: List<OfflineApiStream> = emptyList()
)

@Serializable
internal data class OfflineApiUserData(
    @SerialName("PlaybackPositionTicks") val positionTicks: Long = 0,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("PlayCount") val playCount: Int = 0,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null
) {
    fun core() = OfflineUserData(positionTicks, played, playCount, lastPlayedDate)
}

@Serializable
internal data class OfflineApiItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String = "",
    @SerialName("CanDownload") val canDownload: Boolean? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("ParentIndexNumber") val seasonNumber: Int? = null,
    @SerialName("IndexNumber") val episodeNumber: Int? = null,
    @SerialName("ProductionYear") val year: Int? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("RunTimeTicks") val runtimeTicks: Long? = null,
    @SerialName("UserData") val userData: OfflineApiUserData? = null,
    @SerialName("MediaSources") val sources: List<OfflineApiSource> = emptyList()
) {
    fun candidate(): OfflineCandidate = sources.firstOrNull().let { source ->
        OfflineCandidate(
            id,
            type,
            source?.isInfinite == true,
            source?.size,
            source?.bitrate,
            runtimeTicks ?: source?.runtimeTicks,
            canDownload
        )
    }
}

@Serializable
private data class OfflineApiItems(@SerialName("Items") val items: List<OfflineApiItem> = emptyList())

@Serializable
private data class OfflineApiPolicy(
    @SerialName("EnableContentDownloading") val downloads: Boolean = false,
    @SerialName("EnableMediaPlayback") val playback: Boolean = false,
    @SerialName("EnableVideoPlaybackTranscoding") val video: Boolean = false,
    @SerialName("EnableAudioPlaybackTranscoding") val audio: Boolean = false
)

@Serializable
private data class OfflineApiUser(
    @SerialName("Id") val id: String = "",
    @SerialName("Policy") val policy: OfflineApiPolicy = OfflineApiPolicy()
)

@Serializable
private data class OfflineApiInitiate(@SerialName("Code") val code: String = "", @SerialName("Secret") val secret: String = "")

@Serializable
private data class OfflineApiAuth(
    @SerialName("AccessToken") val token: String? = null,
    @SerialName("User") val user: OfflineApiUser? = null
)

internal class OfflineApiException(val status: Int) : IOException("HTTP $status")

/** A small, explicit client: every call here names the token it uses, because two different ones are in play. */
internal class OfflineApi(private val client: OkHttpClient, private val identity: ClientIdentity) {
    private val json = Json { ignoreUnknownKeys = true }

    fun authorization(deviceId: String, token: String?): String = AuthorizationHeader.build(identity.copy(deviceId = deviceId), token)

    fun url(serverUrl: String, request: OfflineRequest): String =
        (serverUrl.trimEnd('/') + "/" + request.path.trimStart('/')).toHttpUrl().newBuilder().apply {
            request.query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build().toString()

    private suspend fun call(url: String, authorization: String, body: String? = null, method: String? = null): String =
        withContext(Dispatchers.IO) {
            withCallCancel { onCall ->
                val request = Request.Builder().url(url).header("Authorization", authorization).apply {
                    if (body != null) post(body.toRequestBody("application/json".toMediaType()))
                    if (method != null) method(method, null)
                }.build()
                val call = client.newCall(request)
                onCall { call.cancel() }
                call.execute().use { response ->
                    if (!response.isSuccessful) throw OfflineApiException(response.code)
                    response.body.string()
                }
            }
        }

    private fun interactive(account: Account) = AuthorizationHeader.build(identity.forAccount(account), account.accessToken)

    /** The default source's facts plus the viewer's own UserData. */
    suspend fun item(account: Account, itemId: String): OfflineApiItem = json.decodeFromString(
        call("${account.server.url.trimEnd('/')}/Users/${account.user.id}/Items/$itemId", interactive(account))
    )

    suspend fun seasonEpisodes(account: Account, seriesId: String, seasonId: String): List<OfflineApiItem> {
        val out = mutableListOf<OfflineApiItem>()
        while (true) {
            val url = "${account.server.url.trimEnd('/')}/Shows/$seriesId/Episodes".toHttpUrl().newBuilder()
                .addQueryParameter("userId", account.user.id)
                .addQueryParameter("seasonId", seasonId)
                .addQueryParameter("fields", "MediaSources,MediaStreams,CanDownload,Overview")
                .addQueryParameter("startIndex", out.size.toString())
                .addQueryParameter("limit", "100")
                .build().toString()
            val page = json.decodeFromString<OfflineApiItems>(call(url, interactive(account))).items
            out += page
            if (page.size < 100) return out
        }
    }

    suspend fun policy(account: Account): OfflinePolicy {
        val user = json.decodeFromString<OfflineApiUser>(call("${account.server.url.trimEnd('/')}/Users/Me", interactive(account)))
        return OfflinePolicy(user.policy.downloads, user.policy.playback, user.policy.video, user.policy.audio)
    }

    /** Reconnect must prove this is still the same viewer before any write. */
    suspend fun sameViewer(account: Account): Boolean =
        json.decodeFromString<OfflineApiUser>(call("${account.server.url.trimEnd('/')}/Users/Me", interactive(account))).id
            .replace("-", "").equals(account.user.id.replace("-", ""), ignoreCase = true)

    suspend fun userData(account: Account, itemId: String): OfflineUserData = json.decodeFromString<OfflineApiUserData>(
        call("${account.server.url.trimEnd('/')}/UserItems/$itemId/UserData?userId=${account.user.id}", interactive(account))
    ).core()

    /** Dirty fields only; a field left out stays as the server has it. */
    suspend fun writeUserData(account: Account, itemId: String, write: OfflineUserDataWrite) {
        val body = buildJsonObject {
            write.positionTicks?.let { put("PlaybackPositionTicks", it) }
            write.played?.let { put("Played", it) }
        }
        call(
            "${account.server.url.trimEnd('/')}/UserItems/$itemId/UserData?userId=${account.user.id}",
            interactive(account),
            body.toString()
        )
    }

    /** Stops a conversion we walked away from. Best effort. */
    suspend fun cleanup(serverUrl: String, request: OfflineRequest, deviceId: String, token: String) {
        runCatching { call(url(serverUrl, request), authorization(deviceId, token), method = "DELETE") }
    }

    /** A Wi-Fi sign-in page answers 200 too. Only this server's own two answers count; anything else means it wasn't reached. */
    suspend fun quickConnectEnabled(serverUrl: String): Boolean =
        when (call("${serverUrl.trimEnd('/')}/QuickConnect/Enabled", authorization(identity.deviceId, null)).trim()) {
            "true" -> true
            "false" -> false
            else -> throw IOException("Not this server's answer")
        }

    /** Issues a token for [deviceId] without asking the viewer anything: their own session authorizes the code. */
    suspend fun issueByQuickConnect(account: Account, deviceId: String): Pair<String, String> {
        val server = account.server.url.trimEnd('/')
        val anonymous = authorization(deviceId, null)
        val start = json.decodeFromString<OfflineApiInitiate>(call("$server/QuickConnect/Initiate", anonymous, ""))
        val authorize = "$server/QuickConnect/Authorize".toHttpUrl().newBuilder().addQueryParameter("code", start.code).build().toString()
        call(authorize, interactive(account), "")
        val exchange = buildJsonObject { put("Secret", start.secret) }
        return authResult(call("$server/Users/AuthenticateWithQuickConnect", anonymous, exchange.toString()))
    }

    suspend fun issueByPassword(account: Account, deviceId: String, password: String): Pair<String, String> {
        val body = buildJsonObject {
            put("Username", account.user.name)
            put("Pw", password)
        }
        return authResult(
            call("${account.server.url.trimEnd('/')}/Users/AuthenticateByName", authorization(deviceId, null), body.toString())
        )
    }

    private fun authResult(text: String): Pair<String, String> {
        val auth = json.decodeFromString<OfflineApiAuth>(text)
        return (auth.token ?: throw IOException("No token")) to (auth.user?.id ?: throw IOException("No viewer"))
    }

    suspend fun logout(serverUrl: String, deviceId: String, token: String) {
        call("${serverUrl.trimEnd('/')}/Sessions/Logout", authorization(deviceId, token), "")
    }
}
