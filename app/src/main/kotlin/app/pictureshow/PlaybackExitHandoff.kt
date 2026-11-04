/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.os.SystemClock
import android.view.KeyEvent
import app.pictureshow.core.ApiTimeouts

/** One destination-owned observation. Route arguments and node labels never enter its output. */
internal class PlaybackExitHandoff(
    private val now: () -> Long = SystemClock::uptimeMillis,
    private val write: (String) -> Unit = PlaybackDiagnostics::write
) {
    private data class Deferred(val keyCode: Int?, val deliver: () -> Unit)

    private data class Pending(
        val owner: String,
        val started: Long,
        var firstInput: String? = null,
        var inputAt: Long? = null,
        var inputScope: String = "activity",
        val pressed: MutableSet<Int> = mutableSetOf(),
        val deferred: MutableList<Deferred> = mutableListOf(),
        var live: Boolean = false,
        var queueClosed: Boolean = false
    )

    private var pending: Pending? = null
    private var destinationRevision = 0L
    private var destinationOwner: String? = null
    private var preparedOwner: String? = null
    private var expiredOwner: String? = null

    // A live pending button handles Select DOWN itself. Its UP must never click a fresh handler.
    private val liveSelectPressed = mutableSetOf<Int>()

    fun begin(owner: String) {
        abandon("superseded")
        destinationRevision++
        preparedOwner = owner
        expiredOwner = null
        pending = Pending(owner, now())
    }

    /** A pending detail opened from Home needs the same destination-owned queue as a return. */
    fun prepare(owner: String) {
        if (destinationOwner == owner && pending == null && preparedOwner != owner) begin(owner)
    }

    /** Retain the outcome through finish so an accepted request never expires silently. */
    fun expired(owner: String): Boolean {
        pending?.takeIf { it.owner == owner }?.let {
            if (now() - it.started >= ApiTimeouts.REQUEST_MILLIS) closeQueue(it, "deadline")
        }
        return expiredOwner == owner
    }

    fun live(owner: String, active: Boolean) {
        if (active) prepare(owner)
        val current = pending?.takeIf { it.owner == owner } ?: return
        current.live = active
        if (now() - current.started >= ApiTimeouts.REQUEST_MILLIS) closeQueue(current, "deadline")
        if (!active || current.queueClosed) return
        // Earlier moves must reach the newly available row before a later live Select.
        // Raw reports retain their order, including media; the pending controls turn
        // Select/media into guarded commands that still wait for authoritative fresh data.
        val reports = current.deferred.filter { it.keyCode != null }
        current.deferred.removeAll(reports.toSet())
        current.pressed.removeAll(remoteActions)
        var delivered = 0
        for (report in reports) {
            if (pending !== current) break
            report.deliver()
            delivered++
        }
        if (delivered > 0) write("exit-input phase=replayed stage=pending reports=$delivered")
    }

    fun input(keyCode: Int, accessibility: Boolean = false) {
        val current = pending ?: return
        val key = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "ok"
            KeyEvent.KEYCODE_BACK -> "back"
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> "play-pause"
            else -> return
        }
        if (current.firstInput == null) {
            current.firstInput = key
            current.inputAt = now() - current.started
            current.inputScope = if (accessibility) "accessibility" else "activity"
        }
    }

    /** Hold remote reports away from stale/absent controls until the published target has focus. */
    fun defer(keyCode: Int, down: Boolean, repeat: Int, deliver: () -> Unit): Boolean {
        if (down && repeat == 0) {
            liveSelectPressed.remove(keyCode)
        } else if (keyCode in liveSelectPressed) {
            if (!down) liveSelectPressed.remove(keyCode)
            return true
        }
        val current = pending ?: return false
        if (down && repeat == 0) input(keyCode)
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode !in remoteActions) return false
        if (now() - current.started >= ApiTimeouts.REQUEST_MILLIS) closeQueue(current, "deadline")
        if (current.live && keyCode in remoteActions && keyCode !in current.pressed) {
            if (down && keyCode in selectActions) {
                if (repeat > 0) return true
                liveSelectPressed += keyCode
            }
            return false
        }
        if (current.queueClosed) {
            if (down && keyCode in selectActions) liveSelectPressed += keyCode
            return true
        }
        // Holding a remote button expresses one pending move, not a burst after loading.
        if (down && repeat > 0) return keyCode in current.pressed
        if (down) {
            current.pressed += keyCode
        } else if (!current.pressed.remove(keyCode)) {
            return false
        }
        if (current.deferred.size >= 32) {
            closeQueue(current, "overflow")
            return true
        }
        current.deferred += Deferred(keyCode, deliver)
        return true
    }

    /** Observe a reader choosing another offered action without inferring its physical key. */
    fun accessibleFocus(owner: String) {
        val current = pending?.takeIf { it.owner == owner } ?: return
        if (current.firstInput == null) {
            // A reader moved to another pending action; its physical direction is service-owned.
            current.firstInput = "focus"
            current.inputAt = now() - current.started
            current.inputScope = "accessibility"
        }
    }

    /** TalkBack can consume Select and invoke a pending button without Activity dispatch. */
    fun accessibleSelect(owner: String, deliver: () -> Unit): Boolean {
        val current = pending?.takeIf { it.owner == owner } ?: return false
        input(KeyEvent.KEYCODE_DPAD_CENTER, accessibility = true)
        if (now() - current.started >= ApiTimeouts.REQUEST_MILLIS) closeQueue(current, "deadline")
        if (current.queueClosed) return false
        if (current.deferred.size >= 32) {
            closeQueue(current, "overflow")
            return false
        }
        current.deferred += Deferred(null, deliver)
        return true
    }

    fun finish(owner: String, target: String, focused: Boolean) {
        val current = pending?.takeIf { it.owner == owner } ?: return
        pending = null
        if (now() - current.started >= ApiTimeouts.REQUEST_MILLIS) closeQueue(current, "deadline")
        val revision = destinationRevision
        val safeTarget =
            target.takeIf {
                it in
                    setOf("primary", "retry", "next", "seasons", "episodes", "played", "favorite", "from-start", "viewer", "none")
            }
                ?: "other"
        write(
            "exit-handoff phase=complete elapsedMs=${now() - current.started} target=$safeTarget focused=$focused " +
                "inputScope=${current.inputScope} inputBefore=${current.firstInput != null} " +
                "firstInput=${current.firstInput ?: "none"} inputMs=${current.inputAt ?: "none"}"
        )
        if (focused) {
            // Replayed OK/Play can navigate. Remaining reports must not reach the next page.
            var reports = 0
            var unoffered = 0
            for (deliver in current.deferred) {
                if (destinationRevision != revision) break
                if (deliver.keyCode != null && deliver.keyCode !in directionActions) {
                    // Raw Select/media must first reach an offered pending control in live().
                    // A status-only/failed arrival never promised a late ordinary action.
                    unoffered++
                    if (deliver.keyCode in selectActions && deliver.keyCode in current.pressed) {
                        liveSelectPressed += deliver.keyCode
                    }
                    continue
                }
                deliver.deliver()
                if (deliver.keyCode != null) reports++
            }
            // target above describes arrival. A later line makes post-arrival raw replay
            // explicit; detail-focus diagnostics show the target each delivered move reaches.
            if (reports > 0) write("exit-input phase=replayed stage=fresh reports=$reports")
            if (unoffered > 0) write("exit-input phase=dropped reason=unoffered reports=$unoffered")
        }
    }

    fun destinationChanged(owner: String?) {
        if (destinationOwner != owner && pending?.owner != owner) preparedOwner = null
        destinationOwner = owner
        if (expiredOwner != owner) expiredOwner = null
        destinationRevision++
        if (pending?.owner != owner) abandon("left")
    }

    private fun closeQueue(current: Pending, reason: String) {
        if (current.queueClosed) return
        current.queueClosed = true
        expiredOwner = current.owner
        write("exit-input phase=dropped reason=$reason reports=${current.deferred.size}")
        current.deferred.clear()
        liveSelectPressed += current.pressed.intersect(selectActions)
        current.pressed.clear()
    }

    private val selectActions = setOf(
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER
    )
    private val directionActions = setOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT
    )

    private val remoteActions = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE
    )

    private fun abandon(reason: String) {
        val current = pending ?: return
        liveSelectPressed += current.pressed.intersect(selectActions)
        pending = null
        write(
            "exit-handoff phase=abandoned elapsedMs=${now() - current.started} reason=$reason " +
                "inputScope=${current.inputScope} inputBefore=${current.firstInput != null} " +
                "firstInput=${current.firstInput ?: "none"} inputMs=${current.inputAt ?: "none"}"
        )
    }
}
