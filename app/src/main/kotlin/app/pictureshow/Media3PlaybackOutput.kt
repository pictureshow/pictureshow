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
    private val renderersFactory: RenderersFactory? = null
) : PlaybackOutput {
    private val mutablePlayer = MutableStateFlow<Player?>(null)
    val player = mutablePlayer.asStateFlow()
    var onReady: (PlayDecision, PlaybackPosition, Long) -> Unit = { _, _, _ -> }
    var onBuffering: (Boolean, PlayDecision) -> Unit = { _, _ -> }
    var onProgress: (PlayDecision) -> Unit = {}
    var onFailure: (Boolean, PlayDecision) -> Unit = { _, _ -> }
    var onEnded: (PlayDecision) -> Unit = {}
    var onStop: () -> Unit = {}
    private var activePlayer: ExoPlayer? = null
    var onSeek: (Long, PlayDecision) -> Boolean = { _, _ -> false }
    private var current: PlayDecision? = null
    private var saved = PlaybackPosition(0, false)
    private var audio = -1
    private var subtitle = -1
    private var listener: Player.Listener? = null
    private var mediaSession: PlaybackMediaSession.Handle? = null
    private val subtitleDelay = SubtitleDelay()
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

    override fun position(): PlaybackPosition = mutablePlayer.value?.let {
        PlaybackPosition(it.currentPosition.coerceAtLeast(0), !it.playWhenReady)
    } ?: saved

    override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) {
        releasePlayer(closeSession = false)
        current = decision
        audio = audioIndex
        subtitle = subtitleIndex
        saved = PlaybackPosition(positionMs, paused)
        val selector = PictureshowTrackSelector(context, decision.method == PlayMethod.Transcode)
        selector.parameters = selector.buildUponParameters()
            .setPreferredAudioLanguage(Locale.getDefault().language)
            .setPreferredTextLanguage(Locale.getDefault().language)
            .setSelectUndeterminedTextLanguage(false)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, audio < 0)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitle < 0)
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
        val dataSources = PlaybackMedia.dataSourceFactory(server, decision, upstream ?: PlaybackMedia.httpFactory(server, decision))
        val extractors = if (videoClock) {
            VideoClockExtractorsFactory { PlaybackDiagnostics.write(decision, "video-clock", it) }
        } else {
            if (detector != null) VideoTailExtractorsFactory(videoTail) else DefaultExtractorsFactory()
        }
        val sourceFactory = DefaultMediaSourceFactory(context, extractors)
            .setDataSourceFactory(detector?.sourceFactory(dataSources) ?: dataSources)
        val baseRenderers = renderersFactory ?: PictureshowRenderersFactory(context, subtitleDelay, passthroughEnabled)
        val renderers = RenderersFactory { handler, video, audio, text, metadata ->
            val created = baseRenderers.createRenderers(handler, video, audio, text, metadata)
            videoRenderers = created.filterIsInstance<EndVideoRenderer>()
            val monitored = VideoEndDiagnostics { logVideo("audio-drained-video-pending") }.wrap(created)
            detector?.wrap(monitored, handler) ?: monitored
        }
        next = ExoPlayer.Builder(context, renderers)
            .setTrackSelector(selector)
            .setMediaSourceFactory(sourceFactory)
            .setLoadControl(createPictureshowLoadControl())
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        subtitleDelay.sourceStartMs = if (serverSeek &&
            decision.mediaSource.mediaStreams.any {
                it.type == MediaStreamType.Subtitle && it.index == subtitleIndex && PlaybackMedia.subtitleUsesFullTimeline(server, it)
            }
        ) {
            startMs
        } else {
            0
        }
        next.setAudioAttributes(AudioAttributes.DEFAULT, true)
        next.setHandleAudioBecomingNoisy(true)
        activePlayer = next
        val exposed = if (serverSeek) {
            TranscodeSeekPlayer(next, positionMs, requireNotNull(decision.mediaSource.runTimeTicks) / 10_000) { target ->
                onSeek(target, decision)
            }
        } else {
            next
        }
        var initialReady = false
        var initialTargetMs = positionMs
        listener = object : Player.Listener {
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
                if (playbackState == Player.STATE_READY) {
                    val readyPosition = position()
                    val readyTargetMs = initialTargetMs
                    PlaybackDiagnostics.write(
                        decision,
                        "ready",
                        "requestedMs=$readyTargetMs appliedMs=${readyPosition.milliseconds} seekable=${next.isCurrentMediaItemSeekable} durationMs=${exposed.duration}"
                    )
                    if (!initialReady) {
                        initialReady = true
                        // Stop the loader immediately, before a zero-reset source can keep playing.
                        // stop() retains playWhenReady so recovery preserves the viewer's pause intent.
                        if (!startPositionApplied(readyTargetMs, readyPosition.milliseconds)) next.stop()
                    }
                    onReady(decision, readyPosition, readyTargetMs)
                }
                if (playbackState == Player.STATE_ENDED) endSignal.ended()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (activePlayer === next) onProgress(decision)
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
                    if (decision.method != PlayMethod.Transcode) select(MediaStreamType.Audio, audio)
                    val selected = decision.mediaSource.mediaStreams.firstOrNull {
                        it.index == subtitle &&
                            it.type == MediaStreamType.Subtitle
                    }
                    if (selected?.deliveryMethod != app.pictureshow.core.playback.SubtitleDeliveryMethod.Encode) {
                        select(MediaStreamType.Subtitle, subtitle)
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
                    onFailure(retryable, decision)
                }
            }
        }.also(exposed::addListener)
        mutablePlayer.value = exposed
        val clipped = if (serverSeek && positionMs > startMs) {
            media.buildUpon().setClippingConfiguration(
                androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(positionMs - startMs).setAllowUnseekableMedia(true).build()
            ).build()
        } else {
            media
        }
        next.setMediaItem(clipped, if (serverSeek) 0 else positionMs)
        next.playWhenReady = !paused
        val existingSession = mediaSession
        if (existingSession == null) {
            mediaSession = PlaybackMediaSession.open(context, exposed) { onStop() }
        } else {
            existingSession.replacePlayer(exposed)
        }
        next.prepare()
    }

    override fun select(type: MediaStreamType, index: Int): Boolean {
        val active = activePlayer ?: return false
        val trackType = if (type == MediaStreamType.Audio) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
        val parameters = active.trackSelectionParameters.buildUpon()
        if (index < 0) {
            val next = parameters.setTrackTypeDisabled(trackType, true).clearOverridesOfType(trackType).build()
            if (next != active.trackSelectionParameters) active.trackSelectionParameters = next
        } else {
            val stream = current?.mediaSource?.mediaStreams?.firstOrNull { it.type == type && it.index == index } ?: return false
            val chosen = PlaybackTracks.find(
                current?.mediaSource?.mediaStreams.orEmpty(),
                stream,
                active.currentTracks.groups.filter { it.type == trackType }
            ) ?: return false
            if (!chosen.first.isTrackSupported(chosen.second, false)) return false
            val next = parameters.setTrackTypeDisabled(trackType, false)
                .setOverrideForType(TrackSelectionOverride(chosen.first.mediaTrackGroup, chosen.second)).build()
            if (next != active.trackSelectionParameters) active.trackSelectionParameters = next
        }
        if (type == MediaStreamType.Audio) audio = index else subtitle = index
        return true
    }

    override fun pause() {
        mutablePlayer.value?.pause()
    }

    override fun beginSeek() {
        physicalEnd?.invalidate()
        activePlayer?.stop()
    }

    override fun release() = releasePlayer(closeSession = true)

    private fun releasePlayer(closeSession: Boolean) {
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
        saved = position()
        mutablePlayer.value = null
        activePlayer = null
        listener?.let { exposed?.removeListener(it) }
        if (closeSession) {
            mediaSession?.close()
            mediaSession = null
        }
        active.release()
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
