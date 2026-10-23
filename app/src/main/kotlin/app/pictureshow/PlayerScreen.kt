/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.Context
import android.os.Build
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
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
import app.pictureshow.core.chooseTrickplay
import app.pictureshow.core.creditsStartMs
import app.pictureshow.core.nextEpisodeTriggerMs
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
            start = next.userData.playbackPositionTicks.coerceAtLeast(0) / 10_000
            current = next
        }, onExit = onExit)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerItemScreen(
    account: Account,
    item: MediaItem,
    startMs: Long,
    catalog: CatalogSession,
    onNext: (MediaItem) -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val view = LocalView.current
    val accessibleControls = rememberAccessibilityEnabled()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val http: HttpClient = koinInject()
    val identity: DeviceIdentityStore = koinInject()
    val preferences: PlaybackPreferencesStore = koinInject()
    var localPreferences by remember { mutableStateOf(PlaybackPreferences()) }
    var subtitleDelayMs by remember { mutableIntStateOf(0) }
    val output = remember { Media3PlaybackOutput(context, account.server, item.name) }
    // Final Stopped/DELETE requests must outlive navigation disposal, then this finite scope cancels itself.
    val reportScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    var probe by remember { mutableStateOf<DeviceCapabilityProbe?>(null) }
    var controller by remember { mutableStateOf<PlaybackSession?>(null) }
    val emptyState = remember { MutableStateFlow(PlaybackState()) }
    val state by (controller?.state ?: emptyState).collectAsState()
    val player by output.player.collectAsState()
    var setupError by remember { mutableStateOf<String?>(null) }
    var controls by remember { mutableStateOf(false) }
    var controlsHeight by remember { mutableStateOf(240.dp) }
    val density = LocalDensity.current
    var controlsTop by remember { mutableStateOf<Int?>(null) }
    var actionsTop by remember { mutableStateOf<Int?>(null) }
    var playerTop by remember { mutableIntStateOf(0) }
    var chooser by remember { mutableStateOf<MediaStreamType?>(null) }
    var interaction by remember { mutableLongStateOf(0) }
    var position by remember { mutableLongStateOf(startMs) }
    var duration by remember { mutableLongStateOf(0) }
    val playback = rememberPlayerPlayback(player, onPause = {
        controls = true
        interaction++
    }, onResume = { interaction++ })
    val paused = playback.paused
    val showPlayButton = playback.showPlayButton
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val errorFocus = remember { FocusRequester() }
    val error = setupError ?: state.error
    var metadata by remember { mutableStateOf(item) }
    var segments by remember { mutableStateOf(emptyList<MediaSegment>()) }
    var nextEpisode by remember { mutableStateOf<MediaItem?>(null) }
    var extras by remember { mutableStateOf<PlaybackExtrasClient?>(null) }
    var tiles by remember { mutableStateOf<TrickplayCache?>(null) }
    var chaptersOpen by remember { mutableStateOf(false) }
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    var rootFocused by remember { mutableStateOf(false) }
    var focusControls by remember { mutableStateOf(false) }
    var actionsFocused by remember { mutableStateOf(false) }
    var nextCancelled by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    var exitRequested by remember { mutableStateOf(false) }

    fun finish(next: MediaItem? = null) {
        if (next == null) {
            if (exitRequested) return
            exitRequested = true
            controller?.close()
            onExit()
            return
        }
        if (switching || exitRequested) return
        switching = true
        controller?.close { if (!exitRequested) onNext(next) } ?: onExit()
    }

    fun seek(delta: Long) {
        val active = player ?: return
        val limit = active.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        val target = ((seekPreview ?: active.currentPosition) + delta).coerceIn(0, limit)
        active.seekTo(target)
        position = target
        seekPreview = target
        controls = true
        interaction++
    }

    fun handleBack() {
        when {
            switching -> finish()
            chaptersOpen -> {
                chaptersOpen = false
                rootFocus.requestFocus()
            }
            chooser != null -> {
                chooser = null
                rootFocus.requestFocus()
            }
            controls -> {
                controls = false
                rootFocus.requestFocus()
            }
            else -> finish()
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
            val clientIdentity = ClientIdentity(Build.MODEL, identity.getOrCreate(), BuildConfig.VERSION_NAME)
            val client = PlaybackClient(http, clientIdentity)
            extras = PlaybackExtrasClient(http, clientIdentity)
            tiles = TrickplayCache(http, account, clientIdentity, item.id)
            val session = PlaybackSession(
                ServerPlaybackGateway(client, account, item.id, activeProbe::snapshot) {
                    catalog.completePlayback(account, item.id)
                },
                output,
                reportScope,
                Locale.getDefault().language,
                onFinished = { catalog.refresh() },
                onCleanupDone = { reportScope.cancel() }
            )
            controller = session
            output.onStop = { finish() }
            output.onSeek = { target, source -> session.seek(target, source) }
            output.onReady = { session.ready(it) }
            output.onBuffering = { waiting, source -> session.buffering(waiting, source) }
            output.onProgress = { session.progress(it) }
            output.onFailure = { retry, source -> session.failed(retry, source) }
            output.onEnded = { source ->
                session.ended(source)
                if (nextEpisode != null && !nextCancelled) ended = true else finish()
            }
            activeProbe.start { session.routeChanged() }
            session.start(startMs)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            setupError = "Could not start the player. Return to the item and try again."
        }
    }
    LaunchedEffect(extras) {
        val client = extras ?: return@LaunchedEffect
        (client.item(account, item.id) as? ClientResult.Success)?.value?.let { metadata = it }
    }
    LaunchedEffect(extras, item.id) {
        val client = extras ?: return@LaunchedEffect
        segments = (client.segments(account, item.id) as? ClientResult.Success)?.value.orEmpty()
    }
    LaunchedEffect(extras, metadata.seriesId) {
        val client = extras ?: return@LaunchedEffect
        nextEpisode = (client.nextEpisode(account, metadata) as? ClientResult.Success)?.value
    }
    LaunchedEffect(seekPreview) {
        if (seekPreview != null) {
            delay(2_000)
            seekPreview = null
        }
    }
    LaunchedEffect(controls, focusControls, chaptersOpen, chooser) {
        if (controls && focusControls && !chaptersOpen && chooser == null) {
            playFocus.requestFocus()
            focusControls = false
        }
    }
    LaunchedEffect(player) {
        while (true) {
            player?.let {
                position = it.currentPosition.coerceAtLeast(0)
                duration = it.duration.takeIf { value -> value != C.TIME_UNSET && value > 0 } ?: 0
            }
            view.keepScreenOn = player?.playWhenReady == true && state.error == null
            delay(500)
        }
    }
    ControlsAutoHide(controls, accessibleControls || paused || actionsFocused || chooser != null || chaptersOpen, interaction) {
        controls = false
        rootFocus.requestFocus()
    }
    DisposableEffect(lifecycle, output) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) output.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
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

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onGloballyPositioned { playerTop = it.positionInRoot().y.toInt() }
            .focusRequester(rootFocus)
            .onFocusChanged { rootFocused = it.isFocused }
            .playerRemoteKeys(
                enabled = chooser == null && !chaptersOpen && error == null && !switching,
                rootFocused = rootFocused,
                seek = ::seek,
                showControls = {
                    controls = true
                    if (rootFocused) focusControls = true
                },
                interact = { interaction++ },
                back = ::handleBack
            )
            .semantics {
                contentDescription = "Video player"
                onClick(label = "Show controls") {
                    controls = true
                    focusControls = true
                    interaction++
                    true
                }
            }
            .focusable()
    ) {
        ContentFrame(player, Modifier.fillMaxSize().clearAndSetSemantics {})
        val controlsVisible = controls && chooser == null && !chaptersOpen && error == null && !switching
        val actionsVisible = chooser == null && !chaptersOpen && error == null && !switching
        val overlayTop = listOfNotNull(controlsTop.takeIf { controlsVisible }, actionsTop.takeIf { actionsVisible }).minOrNull()
        PlayerSubtitles(player, localPreferences.appearance, overlayTop?.minus(playerTop))
        if (switching) PlaybackStatus("Starting next episode…", Modifier.align(Alignment.Center))
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
                Button(onClick = { finish() }, modifier = Modifier.focusRequester(errorFocus)) { Text("Back to details") }
            }
            LaunchedEffect(error) { errorFocus.requestFocus() }
        }
        if (controlsVisible) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged {
                    controlsHeight = with(density) { it.height.toDp() }
                }.onGloballyPositioned { controlsTop = it.positionInRoot().y.toInt() }.background(Color(0xE610131C)).padding(36.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val sourceId = state.decision?.mediaSourceId
                val info = chooseTrickplay(metadata, sourceId, (resources.displayMetrics.widthPixels / 6).coerceIn(160, 640))
                val tile = seekPreview?.let { target -> info?.let { trickplayTile(it, target) } }
                if (tile != null && tiles != null && sourceId != null) {
                    TrickplayPreview(requireNotNull(tiles), sourceId, tile, requireNotNull(seekPreview))
                }
                Text(item.name, style = MaterialTheme.typography.headlineSmall)
                // Capture for speech when controls open, on pause/resume, and after an explicit seek.
                // The visual clock keeps moving; it is not a live accessibility announcement.
                val announcedPosition = remember(paused, seekPreview, duration) { position }
                PlayerTimeline(position, announcedPosition, duration) { target ->
                    player?.seekTo(target)
                    position = target
                    seekPreview = target
                    interaction++
                }
                val clock = (if (paused) "Paused  ·  " else "") + "${playerTime(position)} / ${playerTime(duration)}"
                Text("$clock  ·  ← / → seek 10 seconds", modifier = Modifier.clearAndSetSemantics {})
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlaybackToggle(paused, showPlayButton, Modifier.focusRequester(playFocus)) {
                        player?.togglePlayback()
                        interaction++
                    }
                    Button(onClick = { chooser = MediaStreamType.Audio }) { Text("Audio") }
                    Button(onClick = {
                        chooser = MediaStreamType.Subtitle
                    }) { Text("Subtitles") }
                    if (metadata.chapters.isNotEmpty()) {
                        Button(onClick = {
                            chaptersOpen = true
                            seekPreview = null
                        }) { Text("Chapters") }
                    }
                    Button(onClick = { finish() }) { Text("Stop") }
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
                }, onFocusRemoved = { if (actionsVisible) rootFocus.requestFocus() }, showButton = actionsVisible)
                nextEpisode?.takeUnless { nextCancelled }?.let { next ->
                    val trigger = nextEpisodeTriggerMs(duration, segments)
                    NextEpisodeControl(
                        account, next, trigger != null && position >= trigger, ended, paused || !actionsVisible,
                        onFocusRemoved = { if (actionsVisible) rootFocus.requestFocus() }, onPlay = { finish(next) },
                        onCancel = { nextCancelled = true }, visible = actionsVisible, compact = controls,
                        remainingMs = if (creditsStartMs(duration, segments) == null) (duration - position).coerceAtLeast(0) else null,
                        autoPlay = !accessibleControls
                    )
                }
            }
        }
        if (chaptersOpen && error == null) {
            Box(Modifier.align(Alignment.CenterEnd).widthIn(min = 400.dp, max = 680.dp).fillMaxSize(0.85f)) {
                ChapterPanel(account, item.id, metadata.chapters, position) { target ->
                    player?.seekTo(target)
                    position = target
                    chaptersOpen = false
                    interaction++
                    rootFocus.requestFocus()
                }
            }
        }
        chooser?.let { type ->
            val streams = state.decision?.mediaSource?.mediaStreams.orEmpty().filter { it.type == type }
            val selected = if (type == MediaStreamType.Audio) state.audioIndex else state.subtitleIndex
            val labels = trackLabels(streams)
            val choiceFocus = remember(type) { FocusRequester() }
            Column(
                Modifier.align(Alignment.CenterEnd).widthIn(min = 400.dp, max = 680.dp).fillMaxSize(0.85f)
                    .background(Color(0xF510131C)).verticalScroll(rememberScrollState()).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(if (type == MediaStreamType.Audio) "Audio" else "Subtitles", style = MaterialTheme.typography.headlineSmall)
                (
                    listOf(-1 to "Off") + streams.mapIndexed { i, stream -> stream.index to labels[i] }
                    ).forEach { (index, label) ->
                    Button(
                        onClick = {
                            controller?.select(type, index)
                            chooser = null
                            interaction++
                            rootFocus.requestFocus()
                        },
                        modifier = Modifier.semantics { this.selected = index == selected }.then(
                            if (index == selected ||
                                (selected !in streams.map { it.index } && index == -1)
                            ) {
                                Modifier.focusRequester(choiceFocus)
                            } else {
                                Modifier
                            }
                        )
                    ) { Text(if (index == selected) "$label · selected" else label) }
                }
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
            LaunchedEffect(type) { choiceFocus.requestFocus() }
        }
    }
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
internal fun PlayerSubtitles(player: Player?, appearance: SubtitleAppearance, overlayTop: Int? = null) {
    val context = LocalContext.current
    val subtitles = remember { SubtitleView(context).apply { isFocusable = false } }
    var videoSize by remember { mutableStateOf(player?.videoSize ?: androidx.media3.common.VideoSize.UNKNOWN) }
    val density = LocalDensity.current
    var cues by remember { mutableStateOf(player?.currentCues?.cues.orEmpty()) }
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
                it.setCues(subtitleCuesInFrame(cues, bounds.width(), bounds.height(), safe.height()))
            }
        )
        DisposableEffect(appearance, bounds.height(), safe.height()) {
            val manager = context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
            val listener = object : CaptioningManager.CaptioningChangeListener() {
                override fun onEnabledChanged(enabled: Boolean) {
                    appearance.applyTo(subtitles, context, bounds.height().toFloat() / safe.height().coerceAtLeast(1))
                }
                override fun onFontScaleChanged(fontScale: Float) {
                    appearance.applyTo(subtitles, context, bounds.height().toFloat() / safe.height().coerceAtLeast(1))
                }
                override fun onUserStyleChanged(userStyle: CaptioningManager.CaptionStyle) {
                    appearance.applyTo(subtitles, context, bounds.height().toFloat() / safe.height().coerceAtLeast(1))
                }
            }
            manager?.addCaptioningChangeListener(listener)
            onDispose { manager?.removeCaptioningChangeListener(listener) }
        }
    }
    DisposableEffect(player) {
        videoSize = player?.videoSize ?: androidx.media3.common.VideoSize.UNKNOWN
        cues = player?.currentCues?.cues.orEmpty()
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                videoSize = size
            }
            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues
            }
        }
        player?.addListener(listener)
        onDispose {
            player?.removeListener(listener)
            subtitles.setCues(emptyList())
        }
    }
}
