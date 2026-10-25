/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.media3.ui.compose.ContentFrame
import app.pictureshow.core.Account
import app.pictureshow.core.chooseTrickplay
import app.pictureshow.core.creditsStartMs
import app.pictureshow.core.nextEpisodeTriggerMs
import app.pictureshow.core.trickplayTile
import kotlinx.coroutines.delay

internal enum class TouchPlayerSheet { Audio, Subtitles, Chapters }

internal class TouchControlsState {
    var visible by mutableStateOf(true)
    var sheet by mutableStateOf<TouchPlayerSheet?>(null)
    var scrubMs by mutableStateOf<Long?>(null)
    var previewMs by mutableStateOf<Long?>(null)
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

internal fun touchSeekTarget(position: Long, duration: Long, forward: Boolean): Long =
    (position + if (forward) 10_000 else -10_000).coerceIn(0, duration.takeIf { it > 0 } ?: Long.MAX_VALUE)

@Composable
internal fun TouchPlayerContent(account: Account, host: PlaybackHostState) {
    val ui = remember { TouchControlsState() }
    val accessible = rememberAccessibilityEnabled()
    val playback = rememberPlayerPlayback(host.player, onPause = {
        ui.visible = true
        ui.interact()
    }, onResume = ui::interact)
    val activity = LocalContext.current.playerActivity()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val resources = LocalResources.current
    var controlsTop by remember { mutableStateOf<Int?>(null) }
    var actionsTop by remember { mutableStateOf<Int?>(null) }
    var playerTop by remember { mutableStateOf(0) }
    val controlsVisible = ui.visible && ui.sheet == null && host.error == null && !host.switching
    val actionsVisible = ui.sheet == null && host.error == null && !host.switching
    val latestControlsVisible by rememberUpdatedState(controlsVisible)
    val seek: (Long) -> Unit = { target ->
        host.seekTo(target)
        ui.previewMs = host.position
        ui.visible = true
        ui.interact()
    }
    val latestSeek by rememberUpdatedState(seek)
    BackHandler {
        if (host.switching) host.finish(reason = "back-during-next") else ui.back { host.finish(reason = "back") }
    }
    ControlsAutoHide(ui.visible, accessible || playback.paused || ui.sheet != null || ui.scrubMs != null, ui.interaction, 5_000) {
        ui.visible = false
    }
    LaunchedEffect(accessible) { if (accessible) ui.visible = true }
    LaunchedEffect(ui.previewMs, ui.interaction) {
        if (ui.previewMs != null && ui.scrubMs == null) {
            delay(2_000)
            ui.previewMs = null
        }
    }
    LaunchedEffect(host.error, host.switching) {
        if (host.error != null || host.switching) {
            ui.sheet = null
            ui.scrubMs = null
        }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black).testTag("touch:player").onGloballyPositioned {
            playerTop = it.positionInRoot().y.toInt()
        }
    ) {
        ContentFrame(host.player, Modifier.fillMaxSize())
        // The visible-controls card sits at the top; only bottom overlays reserve caption space.
        val overlayTop = controlsTop.takeIf { controlsVisible } ?: actionsTop.takeIf { actionsVisible && !controlsVisible }
        PlayerSubtitles(host.player, host.localPreferences.appearance, overlayTop?.minus(playerTop))
        // The gesture layer is below the buttons and slider. TalkBack gets an explicit show/hide action.
        Box(
            Modifier.fillMaxSize().testTag("touch:gestures").pointerInput(host) {
                detectTapGestures(
                    onTap = {
                        ui.visible = !ui.visible
                        ui.interact()
                    },
                    onDoubleTap = { offset -> latestSeek(touchSeekTarget(host.position, host.duration, offset.x >= size.width / 2)) }
                )
            }.semantics {
                contentDescription = "Video player"
                onClick(label = if (latestControlsVisible) "Hide controls" else "Show controls") {
                    ui.visible = !ui.visible
                    ui.interact()
                    true
                }
            }
        )

        if (controlsVisible) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing)
                    .onGloballyPositioned { controlsTop = it.positionInRoot().y.toInt() }
                    .background(Color(0xE610131C)).heightIn(max = if (landscape) 260.dp else 430.dp)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(host.item.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                val sourceId = host.state.decision?.mediaSourceId
                val info = chooseTrickplay(host.metadata, sourceId, (resources.displayMetrics.widthPixels / 4).coerceIn(160, 640))
                val preview = ui.scrubMs ?: ui.previewMs
                val tile = preview?.let { target -> info?.let { trickplayTile(it, target) } }
                if (tile != null && host.tiles != null && sourceId != null) {
                    Box(Modifier.testTag("touch:thumbnail")) {
                        TrickplayPreview(requireNotNull(host.tiles), sourceId, tile, requireNotNull(preview))
                    }
                }
                TouchPlayerScrubber(host.position, host.duration, ui.scrubMs, onPreview = {
                    ui.scrubMs = it
                    ui.interact()
                }, onSeek = {
                    ui.scrubMs?.let(seek)
                    ui.scrubMs = null
                })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            host.player?.togglePlayback()
                            ui.interact()
                        },
                        Modifier.testTag("touch:toggle").semantics {
                            stateDescription = if (playback.paused) {
                                "Paused"
                            } else if (playback.showPlayButton) {
                                "Not playing"
                            } else {
                                "Playing"
                            }
                        }
                    ) { Text(if (playback.showPlayButton) "Play" else "Pause") }
                    TextButton(onClick = { seek(touchSeekTarget(host.position, host.duration, false)) }) { Text("−10 s") }
                    TextButton(onClick = { seek(touchSeekTarget(host.position, host.duration, true)) }) { Text("+10 s") }
                    TouchPlayerSheet.entries.forEach { sheet ->
                        if (sheet != TouchPlayerSheet.Chapters || host.metadata.chapters.isNotEmpty()) {
                            TextButton(onClick = {
                                ui.sheet = sheet
                                ui.interact()
                            }, Modifier.testTag("touch:open:$sheet")) { Text(sheet.name) }
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
                SegmentControl(host.segments, host.position, host.localPreferences.segmentModes, seek, {
                }, showButton = actionsVisible, button = { label, action ->
                    Button(onClick = action, Modifier.testTag("touch:skip")) { Text(label) }
                })
                host.nextEpisode?.takeUnless { host.nextCancelled }?.let { next ->
                    val trigger = nextEpisodeTriggerMs(host.duration, host.segments)
                    NextEpisodeControl(
                        account, next, trigger != null && host.position >= trigger, host.ended,
                        playback.paused || playback.buffering || host.state.preparing || !actionsVisible,
                        onFocusRemoved = {}, onPlay = { host.finish(next) }, onCancel = {
                            PlaybackDiagnostics.write("continuation-cancel item=${host.item.id}")
                            host.nextCancelled = true
                        },
                        remainingMs = if (creditsStartMs(host.duration, host.segments) ==
                            null
                        ) {
                            (host.duration - host.position).coerceAtLeast(0)
                        } else {
                            null
                        },
                        autoPlay = !accessible, visible = actionsVisible, card = { seconds, play, cancel ->
                            TouchNextEpisodeCard(next.name, seconds, !accessible, play, cancel)
                        }
                    )
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
                    if (host.error != null) Button(onClick = { host.finish(reason = "error-back") }) { Text("Back to details") }
                }
            }
        }
    }
    ui.sheet?.let { sheet ->
        TouchPlayerSheetContent(sheet, host, onDismiss = {
            ui.sheet = null
            ui.interact()
        }, onSeek = seek)
    }
}

@Composable
internal fun TouchPlayerScrubber(position: Long, duration: Long, preview: Long?, onPreview: (Long) -> Unit, onSeek: () -> Unit) {
    val limit = duration.coerceAtLeast(1)
    Column {
        Slider(
            value = (preview ?: position).coerceIn(0, limit).toFloat(),
            onValueChange = { onPreview(it.toLong().coerceIn(0, limit)) },
            onValueChangeFinished = onSeek,
            valueRange = 0f..limit.toFloat(),
            enabled = duration > 0,
            modifier = Modifier.fillMaxWidth().testTag("touch:scrubber").semantics { contentDescription = "Playback position" }
        )
        Text(
            "${playerTime(preview ?: position)} / ${playerTime(duration)}",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
internal fun TouchNextEpisodeCard(name: String, seconds: Long, autoPlay: Boolean, onPlay: () -> Unit, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (autoPlay) "Next episode · $seconds s left" else "Next episode", style = MaterialTheme.typography.labelLarge)
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPlay, Modifier.testTag("next:play")) { Text("Play now") }
                TextButton(onClick = onCancel, Modifier.testTag("next:cancel")) { Text("Cancel") }
            }
        }
    }
}
