/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.ui.compose.ContentFrame
import app.pictureshow.core.Account
import app.pictureshow.core.SegmentMode
import app.pictureshow.core.activeSegment
import app.pictureshow.core.chooseTrickplay
import app.pictureshow.core.creditsStartMs
import app.pictureshow.core.displayRuntimeMillis
import app.pictureshow.core.nextEpisodeCardSeconds
import app.pictureshow.core.nextEpisodeTriggerMs
import app.pictureshow.core.playback.ConversionNotice
import app.pictureshow.core.trickplayTile
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal enum class TouchPlayerSheet(val title: String) {
    Audio("Audio"),
    Subtitles("Subtitles"),
    Chapters("Chapters"),
    Speed("Playback speed"),
    Info("Playback info")
}

/** What the viewer calls this device; PLAY-27 names it in the conversion notice. */
internal fun touchDeviceNoun(smallestWidthDp: Int): String = if (smallestWidthDp >= 600) "this tablet" else "this phone"

/**
 * A phone's player opens landscape and offers the toggle. A large screen follows the device and offers neither: asked to
 * lock, it can hold the player in a band across the display instead of rotating (TOUCH-10).
 */
internal fun touchPlayerLocksOrientation(smallestWidthDp: Int): Boolean = smallestWidthDp < 600

/** Same wording as web's touch line (PLAY-27): the note is the path to Playback info. */
internal const val TOUCH_CONVERSION_NOTICE_ACTION = "Tap this note for details and fixes."

internal class TouchControlsState {
    var visible by mutableStateOf(true)
    var sheet by mutableStateOf<TouchPlayerSheet?>(null)
    var scrubMs by mutableStateOf<Long?>(null)
    var interaction by mutableLongStateOf(0)

    fun interact() {
        interaction++
    }
    fun back(exit: () -> Unit) {
        when {
            sheet != null -> sheet = null
            visible -> {
                visible = false
                scrubMs = null
            }
            else -> exit()
        }
        interact()
    }
}

private const val TOUCH_SEEK_STEP_MS = 10_000L

/** One width for the action column, so a card arriving or leaving never moves Cancel or Skip. */
private val TOUCH_ACTIONS_WIDTH = 360.dp

/** The notice, the action column and the panel's content all keep this far from the safe edge. */
private val TOUCH_PLAYER_EDGE = 16.dp

/** The narrowest notice worth keeping beside the action column; a window with less room stacks them. */
private val TOUCH_NOTICE_MIN_BESIDE = 280.dp

/** A bar action's text sits this far inside its target; hanging the target out by it puts the text on the panel's line. */
private val TOUCH_BAR_TEXT_INSET = 8.dp

/** The label takes the room of its widest wording, at any font scale, so its neighbours never move when it changes. */
@Composable
internal fun TouchReservedLabel(label: String, reserve: String, alignment: Alignment = Alignment.Center) {
    Box(contentAlignment = alignment) {
        Text(reserve, Modifier.alpha(0f).clearAndSetSemantics {}, maxLines = 1)
        Text(label, maxLines = 1)
    }
}

/** What the player needs to hand off. Only the touch app provides it, so a player without it offers no "Play on". */
internal class TouchPlayerCast(val cast: CastSenderHost, val remote: RemoteControllerHost)

internal val LocalTouchPlayerCast = staticCompositionLocalOf<TouchPlayerCast?> { null }

/** This player during a Cast handoff. */
private class PlayerCastOutput(private val host: PlaybackHostState) : CastLocalOutput {
    override suspend fun pauseAndRead(): CastLocalMoment {
        val paused = host.output.viewerPaused(host.player)
        host.output.pauseForApp()
        withTimeoutOrNull(1_000) { while (host.player?.isPlaying == true) delay(20) }
        return CastLocalMoment(host.output.position().milliseconds, paused)
    }

    override fun release() = host.finish(reason = "cast")

    override fun resume() {
        host.player?.play()
    }
}

@Composable
internal fun TouchPlayerContent(account: Account, host: PlaybackHostState) {
    val ui = remember { TouchControlsState() }
    val entry = LocalTouchPlayerCast.current
    val castOutput = remember(host) { PlayerCastOutput(host) }
    // Null without a sender, so a player that has none reads nothing from it.
    val castUi = entry?.let { it.cast.state.collectAsState().value }
    val castStatus = castUi?.let(::castStatusLine)
    // A chooser left open, or a pick on its way, is holding this viewing's next step, as a sheet does (CAST-23).
    val choosing = entry?.cast?.holdsNextStep(castOutput) == true
    val atEnd = host.atEnd
    if (entry != null) {
        DisposableEffect(castOutput) { onDispose { entry.cast.localClosed(castOutput) } }
        // position() is now the last moment, so a pick here, or one still starting, would send the TV the end of a finished title.
        LaunchedEffect(atEnd) { if (atEnd) entry.cast.localAdvancing(castOutput) }
        LaunchedEffect(host) { if (entry.cast.consumeStartPaused(host.item.id)) host.output.pause() }
    }
    val accessible = rememberScreenReaderInUse()
    // Prepared with the player, as on TV: a sheet that prepared its own would measure its waiting line, then grow.
    val trackMenus = rememberPlayerTrackMenus(
        host.state.decision?.mediaSourceId,
        host.state.decision?.mediaSource?.mediaStreams.orEmpty()
    )
    val playback = rememberPlayerPlayback(host.player, onPause = {
        // Leaving pauses the player too: the controls stay down while the stop report finishes, or Back looks refused.
        if (!host.exitRequested) ui.visible = true
        ui.interact()
    }, onResume = ui::interact)
    val pauseIntent by host.output.pauseIntent.collectAsState()
    val foreground by host.output.foreground.collectAsState()
    val speed by host.output.speed.collectAsState()
    val paused = playback.paused || pauseIntent
    var topCues by remember { mutableStateOf(false) }
    SideEffect {
        host.onFinishing = {
            ui.sheet = null
            ui.scrubMs = null
            // From the moment this player starts to leave, a pick has nothing left to act on (CAST-23).
            entry?.cast?.localLeaving(castOutput)
        }
        host.onAdvancing = { entry?.cast?.localAdvancing(castOutput) }
        host.onSeekShown = {
            ui.visible = true
            ui.interact()
        }
    }
    val activity = LocalContext.current.playerActivity()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val locksOrientation = touchPlayerLocksOrientation(LocalConfiguration.current.smallestScreenWidthDp)
    val resources = LocalResources.current
    val density = LocalDensity.current
    var controlsHeight by remember { mutableIntStateOf(0) }
    var controlsTop by remember { mutableStateOf<Int?>(null) }
    var noticeBounds by remember { mutableStateOf<Rect?>(null) }
    var actionsLeft by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var actionsHeight by remember { mutableIntStateOf(0) }
    // A font change now arrives in place, and a gap held from the old size would be a guess at the new one.
    var heldNoticeGap by remember(density.fontScale) { mutableStateOf(0.dp) }
    var playerTop by remember { mutableStateOf(0) }
    val controlsVisible = ui.visible && ui.sheet == null && host.error == null && !host.switching
    val actionsVisible = ui.sheet == null && host.error == null && !host.switching
    val latestControlsVisible by rememberUpdatedState(controlsVisible)
    BackHandler {
        if (host.switching) host.finish(reason = "back-during-next") else ui.back { host.finish(reason = "back") }
    }
    ControlsAutoHide(ui.visible, accessible || paused || ui.sheet != null || ui.scrubMs != null, ui.interaction) {
        ui.visible = false
    }
    LaunchedEffect(accessible) { if (accessible) ui.visible = true }
    // Coming back to a paused player, someone needs to see it's alive and how to go on.
    LaunchedEffect(foreground) { if (foreground && paused && !host.exitRequested) ui.visible = true }
    LaunchedEffect(host.error, host.switching) {
        if (host.error != null || host.switching) {
            ui.sheet = null
            ui.scrubMs = null
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).testTag("touch:player").onGloballyPositioned {
            playerTop = it.positionInRoot().y.toInt()
        }
    ) {
        ContentFrame(host.player, Modifier.fillMaxSize())
        // Skip and the next card sit at the top, so only the controls panel lifts the captions.
        val overlayTop = controlsTop.takeIf { controlsVisible }
        PlayerSubtitles(
            host.player,
            host.localPreferences.appearance,
            overlayTop?.minus(playerTop),
            host.output.subtitleDiagnostics,
            onTopCues = { topCues = it }
        )
        // The gesture layer is below the buttons and slider. TalkBack gets an explicit show/hide action.
        Box(
            Modifier.fillMaxSize().testTag("touch:gestures").pointerInput(host) {
                detectTapGestures(
                    onTap = {
                        ui.visible = !ui.visible
                        ui.interact()
                    },
                    onDoubleTap = { offset -> host.seek(if (offset.x >= size.width / 2) TOUCH_SEEK_STEP_MS else -TOUCH_SEEK_STEP_MS) }
                )
            }.semantics {
                contentDescription = "Video player"
                onClick(label = if (latestControlsVisible) "Hide controls" else "Show controls") {
                    ui.visible = !ui.visible
                    ui.interact()
                    true
                }
                // PLAY-03's reader actions: a screen reader reaches a seek without first showing the controls.
                customActions = listOf(
                    CustomAccessibilityAction("Back 10 seconds") {
                        host.seek(-TOUCH_SEEK_STEP_MS)
                        true
                    },
                    CustomAccessibilityAction("Forward 10 seconds") {
                        host.seek(TOUCH_SEEK_STEP_MS)
                        true
                    }
                )
            }
        )
        // PLAY-27: top of the frame, clear of subtitles and controls. A tap opens the Playback info it points to.
        val noticeDevice = touchDeviceNoun(LocalConfiguration.current.smallestScreenWidthDp)
        // A wide window holds the notice beside the action column, so Skip keeps its place at the top.
        val layoutDirection = LocalLayoutDirection.current
        val safeSides = WindowInsets.safeDrawing.let {
            with(density) { (it.getLeft(density, layoutDirection) + it.getRight(density, layoutDirection)).toDp() }
        }
        val noticeRoom = maxWidth - safeSides - TOUCH_PLAYER_EDGE * 2 - TOUCH_ACTIONS_WIDTH - 12.dp
        val noticeCap = noticeRoom.takeIf { it >= TOUCH_NOTICE_MIN_BESIDE }
        ConversionNoticeHost(
            host.state.decision?.takeIf { host.localPreferences.conversionNotice }?.let {
                ConversionNotice.of(it, host.state.subtitleIndex, noticeDevice)
            },
            allowed = !topCues && host.player != null && foreground && !host.state.preparing && actionsVisible,
            modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(TOUCH_PLAYER_EDGE),
            card = { notice, placement, announce, details ->
                DisposableEffect(Unit) { onDispose { noticeBounds = null } }
                TouchConversionNoticeCard(
                    notice,
                    placement.then(noticeCap?.let { Modifier.widthIn(max = it) } ?: Modifier)
                        .onGloballyPositioned { noticeBounds = it.boundsInRoot() },
                    announce,
                    details
                )
            }
        ) {
            ui.sheet = TouchPlayerSheet.Info
            ui.interact()
        }

        // Preview is an overlay above the measured controls, never a child of their column.
        // Loading a bitmap during a held gesture must not move the slider under the finger.
        val sourceId = host.state.decision?.mediaSourceId
        val info = chooseTrickplay(host.metadata, sourceId, (resources.displayMetrics.widthPixels / 4).coerceIn(160, 640))
        val preview = ui.scrubMs ?: host.seekPreview
        val tile = preview?.let { target -> info?.let { trickplayTile(it, target) } }
        if (controlsVisible && tile != null && host.tiles != null && sourceId != null) {
            val controlsSize = with(density) { controlsHeight.toDp() }
            val imageHeight = (maxHeight - controlsSize - 56.dp).coerceIn(0.dp, 135.dp)
            if (imageHeight > 0.dp) {
                Surface(
                    Modifier.align(Alignment.BottomStart)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .padding(start = 16.dp, bottom = controlsSize + 8.dp)
                        .testTag("touch:thumbnail"),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Box(Modifier.padding(4.dp)) {
                        TrickplayPreview(requireNotNull(host.tiles), sourceId, tile, requireNotNull(preview), imageHeight)
                    }
                }
            }
        }

        if (controlsVisible) {
            Column(
                // The scrim reaches the screen's edges behind a cutout; only its content keeps to the safe area (TOUCH-10).
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .onGloballyPositioned {
                        controlsTop = it.positionInRoot().y.toInt()
                        controlsHeight = it.size.height
                    }
                    .testTag("touch:panel").background(Color(0xE610131C))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .heightIn(max = if (landscape) 260.dp else 430.dp)
                    .verticalScroll(rememberScrollState()).padding(horizontal = TOUCH_PLAYER_EDGE, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(host.item.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                val spoken = rememberAnnouncedPosition(
                    paused,
                    seekRequest = ui.scrubMs ?: host.seekPreview ?: host.state.seekTargetMs,
                    durationMs = host.duration,
                    ended = host.ended,
                    positionMs = host.position
                )
                TouchPlayerScrubber(host.position, host.duration, ui.scrubMs, ended = host.ended, spokenMs = spoken, onPreview = {
                    ui.scrubMs = it
                    ui.interact()
                }, onSeek = {
                    ui.scrubMs?.let(host::seekTo)
                    ui.scrubMs = null
                })
                val toggle: @Composable () -> Unit = {
                    Button(
                        onClick = {
                            val pending = host.deferredNext
                            if (pending != null) host.finish(pending) else host.player?.togglePlayback()
                            ui.interact()
                        },
                        Modifier.testTag("touch:toggle").semantics {
                            stateDescription = playbackToggleState(paused, playback.showPlayButton)
                        }
                    ) { TouchReservedLabel(if (playback.showPlayButton) "Play" else "Pause", "Pause") }
                }
                val back: @Composable () -> Unit = {
                    TouchBarButton(
                        { host.seek(-TOUCH_SEEK_STEP_MS) },
                        Modifier.testTag("touch:back10").semantics { contentDescription = "Back 10 seconds" }
                    ) { Text("−10 s") }
                }
                val forward: @Composable () -> Unit = {
                    TouchBarButton(
                        { host.seek(TOUCH_SEEK_STEP_MS) },
                        Modifier.testTag("touch:forward10").semantics { contentDescription = "Forward 10 seconds" }
                    ) { Text("+10 s") }
                }
                val stop: @Composable () -> Unit = {
                    // Its text ends where the scrubber does; the target hangs into the panel's margin.
                    TouchBarButton(
                        { host.finish(reason = "stop-button") },
                        Modifier.offset(x = TOUCH_BAR_TEXT_INSET).testTag("touch:stop")
                    ) {
                        Text("Stop")
                    }
                }
                val options = buildList<@Composable (Modifier) -> Unit> {
                    TouchPlayerSheet.entries.forEach { sheet ->
                        if (sheet != TouchPlayerSheet.Chapters || host.metadata.chapters.isNotEmpty()) {
                            add { placement ->
                                TouchBarButton({
                                    ui.sheet = sheet
                                    ui.interact()
                                }, placement.testTag("touch:open:$sheet")) {
                                    if (sheet == TouchPlayerSheet.Speed) {
                                        TouchReservedLabel(
                                            "Speed · ${app.pictureshow.core.playback.PlaybackSpeeds.label(speed.speed)}",
                                            "Speed · 1.75×",
                                            // Its word starts on the column's edge whichever rate is showing.
                                            Alignment.CenterStart
                                        )
                                    } else {
                                        Text(sheet.title, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                    if (locksOrientation) {
                        add { placement ->
                            TouchBarButton({
                                activity?.requestedOrientation = if (landscape) {
                                    ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                                } else {
                                    ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                                }
                                ui.interact()
                            }, placement.testTag("touch:orientation")) { Text(if (landscape) "Portrait" else "Landscape", maxLines = 1) }
                        }
                    }
                }
                // Stop and the mark stay together, so a very large font moves them as one instead of parting them.
                val exit: @Composable (Modifier) -> Unit = { placement ->
                    Row(placement, horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        if (entry != null) {
                            TouchPlayOnMark(castStatus, filled = castUi?.let(::castIsCasting) == true, enabled = !atEnd) {
                                entry.cast.openChooser(
                                    CastPlayRequest(
                                        listOf(castPlayItem(account.server, host.item)),
                                        startMs = host.output.position().milliseconds,
                                        mediaSourceId = host.state.decision?.mediaSourceId,
                                        local = castOutput,
                                        onJellyfinPick = {
                                            // The player keeps playing under the chooser, so where it is now, not at the tap.
                                            entry.remote.playNow(listOf(host.item.id), host.output.position().milliseconds * MS_TO_TICKS)
                                            host.finish(reason = "remote")
                                        }
                                    )
                                )
                                ui.interact()
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        stop()
                    }
                }
                if (this@BoxWithConstraints.maxWidth < 600.dp) {
                    // Transport and exit on one line, then fixed columns: a break never falls where the width runs out.
                    // A very large font moves the exit to its own line, still at the far end, instead of squeezing it.
                    FlowRow(Modifier.fillMaxWidth()) {
                        toggle()
                        Spacer(Modifier.width(8.dp))
                        back()
                        forward()
                        exit(Modifier.weight(1f))
                    }
                    TouchOptionGrid(options)
                } else {
                    TouchWideControls(
                        transport = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                toggle()
                                Spacer(Modifier.width(8.dp))
                                back()
                                forward()
                            }
                        },
                        options = options,
                        stop = { exit(Modifier) }
                    )
                }
                host.state.reportError?.let { Text("Could not save progress: $it", color = Color.White) }
            }
        }
        if (host.error == null && !host.switching) {
            // A narrow window can't hold the notice and the action column side by side, so the column waits below it.
            val noticeGap = noticeBounds?.takeIf { it.right > actionsLeft }
                ?.let { with(density) { it.height.toDp() + 12.dp } } ?: 0.dp
            // Whatever is showing keeps its place when the notice ends; the gap closes once the column is empty.
            val belowNotice = if (actionsHeight > 0 && noticeBounds == null) heldNoticeGap else noticeGap
            SideEffect { heldNoticeGap = belowNotice }
            // One anchor in every state: a tap aimed at Cancel lands on Cancel whether or not the controls just appeared.
            Column(
                Modifier.align(Alignment.TopEnd).testTag("touch:actions").windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = belowNotice)
                    .padding(TOUCH_PLAYER_EDGE)
                    .width(TOUCH_ACTIONS_WIDTH.coerceAtMost(this@BoxWithConstraints.maxWidth - TOUCH_PLAYER_EDGE * 2))
                    .onGloballyPositioned {
                        // Not boundsInRoot: an empty column has no bounds, which would read as a left edge of 0.
                        actionsLeft = it.positionInRoot().x
                        actionsHeight = it.size.height
                    },
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val next = (host.deferredNext ?: host.nextEpisode)?.takeUnless { host.nextCancelled }
                val trigger = nextEpisodeTriggerMs(host.duration, host.segments)
                val eligible = host.deferredNext != null || (trigger != null && host.position >= trigger)
                // With the card up its Play now is the one primary, and Skip travels inside it, quietly.
                val cardUp = next != null && actionsVisible && (eligible || host.ended)
                SegmentControl(host.segments, host.position, host.localPreferences.segmentModes, host::seekTo, {
                }, showButton = actionsVisible && !cardUp, button = { label, action ->
                    // 48 dp drawn, not 40 in a 48 dp target: its top then meets the notice's beside it.
                    Button(onClick = action, Modifier.heightIn(min = 48.dp).testTag("touch:skip")) { Text(label) }
                })
                next?.let {
                    key(host.deferredNext, host.output.stopGeneration) {
                        val suspended =
                            paused || playback.buffering || host.state.preparing || !actionsVisible || ui.scrubMs != null || choosing
                        val creditsCountdown = creditsStartMs(host.duration, host.segments) != null
                        val autoPlay = !accessible && foreground && !pauseIntent && host.deferredNext == null
                        val skip = activeSegment(host.segments, host.position, host.localPreferences.segmentModes)
                            ?.takeIf { (host.localPreferences.segmentModes[it.type] ?: SegmentMode.Ask) == SegmentMode.Ask }
                        NextEpisodeControl(
                            account, next, eligible, host.ended, suspended,
                            onFocusRemoved = {}, onPlay = { host.finish(next) }, onCancel = host::cancelNext,
                            remainingMs = if (creditsCountdown) null else (host.duration - host.position).coerceAtLeast(0),
                            autoPlay = autoPlay,
                            visible = actionsVisible,
                            card = { seconds, play, cancel ->
                                val shown = nextEpisodeCardSeconds(
                                    seconds,
                                    creditsCountdown,
                                    host.duration.takeIf { it > 0 }?.let { (it - host.position).coerceAtLeast(0) } ?: 0,
                                    speed.speed.toDouble(),
                                    autoPlay,
                                    host.ended,
                                    suspended
                                )
                                TouchNextEpisodeCard(
                                    next.name,
                                    shown ?: 0,
                                    shown != null,
                                    play,
                                    cancel,
                                    announcement = catalogAnnouncement(next),
                                    skipLabel = skip?.let { "Skip ${segmentLabel(it.type)}" },
                                    onSkip = { skip?.let { host.seekTo(it.endMs) } }
                                )
                            }
                        )
                    }
                }
            }
        }
        val message = when {
            host.switching -> "Starting next episode…"
            host.findingNext && !host.exitRequested -> "Finding next episode…"
            host.error != null -> host.error
            host.state.bufferingSlow -> "Still waiting for playback. You can go back and try again."
            host.state.preparing -> "Preparing playback…"
            else -> null
        }
        if (message != null) {
            Surface(Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 480.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp)) {
                    Text(message)
                    if (host.error != null) host.state.reportError?.let { Text("Could not save progress: $it") }
                    if (host.error != null) Button(onClick = { host.finish(reason = "error-back") }) { Text("Back to details") }
                }
            }
        }
    }
    ui.sheet?.let { sheet ->
        TouchPlayerSheetContent(sheet, host, trackMenus, account, onDismiss = {
            ui.sheet = null
            ui.interact()
        }, onSeek = host::seekTo)
    }
}

/**
 * Lines for the options, each a list of indexes. Greedy, except that no option is left alone on the last line when the
 * line above can spare one: only a very large font on a narrow window gets here.
 */
internal fun touchOptionLines(widths: List<Int>, room: Int, gap: Int): List<List<Int>> {
    val lines = mutableListOf<MutableList<Int>>()
    var used = 0
    widths.forEachIndexed { index, width ->
        if (lines.isEmpty() || used + gap + width > room) {
            lines.add(mutableListOf(index))
            used = width
        } else {
            lines.last().add(index)
            used += gap + width
        }
    }
    val last = lines.last()
    val above = lines.getOrNull(lines.size - 2)
    if (last.size == 1 && above != null && above.size > 1 && widths[above.last()] + gap + widths[last[0]] <= room) {
        last.add(0, above.removeAt(above.lastIndex))
    }
    return lines
}

/** The most columns, up to three, that hold every label on one line with some air before the next column's text. */
internal fun touchOptionColumns(textWidths: List<Int>, room: Int, air: Int): Int =
    (3 downTo 2).firstOrNull { columns -> textWidths.all { it <= room / columns - air } } ?: 1

/**
 * The narrow panel's options, in fixed columns so labels don't move with their neighbours' wording. A button keeps its natural
 * width rather than the column's, so its label never wraps; the text starts on the column's edge and the target hangs out by
 * its inset. Measured, so the font scale and the reserved Speed label count.
 */
@Composable
private fun TouchOptionGrid(options: List<@Composable (Modifier) -> Unit>) {
    Layout(
        content = { options.forEach { it(Modifier) } },
        modifier = Modifier.fillMaxWidth().testTag("touch:option-grid")
    ) { measurables, constraints ->
        val boxes = measurables.map { it.measure(Constraints()) }
        val width = constraints.maxWidth
        val inset = TOUCH_BAR_TEXT_INSET.roundToPx()
        val columns = touchOptionColumns(boxes.map { it.width - inset * 2 }, width, 8.dp.roundToPx())
        val lines = boxes.chunked(columns)
        val lineGap = 4.dp.roundToPx()
        val height = lines.sumOf { line -> line.maxOf { it.height } } + lineGap * (lines.size - 1).coerceAtLeast(0)
        layout(width, height) {
            var y = 0
            lines.forEach { line ->
                val lineHeight = line.maxOf { it.height }
                line.forEachIndexed { column, box ->
                    box.placeRelative(width * column / columns - inset, y + (lineHeight - box.height) / 2)
                }
                y += lineHeight + lineGap
            }
        }
    }
}

/**
 * Play on, a mark beside Stop since both move this viewing away from here. [status] is what a sender is doing elsewhere, the
 * line the browsing bar shows and the player hides, and a reader hears it. Only [filled] draws: the line also speaks for a
 * device that is starting, reconnecting or lost, and a filled mark there would claim the opposite of the truth. Dimmed at the
 * end of a title, which has nothing left to send: its next-episode card is the way on.
 */
@Composable
internal fun TouchPlayOnMark(status: String?, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    TouchBarButton(
        onClick,
        Modifier.testTag("touch:play-on").semantics {
            contentDescription = "Play on"
            status?.let { stateDescription = it }
        },
        enabled = enabled
    ) {
        Icon(touchActionIcon(if (filled) "casting" else "cast"), contentDescription = null, modifier = Modifier.size(24.dp))
    }
}

/**
 * Three groups on one line when all of them fit at their natural size: transport, options, and Stop at the far end, since
 * it exits at once. Otherwise transport and Stop, then the options on a line of their own with their text on Pause's edge,
 * as in the portrait panel. Measured, so the font scale, the title having chapters and the safe insets all count, and a
 * reserved label keeps the choice from flipping when the speed or Play/Pause changes.
 */
@Composable
private fun TouchWideControls(
    transport: @Composable () -> Unit,
    options: List<@Composable (Modifier) -> Unit>,
    stop: @Composable () -> Unit
) {
    Layout(
        contents = listOf(transport, { options.forEach { it(Modifier) } }, stop),
        modifier = Modifier.fillMaxWidth().testTag("touch:wide-controls")
    ) { (transportSlot, optionSlots, stopSlot), constraints ->
        val natural = constraints.copy(minWidth = 0, minHeight = 0)
        val transportBox = transportSlot.single().measure(natural)
        val optionBoxes = optionSlots.map { it.measure(natural) }
        val stopBox = stopSlot.single().measure(natural)
        val width = constraints.maxWidth
        val optionGap = 4.dp.roundToPx()
        val groupGap = 16.dp.roundToPx()
        val optionsWidth = optionBoxes.sumOf { it.width } + optionGap * (optionBoxes.size - 1)
        val oneLine = transportBox.width + groupGap + optionsWidth + groupGap + stopBox.width <= width
        val mainHeight = maxOf(transportBox.height, stopBox.height, if (oneLine) optionBoxes.maxOf { it.height } else 0)
        val textInset = 8.dp.roundToPx()
        val lines = if (oneLine) {
            emptyList()
        } else {
            // The options' text, not their target, starts on Pause's edge, so a line has the inset's extra room at each end.
            touchOptionLines(optionBoxes.map { it.width }, width + textInset * 2, optionGap).map { line -> line.map(optionBoxes::get) }
        }
        val lineGap = 4.dp.roundToPx()
        val height = mainHeight + lines.sumOf { line -> lineGap + line.maxOf { it.height } }
        layout(width, height) {
            transportBox.placeRelative(0, (mainHeight - transportBox.height) / 2)
            stopBox.placeRelative(width - stopBox.width, (mainHeight - stopBox.height) / 2)
            if (oneLine) {
                var x = transportBox.width + groupGap
                optionBoxes.forEach {
                    it.placeRelative(x, (mainHeight - it.height) / 2)
                    x += it.width + optionGap
                }
            } else {
                var y = mainHeight
                lines.forEach { line ->
                    y += lineGap
                    val lineHeight = line.maxOf { it.height }
                    var x = -textInset
                    line.forEach {
                        it.placeRelative(x, y + (lineHeight - it.height) / 2)
                        x += it.width + optionGap
                    }
                    y += lineHeight
                }
            }
        }
    }
}

@Composable
internal fun TouchPlayerScrubber(
    position: Long,
    duration: Long,
    preview: Long?,
    ended: Boolean = false,
    spokenMs: Long = position,
    onPreview: (Long) -> Unit,
    onSeek: () -> Unit
) {
    val limit = duration.coerceAtLeast(1)
    val shown = preview ?: if (ended) completedPlaybackPosition(position, duration) else playbackEndPosition(position, duration)
    // The clock moves every second; the reader hears it on arrival, pause and seek, as PLAY-06 asks, never per tick.
    val spoken = spokenPlaybackPosition(if (ended) completedPlaybackPosition(position, duration) else preview ?: spokenMs, duration)
    Column {
        Slider(
            value = shown.coerceIn(0, limit).toFloat(),
            onValueChange = { onPreview(it.toLong().coerceIn(0, limit)) },
            onValueChangeFinished = onSeek,
            valueRange = 0f..limit.toFloat(),
            enabled = duration > 0,
            modifier = Modifier.fillMaxWidth().testTag("touch:scrubber").semantics {
                contentDescription = "Playback position"
                stateDescription = spoken
            }
        )
        Text(
            displayRuntimeMillis(duration)?.let { "${playerTime(shown)} / ${playerTime(it)}" } ?: playerTime(shown),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

/**
 * A bar action: tighter than a default text button, so the row fits one line, and still a 48 dp target. A set minimum
 * replaces `TextButton`'s wider one, which centred a short word and took its text off the line it starts from.
 */
@Composable
private fun TouchBarButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    TextButton(
        onClick,
        modifier.widthIn(min = 48.dp),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = TOUCH_BAR_TEXT_INSET, vertical = 8.dp)
    ) {
        content()
    }
}

@Composable
internal fun TouchNextEpisodeCard(
    name: String,
    seconds: Long,
    counting: Boolean,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    announcement: String = name,
    skipLabel: String? = null,
    onSkip: () -> Unit = {}
) {
    Surface(Modifier.fillMaxWidth().testTag("next:card"), color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (counting) {
                Text("Next episode · $seconds s left", style = MaterialTheme.typography.labelLarge)
            } else {
                // Nothing is counting; with a reader nothing advances by itself, so the card announces that it arrived.
                Text(
                    "Next episode",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clearAndSetSemantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "Next episode, $announcement"
                    }
                )
            }
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            // Skip comes last, so it can arrive or leave without moving Play now or Cancel.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPlay,
                    Modifier.testTag("next:play").semantics { contentDescription = "Play now, next episode, $announcement" }
                ) { Text("Play now") }
                TextButton(
                    onClick = onCancel,
                    Modifier.testTag("next:cancel").semantics { contentDescription = "Cancel next episode" }
                ) { Text("Cancel") }
                if (skipLabel != null) {
                    // At the card's end, so its inner margin is the same both sides; Cancel stays where it was.
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        FilledTonalButton(
                            onClick = onSkip,
                            Modifier.testTag("touch:skip"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) { Text(skipLabel) }
                    }
                }
            }
        }
    }
}

/** Never blocks the video: a passing tap anywhere else still reaches the player, and playback continues underneath. */
@Composable
internal fun TouchConversionNoticeCard(
    notice: ConversionNotice,
    modifier: Modifier = Modifier,
    announce: Boolean = true,
    onDetails: () -> Unit
) {
    Surface(
        onClick = onDetails,
        modifier = modifier.widthIn(max = 480.dp).testTag("touch:conversion-notice").semantics(mergeDescendants = true) {
            if (announce) liveRegion = LiveRegionMode.Polite
            onClick(label = "Open Playback info") {
                onDetails()
                true
            }
        },
        color = Color(0xE610131C),
        contentColor = Color.White,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(notice.title, style = MaterialTheme.typography.titleMedium)
            Text("${notice.reason} $TOUCH_CONVERSION_NOTICE_ACTION", fontSize = 16.sp)
        }
    }
}
