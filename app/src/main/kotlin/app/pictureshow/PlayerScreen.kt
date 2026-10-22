/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
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
import app.pictureshow.core.MediaItem
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlaybackClient
import app.pictureshow.core.playback.PlaybackSettings
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
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val http: HttpClient = koinInject()
    val identity: DeviceIdentityStore = koinInject()
    val output = remember { Media3PlaybackOutput(context, account.server, item.name) }
    // Final Stopped/DELETE requests must outlive navigation disposal, then this finite scope cancels itself.
    val reportScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val probe = remember { DeviceCapabilityProbe(context, PlaybackSettings(maxStreamingBitrate = null, passthroughEnabled = true)) }
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

    fun seek(delta: Long) {
        val active = player ?: return
        val limit = active.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        active.seekTo((active.currentPosition + delta).coerceIn(0, limit))
        controls = true
        interaction++
    }

    BackHandler {
        when {
            chooser != null -> {
                chooser = null
                rootFocus.requestFocus()
            }
            controls -> {
                controls = false
                rootFocus.requestFocus()
            }
            else -> onExit()
        }
    }
    LaunchedEffect(Unit) {
        rootFocus.requestFocus()
        try {
            val client = PlaybackClient(http, ClientIdentity(Build.MODEL, identity.getOrCreate(), BuildConfig.VERSION_NAME))
            val session = PlaybackSession(
                ServerPlaybackGateway(client, account, item.id, probe::snapshot),
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
                session.close()
                onExit()
            }
            probe.start { session.routeChanged() }
            session.start(startMs)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            setupError = "Could not start the player. Return to the item and try again."
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
    LaunchedEffect(controls, chooser, interaction, paused) {
        if (controls && chooser == null && !paused) {
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
            view.keepScreenOn = false
            probe.close()
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
            .onPreviewKeyEvent { event ->
                if (chooser != null || error != null) return@onPreviewKeyEvent false
                val key = event.key
                val center = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
                if (event.type == KeyEventType.KeyDown && (key == Key.DirectionLeft || key == Key.DirectionRight)) {
                    val step = if (event.nativeKeyEvent.repeatCount >= 5) 30_000 else 10_000
                    seek(if (key == Key.DirectionLeft) -step.toLong() else step.toLong())
                    true
                } else if (!controls && center) {
                    if (event.type == KeyEventType.KeyUp) {
                        player?.let { if (it.playWhenReady) it.pause() else it.play() }
                        controls = true
                        interaction++
                    }
                    true
                } else if (event.type == KeyEventType.KeyDown && key == Key.DirectionDown) {
                    controls = true
                    interaction++
                    // Request after the control is composed.
                    false
                } else {
                    if (event.type == KeyEventType.KeyDown) interaction++
                    false
                }
            }
            .focusable()
    ) {
        ContentFrame(player, Modifier.fillMaxSize())
        PlayerSubtitles(player)
        if (state.preparing && setupError == null) {
            Text("Preparing playback…", modifier = Modifier.align(Alignment.Center))
        }
        if (error != null) {
            Column(Modifier.align(Alignment.Center).widthIn(max = 720.dp).background(Color(0xEE10131C)).padding(32.dp)) {
                Text(error)
                Button(onClick = onExit, modifier = Modifier.focusRequester(errorFocus)) { Text("Back to details") }
            }
            LaunchedEffect(error) { errorFocus.requestFocus() }
        }
        if (controls && chooser == null && error == null) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xE610131C)).padding(36.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(item.name, style = MaterialTheme.typography.headlineSmall)
                val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(5.dp).background(Color.DarkGray)) {
                    Box(Modifier.fillMaxWidth(fraction).height(5.dp).background(Color(0xFFB8C4FF)))
                }
                Text("${playerTime(position)} / ${playerTime(duration)}  ·  ← / → seek 10 seconds")
                Button(
                    onClick = {
                        player?.let { if (it.playWhenReady) it.pause() else it.play() }
                        interaction++
                    },
                    modifier = Modifier.focusRequester(playFocus)
                ) { Text(if (paused) "Play" else "Pause") }
                Button(onClick = { chooser = MediaStreamType.Audio }) { Text("Audio · ${streamLabel(state, MediaStreamType.Audio)}") }
                Button(onClick = {
                    chooser = MediaStreamType.Subtitle
                }) { Text("Subtitles · ${streamLabel(state, MediaStreamType.Subtitle)}") }
                Button(onClick = onExit) { Text("Stop playback") }
                state.reportError?.let { Text("Could not save progress: $it", color = Color(0xFFFFCCB8)) }
            }
            LaunchedEffect(controls) { playFocus.requestFocus() }
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
                        it.index to (it.displayTitle ?: it.language ?: "Track ${it.index}")
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
            }
            LaunchedEffect(type) { choiceFocus.requestFocus() }
        }
    }
}

private fun streamLabel(state: PlaybackState, type: MediaStreamType): String {
    val index = if (type == MediaStreamType.Audio) state.audioIndex else state.subtitleIndex
    return state.decision?.mediaSource?.mediaStreams?.firstOrNull { it.type == type && it.index == index }
        ?.let { it.displayTitle ?: it.language ?: "Track $index" } ?: "Off"
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
private fun PlayerSubtitles(player: Player?) {
    val context = LocalContext.current
    val subtitles = remember { SubtitleView(context).apply { isFocusable = false } }
    AndroidView(factory = { subtitles }, modifier = Modifier.fillMaxSize())
    DisposableEffect(player) {
        subtitles.setCues(player?.currentCues?.cues.orEmpty())
        val listener = object : Player.Listener {
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
