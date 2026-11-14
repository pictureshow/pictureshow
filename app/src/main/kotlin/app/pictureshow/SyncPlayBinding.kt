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
        sync.socket(remote.syncSocketReady)
        val observer = LifecycleEventObserver { _, event ->
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "PictureshowSyncNative",
                    "trace-coordinator elapsedMs=${android.os.SystemClock.elapsedRealtime()} lifecycle=$event"
                )
            }
            if (event == Lifecycle.Event.ON_STOP) sync.background()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "PictureshowSyncNative",
                    "trace-coordinator elapsedMs=${android.os.SystemClock.elapsedRealtime()} lifecycle=dispose"
                )
            }
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
    SyncPlayChooser(sync)
    sync.externalRouteConsent?.let { consent ->
        SyncLeaveForCastConsent(
            onDismiss = { sync.cancelExternalRoute(consent.scope, consent.id) },
            onConfirm = { sync.leaveForExternalRoute(consent.scope, consent.id) }
        )
    }
}

/** The actual consent component is also used by the explicitly labeled, network-free debug fixture. */
@Composable
internal fun SyncLeaveForCastConsent(onDismiss: () -> Unit, onConfirm: () -> Unit, fixtureLabel: String? = null) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            androidx.compose.foundation.layout.Column {
                fixtureLabel?.let {
                    androidx.compose.material3.Text(
                        androidx.compose.ui.res.stringResource(R.string.sync_fixture, it),
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge
                    )
                }
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.sync_cast_title))
            }
        },
        text = {
            androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.sync_cast_explanation))
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.sync_cast_confirm))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.sync_cancel))
            }
        }
    )
}

/** Shared by TV and touch; download provenance belongs to the actual viewing, not its metadata or connection. */
internal fun PlaybackHostState.syncRendererSnapshot(): SyncRendererState {
    val p = player
    val d = state.decision
    val duration = this.duration.takeIf { it > 0 } ?: p?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0
    return SyncRendererState(
        item.id,
        output.position().milliseconds.coerceAtLeast(0) * 10_000,
        duration * 10_000,
        p?.playbackState == Player.STATE_READY && error == null && !state.preparing,
        p?.playbackState == Player.STATE_BUFFERING,
        p?.isPlaying == true,
        syncSourceEligible(
            duration * 10_000,
            d?.mediaSource != null && p?.isCurrentMediaItemSeekable == true,
            item.type == "TvChannel" || d?.mediaSource?.liveStreamId != null,
            d?.mediaSource?.isInfiniteStream == true,
            offline = localCopy
        ),
        // Our known PCM sink uses Sonic in software; encoded, unknown and injected outputs retain seek fallback.
        rateSupported = output.supportsSyncRate(p),
        native = output.syncNativeSnapshot(),
        playWhenReady = p?.playWhenReady == true
    )
}

/** Native SEEK is emitted when Media3 actually accepts a position, including deferred availability. */
internal fun syncPositionApplicationListener(onApplied: () -> Unit): Player.Listener = object : Player.Listener {
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) onApplied()
    }
}

/** Shared by group transport and correction; only correction rechecks its live execution band. */
internal fun PlaybackHostState.applySyncTimeline(
    positionTicks: Long?,
    playing: Boolean?,
    rate: Double,
    correctionSampledAtMillis: Long? = null,
    executionNow: () -> Long = android.os.SystemClock::elapsedRealtime
): Boolean = output.applySync {
    output.traceNative("sync-before", "requestedTicks=$positionTicks requestedPlaying=$playing rate=$rate")
    val active = player ?: return@applySync false
    val current = active.currentPosition.coerceAtLeast(0)
    val target = if (correctionSampledAtMillis != null && positionTicks != null) {
        val end = duration.takeIf { it > 0 } ?: active.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0
        app.pictureshow.core.sync.syncCorrectionPosition(
            current * 10_000,
            positionTicks,
            playing == true,
            correctionSampledAtMillis,
            executionNow(),
            end * 10_000,
            resolutionTicks = 10_000
        )
    } else {
        positionTicks
    }
    var changed = false
    if (target != null && kotlin.math.abs(current - target / 10_000) > 1) {
        seekToLocal(target / 10_000, revealControls = false, bypassSync = true)
        changed = pendingSeek == null && state.seekTargetMs == null
    }
    output.applySyncRate(rate.toFloat(), active)
    if (playing != null && active.playWhenReady != playing) active.playWhenReady = playing
    output.traceNative(
        "sync-after",
        "requestedTicks=$positionTicks executionTicks=${target ?: -1} requestedPlaying=$playing sought=$changed rate=$rate"
    )
    changed
}

@Composable
internal fun SyncPlaybackBinding(host: PlaybackHostState) {
    val sync: SyncPlayHost = koinInject()
    val opening = LocalSyncOpen.current
    val token = remember { Any() }
    host.sync = sync
    val bridge = remember(host, sync) {
        object : SyncRenderer {
            override val sourceIdentity: Any get() = host.player ?: host.output
            override val positionApplicationPending: Boolean get() = host.pendingSeek != null || host.state.seekTargetMs != null
            override fun snapshot(): SyncRendererState = host.syncRendererSnapshot()
            override fun apply(positionTicks: Long?, playing: Boolean?, rate: Double): Boolean =
                host.applySyncTimeline(positionTicks, playing, rate)
            override fun correct(positionTicks: Long, playing: Boolean, rate: Double, sampledAtMillis: Long): Boolean =
                host.applySyncTimeline(positionTicks, playing, rate, sampledAtMillis)
        }
    }
    val generation = sync.membership.generation
    val entryId = sync.membership.queue?.current?.playlistItemId
    DisposableEffect(sync, host, opening, host.player, generation, entryId) {
        val active = host.player
        val positionListener = syncPositionApplicationListener {
            if (active != null) sync.rendererPositionApplied(token, bridge, active, generation, entryId)
        }
        active?.addListener(positionListener)
        host.output.traceNative("binding-install", "localCopy=${host.localCopy}")
        host.output.syncRequest = sync::transport
        host.output.syncPrimaryAction = { current ->
            if (sync.participating) {
                current.performTouchPrimaryAction(sync)
                true
            } else {
                false
            }
        }
        host.output.onSyncPauseRequested = { host.onViewerPauseShown() }
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
            active?.removeListener(positionListener)
            host.output.traceNative("binding-dispose")
            sync.clearRenderer(token)
            host.output.syncRequest = null
            host.output.syncPrimaryAction = null
            host.output.onSyncPauseRequested = {}
            host.output.syncParticipating = { false }
            host.output.endSyncRate()
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

/** The actual chooser binding captures identities so an old layout/close cannot acknowledge a later record. */
@Composable
internal fun SyncPlayChooser(sync: SyncPlayHost) {
    val presentation by sync.state.collectAsState()
    if (sync.panelOpen) {
        val accountScope = sync.departureState.scope
        val session = sync.chooserSession
        val departureId = presentation.departure?.id
        SyncPlayPanel(
            presentation, sync::action,
            { sync.dismissChooser(accountScope, departureId, session) },
            fixtureLabel = null,
            onDeparturePresented = { id -> sync.departurePresented(accountScope, id, session) }
        )
    }
}
