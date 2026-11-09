/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import app.pictureshow.core.Server
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.TranscodeSeek
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The only layer that owns a decoder. Replacing a source releases the old player and its callbacks first. */
internal class Media3PlaybackOutput(
    private val context: Context,
    private val server: Server,
    private val title: String? = null,
    private val upstream: androidx.media3.datasource.DataSource.Factory? = null,
    private val renderersFactory: RenderersFactory? = null,
    private val subtitleDelay: SubtitleDelay = SubtitleDelay(),
    private val sourceWrapper: (
        (androidx.media3.exoplayer.source.MediaSource.Factory) -> androidx.media3.exoplayer.source.MediaSource.Factory
    )? = null
) : PlaybackOutput {
    private val viewingSpeed = ViewingPlaybackSpeed()
    val speed = viewingSpeed.state
    fun setSpeed(value: Float) {
        viewingSpeed.select(value, mutablePlayer.value)
        PlaybackDiagnostics.write("speed requested=$value effective=${speed.value.speed} pitch=1.0 positionMs=${position().milliseconds}")
    }
    private val mutablePlayer = MutableStateFlow<Player?>(null)
    val player = mutablePlayer.asStateFlow()
    var subtitleDiagnostics: SubtitleCueDiagnostics? = null
        private set
    var onReady: (PlayDecision, PlaybackPosition, Long) -> Unit = { _, _, _ -> }
    var onBuffering: (Boolean, PlayDecision) -> Unit = { _, _ -> }
    var onProgress: (PlayDecision) -> Unit = {}
    var onFailure: (Boolean, PlayDecision) -> Unit = { _, _ -> }
    var onAudioFailure: (PlayDecision, Boolean) -> Unit = { source, _ -> onFailure(false, source) }
    var onEnded: (PlayDecision) -> Unit = {}
    var onStop: () -> Unit = {}
    private val fdDiagnostics = PlaybackFdDiagnostics()
    private var activePlayer: ExoPlayer? = null
    private var loadDiagnostics: PlaybackLoadDiagnostics? = null
    private val loadHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val loadSummary = object : Runnable {
        override fun run() {
            loadDiagnostics?.summary()
            if (loadDiagnostics != null) loadHandler.postDelayed(this, 10_000)
        }
    }
    private var playback: PlaybackReleaseFence.Playback? = null
    private var reopenState: ReopeningPlayer? = null
    var onSeek: (Long, PlayDecision) -> Boolean = { _, _ -> false }
    private var current: PlayDecision? = null
    private var saved = PlaybackPosition(0, false)
    private val mutableForeground = MutableStateFlow(true)
    val foreground = mutableForeground.asStateFlow()
    private val mutablePauseIntent = MutableStateFlow(false)
    val pauseIntent = mutablePauseIntent.asStateFlow()

    /** Whether the viewer had paused when the app, or another app taking the audio, last stopped this. The player's state can't tell them apart. */
    private var pausedBeforeApp: Boolean? = null

    /**
     * The viewer's own pause, which the app going away, stopping for a failure or a handoff, or losing the audio to another app isn't.
     * An unplugged headset counts as theirs: pulling it is the viewer's act.
     */
    fun viewerPaused(player: Player? = mutablePlayer.value): Boolean =
        pausedBeforeApp ?: (mutablePauseIntent.value || player?.playWhenReady == false)

    var stopGeneration: Long = 0
        private set

    /** Stop belongs to the viewing, including opens that have not published a player yet. */
    fun setForeground(value: Boolean) {
        if (!value && mutableForeground.value) stopGeneration++
        mutableForeground.value = value
        if (!value) pauseForApp()
        val snapshot = position()
        PlaybackDiagnostics.write("lifecycle foreground=$value paused=${snapshot.paused} positionMs=${snapshot.milliseconds}")
    }

    internal fun playIntent(requested: Boolean): Boolean {
        if (requested && !mutableForeground.value) return false
        if (requested) {
            mutablePauseIntent.value = false
            pausedBeforeApp = null
        }
        return requested
    }
    private var completedPosition: PlaybackPosition? = null
    private var audio = -1
    private var subtitle = -1
    private var listener: Player.Listener? = null
    private var mediaSession: PlaybackMediaSession.Handle? = null
    private var subtitleServerStartMs = 0L
    private var physicalEnd: PhysicalPlaybackEnd? = null
    var passthroughEnabled: Boolean = true
    val subtitleDelayMs: Int get() = subtitleDelay.milliseconds

    fun setSubtitleDelayMs(milliseconds: Int) {
        if (!subtitleDelay.set(milliseconds)) return
        // TextRenderer discards old cue history. Re-read at the current point after offset changes.
        // A one-millisecond nudge avoids ExoPlayer optimizing an exactly identical seek away.
        mutablePlayer.value?.let { active ->
            val position = active.currentPosition.coerceAtLeast(0)
            val target = if (active.duration > 0 && position >= active.duration) (position - 1).coerceAtLeast(0) else position + 1
            active.seekTo(target)
        }
    }

    override fun position(): PlaybackPosition {
        val snapshot = completedPosition ?: mutablePlayer.value?.let(::boundedPlaybackPosition) ?: saved
        return if (mutablePauseIntent.value || !mutableForeground.value) snapshot.copy(paused = true) else snapshot
    }

    override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) {
        releasePlayer(closeSession = false)
        current = decision
        val captions = SubtitleCueDiagnostics(decision)
        subtitleDiagnostics = captions
        captions.selected(subtitleIndex, paused, positionMs)
        audio = audioIndex
        subtitle = subtitleIndex
        saved = PlaybackPosition(positionMs, paused || mutablePauseIntent.value || !mutableForeground.value)
        completedPosition = null
        val selector = PictureshowTrackSelector(context, decision.method == PlayMethod.Transcode)
        selector.parameters = selector.buildUponParameters()
            .setPreferredAudioLanguage(Locale.getDefault().language)
            .setPreferredTextLanguage(Locale.getDefault().language)
            .setSelectUndeterminedTextLanguage(false)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, audio < 0)
            // Publish track groups before selecting the exact server index. Otherwise Media3's
            // language default can briefly start an unselected external subtitle request.
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        val serverSeek = TranscodeSeek.required(decision)
        val videoClock = serverSeek && !TranscodeSeek.audioAlreadyEncodes(decision)
        val startMs = if (serverSeek) TranscodeSeek.startPositionMs(decision, positionMs) else 0
        val media = PlaybackMedia.item(server, decision, title, if (serverSeek) startMs else null)
        PlaybackDiagnostics.write(
            decision,
            "open",
            "requestedMs=$positionMs serverMs=$startMs method=${decision.method} serverSeek=$serverSeek " +
                "route=${if (videoClock) {
                    "progressive-video-clock"
                } else if (serverSeek) {
                    "progressive-encoded-audio"
                } else {
                    "native"
                }} " +
                "path=${media.localConfiguration?.uri?.path}"
        )
        lateinit var next: ExoPlayer
        var videoRenderers = emptyList<EndVideoRenderer>()
        val videoTail = VideoTail()
        val playing = java.util.concurrent.atomic.AtomicBoolean(false)
        fun logVideo(reason: String) {
            videoRenderers.forEach {
                PlaybackDiagnostics.write(
                    decision,
                    "video-end",
                    it.endSnapshot.diagnostic(reason) +
                        " extracted=${videoTail.extracted} tailUs=${videoTail.lastSampleUs} durationUs=${videoTail.durationUs}"
                )
            }
        }
        val endSignal = PlaybackEndSignal({ next.takeIf { activePlayer === it } }) { reason ->
            logVideo(reason)
            captureCompletedPosition(decision)
            PlaybackDiagnostics.write(decision, "end", "reason=$reason positionMs=${position().milliseconds}")
            onEnded(decision)
        }
        val detector = if (decision.method != PlayMethod.Transcode) {
            PhysicalPlaybackEnd(
                requireNotNull(media.localConfiguration).uri,
                canEnd = endSignal::canEnd,
                videoTail = videoTail,
                videoSnapshots = {
                    videoRenderers.filter { it.state != androidx.media3.exoplayer.Renderer.STATE_DISABLED }.map { it.endSnapshot }
                },
                isPlaying = { playing.get() },
                canEndVideoStalled = endSignal::canEndPlaying,
                onVideoStalled = { endSignal.ended("video-stalled") },
                diagnostic = { PlaybackDiagnostics.write(decision, "source-end", it) }
            ) { endSignal.ended("av-drained") }
        } else {
            null
        }
        physicalEnd = detector
        val loads = PlaybackLoadDiagnostics()
        loadDiagnostics = loads
        val sourceData = PlaybackMedia.dataSourceFactory(
            server,
            decision,
            upstream ?: PlaybackMedia.httpFactory(server, decision, nativeHlsMedia = false)
        )
        val dataSources = androidx.media3.datasource.DataSource.Factory {
            sourceData.createDataSource().also { it.addTransferListener(loads) }
        }
        val extractors = if (videoClock) {
            VideoClockExtractorsFactory { PlaybackDiagnostics.write(decision, "video-clock", it) }
        } else {
            if (detector != null) VideoTailExtractorsFactory(videoTail) else DefaultExtractorsFactory()
        }
        val sourceFactory = DefaultMediaSourceFactory(context, extractors)
            .setDataSourceFactory(detector?.sourceFactory(dataSources) ?: dataSources)
        val baseRenderers =
            renderersFactory ?: PictureshowRenderersFactory(context, subtitleDelay, passthroughEnabled, captions) { encoded ->
                loadHandler.post {
                    if (current === decision) viewingSpeed.audioOutput(encoded, mutablePlayer.value)
                }
            }
        val renderers = RenderersFactory { handler, video, audio, text, metadata ->
            val created = baseRenderers.createRenderers(handler, video, audio, text, metadata)
            videoRenderers = created.filterIsInstance<EndVideoRenderer>()
            val monitored = VideoEndDiagnostics { logVideo("audio-drained-video-pending") }.wrap(created)
            val final = detector?.wrap(monitored, handler) ?: monitored
            final.map { renderer ->
                if (renderer.trackType == C.TRACK_TYPE_TEXT) {
                    OptionalSubtitleRenderer(renderer) {
                        val av = final.filter {
                            it.trackType in listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO) &&
                                it.state != androidx.media3.exoplayer.Renderer.STATE_DISABLED
                        }
                        av.isNotEmpty() && av.all { it.isEnded }
                    }
                } else {
                    renderer
                }
            }.toTypedArray()
        }
        val playbackThread = PlaybackReleaseFence.playback(context)
        playback = playbackThread
        fdDiagnostics.opened(playbackThread::hasUnfinishedPredecessors)
        next = try {
            ExoPlayer.Builder(context, renderers)
                .setPlaybackLooper(playbackThread.looper)
                .setTrackSelector(selector)
                .setMediaSourceFactory(sourceWrapper?.invoke(sourceFactory) ?: sourceFactory)
                .setLoadControl(createPictureshowLoadControl())
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build()
        } catch (failure: Throwable) {
            playbackThread.abortConstruction()
            playback = null
            throw failure
        }
        activePlayer = next
        subtitleServerStartMs = if (serverSeek) startMs else 0
        updateSubtitleOffset(subtitleIndex)
        next.setAudioAttributes(AudioAttributes.DEFAULT, true)
        next.setHandleAudioBecomingNoisy(true)
        next.addAnalyticsListener(loads)
        loadHandler.postDelayed(loadSummary, 10_000)
        next.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            private var firstVideoDecoder = true
            override fun onVideoDecoderInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                if (activePlayer === next && firstVideoDecoder) {
                    firstVideoDecoder = false
                    PlaybackDiagnostics.write(
                        decision,
                        "video-decoder",
                        "name=$decoderName initMs=$initializationDurationMs " +
                            playbackThread.videoInitFields(initializedTimestampMs, initializationDurationMs)
                    )
                }
            }

            override fun onAudioDecoderInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                if (activePlayer ===
                    next
                ) {
                    PlaybackDiagnostics.write(decision, "audio-decoder", "name=$decoderName initMs=$initializationDurationMs")
                }
            }

            override fun onAudioUnderrun(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long
            ) {
                if (activePlayer === next) {
                    PlaybackDiagnostics.write(
                        decision,
                        "audio-underrun",
                        "bytes=$bufferSize bufferMs=$bufferSizeMs sinceFeedMs=$elapsedSinceLastFeedMs bufferedMs=${next.totalBufferedDuration}"
                    )
                }
            }
        })
        // Set decoder parameters before the stateful wrappers can snapshot an empty, idle player.
        viewingSpeed.apply(next)
        val gated = ReleaseGatedPlayer(next)
        val managed = ReopeningPlayer(gated, ::playIntent)
        reopenState = managed
        val exposed = if (serverSeek) {
            TranscodeSeekPlayer(managed, positionMs, requireNotNull(decision.mediaSource.runTimeTicks) / 10_000) { target ->
                onSeek(target, decision)
            }
        } else {
            managed
        }
        var initialReady = false
        var initialTargetMs = positionMs
        listener = object : Player.Listener {
            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                if (activePlayer === next) viewingSpeed.observed(playbackParameters)
            }

            override fun onRenderedFirstFrame() {
                if (BuildConfig.DEBUG && activePlayer === next) {
                    Log.d(
                        "PictureshowPlayback",
                        "first-frame uptimeMs=${SystemClock.elapsedRealtime()} positionMs=${position().milliseconds}"
                    )
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (activePlayer === next) {
                    playing.set(player.isPlaying)
                    onBuffering(player.playbackState == Player.STATE_BUFFERING && player.playWhenReady, decision)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (activePlayer !== next) return
                if (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY) {
                    PlaybackDiagnostics.write(
                        decision,
                        "buffer-state",
                        "state=$playbackState bufferedMs=${next.totalBufferedDuration} loading=${next.isLoading} positionMs=${position().milliseconds}"
                    )
                }
                if (playbackState == Player.STATE_READY) {
                    val readyPosition = position()
                    val readyTargetMs = initialTargetMs
                    PlaybackDiagnostics.write(
                        decision,
                        "ready",
                        "requestedMs=$readyTargetMs appliedMs=${readyPosition.milliseconds} paused=${readyPosition.paused} foreground=${mutableForeground.value} seekable=${next.isCurrentMediaItemSeekable} durationMs=${exposed.duration}"
                    )
                    if (!initialReady) {
                        initialReady = true
                        // Stop the loader immediately, before a zero-reset source can keep playing.
                        // stop() retains playWhenReady so recovery preserves the viewer's pause intent.
                        if (!startPositionApplied(readyTargetMs, readyPosition.milliseconds)) {
                            managed.beginReopen()
                            next.stop()
                        }
                    }
                    onReady(decision, readyPosition, readyTargetMs)
                }
                if (playbackState == Player.STATE_ENDED) endSignal.ended()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (activePlayer === next) {
                    PlaybackDiagnostics.write(
                        decision,
                        "play-state",
                        "playing=$isPlaying paused=${position().paused} foreground=${mutableForeground.value} positionMs=${position().milliseconds}"
                    )
                }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (activePlayer === next) {
                    // Another app took the audio for good and the player stopped itself. It was playing, so the viewer hadn't paused.
                    if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS && !playWhenReady) pausedBeforeApp = false
                    onProgress(decision)
                    // Media3 may reach ENDED after a paused seek. Play completes the held item;
                    // the published wrapper prevents the usual Play-at-ENDED rewind.
                    if (playWhenReady && next.playbackState == Player.STATE_ENDED) endSignal.ended()
                }
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (activePlayer === next) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        detector?.invalidate()
                        if (!serverSeek) {
                            initialTargetMs = newPosition.positionMs
                            onSeek(newPosition.positionMs, decision)
                        }
                    }
                    onProgress(decision)
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                if (activePlayer === next) {
                    captions.tracks(decision.mediaSource.mediaStreams, tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT })
                    if (decision.method != PlayMethod.Transcode) selectTrack(MediaStreamType.Audio, audio, false)
                    val selected = decision.mediaSource.mediaStreams.firstOrNull {
                        it.index == subtitle &&
                            it.type == MediaStreamType.Subtitle
                    }
                    if (selected?.deliveryMethod != app.pictureshow.core.playback.SubtitleDeliveryMethod.Encode) {
                        selectTrack(MediaStreamType.Subtitle, subtitle, false)
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (activePlayer === next) {
                    detector?.invalidate()
                    // Never display/log the exception: upstream source errors include request URLs.
                    val retryable = error is ExoPlaybackException &&
                        error.type in listOf(ExoPlaybackException.TYPE_SOURCE, ExoPlaybackException.TYPE_RENDERER)
                    PlaybackDiagnostics.write(
                        decision,
                        "player-error",
                        "code=${error.errorCode} type=${(error as? ExoPlaybackException)?.type ?: -1} retryable=$retryable positionMs=${position().milliseconds}"
                    )
                    val refusal = generateSequence<Throwable>(error) {
                        it.cause
                    }.filterIsInstance<EncodedAudioOutputRefused>().firstOrNull()
                    val changed = refusal?.let { AudioOutputFailures(context).reject(it.encoding, it.route) } == true
                    val audioError = error.errorCode in setOf(
                        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
                    ) ||
                        (
                            error is ExoPlaybackException &&
                                error.type == ExoPlaybackException.TYPE_RENDERER &&
                                error.rendererFormat?.sampleMimeType?.startsWith("audio/") == true
                            )
                    if (audioError) {
                        PlaybackDiagnostics.write(decision, "audio-recovery", "encoding=${refusal?.encoding ?: -1} rejected=$changed")
                        onAudioFailure(decision, changed)
                    } else {
                        onFailure(retryable, decision)
                    }
                }
            }
        }.also(exposed::addListener)
        mutablePlayer.value = exposed
        if (decision.method == PlayMethod.Transcode &&
            !serverSeek &&
            androidx.media3.common.util.Util.inferContentTypeForUriAndMimeType(
                requireNotNull(media.localConfiguration).uri,
                media.localConfiguration?.mimeType
            ) == C.CONTENT_TYPE_HLS
        ) {
            val segmentData = PlaybackMedia.dataSourceFactory(server, decision, upstream ?: PlaybackMedia.httpFactory(server, decision))
            val segments = androidx.media3.datasource.DataSource.Factory {
                segmentData.createDataSource().also { it.addTransferListener(loads) }
            }
            val hls = HlsPreparationSource.create(media, dataSources, segments, captions::loaded) { requestedUs, prepareUs ->
                PlaybackDiagnostics.write(decision, "hls-bootstrap", "requestedUs=$requestedUs prepareUs=$prepareUs")
            }
            next.setMediaSource(hls, positionMs)
        } else {
            val unclipped = media.buildUpon().setSubtitleConfigurations(emptyList()).build()
            val primary = sourceFactory.createMediaSource(unclipped)
            val merged = IndependentSubtitleSource.merge(primary, media, dataSources, captions::loaded)
            val source = if (serverSeek && positionMs > startMs) {
                androidx.media3.exoplayer.source.ClippingMediaSource.Builder(merged)
                    .setStartPositionMs(positionMs - startMs).setAllowUnseekableMedia(true).build()
            } else {
                merged
            }
            next.setMediaSource(source, if (serverSeek) 0 else positionMs)
        }
        next.playWhenReady =
            app.pictureshow.core.playback.PlaybackVisibility.playWhenReady(!paused, true, mutableForeground.value, mutablePauseIntent.value)
        // Publish a prepared replacement: IDLE would advertise no media to external controllers.
        gated.hold()
        playbackThread.prepare {
            if (activePlayer === next) gated.prepareAfterRelease()
        }
        val existingSession = mediaSession
        if (existingSession == null) {
            mediaSession = PlaybackMediaSession.open(context, exposed) { onStop() }
        } else {
            existingSession.replacePlayer(exposed)
        }
    }

    override fun reselect(type: MediaStreamType, index: Int) {
        if (type == MediaStreamType.Subtitle) {
            val snapshot = position()
            subtitleDiagnostics?.selected(index, snapshot.paused, snapshot.milliseconds)
        }
    }

    override fun select(type: MediaStreamType, index: Int): Boolean = selectTrack(type, index, true)

    private fun selectTrack(type: MediaStreamType, index: Int, choice: Boolean): Boolean {
        val active = activePlayer ?: return false
        val trackType = if (type == MediaStreamType.Audio) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
        val parameters = active.trackSelectionParameters.buildUpon()
        val next = if (index < 0) {
            parameters.setTrackTypeDisabled(trackType, true).clearOverridesOfType(trackType).build()
        } else {
            val stream = current?.mediaSource?.mediaStreams?.firstOrNull { it.type == type && it.index == index } ?: return false
            val chosen = PlaybackTracks.find(
                current?.mediaSource?.mediaStreams.orEmpty(),
                stream,
                active.currentTracks.groups.filter { it.type == trackType }
            ) ?: return false
            if (!chosen.first.isTrackSupported(chosen.second, false)) return false
            parameters.setTrackTypeDisabled(trackType, false)
                .setOverrideForType(TrackSelectionOverride(chosen.first.mediaTrackGroup, chosen.second)).build()
        }
        if (type == MediaStreamType.Audio) {
            audio = index
        } else {
            subtitle = index
            if (choice) subtitleDiagnostics?.selected(index, !active.playWhenReady, position().milliseconds)
            // The playback thread may reset the text renderer as soon as this command is posted.
            // Publish its new clock first, or a full-file -> rebased switch can reset at the old
            // full-file time and discard every newly selected cue as preceding the reset.
            updateSubtitleOffset(index)
        }
        if (next != active.trackSelectionParameters) active.trackSelectionParameters = next
        return true
    }

    private fun updateSubtitleOffset(index: Int) {
        val stream = current?.mediaSource?.mediaStreams?.firstOrNull {
            it.type == MediaStreamType.Subtitle && it.index == index
        }
        subtitleDelay.sourceStartMs = app.pictureshow.core.playback.ExternalSubtitleLoading.sourceOffsetMs(
            stream != null && PlaybackMedia.subtitleUsesFullTimeline(server, stream),
            subtitleServerStartMs
        )
    }

    /** Snapshot before EOS releases the decoder; both the UI and Stop use this same final clock. */
    private fun captureCompletedPosition(decision: PlayDecision) {
        val exposed = mutablePlayer.value ?: activePlayer ?: return
        val raw = exposed.currentPosition
        val duration = exposed.duration
        completedPosition = PlaybackPosition(completedPlaybackPosition(raw, duration), !exposed.playWhenReady)
        PlaybackDiagnostics.write(
            decision,
            "end-position",
            "rawMs=$raw streamDurationMs=$duration serverRuntimeMs=${decision.mediaSource.runTimeTicks?.div(10_000)} " +
                "finalMs=${completedPosition?.milliseconds}"
        )
    }

    override fun pauseForApp() {
        val viewer = viewerPaused()
        pause()
        pausedBeforeApp = viewer
    }

    override fun pause() {
        pausedBeforeApp = null
        mutablePauseIntent.value = true
        saved = saved.copy(paused = true)
        completedPosition = completedPosition?.copy(paused = true)
        mutablePlayer.value?.pause()
        activePlayer?.pause()
    }

    override fun beginSeek() {
        physicalEnd?.invalidate()
        reopenState?.beginReopen()
        activePlayer?.stop()
    }

    override fun release() = releasePlayer(closeSession = true)

    private fun releasePlayer(closeSession: Boolean) {
        loadHandler.removeCallbacks(loadSummary)
        loadDiagnostics?.summary(final = true)
        loadDiagnostics = null
        physicalEnd?.invalidate()
        physicalEnd = null
        val active = activePlayer
        if (active == null) {
            if (closeSession) {
                mediaSession?.close()
                mediaSession = null
            }
            return
        }
        val exposed = mutablePlayer.value
        if (!closeSession) reopenState?.beginReopen()
        saved = position()
        mutablePlayer.value = null
        activePlayer = null
        listener?.let { exposed?.removeListener(it) }
        if (closeSession) {
            mediaSession?.close()
            mediaSession = null
        }
        active.stop()
        requireNotNull(playback).release(active, requireNotNull(current))
        playback = null
        reopenState = null
        listener = null
    }
}

/** The app-thread seek mask is BUFFERING before the playback thread has reset the old renderers. */
internal class PlaybackEndSignal(private val currentPlayer: () -> Player?, private val onEnd: (String) -> Unit) {
    private var sent = false
    fun canEnd(): Boolean {
        val player = currentPlayer() ?: return false
        return !sent &&
            player.playerError == null &&
            player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED)
    }

    // Called on the app thread: pause/suppression masks can precede onEvents publication.
    fun canEndPlaying(): Boolean = canEnd() && currentPlayer()?.isPlaying == true

    fun ended(reason: String = "media3-eos") {
        if (!canEnd()) return
        sent = true
        onEnd(reason)
    }
}
