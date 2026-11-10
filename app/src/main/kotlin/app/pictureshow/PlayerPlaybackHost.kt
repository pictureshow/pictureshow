/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.Context
import android.util.Log
import android.view.accessibility.CaptioningManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Player
import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import app.pictureshow.core.MediaSegment
import app.pictureshow.core.PlaybackExtrasClient
import app.pictureshow.core.UserConfigurationClient
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlaybackClient
import io.ktor.client.HttpClient
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** A pending or failed optional lookup is not evidence that this is the last episode. */
internal data class EpisodeLookup(val attempt: Int, val result: ClientResult<MediaItem?>)

/**
 * The one owner of a viewing, whichever presentation shows it. Everything here is playback: the session and its
 * callbacks, seeks, the end of the title and what follows it, watched-state hand-off, background and disposal.
 * A presentation adds buttons, focus and speech, and tells the host only what it must react to through the
 * `on...` hooks. New player behaviour belongs here unless it is about one mode's input or layout.
 */
internal class PlaybackHostState(
    val item: MediaItem,
    val startMs: Long,
    val output: Media3PlaybackOutput,
    private val onNext: (MediaItem) -> Unit,
    private val onExit: () -> Unit,
    /** Null where this device isn't a remote-control target (REM-09: Android TV only). */
    val remote: RemoteTargetHost? = null
) {
    /** Final Stopped/DELETE requests must outlive navigation disposal, then this finite scope cancels itself. */
    val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var sync: SyncPlayHost? = null
    var localPreferences by mutableStateOf(PlaybackPreferences())
    var subtitleDelayMs by mutableIntStateOf(0)
    var probe by mutableStateOf<DeviceCapabilityProbe?>(null)
    var controller by mutableStateOf<PlaybackSession?>(null)
    var setupError by mutableStateOf<String?>(null)
    var position by mutableLongStateOf(startMs)
    var duration by mutableLongStateOf(0)
    var metadata by mutableStateOf(item)
    var segments by mutableStateOf(emptyList<MediaSegment>())
    internal var nextLookup by mutableStateOf<EpisodeLookup?>(null)
    var nextRetry by mutableIntStateOf(0)

    /** A title queued from a controller goes first. With no controller this is always null, and nothing below changes. */
    var remoteNext by mutableStateOf<MediaItem?>(null)
    var remoteNextResolving by mutableStateOf(false)
    var extras by mutableStateOf<PlaybackExtrasClient?>(null)
    var tiles by mutableStateOf<TrickplayCache?>(null)
    var nextCancelled by mutableStateOf(false)
    var ended by mutableStateOf(false)
    var switching by mutableStateOf(false)

    /** Stop was committed before backgrounding: the viewing is done, but only a deliberate Next or Play opens this. */
    var deferredNext by mutableStateOf<MediaItem?>(null)
    var advanceReported by mutableStateOf(false)
    var exitRequested by mutableStateOf(false)
    var stateSource by mutableStateOf<androidx.compose.runtime.State<PlaybackState>>(mutableStateOf(PlaybackState()))
    var playerSource by mutableStateOf<androidx.compose.runtime.State<Player?>>(mutableStateOf(null))

    /** Both are scoped to one decoder; [rememberPlaybackHostState] replaces them when the player changes. */
    internal var seekPreviewSource by mutableStateOf<MutableState<Long?>>(mutableStateOf(null))
    internal var pendingSeekSource by mutableStateOf<MutableState<Long?>>(mutableStateOf(null))
    var seekPreview: Long?
        get() = seekPreviewSource.value
        set(value) {
            seekPreviewSource.value = value
        }

    /** Native HLS replacements can exist before their manifest advertises seeking; keep the intent until then. */
    var pendingSeek: Long?
        get() = pendingSeekSource.value
        set(value) {
            pendingSeekSource.value = value
        }

    /** Panels close before any exit. */
    var onFinishing: () -> Unit = {}

    /** Once the next item is really on its way, not when asking was refused. */
    var onAdvancing: () -> Unit = {}

    /** A seek reveals the controls with its new position. */
    var onSeekShown: () -> Unit = {}

    /** Before the output learns the activity stopped. */
    var onStopped: () -> Unit = {}
    val state get() = stateSource.value
    val player get() = playerSource.value
    val error get() = setupError ?: state.error
    val nextEpisode get() = remoteNext ?: (nextLookup?.result as? ClientResult.Success)?.value
    val findingNext get() = ended && (nextLookup == null || nextLookup?.result is ClientResult.Failure)

    /** This viewing is over, though the player may wait here for the viewer: with a screen reader on, or after a trip away. */
    val atEnd get() = ended || deferredNext != null

    fun finish(next: MediaItem? = null, reason: String = "next-episode") {
        if (next != null && sync?.transport("next") == true) return
        if (next == null && sync?.participating == true) sync?.leave()
        onFinishing()
        if (!exitRequested && !switching) {
            PlaybackDiagnostics.write(PlaybackDiagnostics.line(state.decision, "exit-request", "itemId=${item.id} reason=$reason"))
        }
        if (next == null) {
            if (exitRequested) return
            exitRequested = true
            if (advanceReported) onExit() else finishPlayback(controller, onExit)
            return
        }
        if (switching || exitRequested || !output.foreground.value) return
        switching = true
        onAdvancing()
        if (advanceReported) {
            onNext(next)
            return
        }
        val generation = output.stopGeneration
        fun mayAdvance() = !exitRequested && output.foreground.value && output.stopGeneration == generation
        controller?.closeForNext(::mayAdvance) {
            advanceReported = true
            if (!exitRequested) {
                if (mayAdvance()) {
                    onNext(next)
                } else {
                    // Stop was already committed before backgrounding. Keep the completed
                    // destination; only a deliberate Next/Play may open its successor.
                    position = output.position().milliseconds
                    deferredNext = next
                    switching = false
                    PlaybackDiagnostics.write("continuation-deferred positionMs=$position")
                }
            }
        } ?: onExit()
    }

    /** The viewer turned down what comes next, so the end of this title leaves the player. */
    fun cancelNext() {
        PlaybackDiagnostics.write("continuation-cancel item=${item.id}")
        nextCancelled = true
        deferredNext = null
        if (advanceReported) finish(reason = "natural-end")
    }

    fun applyPendingSeek() {
        val active = player ?: return
        val requested = pendingSeek ?: return
        if (!active.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
        val limit = active.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        val target = requested.coerceIn(0, limit)
        // Clear before seekTo: its synchronous callbacks can update available commands.
        pendingSeek = null
        active.seekTo(target)
        position = target
        seekPreview = target
    }

    /** A relative step, accumulating against the last intended target (PLAY-07). */
    fun seek(delta: Long) {
        if (sync?.transport("seek", (position + delta).coerceIn(0, duration.coerceAtLeast(0))) == true) return
        val serverTarget = controller?.stepSeek(delta)
        if (serverTarget != null) {
            position = serverTarget
            seekPreview = serverTarget
        } else {
            val active = player ?: return
            val limit = active.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
            val target = ((pendingSeek ?: seekPreview ?: active.currentPosition) + delta).coerceIn(0, limit)
            pendingSeek = target
            position = target
            seekPreview = target
            applyPendingSeek()
        }
        onSeekShown()
    }

    /** An absolute target: a scrub, a chapter or a controller's seek. */
    fun seekTo(targetMs: Long) {
        if (sync?.transport("seek", targetMs) == true) return
        seekToLocal(targetMs)
    }

    fun seekToLocal(targetMs: Long) {
        val current = state.decision
        if (current != null &&
            app.pictureshow.core.playback.TranscodeSeek.required(current) &&
            controller?.seek(targetMs, current) == true
        ) {
            position = targetMs
            seekPreview = targetMs
        } else {
            pendingSeek = targetMs
            position = targetMs
            seekPreview = targetMs
            applyPendingSeek()
        }
        onSeekShown()
    }
}

/** The host's state, without its effects: a presentation calls [PlaybackHostEffects] where its own effects need it. */
@Composable
internal fun rememberPlaybackHostState(
    account: Account,
    item: MediaItem,
    startMs: Long,
    onNext: (MediaItem) -> Unit,
    onExit: () -> Unit,
    providedOutput: Media3PlaybackOutput? = null,
    remote: RemoteTargetHost? = null
): PlaybackHostState {
    val context = LocalContext.current
    val output = remember { providedOutput ?: Media3PlaybackOutput(context, account.server, item.name) }
    val latestNext by rememberUpdatedState(onNext)
    val latestExit by rememberUpdatedState(onExit)
    val host = remember { PlaybackHostState(item, startMs, output, { latestNext(it) }, { latestExit() }, remote) }
    val emptyState = remember { MutableStateFlow(PlaybackState()) }
    host.stateSource = (host.controller?.state ?: emptyState).collectAsState()
    host.playerSource = output.player.collectAsState()
    val player = host.player
    host.seekPreviewSource = remember(player) { mutableStateOf(null) }
    host.pendingSeekSource = remember(player) { mutableStateOf(null) }
    return host
}

/** A presentation with no effects of its own to order around the session's. */
@Composable
internal fun rememberPlaybackHost(
    account: Account,
    item: MediaItem,
    startMs: Long,
    catalog: CatalogSession,
    onNext: (MediaItem) -> Unit,
    onExit: () -> Unit,
    providedOutput: Media3PlaybackOutput? = null
): PlaybackHostState {
    val host = rememberPlaybackHostState(account, item, startMs, onNext, onExit, providedOutput)
    val paused by host.output.pauseIntent.collectAsState()
    PlaybackHostEffects(host, account, catalog, paused)
    return host
}

@Composable
private fun RemoteTargetBinding(
    host: PlaybackHostState,
    remote: RemoteTargetHost,
    account: Account,
    catalog: CatalogSession,
    paused: Boolean
) {
    LaunchedEffect(remote.queueRevision, catalog) {
        host.remoteNextResolving = remote.upcoming != null
        host.remoteNext = remote.upcoming?.let { id ->
            (resolvePlaybackEntry(PlaybackEntry(id), account, catalog) as? ResolvedPlaybackEntry.Open)?.item
        }
        host.remoteNextResolving = false
    }
    val remotePlayer = remember { Any() }
    val remoteBridge = remember(host) {
        object : RemotePlayerBridge {
            override fun pause() {
                if (host.sync?.transport("pause") != true) host.player?.pause()
            }
            override fun unpause() {
                if (host.sync?.transport("play") != true) host.player?.play()
            }
            override fun report() {
                host.controller?.progress()
            }
            override fun seekTo(positionMs: Long) = host.seekTo(positionMs)
            override fun stop() {
                if (host.sync?.transport("stop") != true) host.finish(reason = "remote-stop")
            }
            override fun next(item: String?) {
                if (host.sync?.transport("next") == true) return
                if (item == null) {
                    host.nextEpisode?.let { host.finish(it, reason = "remote-next") }
                    return
                }
                host.reportScope.launch {
                    (resolvePlaybackEntry(PlaybackEntry(item), account, catalog) as? ResolvedPlaybackEntry.Open)?.let {
                        host.finish(it.item, reason = "remote-next")
                    }
                }
            }
            override fun previous(): Boolean = host.sync?.transport("previous") == true
            override fun audio(index: Int) {
                val streams = host.state.decision?.mediaSource?.mediaStreams.orEmpty()
                if (streams.any { it.type == MediaStreamType.Audio && it.index == index }) {
                    host.controller?.select(MediaStreamType.Audio, index)
                }
            }
            override fun subtitle(index: Int) {
                val streams = host.state.decision?.mediaSource?.mediaStreams.orEmpty()
                if (index == -1 || streams.any { it.type == MediaStreamType.Subtitle && it.index == index }) {
                    host.controller?.select(MediaStreamType.Subtitle, index)
                }
            }
        }
    }
    // Once per mount: a title being replaced may still recompose, and must not take the target back from its successor.
    DisposableEffect(remote, remoteBridge) {
        remote.setPlayer(remotePlayer, remoteBridge)
        onDispose { remote.clearPlayer(remotePlayer) }
    }
    with(host) {
        val remoteReady = !exitRequested && !switching && state.decision != null && !state.preparing && player != null && duration > 0
        LaunchedEffect(item.id, remoteReady, paused, duration, position) {
            remote.report(
                remotePlayer,
                app.pictureshow.core.control.RemoteViewing(
                    item = item.id,
                    ready = remoteReady,
                    durationTicks = duration * 10_000,
                    ticks = position * 10_000,
                    paused = paused,
                    canSeek = duration > 0
                )
            )
        }
    }
}

/** Starts and ends the viewing. [paused] is the presentation's view of playback intent, reported to a controller. */
@Composable
internal fun PlaybackHostEffects(host: PlaybackHostState, account: Account, catalog: CatalogSession, paused: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val http: HttpClient = koinInject()
    val identity: DeviceIdentityStore = koinInject()
    val preferences: PlaybackPreferencesStore = koinInject()
    val output = host.output
    val foreground by output.foreground.collectAsState()
    val pauseIntent by output.pauseIntent.collectAsState()
    SyncPlaybackBinding(host)
    host.remote?.let { RemoteTargetBinding(host, it, account, catalog, paused) }
    with(host) {
        DisposableEffect(player) {
            val active = player
            val listener = object : Player.Listener {
                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = applyPendingSeek()
            }
            active?.addListener(listener)
            applyPendingSeek()
            onDispose { active?.removeListener(listener) }
        }
        LaunchedEffect(Unit) {
            try {
                localPreferences = preferences.read()
                output.passthroughEnabled = localPreferences.passthrough
                val activeProbe = DeviceCapabilityProbe(context, localPreferences.deviceSettings())
                probe = activeProbe
                val clientIdentity =
                    ClientIdentity(
                        deviceDisplayName(context),
                        account.deviceId?.takeIf(String::isNotBlank) ?: identity.getOrCreate(),
                        BuildConfig.VERSION_NAME
                    )
                val client = PlaybackClient(http, clientIdentity)
                extras = PlaybackExtrasClient(http, clientIdentity) { message -> Log.i("NextEpisode", message) }
                tiles = TrickplayCache(http, account, clientIdentity, item.id)
                val captionsEnabled = (context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager)?.isEnabled == true
                val configuration = if (captionsEnabled) {
                    (UserConfigurationClient(http, clientIdentity).configuration(account) as? ClientResult.Success)?.value
                } else {
                    null
                }
                val captionLanguage = captionSubtitleLanguage(captionsEnabled, configuration, Locale.getDefault().toLanguageTag())
                val report = Any()
                val localOwner = LocalVideoOwnerAdapter(
                    app.pictureshow.core.control.ViewingIdentity(
                        account.server.id,
                        account.user.id,
                        clientIdentity.deviceId,
                        java.util.UUID.randomUUID().toString(),
                        0
                    ),
                    ServerPlaybackGateway(client, account, item.id, activeProbe::snapshot, captionLanguage) {
                        catalog.completePlayback(account, item.id, report)
                    },
                    MetadataPlaybackOutput(output) { playbackMetadata(metadata) },
                    item.id
                ) { reason -> PlaybackDiagnostics.write("owner-report suppressed reason=$reason") }
                val session = PlaybackSession(
                    localOwner.playbackGateway,
                    localOwner.playbackOutput,
                    reportScope,
                    Locale.getDefault().language,
                    onFinished = { catalog.refresh(item.id, report) },
                    onCompletionPending = {
                        catalog.playbackPending(item.id, report, setOfNotNull(item.seriesId, item.seasonId))
                    },
                    onCleanupDone = {
                        localOwner.cleanupDone()
                        reportScope.cancel()
                    },
                    diagnostic = PlaybackDiagnostics::write
                )
                controller = session
                output.onStop = { if (sync?.transport("stop") != true) finish(reason = "system-stop") }
                output.onSeek = { target, source -> session.seek(target, source) }
                output.onReady = { source, position, target -> session.ready(source, position, target) }
                output.onBuffering = { waiting, source -> session.buffering(waiting, source) }
                output.onProgress = { session.progress(it) }
                output.onFailure = { retry, source -> session.failed(retry, source) }
                output.onAudioFailure = { source, local -> session.audioFailed(source, local) }
                output.onEnded = ended@{ source ->
                    if (sync?.transport("next") == true) return@ended
                    if (!output.foreground.value || output.pauseIntent.value) return@ended
                    val finalPosition = output.position().milliseconds
                    val finalDuration = output.player.value?.duration?.takeIf { it > 0 } ?: duration
                    if (session.ended(source)) {
                        position = finalPosition
                        duration = finalDuration
                        PlaybackDiagnostics.write(
                            "continuation item=${item.id} lookup=${when (val result = nextLookup?.result) {
                                null -> "pending"
                                is ClientResult.Failure -> "failed"
                                is ClientResult.Success -> result.value?.id ?: "none"
                            }} cancelled=$nextCancelled"
                        )
                        ended = true
                    }
                }
                activeProbe.start { session.routeChanged() }
                PlaybackDiagnostics.write("start-request item=${item.id} requestedMs=$startMs")
                session.start(startMs)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PlaybackDiagnostics.write("failure item=${item.id} reason=setup")
                setupError = "Could not start the player. Return to the item and try again."
            }
        }
        LaunchedEffect(extras) {
            val client = extras ?: return@LaunchedEffect
            (client.item(account, item.id) as? ClientResult.Success)?.value?.let { metadata = it }
        }
        LaunchedEffect(player, metadata) { player?.let { updatePlaybackMetadata(it, metadata) } }
        LaunchedEffect(extras, item.id) {
            val client = extras ?: return@LaunchedEffect
            segments = (client.segments(account, item.id) as? ClientResult.Success)?.value.orEmpty()
        }
        LaunchedEffect(extras, metadata.id, metadata.type, metadata.seriesId, nextRetry) {
            val client = extras ?: return@LaunchedEffect
            nextLookup = null
            nextLookup = EpisodeLookup(nextRetry, client.nextEpisode(account, metadata))
        }
        val upcoming = remote?.upcoming
        LaunchedEffect(ended, nextLookup, nextCancelled, foreground, pauseIntent, remoteNext, upcoming, remoteNextResolving) {
            if (sync?.participating == true || !ended || !foreground || pauseIntent) return@LaunchedEffect
            val lookup = nextLookup
            if (nextCancelled) {
                finish(reason = "natural-end")
            } else if (!remoteQueueHoldsEnd(upcoming, remoteNext, remoteNextResolving)) {
                when (val result = lookup?.result) {
                    is ClientResult.Failure -> if (lookup.attempt == 0) nextRetry = 1 else finish(reason = "natural-end")
                    is ClientResult.Success -> if (result.value == null) finish(reason = "natural-end")
                    null -> Unit
                }
            }
        }
        LaunchedEffect(seekPreview) {
            if (seekPreview != null) {
                delay(2_000)
                seekPreview = null
            }
        }
        LaunchedEffect(player) {
            while (true) {
                player?.let {
                    position = state.seekTargetMs ?: if (ended) output.position().milliseconds else it.currentPosition.coerceAtLeast(0)
                    duration = it.duration.takeIf { value -> value != C.TIME_UNSET && value > 0 } ?: 0
                }
                view.keepScreenOn = player?.playWhenReady == true && state.error == null
                delay(500)
            }
        }
        DisposableEffect(lifecycle, output) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    onStopped()
                    output.setForeground(false)
                } else if (event == Lifecycle.Event.ON_START) {
                    output.setForeground(true)
                }
            }
            output.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        DisposableEffect(Unit) {
            onDispose {
                if (!exitRequested && !switching) PlaybackDiagnostics.write("exit-request item=${item.id} reason=dispose")
                exitRequested = true
                view.keepScreenOn = false
                probe?.close()
                tiles?.close()
                if (controller != null) {
                    controller?.close()
                } else {
                    output.release()
                    reportScope.cancel()
                }
            }
        }
    }
}
