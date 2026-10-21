package app.pictureshow.core.playback

import app.pictureshow.core.Account
import app.pictureshow.core.AuthorizationHeader
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException

/** Where playback starts and which streams the user picked; null leaves the choice to the server. */
data class PlaybackRequest(
    val startPosition: Duration = Duration.ZERO,
    val mediaSourceId: String? = null,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null
)

/** Jellyfin ticks are 100 ns. */
object Ticks {
    const val PER_MILLISECOND: Long = 10_000

    fun of(duration: Duration): Long = duration.inWholeMicroseconds * 10

    fun fromMillis(milliseconds: Long): Long = milliseconds * PER_MILLISECOND
}

/** The playback endpoints: PlaybackInfo negotiation and the session reports the server uses for resume and activity. */
class PlaybackClient(private val client: HttpClient, private val identity: ClientIdentity) {
    suspend fun playbackInfo(account: Account, itemId: String, body: PlaybackInfoRequest): ClientResult<PlaybackInfoResponse> = request(
        account,
        "Items/${PlayDecisions.encode(itemId)}/PlaybackInfo",
        PlaybackJson.encodeToString(PlaybackInfoRequest.serializer(), body)
    ) { PlaybackJson.decodeFromString(PlaybackInfoResponse.serializer(), it) }

    /** Sends the device profile, then picks the source, method and URL from the server's answer. */
    suspend fun prepare(
        account: Account,
        itemId: String,
        capabilities: DeviceCapabilities,
        playback: PlaybackRequest = PlaybackRequest()
    ): ClientResult<PlayOutcome> {
        val body = playbackInfoRequest(account, capabilities, playback)
        return when (val info = playbackInfo(account, itemId, body)) {
            is ClientResult.Failure -> info
            is ClientResult.Success -> ClientResult.Success(
                PlayDecisions.decide(account.server, itemId, info.value, identity, account.accessToken, playback.mediaSourceId)
            )
        }
    }

    suspend fun reportStart(account: Account, info: PlaybackProgressInfo): ClientResult<Unit> =
        request(account, "Sessions/Playing", PlaybackJson.encodeToString(PlaybackProgressInfo.serializer(), info)) { }

    suspend fun reportProgress(account: Account, info: PlaybackProgressInfo): ClientResult<Unit> =
        request(account, "Sessions/Playing/Progress", PlaybackJson.encodeToString(PlaybackProgressInfo.serializer(), info)) { }

    suspend fun reportStopped(account: Account, info: PlaybackStopInfo): ClientResult<Unit> =
        request(account, "Sessions/Playing/Stopped", PlaybackJson.encodeToString(PlaybackStopInfo.serializer(), info)) { }

    private suspend fun <T> request(account: Account, path: String, body: String, decode: (String) -> T): ClientResult<T> = try {
        withTimeoutOrNull(TIMEOUT_MILLIS) {
            val response = client.request("${account.server.url.trimEnd('/')}/$path") {
                method = HttpMethod.Post
                headers.append(HttpHeaders.Authorization, AuthorizationHeader.build(identity, account.accessToken))
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            when (response.status.value) {
                in 200..299 -> ClientResult.Success(decode(response.bodyAsText()))
                401 -> failure(ErrorKind.INVALID_CREDENTIALS, "The server no longer accepts this sign-in. Sign in again.")
                else -> failure(ErrorKind.SERVER, "The server could not complete the request (${response.status.value}).")
            }
        } ?: failure(ErrorKind.NETWORK, "The server took too long to respond. Check your connection and try again.")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SerializationException) {
        failure(ErrorKind.INVALID_RESPONSE, "The server returned an unreadable response.")
    } catch (_: IllegalArgumentException) {
        failure(ErrorKind.INVALID_RESPONSE, "The server returned an incomplete response.")
    } catch (_: Exception) {
        failure(ErrorKind.NETWORK, "Could not reach the server. Check your connection and try again.")
    }

    private fun failure(kind: ErrorKind, message: String) = ClientResult.Failure(ClientError(kind, message))

    companion object {
        /** PlaybackInfo may probe the file or open a live stream, so it gets longer than the 4 s sign-in calls. */
        private const val TIMEOUT_MILLIS = 20_000L

        fun playbackInfoRequest(account: Account, capabilities: DeviceCapabilities, playback: PlaybackRequest): PlaybackInfoRequest =
            PlaybackInfoRequest(
                userId = account.user.id,
                maxStreamingBitrate = capabilities.settings.maxStreamingBitrate,
                startTimeTicks = Ticks.of(playback.startPosition),
                audioStreamIndex = playback.audioStreamIndex,
                subtitleStreamIndex = playback.subtitleStreamIndex,
                mediaSourceId = playback.mediaSourceId,
                deviceProfile = DeviceProfileBuilder.build(capabilities)
            )
    }
}

/** Builds the session reports for one playback from its [PlayDecision]. */
class PlaybackReports(private val decision: PlayDecision) {
    fun progress(
        position: Duration,
        isPaused: Boolean,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        canSeek: Boolean = true
    ): PlaybackProgressInfo = PlaybackProgressInfo(
        itemId = decision.itemId,
        mediaSourceId = decision.mediaSourceId,
        playSessionId = decision.playSessionId,
        liveStreamId = decision.mediaSource.liveStreamId,
        playMethod = decision.method,
        positionTicks = Ticks.of(position),
        audioStreamIndex = audioStreamIndex,
        subtitleStreamIndex = subtitleStreamIndex,
        isPaused = isPaused,
        canSeek = canSeek
    )

    fun stopped(position: Duration, failed: Boolean = false): PlaybackStopInfo = PlaybackStopInfo(
        itemId = decision.itemId,
        mediaSourceId = decision.mediaSourceId,
        playSessionId = decision.playSessionId,
        liveStreamId = decision.mediaSource.liveStreamId,
        positionTicks = Ticks.of(position),
        failed = failed
    )
}
