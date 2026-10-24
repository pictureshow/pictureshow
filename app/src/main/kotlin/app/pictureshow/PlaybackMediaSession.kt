/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.PendingIntent
import android.content.Context
import android.media.session.MediaController
import android.os.Handler
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaSession

/** Foreground video session, with a bounded session-only grace period for short rebuffers. */
internal object PlaybackMediaSession {
    private var active: Handle? = null
    private val openingMetadata = ThreadLocal<MediaMetadata?>()

    /** The synchronous output-open call owns this scope, including decoder replacements and failures. */
    fun <T> withMetadata(metadata: MediaMetadata, open: () -> T): T {
        val previous = openingMetadata.get()
        openingMetadata.set(metadata)
        return try {
            open()
        } finally {
            openingMetadata.set(previous)
        }
    }

    private fun applyOpeningMetadata(player: Player) {
        openingMetadata.get()?.let { updatePlaybackMetadata(player, it) }
    }

    fun open(context: Context, player: Player, onStop: (() -> Unit)? = null): Handle {
        active?.close()
        applyOpeningMetadata(player)
        return Handle(context, player, onStop).also { active = it }
    }

    /** Activity-delivered media keys take the same session path as Assistant and system controls. */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in MEDIA_KEYS) return false
        return active?.controller?.dispatchMediaButtonEvent(event) ?: false
    }

    class Handle internal constructor(context: Context, player: Player, onStop: (() -> Unit)?) {
        private var closed = false
        private var stopRequested = false

        // STOP means leaving this viewer. Do not idle the decoder before finish captures its position.
        // A plain forwarding wrapper also permits finish to synchronously release the session/player.
        private val stop = onStop

        private var rebufferPlayer: SessionRebufferPlayer? = null

        private fun sessionPlayer(player: Player): Player {
            if (stop == null) return player
            val previous = rebufferPlayer
            val replacement = SessionRebufferPlayer(player, previous?.window()) {
                if (!closed && !stopRequested) {
                    stopRequested = true
                    stop.invoke()
                }
            }
            rebufferPlayer = replacement
            return replacement
        }
        val session: MediaSession
        internal val controller: MediaController

        init {
            val builder = MediaSession.Builder(
                context,
                sessionPlayer(player)
            ).setId("pictureshow-player").setCallback(AnonymousMediaButtons)
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                builder.setSessionActivity(
                    PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
            }
            session = builder.build()
            controller = MediaController(context, session.platformToken)
        }

        /** Keep the platform token and external controllers alive while replacing only the decoder. */
        fun replacePlayer(player: Player) {
            if (!closed) {
                applyOpeningMetadata(player)
                val previous = rebufferPlayer
                session.setPlayer(sessionPlayer(player))
                previous?.detach()
            }
        }

        fun close() {
            if (closed) return
            closed = true
            if (active === this) active = null
            session.release()
            rebufferPlayer?.detach()
            rebufferPlayer = null
        }
    }

    private val MEDIA_KEYS = setOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_REWIND
    )
}

/**
 * A BUFFERING-to-PLAYING legacy transition can make the TV launcher repost and speak Now Playing.
 * Keep an already playing viewing in PLAYING for a short stall, without changing the UI
 * player, metadata or playback intent. Initial preparation and stalls beyond five seconds are real
 * BUFFERING. The deadline belongs to the viewing, including decoder replacement, not each event.
 *
 * Media3 1.11.1 maps READY + play intent to legacy PLAYING, but takes legacy speed from isPlaying.
 * Only playbackState is projected here: actual isPlaying stays false while stalled, producing speed
 * zero and an actual, frozen position. Pretending the decoder is playing would make legacy clients
 * extrapolate position through the stall. This presentation is for the session only.
 */
private class SessionRebufferPlayer(private val output: Player, previous: Window?, private val onStop: () -> Unit) :
    ForwardingPlayer(output) {
    data class Window(val deadlineMs: Long?, val eligible: Boolean)

    private val handler = Handler(output.applicationLooper)
    private var deadlineMs = previous?.deadlineMs
    private var eligible = previous?.eligible ?: output.isPlaying
    private var stopped = false
    private var detached = false
    private var scheduledDeadlineMs: Long? = null
    private val listeners = java.util.IdentityHashMap<Player.Listener, SessionListener>()
    private val expiry = Runnable {
        scheduledDeadlineMs = null
        if (!detached) {
            refreshWindow()
            listeners.values.toList().forEach { it.dispatchState() }
        }
    }
    private val observer = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refreshWindow()
    }

    init {
        output.addListener(observer)
        refreshWindow()
    }

    fun window(): Window {
        refreshWindow()
        return Window(deadlineMs, eligible)
    }

    private fun refreshWindow() {
        if (detached) return
        if (!stopped && output.isPlaying) {
            eligible = true
            deadlineMs = null
        } else if (!stopped &&
            output.playbackState == Player.STATE_BUFFERING &&
            output.playWhenReady &&
            output.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            output.playerError == null
        ) {
            if (deadlineMs == null && eligible) {
                deadlineMs = SystemClock.uptimeMillis() + REBUFFER_GRACE_MS
                eligible = false
            }
        } else {
            eligible = false
            deadlineMs = null
        }
        val deadline = deadlineMs
        if (deadline == null) {
            handler.removeCallbacks(expiry)
            scheduledDeadlineMs = null
        } else if (deadline > SystemClock.uptimeMillis() && deadline != scheduledDeadlineMs) {
            handler.removeCallbacks(expiry)
            scheduledDeadlineMs = deadline
            handler.postAtTime(expiry, deadline)
        }
        // An overdue callback still owes listeners the state change. A getter must not cancel it.
    }

    override fun getPlaybackState(): Int {
        refreshWindow()
        if (stopped) return Player.STATE_ENDED
        return if (deadlineMs?.let { SystemClock.uptimeMillis() < it } == true) Player.STATE_READY else output.playbackState
    }

    override fun isPlaying(): Boolean = !stopped && output.isPlaying

    override fun stop() {
        if (stopped || detached) return
        stopped = true
        refreshWindow()
        listeners.values.toList().forEach { it.dispatchState() }
        onStop()
    }

    override fun addListener(listener: Player.Listener) {
        if (detached || listeners.containsKey(listener)) return
        SessionListener(listener).also {
            listeners[listener] = it
            super.addListener(it)
        }
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)?.let { super.removeListener(it) }
    }

    fun detach() {
        if (detached) return
        detached = true
        handler.removeCallbacks(expiry)
        output.removeListener(observer)
        listeners.values.toList().forEach { super.removeListener(it) }
        listeners.clear()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private inner class SessionListener(private val listener: Player.Listener) : Player.Listener {
        private var lastState = playbackState
        private var lastIsPlaying = isPlaying

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            lastIsPlaying = this@SessionRebufferPlayer.isPlaying
            listener.onIsPlayingChanged(lastIsPlaying)
        }

        // Player.Listener's Java default methods need explicit forwarding; Kotlin delegation
        // inherits their no-op implementations instead. Keep all unrelated session events intact.
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) =
            listener.onTimelineChanged(timeline, reason)
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) =
            listener.onMediaItemTransition(mediaItem, reason)
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) = listener.onTracksChanged(tracks)
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = listener.onMediaMetadataChanged(mediaMetadata)
        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = listener.onPlaylistMetadataChanged(mediaMetadata)
        override fun onIsLoadingChanged(isLoading: Boolean) = listener.onIsLoadingChanged(isLoading)
        override fun onLoadingChanged(isLoading: Boolean) = listener.onLoadingChanged(isLoading)
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = listener.onAvailableCommandsChanged(availableCommands)
        override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) =
            listener.onTrackSelectionParametersChanged(parameters)
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = listener.onPlayWhenReadyChanged(playWhenReady, reason)
        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) =
            listener.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
        override fun onRepeatModeChanged(repeatMode: Int) = listener.onRepeatModeChanged(repeatMode)
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = listener.onShuffleModeEnabledChanged(shuffleModeEnabled)
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) = listener.onPlayerError(error)
        override fun onPlayerErrorChanged(error: androidx.media3.common.PlaybackException?) = listener.onPlayerErrorChanged(error)
        override fun onPositionDiscontinuity(reason: Int) = listener.onPositionDiscontinuity(reason)
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
            listener.onPositionDiscontinuity(oldPosition, newPosition, reason)
        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) =
            listener.onPlaybackParametersChanged(playbackParameters)
        override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = listener.onSeekBackIncrementChanged(seekBackIncrementMs)
        override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) =
            listener.onSeekForwardIncrementChanged(seekForwardIncrementMs)
        override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) =
            listener.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
        override fun onAudioSessionIdChanged(audioSessionId: Int) = listener.onAudioSessionIdChanged(audioSessionId)
        override fun onAudioAttributesChanged(audioAttributes: androidx.media3.common.AudioAttributes) =
            listener.onAudioAttributesChanged(audioAttributes)
        override fun onVolumeChanged(volume: Float) = listener.onVolumeChanged(volume)
        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = listener.onSkipSilenceEnabledChanged(skipSilenceEnabled)
        override fun onDeviceInfoChanged(deviceInfo: androidx.media3.common.DeviceInfo) = listener.onDeviceInfoChanged(deviceInfo)
        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = listener.onDeviceVolumeChanged(volume, muted)
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = listener.onVideoSizeChanged(videoSize)
        override fun onSurfaceSizeChanged(width: Int, height: Int) = listener.onSurfaceSizeChanged(width, height)
        override fun onRenderedFirstFrame() = listener.onRenderedFirstFrame()
        override fun onCues(cues: List<androidx.media3.common.text.Cue>) = listener.onCues(cues)
        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) = listener.onCues(cueGroup)
        override fun onMetadata(metadata: androidx.media3.common.Metadata) = listener.onMetadata(metadata)

        // State callbacks and the matching event flag must describe the same projected state.
        override fun onPlaybackStateChanged(playbackState: Int) = Unit

        @Deprecated("Deprecated in Player.Listener")
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            @Suppress("DEPRECATION")
            listener.onPlayerStateChanged(playWhenReady, this@SessionRebufferPlayer.playbackState)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (detached) return
            val flags = FlagSet.Builder()
            for (i in 0 until events.size()) {
                val flag = events.get(i)
                if (flag != Player.EVENT_PLAYBACK_STATE_CHANGED) flags.add(flag)
            }
            val state = playbackState
            if (state != lastState) {
                lastState = state
                listener.onPlaybackStateChanged(state)
                flags.add(Player.EVENT_PLAYBACK_STATE_CHANGED)
            }
            val projected = Player.Events(flags.build())
            if (projected.size() != 0) listener.onEvents(this@SessionRebufferPlayer, projected)
        }

        fun dispatchState() {
            if (detached) return
            val flags = FlagSet.Builder()
            val state = playbackState
            if (state != lastState) {
                lastState = state
                listener.onPlaybackStateChanged(state)
                flags.add(Player.EVENT_PLAYBACK_STATE_CHANGED)
            }
            val playing = isPlaying
            if (playing != lastIsPlaying) {
                lastIsPlaying = playing
                listener.onIsPlayingChanged(playing)
                flags.add(Player.EVENT_IS_PLAYING_CHANGED)
            }
            val events = Player.Events(flags.build())
            if (events.size() != 0) listener.onEvents(this@SessionRebufferPlayer, events)
        }
    }

    companion object {
        private const val REBUFFER_GRACE_MS = 5_000L
    }
}
