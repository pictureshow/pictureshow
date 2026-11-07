/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.PendingIntent
import android.content.Intent
import android.view.KeyEvent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.koin.android.ext.android.inject

/** Audio keeps going when the screens go. Not exported: the OS reaches it through the platform session, nobody binds it. */
internal class MusicPlaybackService : MediaSessionService() {
    private val host: MusicHost by inject()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = host.openSession(this) { player ->
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        MediaSession.Builder(this, player)
            .setId(MUSIC_SESSION_ID)
            .setSessionActivity(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCallback(MusicSessionCallback(host))
            .build()
    }

    override fun onDestroy() {
        for (session in sessions) {
            host.closeSession(session)
            session.release()
        }
        super.onDestroy()
    }
}

internal const val MUSIC_SESSION_ID = "pictureshow-music"

internal class MusicSessionCallback(private val host: MusicHost) : MediaSession.Callback {
    override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
        val key = intent.keyEventExtra() ?: return false
        if (key.action != KeyEvent.ACTION_DOWN) return false
        // True ends it here: this press already acted.
        return !host.acceptKey("${key.keyCode}@${key.downTime}")
    }

    @Suppress("DEPRECATION")
    private fun Intent.keyEventExtra(): KeyEvent? = getParcelableExtra(Intent.EXTRA_KEY_EVENT)
}

/**
 * What the OS controls hold: its buttons go through the same reducer as the screens, so a queue the OS can't see, and
 * an order it didn't choose, stay the queue's own. The queue's items aren't the OS's to change.
 */
internal class MusicSessionPlayer(player: Player, private val host: MusicHost) : ForwardingPlayer(player) {
    override fun getAvailableCommands(): Player.Commands = adjust(super.getAvailableCommands())

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    private fun adjust(commands: Player.Commands): Player.Commands = commands.buildUpon()
        .removeAll(Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_MEDIA_ITEM)
        .addIf(Player.COMMAND_SEEK_TO_NEXT, host.hasNext())
        .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, host.hasNext())
        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        .build()

    override fun play() = host.play()

    override fun pause() = host.pause()

    override fun stop() = host.stop()

    override fun seekToNext() = host.next()

    override fun seekToNextMediaItem() = host.next()

    override fun seekToPrevious() = host.previous()

    override fun seekToPreviousMediaItem() = host.previous()

    override fun hasNextMediaItem(): Boolean = host.hasNext()

    override fun setRepeatMode(repeatMode: Int) = host.setRepeat(
        when (repeatMode) {
            Player.REPEAT_MODE_ONE -> "one"
            Player.REPEAT_MODE_ALL -> "all"
            else -> "off"
        }
    )

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = host.setShuffle(shuffleModeEnabled)
}
