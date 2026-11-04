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
import app.pictureshow.core.playback.PlaybackClient
import io.ktor.client.HttpClient
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.compose.koinInject

internal data class TouchEpisodeLookup(val attempt: Int, val result: ClientResult<MediaItem?>)

/** Session ownership and terminal transitions shared by the remote and touch presentations. */
internal class PlaybackHostState(
    val item: MediaItem,
    startMs: Long,
    val output: Media3PlaybackOutput,
    private val onNext: (MediaItem) -> Unit,
    private val onExit: () -> Unit
) {
    var localPreferences by mutableStateOf(PlaybackPreferences())
    var subtitleDelayMs by mutableIntStateOf(0)
    var probe by mutableStateOf<DeviceCapabilityProbe?>(null)
    var controller by mutableStateOf<PlaybackSession?>(null)
    var setupError by mutableStateOf<String?>(null)
    var position by mutableLongStateOf(startMs)
    var duration by mutableLongStateOf(0)
    var metadata by mutableStateOf(item)
    var segments by mutableStateOf(emptyList<MediaSegment>())
    internal var nextLookup by mutableStateOf<TouchEpisodeLookup?>(null)
    var nextRetry by mutableIntStateOf(0)
    var extras by mutableStateOf<PlaybackExtrasClient?>(null)
    var tiles by mutableStateOf<TrickplayCache?>(null)
    var nextCancelled by mutableStateOf(false)
    var ended by mutableStateOf(false)
    var switching by mutableStateOf(false)
    var exitRequested by mutableStateOf(false)
    var stateSource by mutableStateOf<androidx.compose.runtime.State<PlaybackState>>(mutableStateOf(PlaybackState()))
    var playerSource by mutableStateOf<androidx.compose.runtime.State<Player?>>(mutableStateOf(null))
    val state get() = stateSource.value
    val player get() = playerSource.value
    val error get() = setupError ?: state.error
    val nextEpisode get() = (nextLookup?.result as? ClientResult.Success)?.value
    val findingNext get() = ended && (nextLookup == null || nextLookup?.result is ClientResult.Failure)
    fun finish(next: MediaItem? = null, reason: String = "next-episode") {
        if (!exitRequested && !switching) {
            PlaybackDiagnostics.write(PlaybackDiagnostics.line(state.decision, "exit-request", "itemId=${item.id} reason=$reason"))
        }
        if (next == null) {
            if (exitRequested) return
            exitRequested = true
            finishPlayback(controller, onExit)
            return
        }
        if (switching || exitRequested) return
        switching = true
        controller?.closeForNext({ !exitRequested }) { if (!exitRequested) onNext(next) } ?: onExit()
    }

    fun seekTo(target: Long) {
        val active = player ?: return
        val limit = active.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        position = target.coerceIn(0, limit)
        active.seekTo(position)
    }
}

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
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val http: HttpClient = koinInject()
    val identity: DeviceIdentityStore = koinInject()
    val preferences: PlaybackPreferencesStore = koinInject()
    val output = remember { providedOutput ?: Media3PlaybackOutput(context, account.server, item.name) }
    // Final Stopped/DELETE requests outlive navigation disposal; cleanup cancels this finite scope.
    val reportScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val latestNext by rememberUpdatedState(onNext)
    val latestExit by rememberUpdatedState(onExit)
    val host = remember { PlaybackHostState(item, startMs, output, { latestNext(it) }, { latestExit() }) }
    with(host) {
        val emptyState = remember { MutableStateFlow(PlaybackState()) }
        stateSource = (controller?.state ?: emptyState).collectAsState()
        playerSource = output.player.collectAsState()
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
                val session = PlaybackSession(
                    ServerPlaybackGateway(client, account, item.id, activeProbe::snapshot, captionLanguage) {
                        catalog.completePlayback(account, item.id)
                    },
                    MetadataPlaybackOutput(output) { playbackMetadata(metadata) },
                    reportScope,
                    Locale.getDefault().language,
                    onFinished = { catalog.refresh(item.id, report) },
                    onCompletionPending = {
                        catalog.playbackPending(item.id, report, setOfNotNull(item.seriesId, item.seasonId))
                    },
                    onCleanupDone = { reportScope.cancel() },
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
                output.onEnded = { source ->
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
            nextLookup = TouchEpisodeLookup(nextRetry, client.nextEpisode(account, metadata))
        }
        LaunchedEffect(ended, nextLookup, nextCancelled) {
            if (!ended) return@LaunchedEffect
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
        LaunchedEffect(player) {
            while (true) {
                player?.let {
                    position = if (ended) output.position().milliseconds else it.currentPosition.coerceAtLeast(0)
                    duration = it.duration.takeIf { value -> value != C.TIME_UNSET && value > 0 } ?: 0
                }
                view.keepScreenOn = player?.playWhenReady == true && state.error == null
                delay(500)
            }
        }
        DisposableEffect(lifecycle, output) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) output.pause() }
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
    return host
}
