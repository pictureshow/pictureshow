/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.pictureshow.core.Account
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineOutboxEntry
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.OfflineServerReach
import app.pictureshow.core.offline.OfflineUserData
import app.pictureshow.core.offline.committedBytes
import app.pictureshow.core.offline.offlineDetailStartTicks
import app.pictureshow.core.offline.offlineReadyEntry
import kotlinx.coroutines.launch

/** This title's download as its own page shows it. [entry] is the ready copy when there is one, else the newest attempt. */
internal data class TouchDetailOffline(
    val entry: OfflineEntry,
    val ready: Boolean,
    val status: String,
    val fraction: Float?,
    val indeterminate: Boolean,
    val error: Boolean
)

/** Movies, episodes and videos download as themselves, so only their pages play a download. */
internal fun offlineDetailPlaysDownload(type: String?): Boolean = type in listOf("Movie", "Episode", "Video")

/**
 * The download state for [itemId]'s page: ready ("Downloaded · 106 MB"), on its way (live progress in the Downloads screen's words), or
 * stopped (its plain reason). Null when nothing of it is on this device; a removal under way counts as gone.
 */
internal fun touchDetailOffline(library: OfflineLibrary?, itemId: String, network: OfflineNetwork): TouchDetailOffline? {
    val entries = library?.manifest?.entries.orEmpty()
    offlineReadyEntry(entries, itemId, OfflinePreset.original)?.let { ready ->
        return TouchDetailOffline(ready, true, "Downloaded · ${offlineBytes(ready.committedBytes())}", null, false, false)
    }
    val entry = entries.filter { it.itemId == itemId && it.job.phase != OfflinePhase.deleting }.maxByOrNull { it.createdSequence }
        ?: return null
    val live = library?.progress?.get(entry.id)
    val words = offlineStatus(entry.job, live, library?.manifest?.wifiOnly ?: true, network, entry.committedBytes())
    val status = when (entry.job.phase) {
        OfflinePhase.transferring -> "Downloading · $words"
        OfflinePhase.queued -> "Download queued"
        OfflinePhase.preparing -> "Preparing the download"
        OfflinePhase.paused -> "Download paused" + if (entry.job.pausedByCap) ": over your storage limit" else ""
        OfflinePhase.failed -> "Download stopped. $words"
        else -> words
    }
    val fraction = live?.total?.takeIf { it > 0 && entry.job.phase == OfflinePhase.transferring }?.let {
        (live.received.toFloat() / it).coerceIn(0f, 1f)
    }
    return TouchDetailOffline(entry, false, status, fraction, offlineIndeterminate(entry.job, live), offlineStatusIsError(entry.job))
}

/**
 * What the page knows about reaching the server. No network at all, or a read that never got an answer, is out of reach; a read the
 * server answered is reachable; anything else is unknown and actions try.
 */
internal fun touchDetailServerReach(network: OfflineNetwork, state: CatalogDetailState): OfflineServerReach = when {
    network == OfflineNetwork.none || state.readUnreachable -> OfflineServerReach.unreachable
    state.readSucceeded && !state.readFailed -> OfflineServerReach.reachable
    else -> OfflineServerReach.unknown
}

/** The server's place for this title when this page holds a fresh read of it; null when it doesn't. */
internal fun touchDetailServerPlace(state: CatalogDetailState): OfflineUserData? {
    val item = state.item?.takeIf { state.readSucceeded && !state.readFailed && !state.loading } ?: return null
    return OfflineUserData(item.userData.playbackPositionTicks.coerceAtLeast(0), item.userData.played)
}

/** Everything the detail page does with a download. */
internal class TouchDetailDownload(
    val offline: TouchDetailOffline?,
    val server: OfflineServerReach,
    val local: TouchDetailLocal?,
    val remove: () -> Unit,
    val openDownloads: () -> Unit
)

/**
 * Reads the engine for one title's page. The local start is read again whenever the page returns, a download changes or a viewing
 * settles, so the Resume label and the place Play starts from always agree.
 */
@Composable
internal fun rememberTouchDetailDownload(
    account: Account,
    itemId: String,
    type: String?,
    state: CatalogDetailState,
    play: (OfflineEntry, startMs: Long) -> Unit,
    openDownloads: () -> Unit
): TouchDetailDownload? {
    val engine = remember { org.koin.mp.KoinPlatform.getKoinOrNull()?.getOrNull<OfflineEngine>() } ?: return null
    val context = LocalContext.current
    val libraries by engine.libraries.collectAsStateWithLifecycle()
    val settled by engine.viewings.revision.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var returns by remember { mutableStateOf(0) }
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { returns++ } }
    // Read on return too: the network can change while the player has the screen.
    val network = remember(returns, libraries) { measuredNetwork(context) }
    val offline = touchDetailOffline(libraries[account.key], itemId, network).takeIf { offlineDetailPlaysDownload(type) }
    val readyEntry = offline?.entry?.takeIf { offline.ready }
    val place by produceState<Pair<OfflineOutboxEntry?, OfflineUserData?>?>(null, readyEntry?.id, returns, settled) {
        value = readyEntry?.let { engine.progressStore(account).place(it.itemId) }
    }
    val server = touchDetailServerReach(network, state)
    val scope = rememberCoroutineScope()
    val local = readyEntry?.let { entry ->
        val known = place
        val startTicks = offlineDetailStartTicks(false, known?.first, known?.second, touchDetailServerPlace(state))
        TouchDetailLocal(startTicks) { fromStart -> play(entry, if (fromStart) 0L else startTicks / 10_000) }
    }
    return TouchDetailDownload(
        offline,
        server,
        local,
        remove = { offline?.entry?.let { entry -> scope.launch { engine.delete(account.key, entry.id) } } },
        openDownloads = openDownloads
    )
}

/**
 * Under the action grid, where Download was: what's on this device and what can be done with it. Removing asks first, Cancel first, as
 * Downloads does; streaming instead is a quiet, deliberate choice beside it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TouchDetailDownloadPanel(
    title: String,
    download: TouchDetailDownload,
    streamInstead: (() -> Unit)?,
    enabled: Boolean,
    onDownload: () -> Unit
) {
    val offline = download.offline
    if (offline == null) {
        OutlinedButton(
            onClick = onDownload,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("touch:detail:download")
        ) { Text("Download") }
        return
    }
    var confirming by rememberSaveable(offline.entry.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("touch:detail:offline"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            offline.status,
            style = MaterialTheme.typography.titleSmall,
            color = if (offline.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }.testTag("touch:detail:offline:status")
        )
        offline.fraction?.let { fraction -> LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth()) }
        if (offline.indeterminate) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (offline.ready && !confirming) {
            Text(
                "Play uses the copy on this phone, with or without your server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (confirming) {
            Text(
                "Remove $title from this phone? You can still stream it from your server.",
                Modifier.testTag("touch:detail:offline:question")
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { confirming = false },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("touch:detail:offline:cancel")
                ) { Text("Cancel") }
                OutlinedButton(
                    onClick = {
                        confirming = false
                        download.remove()
                    },
                    colors = offlineDestructiveTouchColors(),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("touch:detail:offline:confirm")
                ) { Text("Remove") }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (offline.ready) {
                    TextButton(
                        onClick = { confirming = true },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("touch:detail:offline:remove")
                    ) { Text("Remove download") }
                    streamInstead?.let {
                        TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp).testTag("touch:detail:offline:stream")) {
                            Text("Stream from server instead")
                        }
                    }
                } else {
                    TextButton(
                        onClick = download.openDownloads,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("touch:detail:offline:open")
                    ) { Text("Open Downloads") }
                }
            }
        }
    }
}
