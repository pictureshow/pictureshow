/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.Context
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.ContentFrame
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import app.pictureshow.core.SegmentMode
import app.pictureshow.core.activeSegment
import app.pictureshow.core.chooseTrickplay
import app.pictureshow.core.creditsStartMs
import app.pictureshow.core.nextEpisodeTriggerMs
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.trickplayTile
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
    PlayerViewing(account, item, startMs, onCurrentItem) { current, start, onNext ->
        PlayerItemScreen(account, current, start, catalog, onNext = onNext, onExit = onExit)
    }
}

/** One item at a time; the next one starts at its own resume point. Each mode's player shows [content]. */
@Composable
internal fun PlayerViewing(
    account: Account,
    item: MediaItem,
    startMs: Long,
    onCurrentItem: (MediaItem) -> Unit,
    content: @Composable (MediaItem, Long, (MediaItem) -> Unit) -> Unit
) {
    var current by remember(account.key, item.id) { mutableStateOf(item) }
    var start by remember(account.key, item.id) { mutableLongStateOf(startMs) }
    LaunchedEffect(current) { onCurrentItem(current) }
    key(current.id) {
        content(current, start) { next ->
            start = app.pictureshow.core.resumeStartPositionMs(next.userData.playbackPositionTicks)
            current = next
        }
    }
}

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
    val resources = LocalResources.current
    val remoteTarget: RemoteTargetHost = koinInject()
    val host = rememberPlaybackHostState(account, item, startMs, onNext, onExit, providedOutput, remoteTarget)
    val view = LocalView.current
    val layoutDirection = LocalLayoutDirection.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val accessibleControls = rememberAssistiveServiceInUse()
    val output = host.output
    val localPreferences by host::localPreferences
    var subtitleDelayMs by host::subtitleDelayMs
    val controller by host::controller
    val state by host::state
    val player by host::player
    val speedState by output.speed.collectAsState()
    var extraPanel by remember { mutableStateOf<String?>(null) }
    val foreground by output.foreground.collectAsState()
    val pauseIntent by output.pauseIntent.collectAsState()
    val setupError by host::setupError
    var controls by remember { mutableStateOf(false) }
    var controlsFeedback by remember { mutableStateOf<String?>(null) }
    var focusSpeaksControlsHidden by remember { mutableStateOf(false) }
    var controlsHeight by remember { mutableStateOf(240.dp) }
    val density = LocalDensity.current
    var controlsTop by remember { mutableStateOf<Int?>(null) }
    var actionsTop by remember { mutableStateOf<Int?>(null) }
    var playerTop by remember { mutableIntStateOf(0) }
    val trackMenus = rememberPlayerTrackMenus(state.decision?.mediaSourceId, state.decision?.mediaSource?.mediaStreams.orEmpty())
    var chooserRequest by remember { mutableStateOf<PlayerTrackChooserRequest?>(null) }
    val chooser = chooserRequest?.type
    var interaction by remember { mutableLongStateOf(0) }
    var position by host::position
    val duration by host::duration
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
    val error = host.error
    val metadata by host::metadata
    val segments by host::segments
    val nextLookup by host::nextLookup
    val nextEpisode = host.nextEpisode
    val tiles by host::tiles
    var chaptersOpen by remember { mutableStateOf(false) }
    var seekPreview by host::seekPreview
    var rootFocused by remember { mutableStateOf(false) }
    var focusControls by remember { mutableStateOf(false) }
    var actionsFocused by remember { mutableStateOf(false) }
    val nextCancelled by host::nextCancelled
    val ended by host::ended
    val switching by host::switching
    val deferredNext by host::deferredNext
    val exitRequested by host::exitRequested
    var topCues by remember { mutableStateOf(false) }
    var conversionDetailsRequested by remember { mutableStateOf(false) }
    // PLAY-05/25/26: Speed and Playback info return the screen reader to their opener, not to the first
    // text it finds after the host view's own empty focus event. Until the reader acknowledges the opener
    // (or the bounded wait fails open), that host event is suppressed and only the opener is offered,
    // as the Seek return does for the position control and the detail page does after playback.
    // The return ends early once its opener cannot be shown (controls hidden, an error, the next item),
    // and nothing is hidden for it while a panel is open again.
    val openerShown = controls && error == null && !switching
    val openerReturnTag = if (!accessibleControls || !openerShown || chooser != null || extraPanel != null || chaptersOpen || seekOpen) {
        null
    } else {
        when (returnFocus) {
            speedFocus -> "player:speed"
            infoFocus -> "player:info"
            else -> null
        }
    }
    PlaybackExitFocusEvents(
        openerReturnTag != null || (accessibleControls && restoringSeekFocus),
        suppressClosingPane = accessibleControls,
        descriptionSpokenByFocus = { CONTROLS_HIDDEN_FEEDBACK.takeIf { focusSpeaksControlsHidden } }
    )
    val readerOpenerOnly: (String) -> Modifier = { tag ->
        if (openerReturnTag != null && openerReturnTag != tag) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier
    }

    fun finish(next: MediaItem? = null, reason: String = "next-episode") = host.finish(next, reason)
    fun seek(delta: Long) = host.seek(delta)
    SideEffect {
        host.onFinishing = {
            extraPanel = null
            chooserRequest = null
        }
        host.onSeekShown = {
            controls = true
            interaction++
        }
        host.onStopped = { if (chooserRequest?.opened == false) chooserRequest = null }
    }

    // One remote press seeks once even when it arrives both as a key and as the reader's scroll action.
    val onePressSeek = remember { OnePressSeek() }
    fun keySeek(delta: Long) {
        if (!onePressSeek.admit(OnePressSeek.Path.Key, delta)) {
            PlaybackDiagnostics.write("player-seek source=key deltaMs=$delta dropped=paired")
            return
        }
        PlaybackDiagnostics.write("player-seek source=key deltaMs=$delta")
        seek(delta)
    }

    // A reader's seek reveals the controls with the position control focused, so the new clock is spoken.
    fun readerSeek(delta: Long, arrow: Boolean = false) {
        if (arrow && !onePressSeek.admit(OnePressSeek.Path.Reader, delta)) {
            PlaybackDiagnostics.write("player-seek source=reader deltaMs=$delta dropped=paired")
            return
        }
        PlaybackDiagnostics.write("player-seek source=reader deltaMs=$delta")
        seek(delta)
        if (accessibleControls) {
            focusControls = false
            restoringSeekFocus = true
            returnFocus = timelineFocus
        } else {
            focusControls = true
        }
    }

    fun readerShowControls() {
        controls = true
        focusControls = true
        interaction++
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
                if (accessibleControls) controlsFeedback = CONTROLS_HIDDEN_FEEDBACK
                val focusMoves = !rootFocused
                val focused = rootFocus.requestFocus()
                focusSpeaksControlsHidden = accessibleControls && focusMoves && focused
            }
            else -> finish(reason = "back")
        }
    }
    BackHandler { handleBack() }
    LaunchedEffect(Unit) { rootFocus.requestFocus() }
    PlaybackHostEffects(host, account, catalog, paused)
    LaunchedEffect(controls, focusControls, chaptersOpen, chooser, seekOpen, extraPanel, restoringSeekFocus) {
        if (controls && focusControls && !chaptersOpen && chooser == null && !seekOpen && extraPanel == null && !restoringSeekFocus) {
            playFocus.requestFocus()
            focusControls = false
        }
    }
    LaunchedEffect(controls) {
        if (controls) {
            controlsFeedback = null
            focusSpeaksControlsHidden = false
        }
    }
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
    val openerReturnGone = !openerShown && (returnFocus == speedFocus || returnFocus == infoFocus)
    LaunchedEffect(openerReturnGone) {
        if (openerReturnGone) {
            PlaybackDiagnostics.write("panel-return cancelled controls=$controls error=${error != null} switching=$switching")
            returnFocus = null
        }
    }
    LaunchedEffect(returnFocus, chooser, chaptersOpen, seekOpen, extraPanel, windowFocused) {
        val target = returnFocus
        if (target != null && windowFocused && chooser == null && extraPanel == null && !chaptersOpen && !seekOpen) {
            androidx.compose.runtime.withFrameNanos { }
            val requested = target.requestFocus()
            PlaybackDiagnostics.write("panel-return requested=$requested")
            val openerTag = if (!accessibleControls) {
                null
            } else {
                when (target) {
                    speedFocus -> "player:speed"
                    infoFocus -> "player:info"
                    else -> null
                }
            }
            if (openerTag != null) {
                val started = android.os.SystemClock.uptimeMillis()
                val acknowledged = awaitInitialPlayerPanelFocus(view, openerTag)
                PlaybackDiagnostics.write(
                    "panel-return target=$openerTag acknowledged=$acknowledged waitMs=${android.os.SystemClock.uptimeMillis() - started}"
                )
            }
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
    ControlsAutoHide(
        controls,
        accessibleControls || paused || actionsFocused || chooser != null || extraPanel != null || chaptersOpen || seekOpen,
        interaction
    ) {
        controls = false
        rootFocus.requestFocus()
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
                    seek = ::keySeek,
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
                        // PLAY-03 with a TV screen reader: TalkBack keeps the D-pad arrows for its own
                        // navigation, and with the controls hidden this surface is its only node, so an arrow
                        // ends at "no next item". There TalkBack scrolls a focused scrollable node in the
                        // arrow's direction, so the arrows keep their meaning: Left/Right seek 10 s and land
                        // on the position control, Up/Down show the controls. Keys without a reader are unchanged.
                        horizontalScrollAxisRange = ReaderArrowRange
                        verticalScrollAxisRange = ReaderArrowRange
                        scrollBy { x, y ->
                            // Compose mirrors horizontal scroll amounts in RTL; the remote's Right still means forward.
                            val forward = if (layoutDirection == LayoutDirection.Rtl) x < 0f else x > 0f
                            when {
                                x != 0f -> readerSeek(if (forward) 10_000L else -10_000L, arrow = true)
                                y != 0f -> readerShowControls()
                                else -> return@scrollBy false
                            }
                            true
                        }
                        customActions = listOf(
                            CustomAccessibilityAction("Back 10 seconds") {
                                readerSeek(-10_000L)
                                true
                            },
                            CustomAccessibilityAction("Forward 10 seconds") {
                                readerSeek(10_000L)
                                true
                            }
                        )
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
            PlayerSubtitles(
                player,
                localPreferences.appearance,
                overlayTop?.minus(playerTop),
                output.subtitleDiagnostics,
                onTopCues = { topCues = it }
            )
            // PLAY-27: top of the frame, away from bottom subtitles and controls; never takes remote focus.
            // It yields while a top-positioned cue (signs, forced translations) is on screen.
            PlayerConversionNotice(
                state.decision,
                state.subtitleIndex,
                localPreferences,
                allowed = !topCues &&
                    player != null &&
                    foreground &&
                    !state.preparing &&
                    error == null &&
                    !switching &&
                    chooser == null &&
                    extraPanel == null &&
                    !chaptersOpen &&
                    !seekOpen,
                // 48 dp is the TV overscan-safe side margin.
                modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 48.dp, vertical = 36.dp)
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
                        }.semantics {
                            isTraversalGroup = true
                            testTagsAsResourceId = true
                        }.background(Color(0xE610131C)).padding(36.dp),
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
                        modifier = if (restoringSeekFocus || openerReturnTag != null) {
                            Modifier.clearAndSetSemantics { hideFromAccessibility() }
                        } else {
                            Modifier
                        }
                    )
                    // Capture for speech when controls open, on pause/resume, and after an explicit seek.
                    // The visual clock keeps moving; it is not a live accessibility announcement.
                    val announcedPosition = rememberAnnouncedPosition(
                        paused,
                        // The preview follows a held key; the server's target can lag it.
                        seekRequest = seekPreview ?: state.seekTargetMs,
                        durationMs = duration,
                        ended = ended,
                        positionMs = position
                    )
                    PlayerTimeline(
                        position,
                        announcedPosition,
                        duration,
                        modifier = Modifier.focusRequester(timelineFocus).then(readerOpenerOnly("player:position")),
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
                        PlaybackToggle(
                            paused,
                            showPlayButton,
                            Modifier.focusRequester(playFocus).then(readerOpenerOnly("player:play")),
                            ended
                        ) {
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
                        }, modifier = Modifier.focusRequester(audioFocus).then(readerOpenerOnly("player:audio"))) { Text("Audio") }
                        Button(scale = playerButtonScale(), onClick = {
                            chooserRequest = PlayerTrackChooserRequest(
                                MediaStreamType.Subtitle,
                                trackMenus,
                                state.decision == null,
                                trackMenus.menus != null && state.decision != null,
                                state.subtitleIndex
                            )
                        }, modifier = Modifier.focusRequester(subtitleFocus).then(readerOpenerOnly("player:subtitles"))) {
                            Text("Subtitles")
                        }
                        if (metadata.chapters.isNotEmpty()) {
                            Button(scale = playerButtonScale(), onClick = {
                                chaptersOpen = true
                                seekPreview = null
                            }, modifier = Modifier.focusRequester(chaptersFocus).then(readerOpenerOnly("player:chapters"))) {
                                Text("Chapters")
                            }
                        }
                        Button(
                            scale = playerButtonScale(),
                            onClick = {
                                openExtraPanel("Playback speed", speedFocus)
                            },
                            modifier = Modifier.focusRequester(speedFocus).testTag("player:speed").then(readerOpenerOnly("player:speed"))
                                .playerTouchClick {
                                    openExtraPanel("Playback speed", speedFocus)
                                }
                        ) {
                            Text("Speed · ${app.pictureshow.core.playback.PlaybackSpeeds.label(speedState.speed)}")
                        }
                        Button(
                            scale = playerButtonScale(),
                            onClick = {
                                openExtraPanel("Playback info", infoFocus)
                            },
                            modifier = Modifier.focusRequester(infoFocus).testTag("player:info").then(readerOpenerOnly("player:info"))
                                .playerTouchClick { openExtraPanel("Playback info", infoFocus) }
                        ) {
                            Text("Playback info")
                        }
                        Button(
                            scale = playerButtonScale(),
                            onClick = { finish(reason = "stop-button") },
                            modifier = readerOpenerOnly("player:stop")
                        ) {
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
                    }, onFocusRemoved = restoreActionFocus, showButton = actionsVisible && openerReturnTag == null)
                    (deferredNext ?: nextEpisode)?.takeUnless { nextCancelled }?.let { next ->
                        key(deferredNext, output.stopGeneration) {
                            val trigger = nextEpisodeTriggerMs(duration, segments)
                            NextEpisodeControl(
                                account, next, deferredNext != null || (trigger != null && position >= trigger), ended,
                                paused || playback.buffering || state.preparing || !actionsVisible,
                                onFocusRemoved = restoreActionFocus, onPlay = { finish(next) },
                                onCancel = host::cancelNext, visible = actionsVisible, compact = controls,
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
    diagnostics: SubtitleCueDiagnostics? = null,
    onTopCues: (Boolean) -> Unit = {}
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
    val currentOnTopCues by rememberUpdatedState(onTopCues)
    DisposableEffect(player) {
        videoSize = player?.videoSize ?: androidx.media3.common.VideoSize.UNKNOWN
        cues = player?.currentCues ?: CueGroup.EMPTY_TIME_ZERO
        currentOnTopCues(cuesNearTop(cues.cues))
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                videoSize = size
            }
            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup
                currentOnTopCues(cuesNearTop(cueGroup.cues))
            }
        }
        player?.addListener(listener)
        onDispose {
            player?.removeListener(listener)
            subtitles.setCues(emptyList())
            currentOnTopCues(false)
        }
    }
}

private const val CONTROLS_HIDDEN_FEEDBACK = "Controls hidden"

/** Both directions always remain available: the surface's "scroll" is a 10 s seek or a reveal, never a position. */
private val ReaderArrowRange = ScrollAxisRange(value = { 1f }, maxValue = { 2f })
