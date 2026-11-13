package app.pictureshow.core.playback

import app.pictureshow.core.Account
import app.pictureshow.core.ApiTimeouts
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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException

/** Where playback starts and which streams the user picked; null leaves the choice to the server. */
data class PlaybackRequest(
    val startPosition: Duration = Duration.ZERO,
    val mediaSourceId: String? = null,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
    val enableDirectPlay: Boolean = true,
    val enableDirectStream: Boolean = true,
    val allowVideoStreamCopy: Boolean = true,
    val allowAudioStreamCopy: Boolean = true,
    /** Audio recovery after this video already played directly; require copy in the stream URL too. */
    val requireVideoStreamCopy: Boolean = false,
    /** A track: its file is opened from the audio route. */
    val audio: Boolean = false
)

/** Jellyfin ticks are 100 ns. */
object Ticks {
    const val PER_MILLISECOND: Long = 10_000

    fun of(duration: Duration): Long {
        val seconds = duration.inWholeSeconds
        if (seconds >= Long.MAX_VALUE / 10_000_000) return Long.MAX_VALUE
        if (seconds <= Long.MIN_VALUE / 10_000_000) return Long.MIN_VALUE
        val remainder = duration - seconds.seconds
        return seconds * 10_000_000 + remainder.inWholeNanoseconds / 100
    }

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
    ): ClientResult<PlayOutcome> = negotiate(account, itemId, playbackInfoRequest(account, capabilities, playback), playback)

    /**
     * The same negotiation for an output that isn't this device, such as a Cast receiver: [profile] is that output's
     * own, and this device's decoders play no part in it.
     */
    suspend fun prepareFor(
        account: Account,
        itemId: String,
        profile: DeviceProfile,
        maxStreamingBitrate: Int,
        playback: PlaybackRequest = PlaybackRequest()
    ): ClientResult<PlayOutcome> = negotiate(
        account,
        itemId,
        PlaybackInfoRequest(
            userId = account.user.id,
            maxStreamingBitrate = maxStreamingBitrate,
            startTimeTicks = Ticks.of(playback.startPosition),
            audioStreamIndex = playback.audioStreamIndex,
            subtitleStreamIndex = playback.subtitleStreamIndex,
            mediaSourceId = playback.mediaSourceId,
            deviceProfile = profile,
            enableDirectPlay = playback.enableDirectPlay,
            enableDirectStream = playback.enableDirectStream,
            allowVideoStreamCopy = playback.allowVideoStreamCopy,
            allowAudioStreamCopy = playback.allowAudioStreamCopy
        ),
        playback
    )

    private suspend fun negotiate(
        account: Account,
        itemId: String,
        body: PlaybackInfoRequest,
        playback: PlaybackRequest
    ): ClientResult<PlayOutcome> {
        var info = playbackInfo(account, itemId, body)
        var boundSourceId = playback.mediaSourceId
        // Jellyfin 12 applies explicit track indices only to a bound MediaSourceId. The first
        // response discovers the server's preferred source; no media URL is opened here, so it
        // cannot start an encoder. Keep the second response's source and play-session identity.
        if (info is ClientResult.Success &&
            info.value.errorCode == null &&
            boundSourceId == null &&
            (playback.audioStreamIndex != null || playback.subtitleStreamIndex != null)
        ) {
            val sources = info.value.mediaSources
            val preferred = sources.firstOrNull {
                it.supportsDirectPlay || it.supportsDirectStream || !it.transcodingUrl.isNullOrBlank()
            } ?: sources.firstOrNull()
            preferred?.id?.takeIf(String::isNotBlank)?.let { sourceId ->
                boundSourceId = sourceId
                info = playbackInfo(account, itemId, body.copy(mediaSourceId = sourceId))
            }
        }
        return when (val result = info) {
            is ClientResult.Failure -> result
            is ClientResult.Success -> ClientResult.Success(
                PlayDecisions.decide(
                    account.server,
                    itemId,
                    result.value,
                    identity.forAccount(account),
                    account.accessToken,
                    boundSourceId,
                    playback.audio
                ).let { outcome ->
                    if (playback.requireVideoStreamCopy && outcome is PlayOutcome.Play) {
                        AudioOnlyRecovery.preserveVideo(outcome.decision, playback.subtitleStreamIndex)
                    } else {
                        outcome
                    }
                }
            )
        }
    }

    suspend fun reportStart(
        account: Account,
        info: PlaybackProgressInfo,
        observe: ((PlaybackHttpOutcome) -> Unit)? = null
    ): ClientResult<Unit> =
        request(account, "Sessions/Playing", PlaybackJson.encodeToString(PlaybackProgressInfo.serializer(), info), observe = observe) { }

    suspend fun reportProgress(
        account: Account,
        info: PlaybackProgressInfo,
        observe: ((PlaybackHttpOutcome) -> Unit)? = null
    ): ClientResult<Unit> = request(
        account,
        "Sessions/Playing/Progress",
        PlaybackJson.encodeToString(PlaybackProgressInfo.serializer(), info),
        observe = observe
    ) {
    }

    suspend fun reportStopped(
        account: Account,
        info: PlaybackStopInfo,
        observe: ((PlaybackHttpOutcome) -> Unit)? = null
    ): ClientResult<Unit> =
        request(account, "Sessions/Playing/Stopped", PlaybackJson.encodeToString(PlaybackStopInfo.serializer(), info), observe = observe) {
        }

    /** End only this device's encode for this play session; never stop another viewer's stream. */
    suspend fun stopEncoding(account: Account, playSessionId: String): ClientResult<Unit> = request(
        account,
        "Videos/ActiveEncodings?deviceId=${PlayDecisions.encode(
            identity.forAccount(account).deviceId
        )}&playSessionId=${PlayDecisions.encode(playSessionId)}",
        null,
        HttpMethod.Delete
    ) { }

    private suspend fun <T> request(
        account: Account,
        path: String,
        body: String?,
        method: HttpMethod = HttpMethod.Post,
        observe: ((PlaybackHttpOutcome) -> Unit)? = null,
        decode: (String) -> T
    ): ClientResult<T> {
        var status: Int? = null
        var failure: PlaybackReportFailure? = null
        try {
            return withTimeoutOrNull(ApiTimeouts.REQUEST_MILLIS) {
                val response = client.request("${account.server.url.trimEnd('/')}/$path") {
                    this.method = method
                    headers.append(HttpHeaders.Authorization, AuthorizationHeader.build(identity.forAccount(account), account.accessToken))
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                }
                status = response.status.value
                if (response.status.value !in 200..299) failure = PlaybackReportFailure.Http
                when (response.status.value) {
                    in 200..299 -> ClientResult.Success(decode(response.bodyAsText()))
                    401 -> failure(ErrorKind.INVALID_CREDENTIALS, "The server no longer accepts this sign-in. Sign in again.")
                    else -> failure(ErrorKind.SERVER, "The server could not complete the request (${response.status.value}).")
                }
            } ?: run {
                failure = PlaybackReportFailure.Timeout
                failure(ErrorKind.NETWORK, "The server took too long to respond. Check your connection and try again.")
            }
        } catch (cancelled: CancellationException) {
            failure = PlaybackReportFailure.Cancelled
            throw cancelled
        } catch (_: SerializationException) {
            failure = PlaybackReportFailure.InvalidResponse
            return failure(ErrorKind.INVALID_RESPONSE, "The server returned an unreadable response.")
        } catch (_: IllegalArgumentException) {
            failure = PlaybackReportFailure.InvalidResponse
            return failure(ErrorKind.INVALID_RESPONSE, "The server returned an incomplete response.")
        } catch (_: Exception) {
            failure = PlaybackReportFailure.Network
            return failure(ErrorKind.NETWORK, "Could not reach the server. Check your connection and try again.")
        } finally {
            observe?.invoke(PlaybackHttpOutcome(status, failure))
        }
    }

    private fun failure(kind: ErrorKind, message: String) = ClientResult.Failure(ClientError(kind, message))

    companion object {
        fun playbackInfoRequest(account: Account, capabilities: DeviceCapabilities, playback: PlaybackRequest): PlaybackInfoRequest =
            PlaybackInfoRequest(
                userId = account.user.id,
                maxStreamingBitrate = capabilities.settings.maxStreamingBitrate,
                startTimeTicks = Ticks.of(playback.startPosition),
                audioStreamIndex = playback.audioStreamIndex,
                subtitleStreamIndex = playback.subtitleStreamIndex,
                mediaSourceId = playback.mediaSourceId,
                deviceProfile = DeviceProfileBuilder.build(capabilities),
                maxAudioChannels = if (capabilities.settings.tuning.downmix) {
                    2
                } else {
                    capabilities.settings.tuning.normalized().maxAudioChannels
                },
                alwaysBurnInSubtitleWhenTranscoding = capabilities.settings.tuning.subtitleBurnIn == SubtitleBurnIn.Always,
                enableDirectPlay = playback.enableDirectPlay,
                enableDirectStream = playback.enableDirectStream,
                allowVideoStreamCopy = playback.allowVideoStreamCopy,
                allowAudioStreamCopy = playback.allowAudioStreamCopy
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
