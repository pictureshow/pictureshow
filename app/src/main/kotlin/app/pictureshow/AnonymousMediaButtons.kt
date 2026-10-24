/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.session.MediaSession

/** Only anonymous OS-delivered media keys use this path; controller connection permissions stay unchanged. */
internal object AnonymousMediaButtons : MediaSession.Callback {
    override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
        if (controllerInfo.controllerVersion != MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION) return false
        if (controllerInfo.packageName != MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME) return false
        return handle(session, intent, "anonymous")
    }

    /** Single-key handling shared with our activity's own, identified legacy controller. */
    internal fun handle(session: MediaSession, intent: Intent, origin: String): Boolean {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return false
        val key = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
        val command = when (key.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> Player.COMMAND_PLAY_PAUSE
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> Player.COMMAND_SEEK_FORWARD
            KeyEvent.KEYCODE_MEDIA_REWIND -> Player.COMMAND_SEEK_BACK
            KeyEvent.KEYCODE_MEDIA_STOP -> Player.COMMAND_STOP
            else -> return false
        }
        // Consume key-up and held-button repeats without applying a second toggle or seek.
        if (key.action != KeyEvent.ACTION_DOWN || key.repeatCount != 0) return true
        val player = session.player
        if (!player.isCommandAvailable(command)) return true
        val before = player.playWhenReady
        when (key.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> Util.handlePlayPauseButtonAction(player)
            KeyEvent.KEYCODE_MEDIA_PLAY -> Util.handlePlayButtonAction(player)
            KeyEvent.KEYCODE_MEDIA_PAUSE -> Util.handlePauseButtonAction(player)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> player.seekForward()
            KeyEvent.KEYCODE_MEDIA_REWIND -> player.seekBack()
            KeyEvent.KEYCODE_MEDIA_STOP -> player.stop()
        }
        PlaybackDiagnostics.write(
            "media-button origin=$origin code=${key.keyCode} eventMs=${key.eventTime} " +
                "playWhenReadyBefore=$before playWhenReadyAfter=${player.playWhenReady}"
        )
        return true
    }
}
