/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.os.Looper
import androidx.core.os.HandlerCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Media3's Compose presentation listener can be cancelled from a worker. Its cancellation flag
 * immediately stops delivery, but its finally block still calls removeListener on that worker.
 * Marshal only that cleanup to the player looper; playback calls retain the player's thread checks.
 */
internal class ListenerCleanupPlayer(player: Player) : ForwardingPlayer(player) {
    private val cleanup = HandlerCompat.createAsync(player.applicationLooper)
    override fun removeListener(listener: Player.Listener) {
        if (Looper.myLooper() == cleanup.looper) {
            super.removeListener(listener)
        } else if (!cleanup.post { removeListener(listener) }) {
            // A dead player looper cannot dispatch further callbacks or accept cleanup work.
            PlaybackDiagnostics.write("listener-cleanup looper-stopped")
        }
    }
}
