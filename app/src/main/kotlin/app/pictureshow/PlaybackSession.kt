/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.playback.AudioOnlyRecovery
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
import app.pictureshow.core.playback.TranscodeSeek
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** Narrow ports allow protocol order and real time scheduling to be tested without a decoder or a server. */
internal interface PlaybackGateway {
    suspend fun prepare(request: PlaybackRequest): ClientResult<PlayOutcome>
    suspend fun start(info: PlaybackProgressInfo): ClientResult<Unit>
    suspend fun progress(info: PlaybackProgressInfo): ClientResult<Unit>
    suspend fun stopped(info: PlaybackStopInfo): ClientResult<Unit>
    suspend fun stopEncoding(sessionId: String): ClientResult<Unit>
    suspend fun played(): ClientResult<Unit> = ClientResult.Success(Unit)
}

internal class ServerPlaybackGateway(
    private val client: PlaybackClient,
    private val account: Account,
    private val itemId: String,
    private val capabilities: () -> DeviceCapabilities,
    private val captionLanguage: String? = null,
    private val markPlayed: suspend () -> ClientResult<Unit>
) : PlaybackGateway {
    override suspend fun prepare(request: PlaybackRequest): ClientResult<PlayOutcome> {
        val snapshot = capabilities()
        val prepared = client.prepare(account, itemId, snapshot, request)
        // Explicit player choices, including Off, survive seeks, route changes and decoder fallback.
        if (captionLanguage == null || request.subtitleStreamIndex != null) return prepared
        val decision = ((prepared as? ClientResult.Success)?.value as? PlayOutcome.Play)?.decision ?: return prepared
        val index = captionSubtitleIndex(decision.mediaSource, captionLanguage) ?: return prepared
        if (index == decision.mediaSource.defaultSubtitleStreamIndex) return prepared
        val sourceId = decision.mediaSourceId?.takeIf(String::isNotBlank) ?: return prepared
        // Never open the first URL. A fresh PlaybackInfo with the source and track pinned lets the server
        // negotiate extraction, remuxing or burn-in, just as it does for an explicit track choice.
        return client.prepare(account, itemId, snapshot, request.copy(mediaSourceId = sourceId, subtitleStreamIndex = index))
    }
    override suspend fun start(info: PlaybackProgressInfo) = client.reportStart(account, info)
    override suspend fun progress(info: PlaybackProgressInfo) = client.reportProgress(account, info)
    override suspend fun stopped(info: PlaybackStopInfo) = client.reportStopped(account, info)
    override suspend fun stopEncoding(sessionId: String) = client.stopEncoding(account, sessionId)
    override suspend fun played() = markPlayed()
}

internal data class PlaybackPosition(val milliseconds: Long, val paused: Boolean)

internal fun startPositionApplied(requestedMs: Long, appliedMs: Long): Boolean =
    requestedMs <= 0 || kotlin.math.abs(appliedMs - requestedMs) <= 1_000

internal interface PlaybackOutput {
    fun position(): PlaybackPosition
    fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean)

    /** Return false if the chosen server stream cannot be switched in this media source. */
    fun select(type: MediaStreamType, index: Int): Boolean
    fun beginSeek() = Unit
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
    private val onFinished: () -> Unit = {},
    /** After the last server cleanup; nothing runs in the scope after this, so its owner may cancel it. */
    private val onCleanupDone: () -> Unit = {},
    /** Published synchronously, before leaving the ended player can expose cached details. */
    private val onCompletionPending: () -> Unit = {},
    private val diagnostic: (String) -> Unit = {}
) {
    private val mutable = MutableStateFlow(PlaybackState())
    val state = mutable.asStateFlow()
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var decision: PlayDecision? = null
    private var requested = PlaybackRequest()
    private var ready = false
    private var started = false

    // Retain the viewing even if replacement negotiation fails after retiring its old stream.
    private var reportingDecision: PlayDecision? = null
    private var fallbackUsed = false
    private var audioRecoveryRequest: PlaybackRequest? = null
    private var audioRecoveryStage = 0
    private var audioRecoveryCopiesVideo = false
    private var closed = false
    private var closing = false
    private var ending = false
    private var endReported = false
    private var nextCompletion: Job? = null
    private var lastPosition = PlaybackPosition(0, false)
    private var ticker: Job? = null
    private var bufferingTimer: Job? = null
    private var bufferingGeneration = 0L
    private var seekTimer: Job? = null
    private var seekSource: PlayDecision? = null
    private var pendingSeek: Long? = null
    private var seeking = false
    private val followUps = mutableListOf<Job>()
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

    /** Capture readiness before queued reports or another native seek can move the output position. */
    fun ready(
        source: PlayDecision? = decision,
        position: PlaybackPosition = output.position(),
        targetMs: Long = requested.startPosition.inWholeMilliseconds
    ) = enqueue {
        if (source !== decision || ready || decision == null || mutable.value.error != null) return@enqueue
        val target = targetMs
        val applied = position.milliseconds
        // An extractor can accept seekTo and then reset an unseekable source to zero. Do not
        // report or continue that viewing at the wrong place. Preserve the exact viewer intent.
        if (!startPositionApplied(target, applied)) {
            trace("start-rejected", "requestedMs=$target appliedMs=$applied reason=start-position-mismatch")
            recoverStart(requireNotNull(source), "start-position-mismatch")
            return@enqueue
        }
        ready = true
        mutable.value = mutable.value.copy(preparing = false, reportError = null)
        if (started) {
            gateway.progress(progressInfo())
        } else {
            started = true
            gateway.start(progressInfo())
        }
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
                // A sparse sync-sample index can require decoding from the beginning. Let the
                // server perform this initial seek once instead of waiting for the terminal timer.
                if (!ready &&
                    requested.startPosition.inWholeMilliseconds > 0 &&
                    source?.method != PlayMethod.Transcode &&
                    !fallbackUsed
                ) {
                    enqueue {
                        if (source === decision && !ready) recoverStart(requireNotNull(source), "initial-seek-timeout")
                    }
                }
                delay(35_000)
                if (source !== decision || generation != bufferingGeneration || closing) return@launch
                capture()
                trace("failure", "reason=buffering-timeout positionMs=${lastPosition.milliseconds}")
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
        if (source === decision && ready && pendingSeek == null && !seeking && mutable.value.error == null) {
            gateway.progress(progressInfo())
        }
    }

    /** Coalesce held/rapid remote steps before stopping the old encode and negotiating the final target. */
    fun seek(positionMs: Long, source: PlayDecision? = decision): Boolean {
        if (closing ||
            mutable.value.error != null ||
            source == null ||
            (source !== decision && source !== seekSource)
        ) {
            return false
        }
        if (!TranscodeSeek.required(source)) {
            // Native seeks stay local. A new intent during initial preparation supersedes the
            // opening offset, so readiness/recovery must validate and preserve this new target.
            if (!ready && source === decision) {
                requested = requested.copy(startPosition = positionMs.coerceAtLeast(0).milliseconds)
                lastPosition = output.position().copy(milliseconds = positionMs.coerceAtLeast(0))
            }
            return false
        }
        pendingSeek = positionMs.coerceIn(0, requireNotNull(source.mediaSource.runTimeTicks) / 10_000)
        seekSource = source
        output.beginSeek()
        buffering(true, source)
        if (seeking) return true
        seekTimer?.cancel()
        seekTimer = owner.launch {
            delay(250)
            enqueue {
                seeking = true
                try {
                    val current = decision ?: return@enqueue
                    capture()
                    val paused = lastPosition.paused
                    retireStream(releaseOutput = false)
                    while (pendingSeek != null && !closing && mutable.value.error == null) {
                        val target = requireNotNull(pendingSeek)
                        pendingSeek = null
                        negotiate(
                            requested.copy(
                                startPosition = target.milliseconds,
                                mediaSourceId = current.mediaSourceId,
                                audioStreamIndex = mutable.value.audioIndex,
                                subtitleStreamIndex = mutable.value.subtitleIndex
                            ),
                            paused,
                            openWhen = { pendingSeek == null }
                        )
                    }
                } finally {
                    seeking = false
                    seekSource = null
                    pendingSeek = null
                }
            }
        }
        return true
    }

    fun select(type: MediaStreamType, index: Int) = enqueue {
        if (mutable.value.error != null) return@enqueue
        if (type !in listOf(MediaStreamType.Audio, MediaStreamType.Subtitle)) return@enqueue
        val current = decision ?: return@enqueue
        capture()
        val nextAudio = if (type == MediaStreamType.Audio) index else mutable.value.audioIndex
        val nextSubtitle = if (type == MediaStreamType.Subtitle) index else mutable.value.subtitleIndex
        val leavingAudioRecovery = type == MediaStreamType.Audio && index != mutable.value.audioIndex && audioRecoveryRequest != null
        val baseline = audioRecoveryRequest
        if (leavingAudioRecovery) {
            requested = requireNotNull(baseline)
            audioRecoveryRequest = null
            audioRecoveryStage = 0
        }
        if (!leavingAudioRecovery && current.method != PlayMethod.Transcode && output.select(type, index)) {
            mutable.value = mutable.value.copy(audioIndex = nextAudio, subtitleIndex = nextSubtitle)
            requested = requested.copy(audioStreamIndex = nextAudio, subtitleStreamIndex = nextSubtitle)
            if (ready) gateway.progress(progressInfo())
        } else {
            // An unsupported in-player audio choice also uses the server. Keep the original
            // policy so the next working track can return to direct playback without an error.
            if (type == MediaStreamType.Audio && !leavingAudioRecovery && audioRecoveryRequest == null) {
                audioRecoveryRequest = requested
                audioRecoveryCopiesVideo = AudioOnlyRecovery.hasDirectVideo(current)
            }
            retireStream(releaseOutput = false)
            negotiate(
                requested.copy(
                    startPosition = lastPosition.milliseconds.milliseconds,
                    mediaSourceId = current.mediaSourceId,
                    audioStreamIndex = nextAudio,
                    subtitleStreamIndex = nextSubtitle,
                    enableDirectPlay = (leavingAudioRecovery || TranscodeSeek.required(current)) && requested.enableDirectPlay,
                    enableDirectStream = (leavingAudioRecovery || TranscodeSeek.required(current)) && requested.enableDirectStream,
                    requireVideoStreamCopy = if (type == MediaStreamType.Audio &&
                        !leavingAudioRecovery
                    ) {
                        audioRecoveryCopiesVideo
                    } else {
                        requested.requireVideoStreamCopy
                    }
                ),
                lastPosition.paused
            )
        }
    }

    fun routeChanged() = enqueue {
        if (mutable.value.error != null) return@enqueue
        val current = decision ?: return@enqueue
        capture()
        audioRecoveryRequest?.let { requested = it }
        audioRecoveryRequest = null
        audioRecoveryStage = 0
        retireStream(releaseOutput = false)
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

    private suspend fun recoverStart(source: PlayDecision, reason: String) {
        val target = requested.startPosition.inWholeMilliseconds
        val paused = output.position().paused
        if (source.method != PlayMethod.Transcode && !fallbackUsed) {
            fallbackUsed = true
            trace("fallback", "reason=$reason targetMs=$target")
            retireStream(releaseOutput = false)
            negotiate(
                requested.copy(
                    mediaSourceId = source.mediaSourceId,
                    audioStreamIndex = mutable.value.audioIndex,
                    subtitleStreamIndex = mutable.value.subtitleIndex,
                    enableDirectPlay = false,
                    enableDirectStream = false,
                    allowVideoStreamCopy = false
                ),
                paused
            )
        } else {
            output.pause()
            trace("failure", "reason=$reason requestedMs=$target")
            mutable.value = mutable.value.copy(
                preparing = false,
                error = "Playback failed. The server could not provide a playable stream."
            )
            finishDecision(failed = true)
        }
    }

    private enum class AudioRecovery { Decode, Server }

    fun failed(canRetryWithTranscode: Boolean, source: PlayDecision? = decision, reason: String = "player-error") =
        handleFailure(canRetryWithTranscode, source, reason, null)

    fun audioFailed(source: PlayDecision? = decision, retryLocally: Boolean = false) = handleFailure(
        true,
        source,
        if (retryLocally) "audio-output-local" else "audio-output",
        if (retryLocally) AudioRecovery.Decode else AudioRecovery.Server
    )

    private fun handleFailure(canRetryWithTranscode: Boolean, source: PlayDecision?, reason: String, audioRecovery: AudioRecovery?) =
        enqueue {
            if (mutable.value.error != null || source !== decision) return@enqueue
            val current = decision ?: return@enqueue
            capture()
            if (audioRecovery != null) {
                if (audioRecoveryStage < 2 && canRetryWithTranscode) {
                    val baseline = audioRecoveryRequest ?: requested.also {
                        audioRecoveryRequest = it
                        audioRecoveryCopiesVideo = AudioOnlyRecovery.hasDirectVideo(current)
                    }
                    val local = audioRecovery == AudioRecovery.Decode && audioRecoveryStage == 0
                    audioRecoveryStage = if (local) 1 else 2
                    trace("fallback", "reason=$reason audioOnly=true local=$local targetMs=${lastPosition.milliseconds}")
                    retireStream(releaseOutput = false)
                    negotiate(
                        baseline.copy(
                            startPosition = lastPosition.milliseconds.milliseconds,
                            mediaSourceId = current.mediaSourceId,
                            audioStreamIndex = mutable.value.audioIndex,
                            subtitleStreamIndex = mutable.value.subtitleIndex,
                            enableDirectPlay = local && baseline.enableDirectPlay,
                            enableDirectStream = local && baseline.enableDirectStream,
                            allowAudioStreamCopy = local && baseline.allowAudioStreamCopy,
                            requireVideoStreamCopy = !local && audioRecoveryCopiesVideo
                        ),
                        lastPosition.paused
                    )
                } else {
                    trace("failure", "reason=$reason audioOnly=true targetMs=${lastPosition.milliseconds}")
                    output.pause()
                    mutable.value =
                        mutable.value.copy(preparing = false, error = "Playback failed. The server could not provide a playable stream.")
                    finishDecision(failed = true)
                }
                return@enqueue
            }
            val retry = canRetryWithTranscode && current.method != PlayMethod.Transcode && !fallbackUsed
            trace(if (retry) "fallback" else "failure", "reason=$reason targetMs=${lastPosition.milliseconds}")
            if (retry) retireStream(releaseOutput = false) else finishDecision(failed = true)
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

    /** EOS is authoritative even when the server runtime is wrong. Stop must land before played. */
    fun ended(source: PlayDecision? = decision): Boolean {
        if (closing || ending || source == null || source !== decision || mutable.value.error != null) return false
        ending = true
        onCompletionPending()
        capture()
        trace("ended", "positionMs=${lastPosition.milliseconds}")
        output.release()
        ticker?.cancel()
        cancelBuffering()
        // This command survives a close queued by navigation immediately after the decoder's EOS.
        commands.trySend {
            if (source === decision && mutable.value.error == null) {
                val stopped = finishDecision()
                if (stopped) {
                    reportPlayed()
                }
                endReported = true
                onFinished()
            }
            ending = false
        }
        return true
    }

    /** Snapshot before releasing the actual player. Final network work survives the screen's disposal. */
    fun close(afterFinished: () -> Unit = {}) {
        // Back/Stop also cancels a played request that is still waiting on the network.
        nextCompletion?.cancel()
        closeForNext({ false }, afterFinished)
    }

    /** A deliberate card advance completes only while navigation still intends to open the next item. */
    fun closeForNext(shouldAdvance: () -> Boolean, afterFinished: () -> Unit) {
        if (closing) return
        val completing = !ending && !endReported && started && mutable.value.error == null && shouldAdvance()
        if (completing) onCompletionPending()
        trace("close", "positionMs=${output.position().milliseconds} ending=$ending")
        closing = true
        seekTimer?.cancel()
        val finalSeekTarget = pendingSeek
        pendingSeek = null
        lastPosition =
            output.position().let {
                when {
                    finalSeekTarget != null -> it.copy(milliseconds = finalSeekTarget)
                    ready -> it
                    else -> it.copy(milliseconds = maxOf(it.milliseconds, lastPosition.milliseconds))
                }
            }
        output.release()
        commands.trySend {
            if (!closed) {
                closed = true
                ticker?.cancel()
                cancelBuffering()
                val stopped = finishDecision()
                // Back/Stop can cancel a switch while the final Stop request is suspended.
                if (stopped && completing && shouldAdvance()) {
                    val completion = owner.launch { reportPlayed() }
                    nextCompletion = completion
                    completion.join()
                    nextCompletion = null
                }
                if (!endReported) onFinished()
                afterFinished()
                commands.close()
                followUps.toList().joinAll()
                onCleanupDone()
            }
        }
    }

    private suspend fun reportPlayed() {
        var result = gateway.played()
        if (result is ClientResult.Failure) result = gateway.played()
        if (result is ClientResult.Failure) mutable.value = mutable.value.copy(reportError = result.error.message)
    }

    private fun enqueue(block: suspend () -> Unit) {
        if (!closing && !ending) commands.trySend { if (!closed && !closing) block() }
    }

    private fun capture() {
        val actual = output.position()
        lastPosition = if (ready) actual else actual.copy(milliseconds = maxOf(actual.milliseconds, lastPosition.milliseconds))
    }

    private suspend fun negotiate(request: PlaybackRequest, paused: Boolean, openWhen: () -> Boolean = { true }) {
        requested = request
        trace(
            "prepare",
            "requestedMs=${request.startPosition.inWholeMilliseconds} directPlay=${request.enableDirectPlay} directStream=${request.enableDirectStream}"
        )
        ready = false
        lastPosition = PlaybackPosition(request.startPosition.inWholeMilliseconds, paused)
        mutable.value = mutable.value.copy(preparing = true, error = null, reportError = null)
        when (val result = gateway.prepare(request)) {
            is ClientResult.Failure -> {
                trace("failure", "reason=negotiation-${result.error.kind}")
                mutable.value = mutable.value.copy(preparing = false, error = result.error.message)
                finishDecision(failed = !closing)
            }
            is ClientResult.Success -> when (val outcome = result.value) {
                is PlayOutcome.Unplayable -> {
                    trace("failure", "reason=no-playable-source")
                    mutable.value = mutable.value.copy(preparing = false, error = outcome.message)
                    finishDecision(failed = !closing)
                }
                is PlayOutcome.Play -> {
                    val next = outcome.decision
                    decision = next
                    reportingDecision = next
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
                    } else if (!openWhen()) {
                        retireStream(releaseOutput = false)
                    } else {
                        try {
                            output.open(
                                next,
                                lastPosition.milliseconds,
                                audio,
                                subtitle,
                                if (started || seeking) output.position().paused else paused
                            )
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

    private fun trace(event: String, fields: String) {
        diagnostic(PlaybackDiagnostics.line(decision ?: reportingDecision, event, fields))
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

    /** Retire an encode, not a viewing. A new PlaybackInfo gets a new id for scoped delayed cleanup. */
    private suspend fun retireStream(releaseOutput: Boolean = true) {
        cancelBuffering()
        ticker?.cancel()
        val current = decision
        if (releaseOutput) output.release() else output.beginSeek()
        ready = false
        decision = null
        if (current?.method == PlayMethod.Transcode) current.playSessionId?.let { stopEncoding(it) }
    }

    private suspend fun finishDecision(failed: Boolean = false, releaseOutput: Boolean = true): Boolean {
        cancelBuffering()
        ticker?.cancel()
        val current = decision ?: reportingDecision ?: return false
        if (releaseOutput) output.release()
        if (ready) gateway.progress(progressInfo())
        val stopped = reportStopped(PlaybackReports(current).stopped(lastPosition.milliseconds.milliseconds, failed))
        // Retired encodes already have their two scoped DELETEs scheduled.
        if (decision?.method == PlayMethod.Transcode) current.playSessionId?.let { stopEncoding(it) }
        ready = false
        decision = null
        reportingDecision = null
        return stopped
    }

    /**
     * A segment request already on its way when the player let go can start a fresh encode just after this DELETE
     * (seen after a fast-forward give-up: the new job ran until the server reaped it), so a second one follows. It also
     * covers a first DELETE that failed. The play session id is never reused by a later negotiation.
     */
    private suspend fun stopEncoding(sessionId: String) {
        gateway.stopEncoding(sessionId)
        followUps.removeAll { it.isCompleted }
        followUps += owner.launch {
            delay(3_000)
            gateway.stopEncoding(sessionId)
        }
    }

    /**
     * Periodic reports retry on the ticker's own schedule, so a single failure is never shown to the viewer. The
     * final stop report is the one that keeps the viewer's place, so it gets one retry before it is surfaced.
     */
    private suspend fun reportStopped(info: PlaybackStopInfo): Boolean {
        var result = gateway.stopped(info)
        if (result is ClientResult.Failure) result = gateway.stopped(info)
        if (result is ClientResult.Failure) mutable.value = mutable.value.copy(reportError = result.error.message)
        return result is ClientResult.Success
    }
}
