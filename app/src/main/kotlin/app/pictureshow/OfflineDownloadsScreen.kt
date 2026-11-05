/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.Account
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.OfflineResourceRole
import app.pictureshow.core.offline.watchedPreview
import coil3.compose.AsyncImage
import java.io.File
import org.koin.compose.koinInject

private val OfflineMuted = Color(0xFFB9C1D6)
private val OfflinePage = Color(0xFF10131C)

internal fun offlineActionLabel(action: OfflineAction): String = when (action) {
    OfflineAction.Play -> "Play"
    OfflineAction.Pause -> "Pause"
    OfflineAction.Resume -> "Resume"
    OfflineAction.Retry -> "Retry"
    OfflineAction.Redownload -> "Download again"
    OfflineAction.AllowCellular -> "Use mobile data"
    OfflineAction.Delete -> "Delete"
}

internal fun offlineConflictText(title: String): String = "$title: progress on the server had changed, so the server's was kept."

@Composable
internal fun offlineArtwork(accountKey: String, entry: OfflineEntry): File? {
    val engine: OfflineEngine = koinInject()
    val art = entry.resources.firstOrNull { it.role == OfflineResourceRole.artwork && it.complete } ?: return null
    return engine.resourceFile(accountKey, entry, art).takeIf(File::exists)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OfflineDownloadsTvScreen(account: Account, offline: Boolean, onPlay: (OfflineEntry) -> Unit, onStorage: () -> Unit, onOnline: () -> Unit, onBack: () -> Unit) {
    val view = rememberOfflineDownloads(account, onPlay)
    val controller = view.controller
    val sections = view.sections
    val first = remember { FocusRequester() }
    BackHandler(controller.confirm != null) { controller.confirm = null }
    BackHandler(controller.confirm == null, onBack)
    LazyColumn(
        Modifier.fillMaxSize().background(OfflinePage).padding(horizontal = 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "top") { Box(Modifier.height(24.dp)) }
        item(key = "heading") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Downloads", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Text(
                    sections.capBytes?.let { "${offlineBytes(sections.usedBytes)} of ${offlineBytes(it)} used" } ?: "${offlineBytes(sections.usedBytes)} used",
                    color = OfflineMuted
                )
                if (view.network == OfflineNetwork.none) Text("No connection. Everything saved below still plays.", color = OfflineMuted)
            }
        }
        item(key = "buttons") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onStorage, modifier = Modifier.focusRequester(first).testTag("downloads:storage")) { Text("Storage and Wi-Fi") }
                if (offline) Button(onClick = onOnline, modifier = Modifier.testTag("downloads:online")) { Text("Open the library") }
            }
        }
        if (view.library.recoverable) {
            item(key = "recoverable") {
                Text(
                    "This list was saved by a newer version of Pictureshow or couldn't be read. Nothing was deleted. Update the app to get it back.",
                    color = OfflineMuted
                )
            }
        }
        if (view.library.needsPassword) item(key = "password") { OfflinePasswordTv(controller) }
        view.conflicts.keys.forEach { itemId ->
            item(key = "conflict:$itemId") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(offlineConflictText(view.conflictTitle(itemId)), modifier = Modifier.weight(1f))
                    Button(onClick = { controller.dismissConflict(itemId) }) { Text("OK") }
                }
            }
        }
        if (sections.isEmpty && !view.library.recoverable) {
            item(key = "empty") { Text("Nothing downloaded yet. Open a movie or an episode and choose Download.", color = OfflineMuted) }
        }
        fun section(title: String, rows: List<OfflineRow>) {
            if (rows.isEmpty()) return
            item(key = "section:$title") {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }.padding(top = 10.dp))
            }
            items(rows, key = { it.entry.id }) { row -> OfflineRowTv(account, row, controller) }
        }
        section("In progress", sections.active)
        section("Needs attention", sections.failed)
        section("Ready to watch", sections.ready)
        item(key = "end") { Box(Modifier.height(32.dp)) }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfflineRowTv(account: Account, row: OfflineRow, controller: OfflineDownloadsController) {
    val confirming = (controller.confirm as? OfflineConfirm.One)?.row?.entry?.id == row.entry.id
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().testTag("downloads:row:${row.entry.id}")) {
        val art = offlineArtwork(account.key, row.entry)
        Box(Modifier.size(width = 80.dp, height = 120.dp).background(Color(0xFF1F2433))) {
            if (art != null) AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleMedium)
            row.subtitle?.let { Text(it, color = OfflineMuted) }
            Text(row.status, modifier = Modifier.testTag("downloads:status:${row.entry.id}"))
            row.fraction?.let { fraction ->
                Box(Modifier.fillMaxWidth(0.6f).height(6.dp).background(Color(0xFF2B3144))) {
                    Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(Color(0xFF9DB7FF)))
                }
            }
            row.entry.warning?.let { Text(it, color = OfflineMuted) }
            if (row.notDownloaded.isNotEmpty()) Text("Not downloaded: ${row.notDownloaded.joinToString(", ")}", color = OfflineMuted)
            if (confirming) {
                val cancel = remember { FocusRequester() }
                Text(if (row.entry.job.phase == app.pictureshow.core.offline.OfflinePhase.ready) "Delete this download from this device?" else "Stop and delete this download?")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { controller.confirm = null }, modifier = Modifier.focusRequester(cancel).testTag("downloads:cancel")) { Text("Cancel") }
                    Button(onClick = { controller.confirmed(emptyList()) }, modifier = Modifier.testTag("downloads:confirm")) { Text("Delete") }
                }
                LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.actions.forEach { action ->
                        Button(
                            onClick = { controller.act(row, action) },
                            modifier = Modifier.testTag("downloads:${action.name.lowercase()}:${row.entry.id}")
                        ) { Text(offlineActionLabel(action)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflinePasswordTv(controller: OfflineDownloadsController) {
    var password by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("This server has Quick Connect off. Downloads need your password once, to get their own sign-in. It isn't saved.")
        androidx.compose.material3.OutlinedTextField(
            password,
            { password = it },
            label = { androidx.compose.material3.Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.width(420.dp)
        )
        Button(onClick = { controller.providePassword(password) }, enabled = password.isNotEmpty()) { Text("Continue") }
    }
}

@Composable
internal fun OfflineStorageTvScreen(account: Account, onBack: () -> Unit) {
    val view = rememberOfflineDownloads(account) {}
    val controller = view.controller
    val engine: OfflineEngine = koinInject()
    val manifest = view.library.manifest
    val first = remember { FocusRequester() }
    var confirmAll by remember { mutableStateOf(false) }
    val played = rememberOfflineWatched(account, manifest.entries)
    val preview = manifest.watchedPreview(played)
    var showPreview by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    LazyColumn(
        Modifier.fillMaxSize().background(OfflinePage).padding(horizontal = 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "top") { Box(Modifier.height(24.dp)) }
        item(key = "heading") { Text("Storage and Wi-Fi", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() }) }
        item(key = "limit") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Limit for this viewer: ${manifest.budget.capBytes?.let(::offlineBytes) ?: "not set. Choose one to download."}. Used: ${offlineBytes(manifest.budget.usedBytes)}."
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    offlineCapSteps(android.os.StatFs(engine.files.root.parentFile!!.path).totalBytes).forEachIndexed { index, step ->
                        Button(
                            onClick = { controller.setCap(step) },
                            modifier = (if (index == 0) Modifier.focusRequester(first) else Modifier).testTag("storage:cap:$step")
                        ) { Text(offlineBytes(step)) }
                    }
                }
            }
        }
        item(key = "wifi") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (manifest.wifiOnly) "Downloads use Wi-Fi or Ethernet only." else "Downloads can use any connection, including mobile data.")
                Button(onClick = { controller.setWifiOnly(!manifest.wifiOnly) }, modifier = Modifier.testTag("storage:wifi")) {
                    Text(if (manifest.wifiOnly) "Allow any connection" else "Use Wi-Fi only")
                }
            }
        }
        item(key = "clean") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Remove watched downloads", style = MaterialTheme.typography.titleMedium)
                if (preview.entryIds.isEmpty()) {
                    Text("Nothing here has been watched yet.", color = OfflineMuted)
                } else if (showPreview) {
                    Text("This removes ${offlineBytes(preview.bytes)}: ${preview.titles.joinToString(", ")}")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val cancel = remember { FocusRequester() }
                        Button(onClick = { showPreview = false }, modifier = Modifier.focusRequester(cancel)) { Text("Cancel") }
                        Button(onClick = {
                            showPreview = false
                            controller.deleteEntries(preview.entryIds)
                        }) { Text("Remove") }
                        LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
                    }
                } else {
                    Button(onClick = { showPreview = true }) { Text("Review what would go") }
                }
            }
        }
        item(key = "all") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (confirmAll) {
                    Text("Delete all ${manifest.entries.size} downloads for this viewer?")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val cancel = remember { FocusRequester() }
                        Button(onClick = { confirmAll = false }, modifier = Modifier.focusRequester(cancel).testTag("storage:cancel-all")) { Text("Cancel") }
                        Button(onClick = {
                            confirmAll = false
                            controller.deleteEntries(manifest.entries.map(OfflineEntry::id))
                        }, modifier = Modifier.testTag("storage:confirm-all")) { Text("Delete all") }
                        LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
                    }
                } else if (manifest.entries.isNotEmpty()) {
                    Button(onClick = { confirmAll = true }, modifier = Modifier.testTag("storage:delete-all")) { Text("Delete all downloads") }
                }
            }
        }
        item(key = "back") { Button(onClick = onBack) { Text("Back") } }
        item(key = "end") { Box(Modifier.height(32.dp)) }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
internal fun OfflineDownloadTvScreen(account: Account, itemId: String, seriesId: String?, seasonId: String?, title: String, onDone: () -> Unit, onBack: () -> Unit) {
    val controller = rememberOfflinePreview(account, itemId, seriesId, seasonId, title)
    val first = remember { FocusRequester() }
    BackHandler(onBack = onBack)
    LazyColumn(
        Modifier.fillMaxSize().background(OfflinePage).padding(horizontal = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "top") { Box(Modifier.height(24.dp)) }
        item(key = "heading") { Text("Download $title", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() }) }
        when (val state = controller.state) {
            OfflinePreviewState.Loading -> item(key = "loading") { Text("Checking what can be downloaded…") }
            is OfflinePreviewState.Unavailable -> {
                item(key = "unavailable") { Text(state.message) }
                item(key = "back") {
                    Button(onClick = onBack, modifier = Modifier.focusRequester(first)) { Text("Back") }
                    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                }
            }
            is OfflinePreviewState.Ready -> {
                item(key = "quality") { Text("Quality", style = MaterialTheme.typography.titleMedium) }
                state.preview.choices.forEachIndexed { index, choice ->
                    item(key = "preset:${choice.preset}") {
                        Button(
                            onClick = { controller.preset = choice.preset },
                            enabled = choice.available,
                            modifier = (if (index == 0) Modifier.focusRequester(first) else Modifier).testTag("download:preset:${choice.preset}")
                        ) {
                            Text(
                                (if (controller.preset == choice.preset && choice.available) "✓ " else "") + offlinePresetLabel(choice.preset) +
                                    if (choice.available) "" else ". " + offlinePresetUnavailable(choice.reason)
                            )
                        }
                    }
                }
                item(key = "first") { LaunchedEffect(Unit) { runCatching { first.requestFocus() } } }
                if (state.preview.items.size > 1) {
                    item(key = "episodes") { Text("Episodes", style = MaterialTheme.typography.titleMedium) }
                    items(state.preview.items, key = { "episode:" + it.id }) { episode ->
                        Button(onClick = { controller.toggleEpisode(episode.id) }, modifier = Modifier.testTag("download:episode:${episode.id}")) {
                            Text((if (episode.id in controller.episodes) "✓ " else "") + (episode.episodeNumber?.let { "$it. " } ?: "") + episode.name)
                        }
                    }
                }
                if (controller.preset == OfflinePreset.original) {
                    item(key = "tracks") { Text("All audio and subtitles in the file stay with it.", color = OfflineMuted) }
                    controller.warning?.let { warning -> item(key = "warning") { Text(warning) } }
                } else {
                    item(key = "audio") { Text("Audio, up to $OFFLINE_MAX_CONVERTED_AUDIO", style = MaterialTheme.typography.titleMedium) }
                    items(controller.sourceTracks("Audio"), key = { "audio:" + it.index }) { track ->
                        Button(onClick = { controller.toggleAudio(track.index) }, modifier = Modifier.testTag("download:audio:${track.index}")) {
                            Text((if (track.index in controller.audio) "✓ " else "") + (track.displayTitle ?: track.language ?: "Track ${track.index}"))
                        }
                    }
                    item(key = "subs") { Text("Subtitles, up to $OFFLINE_MAX_CONVERTED_SUBTITLES", style = MaterialTheme.typography.titleMedium) }
                    items(controller.sourceTracks("Subtitle"), key = { "subtitle:" + it.index }) { track ->
                        Button(
                            onClick = { controller.toggleSubtitle(track.index) },
                            enabled = track.isText,
                            modifier = Modifier.testTag("download:subtitle:${track.index}")
                        ) {
                            Text(
                                (if (track.index in controller.subtitles) "✓ " else "") + (track.displayTitle ?: track.language ?: "Track ${track.index}") +
                                    if (track.isText) "" else ". Picture subtitles can't be converted."
                            )
                        }
                    }
                }
                item(key = "size") {
                    val estimate = state.preview.choices.firstOrNull { it.preset == controller.preset }?.estimateBytes
                    val count = controller.selectedItems().size.coerceAtLeast(1)
                    Text(offlineSizeText(controller.selectedItems(), controller.preset) { estimate?.let { total -> total / state.preview.items.size.coerceAtLeast(count) } })
                }
                item(key = "cellular") {
                    Button(onClick = { controller.cellular = !controller.cellular }, modifier = Modifier.testTag("download:cellular")) {
                        Text(if (controller.cellular) "✓ Can use mobile data for this" else "Wi-Fi only for this. Select to allow mobile data.")
                    }
                }
                item(key = "start") {
                    Button(
                        onClick = { controller.start(onDone) },
                        enabled = !controller.started && controller.selectedItems().isNotEmpty() && state.preview.choices.any { it.preset == controller.preset && it.available },
                        modifier = Modifier.testTag("download:start")
                    ) { Text("Download") }
                }
            }
        }
        item(key = "end") { Box(Modifier.height(32.dp)) }
    }
}
