/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
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
import org.koin.compose.koinInject

@Composable
internal fun PlayerScreen(account: Account, item: MediaItem, startMs: Long, catalog: CatalogSession, onExit: () -> Unit) {
    var current by remember(account.key, item.id) { mutableStateOf(item) }
    var start by remember(account.key, item.id) { mutableLongStateOf(startMs) }
    key(current.id) {
        PlayerItemScreen(account, current, start, catalog, onNext = { next ->
            start = next.userData.playbackPositionTicks.coerceAtLeast(0) / 10_000
            current = next
        }, onExit = onExit)
    }
}

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
    var chooser by remember { mutableStateOf<MediaStreamType?>(null) }
    var interaction by remember { mutableLongStateOf(0) }
    var position by remember { mutableLongStateOf(startMs) }
    var duration by remember { mutableLongStateOf(0) }
    var paused by remember { mutableStateOf(false) }
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

    BackHandler {
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
                ServerPlaybackGateway(client, account, item.id, activeProbe::snapshot),
                output,
                reportScope,
                Locale.getDefault().language
            ) {
                catalog.refresh()
                reportScope.cancel()
            }
            controller = session
            output.onReady = { session.ready(it) }
            output.onProgress = { session.progress(it) }
            output.onFailure = { retry, source -> session.failed(retry, source) }
            output.onEnded = {
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
                paused = !it.playWhenReady
            }
            view.keepScreenOn = player?.playWhenReady == true && state.error == null
            delay(500)
        }
    }
    LaunchedEffect(controls, chooser, chaptersOpen, interaction, paused, actionsFocused) {
        if (controls && chooser == null && !chaptersOpen && !paused && !actionsFocused) {
            delay(7_000)
            controls = false
            rootFocus.requestFocus()
        }
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
            .focusRequester(rootFocus)
            .onFocusChanged { rootFocused = it.isFocused }
            .playerRemoteKeys(
                enabled = chooser == null && !chaptersOpen && error == null && !switching,
                rootFocused = rootFocused,
                controlsVisible = controls,
                seek = ::seek,
                toggleAndShow = {
                    player?.let { if (it.playWhenReady) it.pause() else it.play() }
                    controls = true
                    focusControls = true
                    interaction++
                },
                showControls = {
                    controls = true
                    if (rootFocused) focusControls = true
                },
                interact = { interaction++ }
            )
            .focusable()
    ) {
        ContentFrame(player, Modifier.fillMaxSize())
        PlayerSubtitles(player, localPreferences.appearance)
        if (switching) Text("Starting next episode…", modifier = Modifier.align(Alignment.Center))
        if (state.preparing && setupError == null && !switching) {
            Text("Preparing playback…", modifier = Modifier.align(Alignment.Center))
        }
        if (error != null) {
            Column(Modifier.align(Alignment.Center).widthIn(max = 720.dp).background(Color(0xEE10131C)).padding(32.dp)) {
                Text(error)
                Button(onClick = { finish() }, modifier = Modifier.focusRequester(errorFocus)) { Text("Back to details") }
            }
            LaunchedEffect(error) { errorFocus.requestFocus() }
        }
        if (controls && chooser == null && !chaptersOpen && error == null && !switching) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xE610131C)).padding(36.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val sourceId = state.decision?.mediaSourceId
                val info = chooseTrickplay(metadata, sourceId, (resources.displayMetrics.widthPixels / 6).coerceIn(160, 640))
                val tile = seekPreview?.let { target -> info?.let { trickplayTile(it, target) } }
                if (tile != null && tiles != null && sourceId != null) {
                    TrickplayPreview(requireNotNull(tiles), sourceId, tile, requireNotNull(seekPreview))
                }
                Text(item.name, style = MaterialTheme.typography.headlineSmall)
                val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(5.dp).background(Color.DarkGray)) {
                    Box(Modifier.fillMaxWidth(fraction).height(5.dp).background(Color(0xFFB8C4FF)))
                }
                Text("${playerTime(position)} / ${playerTime(duration)}  ·  ← / → seek 10 seconds")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            player?.let { if (it.playWhenReady) it.pause() else it.play() }
                            interaction++
                        },
                        modifier = Modifier.focusRequester(playFocus)
                    ) { Text(if (paused) "Play" else "Pause") }
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
                state.reportError?.let { Text("Could not save progress: $it", color = Color(0xFFFFCCB8)) }
            }
        }
        if (error == null && !switching) {
            val actionsVisible = chooser == null && !chaptersOpen
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 36.dp, bottom = if (controls) 240.dp else 36.dp)
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
                        onCancel = { nextCancelled = true }, visible = actionsVisible, compact = controls
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
            val choiceFocus = remember(type) { FocusRequester() }
            Column(
                Modifier.align(Alignment.CenterEnd).widthIn(min = 400.dp, max = 680.dp).fillMaxSize(0.85f)
                    .background(Color(0xF510131C)).verticalScroll(rememberScrollState()).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(if (type == MediaStreamType.Audio) "Audio" else "Subtitles", style = MaterialTheme.typography.headlineSmall)
                (
                    listOf(-1 to "Off") + streams.map {
                        it.index to trackLabel(it)
                    }
                    ).forEach { (index, label) ->
                    Button(
                        onClick = {
                            controller?.select(type, index)
                            chooser = null
                            interaction++
                            rootFocus.requestFocus()
                        },
                        modifier = if (index == selected || (selected !in streams.map { it.index } && index == -1)) {
                            Modifier.focusRequester(choiceFocus)
                        } else {
                            Modifier
                        }
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
private fun PlayerSubtitles(player: Player?, appearance: SubtitleAppearance) {
    val context = LocalContext.current
    val subtitles = remember { SubtitleView(context).apply { isFocusable = false } }
    var videoSize by remember { mutableStateOf(player?.videoSize ?: androidx.media3.common.VideoSize.UNKNOWN) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bounds = with(density) { subtitleVideoFrame(maxWidth.roundToPx(), maxHeight.roundToPx(), videoSize) }
        val width = with(density) { bounds.width().toDp() }
        val height = with(density) { bounds.height().toDp() }
        AndroidView(factory = { subtitles }, modifier = Modifier.size(width, height), update = { appearance.applyTo(it, context) })
    }
    DisposableEffect(appearance) {
        val manager = context.getSystemService(android.content.Context.CAPTIONING_SERVICE) as? android.view.accessibility.CaptioningManager
        val listener = object : android.view.accessibility.CaptioningManager.CaptioningChangeListener() {
            override fun onEnabledChanged(enabled: Boolean) {
                appearance.applyTo(subtitles, context)
            }
            override fun onFontScaleChanged(fontScale: Float) {
                appearance.applyTo(subtitles, context)
            }
            override fun onUserStyleChanged(userStyle: android.view.accessibility.CaptioningManager.CaptionStyle) {
                appearance.applyTo(subtitles, context)
            }
        }
        manager?.addCaptioningChangeListener(listener)
        onDispose { manager?.removeCaptioningChangeListener(listener) }
    }
    DisposableEffect(player) {
        videoSize = player?.videoSize ?: androidx.media3.common.VideoSize.UNKNOWN
        subtitles.setCues(player?.currentCues?.cues.orEmpty())
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                videoSize = size
            }
            override fun onCues(cueGroup: CueGroup) {
                subtitles.setCues(cueGroup.cues)
            }
        }
        player?.addListener(listener)
        onDispose {
            player?.removeListener(listener)
            subtitles.setCues(emptyList())
        }
    }
}
