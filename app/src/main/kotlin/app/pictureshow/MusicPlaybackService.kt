/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/** Audio keeps going when the screens go. Not exported: the OS reaches it through the platform session, nobody binds it. */
internal class MusicPlaybackService : MediaSessionService() {
    private val host: MusicHost by inject()

    private val remoteScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var castPlayer: CastSessionPlayer? = null
    private var castSession: MediaSession? = null

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        if (controllerInfo.connectionHints.getBoolean(CAST_SESSION_HINT) && !usesTvInterface(this)) {
            castSession?.let { return it }
            val sender = org.koin.java.KoinJavaComponent.getKoin().get<CastSenderHost>()
            val player = CastSessionPlayer(sender)
            val session = MediaSession.Builder(this, player).setId("pictureshow-cast")
                .setSessionActivity(openActivity())
                .setMediaButtonPreferences(
                    listOf(
                        CommandButton.Builder(
                            CommandButton.ICON_SKIP_BACK_10
                        ).setPlayerCommand(Player.COMMAND_SEEK_BACK).setDisplayName(getString(R.string.cast_back)).build(),
                        CommandButton.Builder(
                            CommandButton.ICON_SKIP_FORWARD_30
                        ).setPlayerCommand(Player.COMMAND_SEEK_FORWARD).setDisplayName(getString(R.string.cast_forward)).build(),
                        CommandButton.Builder(CommandButton.ICON_STOP)
                            .setPlayerCommand(Player.COMMAND_STOP).setDisplayName(getString(R.string.cast_stop)).build()
                    )
                )
                .build()
            castPlayer = player
            castSession = session
            remoteScope.launch {
                sender.state.takeWhile { it.cast?.owner?.phase == "active" && it.cast.link == "connected" }.collect {
                    player.refresh()
                }
                removeSession(session)
                session.release()
                player.release()
                if (castSession === session) {
                    castSession = null
                    castPlayer = null
                }
            }
            return session
        }
        return host.openSession(this) { player ->
            val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            MediaSession.Builder(this, player)
                .setId(MUSIC_SESSION_ID)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        open,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                .setCallback(MusicSessionCallback(host))
                .build()
        }
    }

    private fun openActivity(): PendingIntent = PendingIntent.getActivity(
        this,
        1,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    override fun onDestroy() {
        remoteScope.cancel()
        castPlayer?.release()
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

    private fun adjust(commands: Player.Commands): Player.Commands {
        val available = commands.buildUpon()
            .removeAll(Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_MEDIA_ITEM)
            .addIf(Player.COMMAND_SEEK_TO_NEXT, host.hasNext())
            .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, host.hasNext())
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        if (host.syncHost()?.participating == true) available.remove(Player.COMMAND_SET_SPEED_AND_PITCH)
        return available.build()
    }

    override fun play() = host.play()

    override fun pause() = host.pause()

    override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) host.play() else host.pause()

    // A controller that picks another row of the output (a queue list on a watch or a car) picks that queue entry.
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = route(mediaItemIndex, positionMs)

    override fun seekToDefaultPosition(mediaItemIndex: Int) = route(mediaItemIndex, 0)

    override fun seekTo(positionMs: Long) = host.seekTo(positionMs)

    override fun seekToDefaultPosition() = host.seekTo(0)

    override fun seekBack() = host.seekTo((currentPosition - seekBackIncrement).coerceAtLeast(0))

    override fun seekForward() {
        val position = currentPosition.coerceAtLeast(0)
        val increment = seekForwardIncrement.coerceAtLeast(0)
        host.seekTo(if (position > Long.MAX_VALUE - increment) Long.MAX_VALUE else position + increment)
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (host.syncHost()?.transport("speed") == true) return
        super.setPlaybackSpeed(speed)
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        if (host.syncHost()?.transport("speed") == true) return
        super.setPlaybackParameters(playbackParameters)
    }

    private fun route(index: Int, positionMs: Long) {
        if (index == currentMediaItemIndex) return host.seekTo(positionMs.coerceAtLeast(0))
        val entry = if (index in 0 until mediaItemCount) getMediaItemAt(index).mediaId else return
        host.jump(entry, positionMs.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0)
    }

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

/** Connection hint used only by this app's controller; a local or TV music client never resolves the sender. */
internal const val CAST_SESSION_HINT = "pictureshow.cast"

/** No decoder: observed receiver state drives the OS, and every command goes through the app's normal host path. */
internal class CastSessionPlayer(private val host: CastSenderHost) : SimpleBasePlayer(Looper.getMainLooper()) {
    fun refresh() = invalidateState()

    override fun getState(): State {
        val ui = host.state.value
        val observed = ui.cast?.observed
        val attached = ui.cast?.let { it.owner.phase == "active" && it.link == "connected" } == true
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_RELEASE
        )
        if (attached) {
            if (ui.controls?.pause == true) commands.add(Player.COMMAND_PLAY_PAUSE)
            if (ui.controls?.stop == true) commands.add(Player.COMMAND_STOP)
            if (ui.controls?.seek == true) {
                commands.addAll(
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_TO_DEFAULT_POSITION
                )
            }
            if (ui.queue.size > 1) {
                commands.addAll(
                    Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_PREVIOUS
                )
            }
            if (ui.controls?.volume == true) {
                commands.addAll(
                    Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                    Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS
                )
            }
            commands.add(Player.COMMAND_SET_REPEAT_MODE)
        }
        val playlist = ui.queue.map { item ->
            val metadata = MediaMetadata.Builder().setTitle(item.title).setArtist(item.artist).setAlbumTitle(item.album)
                .setArtworkUri(item.imageUrl?.toUri()).setIsPlayable(true).build()
            MediaItemData.Builder(item.entry).setMediaItem(MediaItem.Builder().setMediaId(item.entry).setMediaMetadata(metadata).build())
                .setDurationUs(if (item.entry == observed?.entry) observed.durationTicks?.div(10) ?: C.TIME_UNSET else C.TIME_UNSET)
                .setIsSeekable(ui.controls?.seek == true).build()
        }
        val index = ui.queue.indexOfFirst { it.entry == observed?.entry }.coerceAtLeast(0)
        return State.Builder().setAvailableCommands(commands.build())
            .setPlaylist(playlist).setCurrentMediaItemIndex(if (playlist.isEmpty()) C.INDEX_UNSET else index)
            .setContentPositionMs(observed?.ticks?.div(MS_TO_TICKS) ?: 0)
            .setPlayWhenReady(attached && observed?.playerState != "paused", Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(
                if (!attached ||
                    playlist.isEmpty()
                ) {
                    Player.STATE_IDLE
                } else if (observed?.playerState in
                    listOf("buffering", "loading")
                ) {
                    Player.STATE_BUFFERING
                } else {
                    Player.STATE_READY
                }
            )
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(30_000)
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(100).build())
            .setDeviceVolume(((ui.volume ?: 0.0) * 100).toInt()).setIsDeviceMuted(ui.muted)
            .setRepeatMode(
                when (ui.repeat) {
                    "one" -> Player.REPEAT_MODE_ONE
                    "all" -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                }
            )
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) host.play() else host.pause()
        return Futures.immediateVoidFuture()
    }
    override fun handleStop(): ListenableFuture<*> {
        host.stop()
        return Futures.immediateVoidFuture()
    }
    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val ui = host.state.value
        val entry = ui.queue.getOrNull(mediaItemIndex)?.entry
        if (entry != null && entry != ui.cast?.observed?.entry) {
            host.jump(entry)
        } else {
            host.seekTo((if (positionMs == C.TIME_UNSET) 0 else positionMs).coerceAtLeast(0) * MS_TO_TICKS)
        }
        return Futures.immediateVoidFuture()
    }
    override fun handleSetDeviceVolume(volume: Int, flags: Int): ListenableFuture<*> {
        host.setVolume(volume / 100.0)
        return Futures.immediateVoidFuture()
    }
    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        host.setVolume((host.state.value.volume ?: 0.0) + 0.05)
        return Futures.immediateVoidFuture()
    }
    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        host.setVolume((host.state.value.volume ?: 0.0) - 0.05)
        return Futures.immediateVoidFuture()
    }
    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        host.setMuted(muted)
        return Futures.immediateVoidFuture()
    }
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        host.setRepeat(
            when (repeatMode) {
                Player.REPEAT_MODE_ONE -> "one"
                Player.REPEAT_MODE_ALL -> "all"
                else -> "off"
            }
        )
        return Futures.immediateVoidFuture()
    }
}
