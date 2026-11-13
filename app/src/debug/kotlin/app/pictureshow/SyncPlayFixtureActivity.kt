/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.pictureshow.core.sync.SyncQuality
import kotlinx.coroutines.delay

/** Debug + shell DUMP permission only. Immutable named input fixtures, never a host, gateway or account. */
class SyncPlayFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preset = intent.getStringExtra("preset") ?: "empty"
        val initial = syncPlayFixture(preset) ?: run {
            finish()
            return
        }
        setContent {
            var state by remember(preset) { mutableStateOf(initial) }
            LaunchedEffect(preset) {
                if (preset == "busy-to-groups") {
                    delay(2_000)
                    state = requireNotNull(syncPlayFixture("list"))
                }
            }
            PictureshowTheme {
                Box(Modifier.fillMaxSize()) {
                    SyncPlayPanel(state, { operation, value ->
                        // Local fixture transitions exercise focus/composition only; there is no network transport.
                        state = when (operation) {
                            "leave" -> requireNotNull(syncPlayFixture("empty"))
                            "join", "create" -> requireNotNull(syncPlayFixture("joined3"))
                            "refresh" -> requireNotNull(syncPlayFixture("list"))
                            "play" -> state.copy(phase = "Playing")
                            "pause" -> state.copy(phase = "Paused")
                            "repeat" -> state.copy(repeat = value ?: state.repeat)
                            "shuffle" -> state.copy(shuffle = value ?: state.shuffle)
                            "ignore-wait" -> state.copy(ignoreWait = value == "true")
                            "remove-entry" -> state.copy(queue = state.queue.filterNot { it.id == value })
                            "select-entry" -> state.copy(queue = state.queue.map { it.copy(current = it.id == value) })
                            else -> state
                        }
                    }, onDismiss = ::finish, fixtureLabel = preset)
                }
            }
        }
    }
}

internal fun syncPlayFixture(preset: String): SyncPlayPresentation? {
    val chooser = SyncPlayPresentation(server = "Cinema server", viewer = "Morgan", policy = "CreateAndJoinGroups", policyKnown = true)
    val joined = chooser.copy(
        groupId = "fixture-group",
        groupName = "Friday films",
        phase = "Paused",
        participants = listOf("Morgan", "Sam", "Riley", "Alex", "Jordan", "Casey"),
        localPlayback = SyncLocalPlayback.READY,
        queue = listOf(
            SyncPlayQueueRow("entry-1", "Big Buck Bunny", true, "film-1"),
            SyncPlayQueueRow("entry-2", "Elephants Dream", false, "film-2"),
            SyncPlayQueueRow("entry-3", "Sintel", false, "film-3")
        )
    )
    return when (preset) {
        "None" -> chooser.copy(policy = "None")
        "JoinGroups" -> chooser.copy(policy = "JoinGroups", groups = listOf(SyncPlayGroupRow("fixture-group", "Friday films", 3)))
        "checking", "busy-to-groups" -> chooser.copy(policyKnown = false, policy = "None", busy = true)
        "empty" -> chooser
        "list" -> chooser.copy(
            groups = listOf(SyncPlayGroupRow("fixture-group", "Friday films", 3), SyncPlayGroupRow("fixture-two", "Weekend music", 1))
        )
        "joined3" -> joined
        "RepeatOne" -> joined.copy(repeat = "RepeatOne", shuffle = "Shuffle")
        "RepeatAll" -> joined.copy(repeat = "RepeatAll", shuffle = "Shuffle", ignoreWait = true)
        "waiting" -> joined.copy(phase = "Waiting", waitReason = "Buffer", localPlayback = SyncLocalPlayback.BUFFERING)
        "playing-estimate" -> joined.copy(phase = "Playing", quality = SyncQuality.IN_SYNC)
        "reconnecting" -> joined.copy(
            phase = "Reconnecting",
            quality = SyncQuality.UNKNOWN,
            notice = SyncNotice(SyncNoticeKind.REJOIN, SyncNoticeTone.ERROR, SyncNoticeAction.REJOIN)
        )
        "error", "librarydenied" -> joined.copy(
            phase = "Error",
            localPlayback = SyncLocalPlayback.UNAVAILABLE,
            notice = SyncNotice(SyncNoticeKind.LIBRARY_DENIED, SyncNoticeTone.ERROR, SyncNoticeAction.LEAVE)
        )
        else -> null
    }
}
