/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
                    if (preset == "cast-consent") {
                        app.pictureshow.ui.PictureshowTheme {
                            SyncLeaveForCastConsent(onDismiss = ::finish, onConfirm = ::finish, fixtureLabel = preset)
                        }
                    } else if (preset.startsWith("chip-")) {
                        val tv = usesTvInterface(this@SyncPlayFixtureActivity)
                        app.pictureshow.ui.PictureshowTheme {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                                SyncPlayerStatusChip(
                                    state,
                                    {},
                                    Modifier.align(Alignment.TopStart).safeDrawingPadding()
                                        .padding(horizontal = if (tv) 48.dp else 16.dp, vertical = if (tv) 28.dp else 16.dp)
                                        .widthIn(max = if (tv) 640.dp else 440.dp),
                                    tv
                                )
                                Text(
                                    stringResource(R.string.sync_fixture, preset),
                                    Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(16.dp)
                                )
                            }
                        }
                    } else {
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
}

internal fun syncPlayFixture(preset: String): SyncPlayPresentation? {
    val chooser = SyncPlayPresentation(server = "Cinema server", viewer = "Morgan", policy = "CreateAndJoinGroups", policyKnown = true)
    val joined = chooser.copy(
        groupId = "fixture-group",
        groupName = "Friday films",
        phase = "Paused",
        participants = listOf("Morgan", "Sam", "Riley", "Taylor", "Jordan", "Casey"),
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
        "joined3", "cast-consent" -> joined
        "joined-empty" -> joined.copy(phase = "Idle", queue = emptyList(), localPlayback = SyncLocalPlayback.UNAVAILABLE)
        "local-unavailable" -> joined.copy(localPlayback = SyncLocalPlayback.UNAVAILABLE)
        "local-preparing" -> joined.copy(localPlayback = SyncLocalPlayback.PREPARING)
        "local-buffering" -> joined.copy(phase = "Waiting", waitReason = "Buffer", localPlayback = SyncLocalPlayback.BUFFERING)
        "local-ready" -> joined
        "title-unavailable" -> joined.copy(
            queue = listOf(SyncPlayQueueRow("entry-unavailable", null, true, "unavailable", titleUnavailable = true))
        )
        "long-titles" -> joined.copy(
            groupName = "Friday-films-with-friends-from-every-time-zone-1234567890",
            queue = listOf(
                SyncPlayQueueRow("entry-long-a", "An Evening of Extraordinary Adventures Across the Mountains and Beyond the Sea", true),
                SyncPlayQueueRow("entry-long-b", "An-Extraordinarily-Long-Hyphenated-Film-Title-12345678901234567890", false)
            )
        )
        "quality-warning" -> joined.copy(quality = SyncQuality.OUT_OF_SYNC)
        "notice-left" -> chooser.copy(notice = SyncNotice(SyncNoticeKind.LEFT, SyncNoticeTone.INFO))
        "notice-queue-pending" -> joined.copy(notice = SyncNotice(SyncNoticeKind.QUEUE_PENDING, SyncNoticeTone.INFO))
        "notice-speed" -> joined.copy(notice = SyncNotice(SyncNoticeKind.SPEED, SyncNoticeTone.INFO))
        "chip-short" -> joined.copy(groupName = "Films", participants = listOf("Sam"))
        "chip-long" -> joined.copy(groupName = "Friday films with friends from every time zone", quality = SyncQuality.IN_SYNC)
        "chip-waiting" -> joined.copy(phase = "Waiting", waitReason = "Buffer", localPlayback = SyncLocalPlayback.BUFFERING)
        "chip-reconnecting" -> joined.copy(phase = "Reconnecting", quality = SyncQuality.UNKNOWN)
        "chip-large-count" -> joined.copy(
            groupName = "Films",
            phase = "Playing",
            quality = SyncQuality.IN_SYNC,
            participants = List(128) {
                "Person $it"
            }
        )
        "RepeatOne" -> joined.copy(repeat = "RepeatOne", shuffle = "Shuffle")
        "RepeatAll" -> joined.copy(repeat = "RepeatAll", shuffle = "Shuffle", ignoreWait = true)
        "waiting" -> joined.copy(phase = "Waiting", waitReason = "Buffer", localPlayback = SyncLocalPlayback.BUFFERING)
        "playing-estimate" -> joined.copy(phase = "Playing", quality = SyncQuality.IN_SYNC)
        "reconnecting" -> joined.copy(
            phase = "Reconnecting",
            quality = SyncQuality.UNKNOWN,
            notice = SyncNotice(SyncNoticeKind.REJOIN, SyncNoticeTone.ERROR, SyncNoticeAction.REJOIN)
        )
        "error" -> joined.copy(
            phase = "Error",
            localPlayback = SyncLocalPlayback.UNAVAILABLE,
            notice = SyncNotice(SyncNoticeKind.PLAYBACK_FAILED, SyncNoticeTone.ERROR, SyncNoticeAction.LEAVE)
        )
        "librarydenied" -> joined.copy(
            phase = "Error",
            localPlayback = SyncLocalPlayback.UNAVAILABLE,
            notice = SyncNotice(SyncNoticeKind.LIBRARY_DENIED, SyncNoticeTone.ERROR, SyncNoticeAction.LEAVE)
        )
        else -> null
    }
}
