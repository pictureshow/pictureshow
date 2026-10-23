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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.pictureshow.core.Server
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.TranscodeSeek
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The only layer that owns a decoder. Replacing a source releases the old player and its callbacks first. */
internal class Media3PlaybackOutput(private val context: Context, private val server: Server, private val title: String? = null) :
    PlaybackOutput {
    private val mutablePlayer = MutableStateFlow<Player?>(null)
    val player = mutablePlayer.asStateFlow()
    var onReady: (PlayDecision) -> Unit = {}
    var onBuffering: (Boolean, PlayDecision) -> Unit = { _, _ -> }
    var onProgress: (PlayDecision) -> Unit = {}
    var onFailure: (Boolean, PlayDecision) -> Unit = { _, _ -> }
    var onEnded: () -> Unit = {}
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
        release()
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
        val sourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(PlaybackMedia.dataSourceFactory(server, decision))
        val next = ExoPlayer.Builder(context, PictureshowRenderersFactory(context, subtitleDelay, passthroughEnabled))
            .setTrackSelector(selector)
            .setMediaSourceFactory(sourceFactory)
            .setLoadControl(createPictureshowLoadControl())
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        val serverSeek = TranscodeSeek.required(decision)
        val startMs = if (serverSeek) TranscodeSeek.startPositionMs(decision, positionMs) else 0
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
                    onBuffering(player.playbackState == Player.STATE_BUFFERING && player.playWhenReady, decision)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (activePlayer !== next) return
                if (playbackState == Player.STATE_READY) onReady(decision)
                if (playbackState == Player.STATE_ENDED) onEnded()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (activePlayer === next) onProgress(decision)
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (activePlayer === next) onProgress(decision)
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
                    // Never display/log the exception: upstream source errors include request URLs.
                    val retryable = error is ExoPlaybackException &&
                        error.type in listOf(ExoPlaybackException.TYPE_SOURCE, ExoPlaybackException.TYPE_RENDERER)
                    onFailure(retryable, decision)
                }
            }
        }.also(exposed::addListener)
        mutablePlayer.value = exposed
        val media = PlaybackMedia.item(server, decision, title, if (serverSeek) startMs else null)
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
        mediaSession = PlaybackMediaSession.open(context, exposed) {
            if (activePlayer === next) onStop()
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
        activePlayer?.stop()
    }

    override fun release() {
        val active = activePlayer ?: return
        val exposed = mutablePlayer.value
        saved = position()
        mutablePlayer.value = null
        activePlayer = null
        listener?.let { exposed?.removeListener(it) }
        mediaSession?.close()
        mediaSession = null
        active.release()
        listener = null
    }
}
