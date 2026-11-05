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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.ui.compose.ContentFrame
import app.pictureshow.core.Account
import app.pictureshow.core.chooseTrickplay
import app.pictureshow.core.creditsStartMs
import app.pictureshow.core.displayRuntimeMillis
import app.pictureshow.core.nextEpisodeTriggerMs
import app.pictureshow.core.playback.ConversionNotice
import app.pictureshow.core.trickplayTile

internal enum class TouchPlayerSheet(val title: String) {
    Audio("Audio"),
    Subtitles("Subtitles"),
    Chapters("Chapters"),
    Speed("Playback speed"),
    Info("Playback info")
}

/** What the viewer calls this device; PLAY-27 names it in the conversion notice. */
internal fun touchDeviceNoun(smallestWidthDp: Int): String = if (smallestWidthDp >= 600) "this tablet" else "this phone"

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

@Composable
internal fun TouchPlayerContent(account: Account, host: PlaybackHostState) {
    val ui = remember { TouchControlsState() }
    val accessible = rememberAccessibilityEnabled()
    val playback = rememberPlayerPlayback(host.player, onPause = {
        ui.visible = true
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
        }
        host.onSeekShown = {
            ui.visible = true
            ui.interact()
        }
    }
    val activity = LocalContext.current.playerActivity()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val resources = LocalResources.current
    val density = LocalDensity.current
    var controlsHeight by remember { mutableIntStateOf(0) }
    var controlsTop by remember { mutableStateOf<Int?>(null) }
    var actionsTop by remember { mutableStateOf<Int?>(null) }
    var playerTop by remember { mutableStateOf(0) }
    val controlsVisible = ui.visible && ui.sheet == null && host.error == null && !host.switching
    val actionsVisible = ui.sheet == null && host.error == null && !host.switching
    val latestControlsVisible by rememberUpdatedState(controlsVisible)
    BackHandler {
        if (host.switching) host.finish(reason = "back-during-next") else ui.back { host.finish(reason = "back") }
    }
    ControlsAutoHide(ui.visible, accessible || paused || ui.sheet != null || ui.scrubMs != null, ui.interaction, 5_000) {
        ui.visible = false
    }
    LaunchedEffect(accessible) { if (accessible) ui.visible = true }
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
        // The visible-controls card sits at the top; only bottom overlays reserve caption space.
        val overlayTop = controlsTop.takeIf { controlsVisible } ?: actionsTop.takeIf { actionsVisible && !controlsVisible }
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
        ConversionNoticeHost(
            host.state.decision?.takeIf { host.localPreferences.conversionNotice }?.let {
                ConversionNotice.of(it, host.state.subtitleIndex, noticeDevice)
            },
            allowed = !topCues && host.player != null && foreground && !host.state.preparing && actionsVisible,
            modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
            card = { notice, placement, announce, details -> TouchConversionNoticeCard(notice, placement, announce, details) }
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
                    Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = controlsSize + 8.dp)
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
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing)
                    .onGloballyPositioned {
                        controlsTop = it.positionInRoot().y.toInt()
                        controlsHeight = it.size.height
                    }
                    .background(Color(0xE610131C)).heightIn(max = if (landscape) 260.dp else 430.dp)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val pending = host.deferredNext
                            if (pending != null) host.finish(pending) else host.player?.togglePlayback()
                            ui.interact()
                        },
                        Modifier.testTag("touch:toggle").semantics {
                            stateDescription = playbackToggleState(paused, playback.showPlayButton)
                        }
                    ) { Text(if (playback.showPlayButton) "Play" else "Pause") }
                    TextButton(
                        onClick = { host.seek(-TOUCH_SEEK_STEP_MS) },
                        Modifier.testTag("touch:back10").semantics { contentDescription = "Back 10 seconds" }
                    ) { Text("−10 s") }
                    TextButton(
                        onClick = { host.seek(TOUCH_SEEK_STEP_MS) },
                        Modifier.testTag("touch:forward10").semantics { contentDescription = "Forward 10 seconds" }
                    ) { Text("+10 s") }
                    TouchPlayerSheet.entries.forEach { sheet ->
                        if (sheet != TouchPlayerSheet.Chapters || host.metadata.chapters.isNotEmpty()) {
                            TextButton(onClick = {
                                ui.sheet = sheet
                                ui.interact()
                            }, Modifier.testTag("touch:open:$sheet")) {
                                Text(
                                    if (sheet == TouchPlayerSheet.Speed) {
                                        "Speed · ${app.pictureshow.core.playback.PlaybackSpeeds.label(speed.speed)}"
                                    } else {
                                        sheet.title
                                    }
                                )
                            }
                        }
                    }
                    TextButton(onClick = {
                        activity?.requestedOrientation =
                            if (landscape) ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                        ui.interact()
                    }) { Text(if (landscape) "Portrait" else "Landscape") }
                    TextButton(onClick = { host.finish(reason = "stop-button") }, Modifier.testTag("touch:stop")) { Text("Stop") }
                }
                host.state.reportError?.let { Text("Could not save progress: $it", color = Color.White) }
            }
        }
        if (host.error == null && !host.switching) {
            Column(
                Modifier.align(if (controlsVisible) Alignment.TopEnd else Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp).widthIn(max = 360.dp)
                    .onGloballyPositioned { actionsTop = it.positionInRoot().y.toInt().takeIf { _ -> it.size.height > 0 } },
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SegmentControl(host.segments, host.position, host.localPreferences.segmentModes, host::seekTo, {
                }, showButton = actionsVisible, button = { label, action ->
                    Button(onClick = action, Modifier.testTag("touch:skip")) { Text(label) }
                })
                (host.deferredNext ?: host.nextEpisode)?.takeUnless { host.nextCancelled }?.let { next ->
                    key(host.deferredNext, host.output.stopGeneration) {
                        val trigger = nextEpisodeTriggerMs(host.duration, host.segments)
                        NextEpisodeControl(
                            account, next, host.deferredNext != null || (trigger != null && host.position >= trigger), host.ended,
                            paused || playback.buffering || host.state.preparing || !actionsVisible || ui.scrubMs != null,
                            onFocusRemoved = {}, onPlay = { host.finish(next) }, onCancel = host::cancelNext,
                            remainingMs = if (creditsStartMs(host.duration, host.segments) ==
                                null
                            ) {
                                (host.duration - host.position).coerceAtLeast(0)
                            } else {
                                null
                            },
                            autoPlay = !accessible && foreground && !pauseIntent && host.deferredNext == null,
                            visible = actionsVisible,
                            card = { seconds, play, cancel ->
                                val autoPlay = !accessible && host.deferredNext == null
                                TouchNextEpisodeCard(next.name, seconds, autoPlay, play, cancel, announcement = catalogAnnouncement(next))
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
        TouchPlayerSheetContent(sheet, host, account, onDismiss = {
            ui.sheet = null
            ui.interact()
        }, onSeek = host::seekTo)
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

@Composable
internal fun TouchNextEpisodeCard(
    name: String,
    seconds: Long,
    autoPlay: Boolean,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    announcement: String = name
) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (autoPlay) {
                Text("Next episode · $seconds s left", style = MaterialTheme.typography.labelLarge)
            } else {
                // With a reader nothing advances by itself, so the card has to announce that it arrived.
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPlay,
                    Modifier.testTag("next:play").semantics { contentDescription = "Play next episode, $announcement" }
                ) { Text("Play now") }
                TextButton(
                    onClick = onCancel,
                    Modifier.testTag("next:cancel").semantics { contentDescription = "Cancel next episode" }
                ) { Text("Cancel") }
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
            Text(notice.title, style = MaterialTheme.typography.titleSmall)
            Text("${notice.reason} $TOUCH_CONVERSION_NOTICE_ACTION", fontSize = 16.sp)
        }
    }
}
