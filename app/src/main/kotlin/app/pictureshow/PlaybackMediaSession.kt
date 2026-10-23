/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.PendingIntent
import android.content.Context
import android.media.session.MediaController
import android.view.KeyEvent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSession

/** Foreground video session: advertise the actual player, never a second hand-maintained playback state. */
internal object PlaybackMediaSession {
    private var active: Handle? = null

    fun open(context: Context, player: Player, onStop: (() -> Unit)? = null): Handle {
        active?.close()
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
        private val sessionPlayer = if (onStop == null) {
            player
        } else {
            object : ForwardingPlayer(player) {
                override fun stop() {
                    if (closed || stopRequested) return
                    stopRequested = true
                    onStop()
                }
            }
        }
        val session: MediaSession
        internal val controller: MediaController

        init {
            val builder = MediaSession.Builder(context, sessionPlayer).setId("pictureshow-player").setCallback(AnonymousMediaButtons)
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                builder.setSessionActivity(
                    PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
            }
            session = builder.build()
            controller = MediaController(context, session.platformToken)
        }

        fun close() {
            if (closed) return
            closed = true
            if (active === this) active = null
            session.release()
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
