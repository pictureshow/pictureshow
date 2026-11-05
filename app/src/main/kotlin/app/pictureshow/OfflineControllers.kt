/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.offline.OfflineConflict
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlayOutcome
import app.pictureshow.core.playback.PlaybackClient
import app.pictureshow.core.playback.PlaybackRequest
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** A destructive action waiting for its explicit yes; every confirmation opens on Cancel. */
internal sealed interface OfflineConfirm {
    data class One(val row: OfflineRow) : OfflineConfirm

    data object All : OfflineConfirm
}

internal class OfflineDownloadsController(
    val account: Account,
    private val engine: OfflineEngine,
    private val scope: CoroutineScope,
    private val onPlay: (OfflineEntry) -> Unit
) {
    var confirm by mutableStateOf<OfflineConfirm?>(null)

    fun act(row: OfflineRow, action: OfflineAction) {
        val key = account.key
        val id = row.entry.id
        when (action) {
            OfflineAction.Play -> onPlay(row.entry)
            OfflineAction.Delete -> confirm = OfflineConfirm.One(row)
            OfflineAction.Pause -> scope.launch { engine.pause(key, id) }
            OfflineAction.Resume -> scope.launch { engine.resume(key, id) }
            OfflineAction.Retry -> scope.launch { engine.retry(key, id) }
            OfflineAction.Redownload -> scope.launch { engine.redownload(key, id) }
            OfflineAction.AllowCellular -> scope.launch {
                engine.setCellular(key, id, true)
                engine.networkChanged()
            }
        }
    }

    fun confirmed(all: List<OfflineEntry>) {
        val pending = confirm
        confirm = null
        scope.launch {
            when (pending) {
                is OfflineConfirm.One -> engine.delete(account.key, pending.row.entry.id)
                OfflineConfirm.All -> engine.deleteMany(account.key, all.map(OfflineEntry::id))
                null -> Unit
            }
        }
    }

    fun dismissConflict(itemId: String) {
        scope.launch { engine.progressStore(account).dismissConflict(itemId) }
    }

    fun setCap(bytes: Long) {
        scope.launch { engine.setCap(account.key, bytes) }
    }

    fun setWifiOnly(value: Boolean) {
        scope.launch { engine.setWifiOnly(account.key, value) }
    }

    fun deleteEntries(ids: List<String>) {
        scope.launch { engine.deleteMany(account.key, ids) }
    }

    fun providePassword(password: String) {
        scope.launch { engine.providePassword(account, password) }
    }
}

internal class OfflineDownloadsView(
    val controller: OfflineDownloadsController,
    val library: OfflineLibrary,
    val sections: OfflineSections,
    val conflicts: Map<String, OfflineConflict>,
    val network: OfflineNetwork
) {
    fun conflictTitle(itemId: String): String = library.manifest.entries.firstOrNull { it.itemId == itemId }?.labels?.title ?: "A download"
}

@Composable
internal fun rememberOfflineDownloads(account: Account, onPlay: (OfflineEntry) -> Unit): OfflineDownloadsView {
    val engine: OfflineEngine = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val libraries by engine.libraries.collectAsState()
    val store = remember(account.key) { engine.progressStore(account) }
    val conflicts by store.conflicts.collectAsState()
    var network by remember { mutableStateOf(measuredNetwork(context)) }
    LaunchedEffect(account.key) {
        engine.start()
        engine.refresh()
        engine.networkChanged()
        while (true) {
            network = measuredNetwork(context)
            delay(2_000)
        }
    }
    val controller = remember(account.key) { OfflineDownloadsController(account, engine, scope, onPlay) }
    val library = libraries[account.key] ?: OfflineLibrary()
    return OfflineDownloadsView(controller, library, offlineSections(library, network), conflicts, network)
}

/** One preview's choices. Held apart from its screens so TV and touch can't drift on what gets downloaded. */
internal class OfflinePreviewController(
    private val account: Account,
    private val engine: OfflineEngine,
    private val api: suspend () -> OfflineApi,
    private val scope: CoroutineScope
) {
    var state by mutableStateOf<OfflinePreviewState>(OfflinePreviewState.Loading)
    var preset by mutableStateOf(OfflinePreset.original)
    var episodes by mutableStateOf(emptySet<String>())
    var audio by mutableStateOf(emptySet<Int>())
    var subtitles by mutableStateOf(emptySet<Int>())
    var cellular by mutableStateOf(false)
    var warning by mutableStateOf<String?>(null)
    var started by mutableStateOf(false)

    fun load(itemId: String, seriesId: String?, seasonId: String?, title: String) {
        scope.launch {
            val next = offlineLoadPreview(api(), account, itemId, seriesId, seasonId, title)
            if (next is OfflinePreviewState.Ready) {
                episodes = next.preview.items.map(OfflineApiItem::id).toSet()
                val first = next.preview.items.first().sources.firstOrNull()?.streams.orEmpty()
                audio =
                    setOfNotNull(
                        (
                            first.firstOrNull {
                                it.type == "Audio" && it.isDefault
                            } ?: first.firstOrNull { it.type == "Audio" }
                            )?.index
                    )
                preset = next.preview.choices.firstOrNull { it.available }?.preset ?: OfflinePreset.original
            }
            state = next
        }
    }

    fun sourceTracks(type: String): List<OfflineApiStream> {
        val ready = state as? OfflinePreviewState.Ready ?: return emptyList()
        return ready.preview.items.first().sources.firstOrNull()?.streams.orEmpty().filter { it.type == type }
    }

    fun toggleEpisode(id: String) {
        episodes = if (id in episodes) episodes - id else episodes + id
    }

    fun toggleAudio(index: Int) {
        audio = when {
            index in audio -> if (audio.size > 1) audio - index else audio
            audio.size >= OFFLINE_MAX_CONVERTED_AUDIO -> audio
            else -> audio + index
        }
    }

    fun toggleSubtitle(index: Int) {
        subtitles = when {
            index in subtitles -> subtitles - index
            subtitles.size >= OFFLINE_MAX_CONVERTED_SUBTITLES -> subtitles
            else -> subtitles + index
        }
    }

    fun selectedItems(): List<OfflineApiItem> {
        val ready = state as? OfflinePreviewState.Ready ?: return emptyList()
        return ready.preview.items.filter { it.id in episodes }
    }

    fun start(onDone: () -> Unit) {
        val items = selectedItems()
        if (items.isEmpty() || started) return
        started = true
        scope.launch {
            items.forEach { item ->
                val own = if (preset.isConvertedVideo) {
                    // Each episode's own indexes: a season's files needn't agree on which index is which language.
                    val base = sourceTracks("Audio").filter { it.index in audio }.map { it.language }
                    val streams = item.sources.firstOrNull()?.streams.orEmpty()
                    val picked = streams.filter { it.type == "Audio" && it.language in base }.map { it.index }
                    picked.ifEmpty { audio.toList() }
                } else {
                    audio.toList()
                }
                val choice = OfflineChoice(item.id, preset, own, subtitles.toList(), cellular)
                engine.enqueue(account, item, choice, warning.takeIf { preset == OfflinePreset.original })
            }
            onDone()
        }
    }
}

@Composable
internal fun rememberOfflinePreview(
    account: Account,
    itemId: String,
    seriesId: String?,
    seasonId: String?,
    title: String
): OfflinePreviewController {
    val engine: OfflineEngine = koinInject()
    val http: HttpClient = koinInject()
    val identity: DeviceIdentityStore = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember {
        OfflinePreviewController(account, engine, { offlineApiFor(context, identity) }, scope)
    }
    LaunchedEffect(Unit) {
        controller.load(itemId, seriesId, seasonId, title)
        controller.warning = offlineOriginalWarning(context, http, identity, account, seasonId?.let { null } ?: itemId)
    }
    return controller
}

internal suspend fun offlineApiFor(context: Context, identity: DeviceIdentityStore): OfflineApi = OfflineApi(
    PlaybackMedia.createPlaybackHttpClient(),
    ClientIdentity(deviceDisplayName(context), identity.getOrCreate(), BuildConfig.VERSION_NAME)
)

/** Asks the normal decision whether this device could play the original as it is (OFF-02). Null when it can, or unknown. */
private suspend fun offlineOriginalWarning(
    context: Context,
    http: HttpClient,
    identity: DeviceIdentityStore,
    account: Account,
    itemId: String?
): String? {
    itemId ?: return null
    val probe = DeviceCapabilityProbe(context)
    return try {
        val clientIdentity = ClientIdentity(
            deviceDisplayName(context),
            account.deviceId?.takeIf(String::isNotBlank) ?: identity.getOrCreate(),
            BuildConfig.VERSION_NAME
        )
        val gateway =
            ServerPlaybackGateway(PlaybackClient(http, clientIdentity), account, itemId, probe::snapshot, null) {
                ClientResult.Success(Unit)
            }
        val decision = ((gateway.prepare(PlaybackRequest()) as? ClientResult.Success)?.value as? PlayOutcome.Play)?.decision ?: return null
        decision.playSessionId?.takeIf { decision.method == PlayMethod.Transcode }?.let { gateway.stopEncoding(it) }
        if (decision.method ==
            PlayMethod.DirectPlay
        ) {
            null
        } else {
            "This device may not play the original without the server converting it. It might not play once you're offline."
        }
    } finally {
        probe.close()
    }
}

/** Where a cold start lands: Downloads when there's no network and something saved to watch. */
internal suspend fun offlineColdStartDestination(
    destination: String,
    engine: OfflineEngine,
    accountKey: String?,
    network: OfflineNetwork
): String {
    if (destination != "home" || accountKey == null || network != OfflineNetwork.none) return destination
    val library = engine.ready()[accountKey] ?: return destination
    return if (library.recoverable || library.manifest.entries.any { it.job.phase == OfflinePhase.ready }) "downloads" else destination
}

/** Which downloads this viewer has watched: this device's own record first, so the answer needs no server. */
@Composable
internal fun rememberOfflineWatched(account: Account, entries: List<OfflineEntry>): Set<String> {
    val engine: OfflineEngine = koinInject()
    var played by remember { mutableStateOf(emptySet<String>()) }
    val ids = entries.map(OfflineEntry::itemId)
    LaunchedEffect(ids) { played = engine.progressStore(account).watched(ids) }
    return played
}

internal suspend fun offlineStartRoute(destination: String, engine: OfflineEngine?, accountKey: String?, context: Context): String =
    engine?.let { offlineColdStartDestination(destination, it, accountKey, measuredNetwork(context)) } ?: destination

/**
 * Asks once for the permission the progress notification needs on Android 13 and later (touch only: a TV has no
 * notification shade). The answer changes nothing about the download: it still runs and Downloads still shows it.
 */
@Composable
internal fun rememberOfflineNotificationAsk(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return remember(context, launcher) {
        {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
