/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.Context
import android.util.Log
import android.view.accessibility.CaptioningManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.ContentFrame
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import app.pictureshow.core.MediaSegment
import app.pictureshow.core.PlaybackExtrasClient
import app.pictureshow.core.SegmentMode
import app.pictureshow.core.UserConfigurationClient
import app.pictureshow.core.activeSegment
import app.pictureshow.core.chooseTrickplay
import app.pictureshow.core.creditsStartMs
import app.pictureshow.core.nextEpisodeTriggerMs
import app.pictureshow.core.playback.ConversionNotice
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlaybackClient
import app.pictureshow.core.trickplayTile
import io.ktor.client.HttpClient
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject

/** A restored route is a recovery destination, not a request to replay its original start offset. */
@Composable
internal fun PlayerRestoration(item: MediaItem, onRestore: (MediaItem) -> Unit, content: @Composable ((MediaItem) -> Unit) -> Unit) {
    // The original item already lives in the navigation argument. Save another only after auto-next.
    var recoveryItem by rememberSaveable { mutableStateOf<String?>(null) }
    var opened by rememberSaveable { mutableStateOf(false) }
    val restoring = remember { opened }
    if (restoring) {
        LaunchedEffect(Unit) { onRestore(recoveryItem?.let { Json.decodeFromString<MediaItem>(it) } ?: item) }
    } else {
        LaunchedEffect(Unit) { opened = true }
        content { current -> recoveryItem = current.takeIf { it.id != item.id }?.let { Json.encodeToString(it) } }
    }
}

@Composable
internal fun PlayerScreen(
    account: Account,
    item: MediaItem,
    startMs: Long,
    catalog: CatalogSession,
    onCurrentItem: (MediaItem) -> Unit = {},
    onExit: () -> Unit
) {
    var current by remember(account.key, item.id) { mutableStateOf(item) }
    var start by remember(account.key, item.id) { mutableLongStateOf(startMs) }
    LaunchedEffect(current) { onCurrentItem(current) }
    key(current.id) {
        PlayerItemScreen(account, current, start, catalog, onNext = { next ->
            start = app.pictureshow.core.resumeStartPositionMs(next.userData.playbackPositionTicks)
            current = next
        }, onExit = onExit)
    }
}

private data class EpisodeLookup(val attempt: Int, val result: ClientResult<MediaItem?>)

private class PlayerTrackChooserRequest(
    val type: MediaStreamType,
    val preparation: PlayerTrackMenuPreparation,
    val awaitingFirstSource: Boolean,
    initiallyOpen: Boolean,
    var selectedIndex: Int
) {
    var opened by mutableStateOf(initiallyOpen)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerItemScreen(
    account: Account,
    item: MediaItem,
    startMs: Long,
    catalog: CatalogSession,
    onNext: (MediaItem) -> Unit,
    onExit: () -> Unit,
    providedOutput: Media3PlaybackOutput? = null
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val view = LocalView.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val accessibleControls = rememberAccessibilityEnabled()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val http: HttpClient = koinInject()
    val identity: DeviceIdentityStore = koinInject()
    val preferences: PlaybackPreferencesStore = koinInject()
    var localPreferences by remember { mutableStateOf(PlaybackPreferences()) }
    var subtitleDelayMs by remember { mutableIntStateOf(0) }
    val output = remember { providedOutput ?: Media3PlaybackOutput(context, account.server, item.name) }
    // Final Stopped/DELETE requests must outlive navigation disposal, then this finite scope cancels itself.
    val reportScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    var probe by remember { mutableStateOf<DeviceCapabilityProbe?>(null) }
    var controller by remember { mutableStateOf<PlaybackSession?>(null) }
    val emptyState = remember { MutableStateFlow(PlaybackState()) }
    val state by (controller?.state ?: emptyState).collectAsState()
    val player by output.player.collectAsState()
    val speedState by output.speed.collectAsState()
    var extraPanel by remember { mutableStateOf<String?>(null) }
    val foreground by output.foreground.collectAsState()
    val pauseIntent by output.pauseIntent.collectAsState()
    var setupError by remember { mutableStateOf<String?>(null) }
    var controls by remember { mutableStateOf(false) }
    var controlsFeedback by remember { mutableStateOf<String?>(null) }
    var controlsHeight by remember { mutableStateOf(240.dp) }
    val density = LocalDensity.current
    var controlsTop by remember { mutableStateOf<Int?>(null) }
    var actionsTop by remember { mutableStateOf<Int?>(null) }
    var playerTop by remember { mutableIntStateOf(0) }
    val trackMenus = rememberPlayerTrackMenus(state.decision?.mediaSourceId, state.decision?.mediaSource?.mediaStreams.orEmpty())
    var chooserRequest by remember { mutableStateOf<PlayerTrackChooserRequest?>(null) }
    val chooser = chooserRequest?.type
    var interaction by remember { mutableLongStateOf(0) }
    var position by remember { mutableLongStateOf(startMs) }
    var duration by remember { mutableLongStateOf(0) }
    val playback = rememberPlayerPlayback(player, onPause = {
        controls = true
        interaction++
    }, onResume = { interaction++ })
    val paused = playback.paused || pauseIntent
    val showPlayButton = playback.showPlayButton
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val errorFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }
    val subtitleFocus = remember { FocusRequester() }
    val chaptersFocus = remember { FocusRequester() }
    val speedFocus = remember { FocusRequester() }
    val infoFocus = remember { FocusRequester() }
    fun openExtraPanel(title: String, opener: FocusRequester) {
        // Keep the opener stable for both key and touch activation.
        opener.requestFocus()
        extraPanel = title
    }
    val timelineFocus = remember { FocusRequester() }
    var seekOpen by remember { mutableStateOf(false) }
    var restoringSeekFocus by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val error = setupError ?: state.error
    var metadata by remember { mutableStateOf(item) }
    var segments by remember { mutableStateOf(emptyList<MediaSegment>()) }
    // A pending or failed optional lookup is not evidence that this is the last episode.
    var nextLookup by remember { mutableStateOf<EpisodeLookup?>(null) }
    var nextRetry by remember { mutableIntStateOf(0) }
    val nextEpisode = (nextLookup?.result as? ClientResult.Success)?.value
    var extras by remember { mutableStateOf<PlaybackExtrasClient?>(null) }
    var tiles by remember { mutableStateOf<TrickplayCache?>(null) }
    var chaptersOpen by remember { mutableStateOf(false) }
    var seekPreview by remember(player) { mutableStateOf<Long?>(null) }
    // Native HLS replacements can exist before their manifest advertises seeking.
    // Keep the intent beyond the short-lived preview, scoped to this player.
    var pendingSeek by remember(player) { mutableStateOf<Long?>(null) }
    var rootFocused by remember { mutableStateOf(false) }
    var focusControls by remember { mutableStateOf(false) }
    var actionsFocused by remember { mutableStateOf(false) }
    var nextCancelled by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    var deferredNext by remember { mutableStateOf<MediaItem?>(null) }
    var advanceReported by remember { mutableStateOf(false) }
    var exitRequested by remember { mutableStateOf(false) }
    var conversionDetailsRequested by remember { mutableStateOf(false) }

    fun finish(next: MediaItem? = null, reason: String = "next-episode") {
        extraPanel = null
        chooserRequest = null
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

    fun seek(delta: Long) {
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
        controls = true
        interaction++
    }

    DisposableEffect(player) {
        val active = player
        val listener = object : Player.Listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = applyPendingSeek()
        }
        active?.addListener(listener)
        applyPendingSeek()
        onDispose { active?.removeListener(listener) }
    }

    fun handleBack() {
        when {
            switching -> finish(reason = "back-during-next")
            extraPanel != null -> {
                returnFocus = if (extraPanel == "Playback speed") speedFocus else infoFocus
                extraPanel = null
            }
            chaptersOpen -> {
                chaptersOpen = false
                returnFocus = chaptersFocus
            }
            seekOpen -> {
                seekOpen = false
                restoringSeekFocus = true
                returnFocus = timelineFocus
            }
            chooser != null -> {
                returnFocus = if (chooser == MediaStreamType.Audio) audioFocus else subtitleFocus
                chooserRequest = null
            }
            controls -> {
                controls = false
                if (accessibleControls) controlsFeedback = "Controls hidden"
                rootFocus.requestFocus()
            }
            else -> finish(reason = "back")
        }
    }
    BackHandler { handleBack() }
    LaunchedEffect(Unit) {
        rootFocus.requestFocus()
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
            )
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
            output.onStop = { finish(reason = "system-stop") }
            output.onSeek = { target, source -> session.seek(target, source) }
            output.onReady = { source, position, target -> session.ready(source, position, target) }
            output.onBuffering = { waiting, source -> session.buffering(waiting, source) }
            output.onProgress = { session.progress(it) }
            output.onFailure = { retry, source -> session.failed(retry, source) }
            output.onAudioFailure = { source, local -> session.audioFailed(source, local) }
            output.onEnded = ended@{ source ->
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
    LaunchedEffect(ended, nextLookup, nextCancelled, foreground, pauseIntent) {
        if (!ended || !foreground || pauseIntent) return@LaunchedEffect
        val lookup = nextLookup
        if (nextCancelled) {
            finish(reason = "natural-end")
        } else {
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
    LaunchedEffect(controls, focusControls, chaptersOpen, chooser, seekOpen, extraPanel, restoringSeekFocus) {
        if (controls && focusControls && !chaptersOpen && chooser == null && !seekOpen && extraPanel == null && !restoringSeekFocus) {
            playFocus.requestFocus()
            focusControls = false
        }
    }
    LaunchedEffect(controls) { if (controls) controlsFeedback = null }
    // A touch on the conversion notice reveals controls first, so Playback info has a real opener to return to.
    LaunchedEffect(conversionDetailsRequested, controls) {
        if (conversionDetailsRequested && controls) {
            androidx.compose.runtime.withFrameNanos { }
            conversionDetailsRequested = false
            if (chooser == null && extraPanel == null && !chaptersOpen && !seekOpen && error == null && !switching) {
                openExtraPanel("Playback info", infoFocus)
            }
        }
    }
    // Pending opens have no window to hand off. A ready panel closes through its native scope below.
    LaunchedEffect(chooserRequest, trackMenus, trackMenus.menus, state.decision != null) {
        val request = chooserRequest ?: return@LaunchedEffect
        if (!request.opened) {
            if (request.preparation !== trackMenus) {
                if (request.awaitingFirstSource && state.decision != null) {
                    // A request made during initial loading follows the first real source.
                    chooserRequest = PlayerTrackChooserRequest(
                        request.type,
                        trackMenus,
                        false,
                        false,
                        if (request.type == MediaStreamType.Audio) state.audioIndex else state.subtitleIndex
                    )
                } else {
                    chooserRequest = null
                    returnFocus = if (request.type == MediaStreamType.Audio) audioFocus else subtitleFocus
                }
            } else if (trackMenus.menus != null && state.decision != null && error == null && !switching && !exitRequested) {
                request.opened = true
            }
        }
    }
    LaunchedEffect(error) {
        if (error != null) {
            restoringSeekFocus = false
            returnFocus = null
            seekOpen = false
            chooserRequest = null
            chaptersOpen = false
            extraPanel = null
        }
    }
    LaunchedEffect(accessibleControls) {
        if (accessibleControls) {
            controls = true
            focusControls = true
        }
    }
    LaunchedEffect(returnFocus, chooser, chaptersOpen, seekOpen, extraPanel, windowFocused) {
        val target = returnFocus
        if (target != null && windowFocused && chooser == null && extraPanel == null && !chaptersOpen && !seekOpen) {
            androidx.compose.runtime.withFrameNanos { }
            val requested = target.requestFocus()
            PlaybackDiagnostics.write("panel-return requested=$requested")
            if (restoringSeekFocus && accessibleControls) {
                val started = android.os.SystemClock.uptimeMillis()
                val acknowledged = awaitInitialPlayerPanelFocus(view, "player:position")
                PlaybackDiagnostics.write(
                    "seek-return requested=$requested acknowledged=$acknowledged waitMs=${android.os.SystemClock.uptimeMillis() - started}"
                )
            }
            restoringSeekFocus = false
            returnFocus = null
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
    ControlsAutoHide(
        controls,
        accessibleControls || paused || actionsFocused || chooser != null || extraPanel != null || chaptersOpen || seekOpen,
        interaction
    ) {
        controls = false
        rootFocus.requestFocus()
    }
    DisposableEffect(lifecycle, output) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (chooserRequest?.opened == false) chooserRequest = null
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

    PlayerSeekHost((seekOpen || extraPanel != null) && error == null && !switching, player = {
        Box(
            Modifier.fillMaxSize().background(Color.Black).testTag("player:surface")
                .onGloballyPositioned { playerTop = it.positionInRoot().y.toInt() }
                .focusProperties { canFocus = !restoringSeekFocus && !seekOpen && extraPanel == null }.focusRequester(rootFocus)
                .onFocusChanged { rootFocused = it.isFocused }
                .playerTouchClick {
                    if (chooser == null && extraPanel == null && !chaptersOpen && !seekOpen && error == null && !switching) {
                        controls = true
                        interaction++
                    }
                }
                .playerRemoteKeys(
                    enabled = chooser == null && extraPanel == null && !chaptersOpen && !seekOpen && error == null && !switching,
                    rootFocused = rootFocused,
                    seek = ::seek,
                    showControls = {
                        controls = true
                        if (rootFocused) focusControls = true
                    },
                    interact = { interaction++ },
                    back = ::handleBack,
                    skip = activeSegment(segments, position, localPreferences.segmentModes)
                        ?.takeIf { (localPreferences.segmentModes[it.type] ?: SegmentMode.Ask) == SegmentMode.Ask }
                        ?.let { segment ->
                            {
                                player?.seekTo(segment.endMs)
                                position = segment.endMs
                                interaction++
                            }
                        }
                )
                .semantics {
                    // Back returns both input and accessibility focus here. Name that transition
                    // on the focused node; a sibling live region can be occluded by the video.
                    // Once controls are visible, their surface is not another screen-reader target.
                    // A delayed Done click can trigger TalkBack's focus recovery after the
                    // timeline was restored. Keep the root hidden without hiding its children.
                    if (!restoringSeekFocus && !controls) contentDescription = controlsFeedback ?: "Video player"
                    if (!restoringSeekFocus && !controls) {
                        onClick(label = "Show controls") {
                            controls = true
                            focusControls = true
                            interaction++
                            true
                        }
                    } else {
                        hideFromAccessibility()
                    }
                }
                .focusable()
        ) {
            ContentFrame(player, Modifier.fillMaxSize().clearAndSetSemantics {})
            val controlsVisible =
                controls && chooser == null && extraPanel == null && !chaptersOpen && !seekOpen && error == null && !switching
            val actionsVisible =
                !restoringSeekFocus && chooser == null && extraPanel == null && !chaptersOpen && !seekOpen && error == null && !switching
            // Removal can run after the composition that created the callback; read current state.
            val actionReturnVisible by rememberUpdatedState(actionsVisible)
            val actionReturnControls by rememberUpdatedState(controlsVisible)
            val actionReturnAccessible by rememberUpdatedState(accessibleControls)
            val restoreActionFocus: () -> Unit = {
                if (actionReturnVisible) {
                    val toTimeline = actionReturnControls && actionReturnAccessible
                    if (toTimeline) restoringSeekFocus = true
                    returnFocus = if (toTimeline) timelineFocus else rootFocus
                    PlaybackDiagnostics.write(
                        "action-return target=${if (toTimeline) "timeline" else "root"} " +
                            "controls=$actionReturnControls accessible=$actionReturnAccessible"
                    )
                }
            }
            val overlayTop = listOfNotNull(controlsTop.takeIf { controlsVisible }, actionsTop.takeIf { actionsVisible }).minOrNull()
            PlayerSubtitles(player, localPreferences.appearance, overlayTop?.minus(playerTop), output.subtitleDiagnostics)
            // PLAY-27: top of the frame, away from bottom subtitles and controls; never takes remote focus.
            ConversionNoticeHost(
                state.decision?.takeIf { localPreferences.conversionNotice }?.let { ConversionNotice.of(it, state.subtitleIndex) },
                allowed = player != null &&
                    foreground &&
                    !state.preparing &&
                    error == null &&
                    !switching &&
                    chooser == null &&
                    extraPanel == null &&
                    !chaptersOpen &&
                    !seekOpen,
                modifier = Modifier.align(Alignment.TopStart).padding(36.dp)
            ) {
                controls = true
                interaction++
                conversionDetailsRequested = true
            }
            if (switching) PlaybackStatus("Starting next episode…", Modifier.align(Alignment.Center))
            if (ended && (nextLookup == null || nextLookup?.result is ClientResult.Failure) && !exitRequested && !switching) {
                PlaybackStatus("Finding next episode…", Modifier.align(Alignment.Center))
            }
            if (state.preparing && !state.bufferingSlow && setupError == null && !switching) {
                PlaybackStatus("Preparing playback…", Modifier.align(Alignment.Center))
            }
            if (state.bufferingSlow && error == null && !switching) {
                Text(
                    "Still waiting for playback. You can go back and try again.",
                    modifier = Modifier.align(Alignment.Center).background(Color(0xEE10131C)).padding(24.dp)
                )
            }
            if (error != null) {
                Column(Modifier.align(Alignment.Center).widthIn(max = 720.dp).background(Color(0xEE10131C)).padding(32.dp)) {
                    Text(error)
                    state.reportError?.let { Text("Could not save progress: $it") }
                    Button(
                        scale = playerButtonScale(),
                        onClick = { finish(reason = "error-back") },
                        modifier = Modifier.focusRequester(errorFocus)
                    ) { Text("Back to details") }
                }
                LaunchedEffect(error) { errorFocus.requestFocus() }
            }
            // Keep opener nodes composed while the sibling modal hides this player tree.
            if (controlsVisible || (controls && extraPanel != null && error == null && !switching)) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .then(if (extraPanel != null) Modifier.alpha(0f).clearAndSetSemantics { hideFromAccessibility() } else Modifier)
                        .onSizeChanged {
                            controlsHeight = with(density) { it.height.toDp() }
                        }.onGloballyPositioned {
                            controlsTop = it.positionInRoot().y.toInt()
                        }.semantics { isTraversalGroup = true }.background(Color(0xE610131C)).padding(36.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val sourceId = state.decision?.mediaSourceId
                    val info = chooseTrickplay(metadata, sourceId, (resources.displayMetrics.widthPixels / 6).coerceIn(160, 640))
                    val tile = seekPreview?.let { target -> info?.let { trickplayTile(it, target) } }
                    if (tile != null && tiles != null && sourceId != null) {
                        TrickplayPreview(requireNotNull(tiles), sourceId, tile, requireNotNull(seekPreview))
                    }
                    Text(
                        item.name,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = if (restoringSeekFocus) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier
                    )
                    // Capture for speech when controls open, on pause/resume, and after an explicit seek.
                    // The visual clock keeps moving; it is not a live accessibility announcement.
                    val announcedPosition = remember(paused, seekPreview, state.seekTargetMs, duration, ended) {
                        state.seekTargetMs ?: position
                    }
                    PlayerTimeline(
                        position,
                        announcedPosition,
                        duration,
                        modifier = Modifier.focusRequester(timelineFocus),
                        paused = paused,
                        ended = ended,
                        arrowsSeek = rootFocused && !restoringSeekFocus,
                        onOpenSeek = {
                            seekOpen = true
                        }
                    )
                    FlowRow(
                        modifier = if (restoringSeekFocus) {
                            Modifier.focusProperties { canFocus = false }.clearAndSetSemantics { hideFromAccessibility() }
                        } else {
                            Modifier
                        },
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        PlaybackToggle(paused, showPlayButton, Modifier.focusRequester(playFocus)) {
                            val pending = deferredNext
                            if (pending != null) finish(pending) else player?.togglePlayback()
                            interaction++
                        }
                        Button(scale = playerButtonScale(), onClick = {
                            chooserRequest = PlayerTrackChooserRequest(
                                MediaStreamType.Audio,
                                trackMenus,
                                state.decision == null,
                                trackMenus.menus != null && state.decision != null,
                                state.audioIndex
                            )
                        }, modifier = Modifier.focusRequester(audioFocus)) { Text("Audio") }
                        Button(scale = playerButtonScale(), onClick = {
                            chooserRequest = PlayerTrackChooserRequest(
                                MediaStreamType.Subtitle,
                                trackMenus,
                                state.decision == null,
                                trackMenus.menus != null && state.decision != null,
                                state.subtitleIndex
                            )
                        }, modifier = Modifier.focusRequester(subtitleFocus)) { Text("Subtitles") }
                        if (metadata.chapters.isNotEmpty()) {
                            Button(scale = playerButtonScale(), onClick = {
                                chaptersOpen = true
                                seekPreview = null
                            }, modifier = Modifier.focusRequester(chaptersFocus)) { Text("Chapters") }
                        }
                        Button(
                            scale = playerButtonScale(),
                            onClick = {
                                openExtraPanel("Playback speed", speedFocus)
                            },
                            modifier = Modifier.focusRequester(speedFocus).playerTouchClick {
                                openExtraPanel("Playback speed", speedFocus)
                            }
                        ) {
                            Text("Speed · ${app.pictureshow.core.playback.PlaybackSpeeds.label(speedState.speed)}")
                        }
                        Button(scale = playerButtonScale(), onClick = {
                            openExtraPanel("Playback info", infoFocus)
                        }, modifier = Modifier.focusRequester(infoFocus).playerTouchClick { openExtraPanel("Playback info", infoFocus) }) {
                            Text("Playback info")
                        }
                        Button(scale = playerButtonScale(), onClick = { finish(reason = "stop-button") }) {
                            Text("Stop")
                        }
                    }
                    state.reportError?.let { Text("Could not save progress: $it") }
                }
            }
            if (error == null && !switching) {
                Column(
                    Modifier.align(Alignment.BottomEnd).padding(end = 36.dp, bottom = if (controls) controlsHeight + 16.dp else 36.dp)
                        .onGloballyPositioned { actionsTop = it.positionInRoot().y.toInt().takeIf { _ -> it.size.height > 0 } }
                        .onFocusChanged { actionsFocused = it.hasFocus }.focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SegmentControl(segments, position, localPreferences.segmentModes, onSeek = { target ->
                        player?.seekTo(target)
                        position = target
                        interaction++
                    }, onFocusRemoved = restoreActionFocus, showButton = actionsVisible)
                    (deferredNext ?: nextEpisode)?.takeUnless { nextCancelled }?.let { next ->
                        key(deferredNext, output.stopGeneration) {
                            val trigger = nextEpisodeTriggerMs(duration, segments)
                            NextEpisodeControl(
                                account, next, deferredNext != null || (trigger != null && position >= trigger), ended,
                                paused || playback.buffering || state.preparing || !actionsVisible,
                                onFocusRemoved = restoreActionFocus, onPlay = { finish(next) },
                                onCancel = {
                                    PlaybackDiagnostics.write("continuation-cancel item=${item.id}")
                                    nextCancelled = true
                                    deferredNext = null
                                    if (advanceReported) finish(reason = "natural-end")
                                }, visible = actionsVisible, compact = controls,
                                remainingMs = if (creditsStartMs(duration, segments) ==
                                    null
                                ) {
                                    (duration - position).coerceAtLeast(0)
                                } else {
                                    null
                                },
                                autoPlay = !accessibleControls && foreground && !pauseIntent && deferredNext == null
                            )
                        }
                    }
                }
            }
            if (chaptersOpen && error == null) {
                PlayerPanel("Chapters", {
                    chaptersOpen = false
                    returnFocus = chaptersFocus
                }, scrollable = false) {
                    ChapterPanel(account, item.id, metadata.chapters, position) { target ->
                        select({
                            player?.seekTo(target)
                            position = target
                            interaction++
                        }) {
                            chaptersOpen = false
                            returnFocus = chaptersFocus
                        }
                    }
                }
            }
            chooserRequest?.takeIf { it.opened && error == null && !switching && !exitRequested }?.let { request ->
                val type = request.type
                val menu = request.preparation.menus?.get(type) ?: return@let
                val streams = menu.streams
                val selected = if (request.preparation === trackMenus) {
                    if (type == MediaStreamType.Audio) state.audioIndex else state.subtitleIndex
                } else {
                    request.selectedIndex
                }
                SideEffect { request.selectedIndex = selected }
                val title = if (type == MediaStreamType.Audio) "Audio" else "Subtitles"
                val currentPreparation by rememberUpdatedState(trackMenus)
                val closeChooser: () -> Unit = {
                    if (chooserRequest === request) {
                        chooserRequest = null
                        returnFocus = if (type == MediaStreamType.Audio) audioFocus else subtitleFocus
                    }
                }
                PlayerPanel(title, closeChooser, scrollable = false) {
                    // Retain the captured rows while the existing native-window focus handoff completes.
                    LaunchedEffect(trackMenus) {
                        if (request.preparation !== trackMenus) close(closeChooser)
                    }
                    TrackChoices(
                        menu.choices,
                        selected,
                        onSelect = { index ->
                            // Native callbacks can outlive the composition that supplied their rows.
                            val active = controller?.state?.value
                            val currentSource = request.preparation === currentPreparation &&
                                active?.decision?.mediaSourceId == request.preparation.sourceId &&
                                active?.decision?.mediaSource?.mediaStreams.orEmpty() == request.preparation.mediaStreams
                            if (chooserRequest === request &&
                                currentSource &&
                                !exitRequested &&
                                !switching &&
                                setupError == null &&
                                active?.error == null
                            ) {
                                select({
                                    controller?.select(type, index)
                                    interaction++
                                }, closeChooser)
                            } else {
                                close(closeChooser)
                            }
                        },
                        title = title
                    ) {
                        if (type == MediaStreamType.Subtitle) {
                            val burnedIn = streams.firstOrNull { it.index == selected }?.deliveryMethod ==
                                app.pictureshow.core.playback.SubtitleDeliveryMethod.Encode
                            if (burnedIn) {
                                Text("These subtitles are burned into the video. Timing and appearance cannot be changed during playback.")
                            }
                            SubtitleDelayControls(subtitleDelayMs, !burnedIn) {
                                subtitleDelayMs = it
                                output.setSubtitleDelayMs(it)
                            }
                        }
                    }
                }
            }
        }
    }, seek = {
        extraPanel?.takeIf { error == null && !switching }?.let { title ->
            val dismiss: () -> Unit = {
                extraPanel = null
                returnFocus = if (title == "Playback speed") speedFocus else infoFocus
            }
            PlayerPanel(title, dismiss, scrollable = title != "Playback speed", inActivityWindow = true) {
                if (title == "Playback speed") {
                    SpeedChoices(speedState, state.decision?.method == app.pictureshow.core.playback.PlayMethod.Transcode) { value ->
                        select({
                            output.setSpeed(value)
                            interaction++
                        }, dismiss)
                    }
                } else {
                    PlaybackInformationContent(
                        state.decision,
                        state.audioIndex,
                        state.subtitleIndex,
                        localPreferences.bitrateMbps,
                        player
                    ) {
                        close(dismiss)
                    }
                }
            }
        }
        if (seekOpen) {
            PlayerPanel("Seek", {
                seekOpen = false
                restoringSeekFocus = true
                returnFocus = timelineFocus
            }, onHandoff = { restoringSeekFocus = true }, inActivityWindow = true) {
                SeekPanel(position, duration, ::seek) {
                    close {
                        seekOpen = false
                        restoringSeekFocus = true
                        returnFocus = timelineFocus
                    }
                }
            }
        }
    })
}

internal fun playerTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    val hours = seconds / 3600
    return if (hours > 0) {
        "$hours:${(seconds % 3600 / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
    } else {
        "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
}

@Composable
internal fun PlayerSubtitles(
    player: Player?,
    appearance: SubtitleAppearance,
    overlayTop: Int? = null,
    diagnostics: SubtitleCueDiagnostics? = null
) {
    val context = LocalContext.current
    val subtitles = remember { SubtitleView(context).apply { isFocusable = false } }
    var videoSize by remember { mutableStateOf(player?.videoSize ?: androidx.media3.common.VideoSize.UNKNOWN) }
    val density = LocalDensity.current
    var cues by remember { mutableStateOf(player?.currentCues ?: CueGroup.EMPTY_TIME_ZERO) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bounds = with(density) { subtitleVideoFrame(maxWidth.roundToPx(), maxHeight.roundToPx(), videoSize) }
        val safe = subtitleSafeFrame(bounds, overlayTop, with(density) { 12.dp.roundToPx() })
        val width = with(density) { safe.width().toDp() }
        val height = with(density) { safe.height().toDp() }
        AndroidView(
            factory = { subtitles },
            modifier = Modifier.offset { IntOffset(safe.left, safe.top) }.size(width, height),
            update = {
                appearance.applyTo(it, context, bounds.height().toFloat() / safe.height().coerceAtLeast(1))
                showSubtitleCues(
                    it,
                    cues,
                    subtitleCuesInFrame(cues.cues, bounds.width(), bounds.height(), safe.height()),
                    player?.currentPosition ?: 0,
                    diagnostics
                )
            }
        )
        val currentAppearance by rememberUpdatedState(appearance)
        val currentHeightScale by rememberUpdatedState(bounds.height().toFloat() / safe.height().coerceAtLeast(1))
        // CaptioningManager registers settings observers through Binder. Keep that work out of overlay changes.
        DisposableEffect(context, subtitles) {
            val manager = context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
            val listener = object : CaptioningManager.CaptioningChangeListener() {
                override fun onEnabledChanged(enabled: Boolean) {
                    currentAppearance.applyTo(subtitles, context, currentHeightScale)
                }
                override fun onFontScaleChanged(fontScale: Float) {
                    currentAppearance.applyTo(subtitles, context, currentHeightScale)
                }
                override fun onUserStyleChanged(userStyle: CaptioningManager.CaptionStyle) {
                    currentAppearance.applyTo(subtitles, context, currentHeightScale)
                }
            }
            manager?.addCaptioningChangeListener(listener)
            onDispose { manager?.removeCaptioningChangeListener(listener) }
        }
    }
    DisposableEffect(player) {
        videoSize = player?.videoSize ?: androidx.media3.common.VideoSize.UNKNOWN
        cues = player?.currentCues ?: CueGroup.EMPTY_TIME_ZERO
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                videoSize = size
            }
            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup
            }
        }
        player?.addListener(listener)
        onDispose {
            player?.removeListener(listener)
            subtitles.setCues(emptyList())
        }
    }
}
