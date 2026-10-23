/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.playback.DeviceCapabilities
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlayOutcome
import app.pictureshow.core.playback.PlaybackClient
import app.pictureshow.core.playback.PlaybackProgressInfo
import app.pictureshow.core.playback.PlaybackReports
import app.pictureshow.core.playback.PlaybackRequest
import app.pictureshow.core.playback.PlaybackStopInfo
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Narrow ports allow protocol order and real time scheduling to be tested without a decoder or a server. */
internal interface PlaybackGateway {
    suspend fun prepare(request: PlaybackRequest): ClientResult<PlayOutcome>
    suspend fun start(info: PlaybackProgressInfo): ClientResult<Unit>
    suspend fun progress(info: PlaybackProgressInfo): ClientResult<Unit>
    suspend fun stopped(info: PlaybackStopInfo): ClientResult<Unit>
    suspend fun stopEncoding(sessionId: String): ClientResult<Unit>
}

internal class ServerPlaybackGateway(
    private val client: PlaybackClient,
    private val account: Account,
    private val itemId: String,
    private val capabilities: () -> DeviceCapabilities
) : PlaybackGateway {
    override suspend fun prepare(request: PlaybackRequest) = client.prepare(account, itemId, capabilities(), request)
    override suspend fun start(info: PlaybackProgressInfo) = client.reportStart(account, info)
    override suspend fun progress(info: PlaybackProgressInfo) = client.reportProgress(account, info)
    override suspend fun stopped(info: PlaybackStopInfo) = client.reportStopped(account, info)
    override suspend fun stopEncoding(sessionId: String) = client.stopEncoding(account, sessionId)
}

internal data class PlaybackPosition(val milliseconds: Long, val paused: Boolean)

internal interface PlaybackOutput {
    fun position(): PlaybackPosition
    fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean)

    /** Return false if the chosen server stream cannot be switched in this media source. */
    fun select(type: MediaStreamType, index: Int): Boolean
    fun pause()
    fun release()
}

internal data class PlaybackState(
    val decision: PlayDecision? = null,
    val preparing: Boolean = true,
    val error: String? = null,
    val reportError: String? = null,
    val bufferingSlow: Boolean = false,
    val audioIndex: Int = -1,
    val subtitleIndex: Int = -1
)

/** Used on the player thread: network transitions are serialized; buffering feedback has its own deadline. */
internal class PlaybackSession(
    private val gateway: PlaybackGateway,
    private val output: PlaybackOutput,
    scope: CoroutineScope,
    private val preferredLanguage: String,
    private val onFinished: () -> Unit = {}
) {
    private val mutable = MutableStateFlow(PlaybackState())
    val state = mutable.asStateFlow()
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var decision: PlayDecision? = null
    private var requested = PlaybackRequest()
    private var ready = false
    private var fallbackUsed = false
    private var closed = false
    private var closing = false
    private var lastPosition = PlaybackPosition(0, false)
    private var ticker: Job? = null
    private var bufferingTimer: Job? = null
    private var bufferingGeneration = 0L
    private val owner = scope

    init {
        scope.launch {
            for (command in commands) command()
        }
    }

    fun start(positionMs: Long) = enqueue {
        if (decision == null && mutable.value.error == null) {
            negotiate(PlaybackRequest(startPosition = positionMs.coerceAtLeast(0).milliseconds), false)
        }
    }

    fun ready(source: PlayDecision? = decision) = enqueue {
        if (source !== decision || ready || decision == null || mutable.value.error != null) return@enqueue
        ready = true
        mutable.value = mutable.value.copy(preparing = false)
        report(gateway.start(progressInfo()))
        ticker?.cancel()
        ticker = owner.launch {
            while (true) {
                delay(10_000)
                progress()
            }
        }
    }

    /** Called on the player thread. Feedback must not wait behind a suspending session report. */
    fun buffering(waiting: Boolean, source: PlayDecision? = decision) {
        if (closing || source !== decision || decision == null || mutable.value.error != null) return
        if (!waiting) {
            cancelBuffering()
        } else if (bufferingTimer == null) {
            val generation = ++bufferingGeneration
            bufferingTimer = owner.launch {
                delay(10_000)
                if (source !== decision || generation != bufferingGeneration || closing) return@launch
                mutable.value = mutable.value.copy(bufferingSlow = true)
                delay(35_000)
                if (source !== decision || generation != bufferingGeneration || closing) return@launch
                capture()
                // Detach immediately, even while the command consumer awaits a slow report.
                mutable.value = mutable.value.copy(
                    preparing = false,
                    bufferingSlow = false,
                    error = "Playback is taking too long to resume. Return to details and try again."
                )
                output.release()
                enqueue { if (source === decision) finishDecision(failed = true) }
            }
        }
    }

    private fun cancelBuffering() {
        bufferingGeneration++
        bufferingTimer?.cancel()
        bufferingTimer = null
        mutable.value = mutable.value.copy(bufferingSlow = false)
    }

    fun progress(source: PlayDecision? = decision) = enqueue {
        if (source === decision && ready && mutable.value.error == null) report(gateway.progress(progressInfo()))
    }

    fun select(type: MediaStreamType, index: Int) = enqueue {
        if (mutable.value.error != null) return@enqueue
        if (type !in listOf(MediaStreamType.Audio, MediaStreamType.Subtitle)) return@enqueue
        val current = decision ?: return@enqueue
        capture()
        val nextAudio = if (type == MediaStreamType.Audio) index else mutable.value.audioIndex
        val nextSubtitle = if (type == MediaStreamType.Subtitle) index else mutable.value.subtitleIndex
        if (current.method != PlayMethod.Transcode && output.select(type, index)) {
            mutable.value = mutable.value.copy(audioIndex = nextAudio, subtitleIndex = nextSubtitle)
            requested = requested.copy(audioStreamIndex = nextAudio, subtitleStreamIndex = nextSubtitle)
            if (ready) report(gateway.progress(progressInfo()))
        } else {
            finishDecision()
            negotiate(
                requested.copy(
                    startPosition = lastPosition.milliseconds.milliseconds,
                    mediaSourceId = current.mediaSourceId,
                    audioStreamIndex = nextAudio,
                    subtitleStreamIndex = nextSubtitle,
                    enableDirectPlay = false,
                    enableDirectStream = false
                ),
                lastPosition.paused
            )
        }
    }

    fun routeChanged() = enqueue {
        if (mutable.value.error != null) return@enqueue
        val current = decision ?: return@enqueue
        capture()
        finishDecision()
        negotiate(
            requested.copy(
                startPosition = lastPosition.milliseconds.milliseconds,
                mediaSourceId = current.mediaSourceId,
                audioStreamIndex = mutable.value.audioIndex,
                subtitleStreamIndex = mutable.value.subtitleIndex
            ),
            lastPosition.paused
        )
    }

    fun failed(canRetryWithTranscode: Boolean, source: PlayDecision? = decision) = enqueue {
        if (mutable.value.error != null || source !== decision) return@enqueue
        val current = decision ?: return@enqueue
        capture()
        val retry = canRetryWithTranscode && current.method != PlayMethod.Transcode && !fallbackUsed
        finishDecision(failed = true)
        if (retry) {
            fallbackUsed = true
            negotiate(
                requested.copy(
                    startPosition = lastPosition.milliseconds.milliseconds,
                    mediaSourceId = current.mediaSourceId,
                    audioStreamIndex = mutable.value.audioIndex,
                    subtitleStreamIndex = mutable.value.subtitleIndex,
                    enableDirectPlay = false,
                    enableDirectStream = false,
                    allowVideoStreamCopy = false
                ),
                lastPosition.paused
            )
        } else {
            output.pause()
            mutable.value =
                mutable.value.copy(preparing = false, error = "Playback failed. The server could not provide a playable stream.")
        }
    }

    /** Snapshot before releasing the actual player. Final network work survives the screen's disposal. */
    fun close(afterFinished: () -> Unit = {}) {
        if (closing) return
        closing = true
        lastPosition =
            output.position().let { if (ready) it else it.copy(milliseconds = maxOf(it.milliseconds, lastPosition.milliseconds)) }
        output.release()
        commands.trySend {
            if (!closed) {
                closed = true
                ticker?.cancel()
                cancelBuffering()
                finishDecision()
                onFinished()
                afterFinished()
                commands.close()
            }
        }
    }

    private fun enqueue(block: suspend () -> Unit) {
        if (!closing) commands.trySend { if (!closed && !closing) block() }
    }

    private fun capture() {
        val actual = output.position()
        lastPosition = if (ready) actual else actual.copy(milliseconds = maxOf(actual.milliseconds, lastPosition.milliseconds))
    }

    private suspend fun negotiate(request: PlaybackRequest, paused: Boolean) {
        requested = request
        ready = false
        lastPosition = PlaybackPosition(request.startPosition.inWholeMilliseconds, paused)
        mutable.value = mutable.value.copy(preparing = true, error = null)
        when (val result = gateway.prepare(request)) {
            is ClientResult.Failure -> mutable.value = mutable.value.copy(preparing = false, error = result.error.message)
            is ClientResult.Success -> when (val outcome = result.value) {
                is PlayOutcome.Unplayable -> mutable.value = mutable.value.copy(preparing = false, error = outcome.message)
                is PlayOutcome.Play -> {
                    val next = outcome.decision
                    decision = next
                    val audio = request.audioStreamIndex ?: PlaybackMedia.defaultStream(
                        next.mediaSource.mediaStreams,
                        MediaStreamType.Audio,
                        next.mediaSource.defaultAudioStreamIndex,
                        preferredLanguage
                    )
                    val subtitle = request.subtitleStreamIndex ?: PlaybackMedia.defaultStream(
                        next.mediaSource.mediaStreams,
                        MediaStreamType.Subtitle,
                        next.mediaSource.defaultSubtitleStreamIndex,
                        preferredLanguage
                    )
                    mutable.value = mutable.value.copy(decision = next, audioIndex = audio, subtitleIndex = subtitle)
                    if (closing) {
                        // PlaybackInfo may already have created an encode even though the screen is gone.
                        finishDecision()
                    } else {
                        try {
                            output.open(next, lastPosition.milliseconds, audio, subtitle, paused)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failed(true, next)
                        }
                    }
                }
            }
        }
    }

    private fun progressInfo(): PlaybackProgressInfo {
        capture()
        return PlaybackReports(requireNotNull(decision)).progress(
            lastPosition.milliseconds.milliseconds,
            lastPosition.paused,
            mutable.value.audioIndex,
            mutable.value.subtitleIndex
        )
    }

    private suspend fun finishDecision(failed: Boolean = false) {
        cancelBuffering()
        ticker?.cancel()
        val current = decision ?: return
        // Freeze the snapshot and detach callbacks before any suspending reports or new negotiation.
        output.release()
        if (ready) report(gateway.progress(progressInfo()))
        report(gateway.stopped(PlaybackReports(current).stopped(lastPosition.milliseconds.milliseconds, failed)))
        if (current.method == PlayMethod.Transcode) current.playSessionId?.let { report(gateway.stopEncoding(it)) }
        ready = false
        decision = null
    }

    private fun report(result: ClientResult<Unit>) {
        if (result is ClientResult.Failure) mutable.value = mutable.value.copy(reportError = result.error.message)
    }
}
