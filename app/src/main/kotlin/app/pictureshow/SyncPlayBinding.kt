/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Player
import app.pictureshow.core.Account
import app.pictureshow.core.SegmentMode
import app.pictureshow.core.SegmentType
import app.pictureshow.core.sync.syncSourceEligible
import org.koin.compose.koinInject

internal val LocalSyncOpen = staticCompositionLocalOf<SyncOpen?> { null }

/** Scope and navigation are shared by the phone and TV shells; item lookup always uses the participant's viewer. */
@Composable
internal fun SyncPlayCoordinator(
    account: Account?,
    catalog: CatalogSession,
    onMusicOpen: () -> Unit = {},
    open: (ResolvedPlaybackEntry.Open) -> Unit
) {
    val sync: SyncPlayHost = koinInject()
    val remote: RemoteTargetHost = koinInject()
    val preferences: PlaybackPreferencesStore = koinInject()
    val music: MusicHost = koinInject()
    DisposableEffect(music, sync) {
        music.syncHost = { sync }
        onDispose { music.syncHost = { null } }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestAccount by rememberUpdatedState(account)
    val latestOpen by rememberUpdatedState(open)
    val latestMusicOpen by rememberUpdatedState(onMusicOpen)
    LaunchedEffect(account) {
        sync.bind(account)
        remote.bind(account, preferences.read().remoteReceiver)
        remote.requireSyncSocket(account?.expired == false)
    }
    DisposableEffect(sync, remote, lifecycle) {
        fun subscription(generation: Long, rotate: Boolean) {
            remote.syncMessages = { text -> sync.receive(text, generation) }
            if (rotate) remote.renewSyncSocket()
        }
        sync.membershipSocket = ::subscription
        subscription(sync.membership.generation, false)
        remote.syncQueueRequest = { effect ->
            when (effect.op) {
                "open" -> sync.queueRequest(app.pictureshow.core.sync.syncNewQueue(effect.items, effect.index ?: 0, effect.ticks ?: 0))

                "queue-next", "queue-add" -> sync.queueRequest(
                    app.pictureshow.core.sync.syncEnqueue(effect.items, effect.op == "queue-next")
                )

                else -> false
            }
        }
        remote.syncConnection = sync::socket
        sync.socket(remote.socketConnected)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) sync.background()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            sync.background()
            sync.membershipSocket = null
            remote.syncMessages = {}
            remote.syncQueueRequest = { false }
            remote.syncConnection = {}
        }
    }
    LaunchedEffect(sync, catalog) {
        sync.opens.collect { request ->
            val viewer = latestAccount ?: return@collect
            if (music.openSync(viewer, request.itemId, request.ticks)) {
                if (sync.resolved(request, true)) latestMusicOpen()
                return@collect
            }
            val result = resolvePlaybackEntry(PlaybackEntry(request.itemId, request.ticks / 10_000), viewer, catalog)
            val entry = result as? ResolvedPlaybackEntry.Open
            if (sync.resolved(request, entry != null) && entry != null) latestOpen(entry.copy(syncOpen = request))
        }
    }
    val presentation by sync.state.collectAsState()
    if (sync.panelOpen) SyncPlayPanel(presentation, sync::action) { sync.panelOpen = false }
    if (sync.pendingExternalRoute != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = sync::cancelExternalRoute,
            title = { androidx.compose.material3.Text("Leave Watch together before casting?") },
            text = {
                androidx.compose.material3.Text(
                    "Casting uses the receiver's clock. Leave the group to continue there; the other participants keep their group."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = sync::leaveForExternalRoute) {
                    androidx.compose.material3.Text("Leave group and cast")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = sync::cancelExternalRoute) {
                    androidx.compose.material3.Text("Cancel")
                }
            }
        )
    }
}

@Composable
internal fun SyncPlaybackBinding(host: PlaybackHostState) {
    val sync: SyncPlayHost = koinInject()
    val opening = LocalSyncOpen.current
    val token = remember { Any() }
    host.sync = sync
    val bridge = remember(host, sync) {
        object : SyncRenderer {
            override fun snapshot(): SyncRendererState {
                val p = host.player
                val d = host.state.decision
                val duration = host.duration.takeIf { it > 0 } ?: p?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0
                return SyncRendererState(
                    host.item.id,
                    host.output.position().milliseconds.coerceAtLeast(0) * 10_000,
                    duration * 10_000,
                    p?.playbackState == Player.STATE_READY && host.error == null && !host.state.preparing,
                    p?.playbackState == Player.STATE_BUFFERING,
                    p?.isPlaying == true,
                    syncSourceEligible(
                        duration * 10_000,
                        d?.mediaSource != null && p?.isCurrentMediaItemSeekable == true,
                        host.item.type == "TvChannel" || d?.mediaSource?.liveStreamId != null,
                        d?.mediaSource?.isInfiniteStream == true,
                        offline = false
                    ),
                    // Media3's generic speed command does not advertise these exact rates for encoded output.
                    // Until separately qualified, use the required bounded seek fallback.
                    rateSupported = false
                )
            }
            override fun apply(positionTicks: Long?, playing: Boolean?, rate: Double) {
                host.output.applySync {
                    if (positionTicks != null && kotlin.math.abs(host.output.position().milliseconds - positionTicks / 10_000) > 1) {
                        host.seekToLocal(positionTicks / 10_000, revealControls = false)
                    }
                    host.player?.let {
                        if (it.playbackParameters.speed != rate.toFloat()) it.setPlaybackSpeed(rate.toFloat())
                        if (playing != null && it.playWhenReady != playing) it.playWhenReady = playing
                    }
                }
            }
        }
    }
    DisposableEffect(sync, host, opening) {
        host.output.syncRequest = sync::transport
        host.output.syncParticipating = { sync.participating }
        val expected = sync.membership.queue?.current
        val selection = if (opening == null && sync.participating && sync.membership.group != null && expected?.itemId != host.item.id) {
            SyncLocalSelection(host.item.id, app.pictureshow.core.sync.millisecondsToSyncTicks(host.startMs) ?: 0)
        } else {
            null
        }
        // Metadata can be unknown here. The host waits for a prepared eligible source before requesting the shared queue.
        if (!sync.bindRenderer(token, bridge, selection, opening = opening)) {
            // A stale server destination must retain pause intent even if its decoder is created later.
            host.output.applySync { host.output.pause() }
        }
        onDispose {
            sync.clearRenderer(token)
            host.output.syncRequest = null
            host.output.syncParticipating = { false }
        }
    }
}

/** Auto skip becomes an explicit group seek; independent episode continuation is disabled. */
internal fun PlaybackHostState.segmentModesForViewing(): Map<SegmentType, SegmentMode> = if (sync?.participating == true) {
    localPreferences.segmentModes.mapValues { (_, mode) ->
        if (mode == SegmentMode.Automatic) SegmentMode.Ask else mode
    }
} else {
    localPreferences.segmentModes
}
