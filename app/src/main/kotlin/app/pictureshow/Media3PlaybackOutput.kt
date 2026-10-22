/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.Context
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
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The only layer that owns a decoder. Replacing a source releases the old player and its callbacks first. */
internal class Media3PlaybackOutput(private val context: Context, private val server: Server) : PlaybackOutput {
    private val mutablePlayer = MutableStateFlow<ExoPlayer?>(null)
    val player = mutablePlayer.asStateFlow()
    var onReady: (PlayDecision) -> Unit = {}
    var onProgress: (PlayDecision) -> Unit = {}
    var onFailure: (Boolean, PlayDecision) -> Unit = { _, _ -> }
    var onEnded: () -> Unit = {}
    private var current: PlayDecision? = null
    private var saved = PlaybackPosition(0, false)
    private var audio = -1
    private var subtitle = -1
    private var listener: Player.Listener? = null

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
        val sourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(PlaybackMedia.dataSourceFactory(decision))
        val next = ExoPlayer.Builder(context, PictureshowRenderersFactory(context))
            .setTrackSelector(selector)
            .setMediaSourceFactory(sourceFactory)
            .setLoadControl(createPictureshowLoadControl())
            .build()
        next.setAudioAttributes(AudioAttributes.DEFAULT, true)
        next.setHandleAudioBecomingNoisy(true)
        listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (mutablePlayer.value !== next) return
                if (playbackState == Player.STATE_READY) onReady(decision)
                if (playbackState == Player.STATE_ENDED) onEnded()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (mutablePlayer.value === next) onProgress(decision)
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (mutablePlayer.value === next) onProgress(decision)
            }
            override fun onTracksChanged(tracks: Tracks) {
                if (mutablePlayer.value === next) {
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
                if (mutablePlayer.value === next) {
                    // Never display/log the exception: upstream source errors include request URLs.
                    val retryable = error is ExoPlaybackException &&
                        error.type in listOf(ExoPlaybackException.TYPE_SOURCE, ExoPlaybackException.TYPE_RENDERER)
                    onFailure(retryable, decision)
                }
            }
        }.also(next::addListener)
        mutablePlayer.value = next
        next.setMediaItem(PlaybackMedia.item(server, decision), positionMs)
        next.playWhenReady = !paused
        next.prepare()
    }

    override fun select(type: MediaStreamType, index: Int): Boolean {
        val active = mutablePlayer.value ?: return false
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

    override fun release() {
        val active = mutablePlayer.value ?: return
        saved = position()
        mutablePlayer.value = null
        listener?.let(active::removeListener)
        active.release()
        listener = null
    }
}
