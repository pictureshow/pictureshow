/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.watchedPreview
import coil3.compose.AsyncImage
import org.koin.compose.koinInject

@Composable
private fun TouchSection(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
internal fun OfflineDownloadsTouchScreen(account: Account, offline: Boolean, onPlay: (OfflineEntry) -> Unit, onStorage: () -> Unit, onOnline: () -> Unit, onBack: (() -> Unit)?) {
    val view = rememberOfflineDownloads(account, onPlay)
    val controller = view.controller
    val sections = view.sections
    TouchPage("Downloads", onBack) {
        Text(
            sections.capBytes?.let { "${offlineBytes(sections.usedBytes)} of ${offlineBytes(it)} used" } ?: "${offlineBytes(sections.usedBytes)} used",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (view.network == OfflineNetwork.none) Text("No connection. Everything saved below still plays.")
        OutlinedButton(onClick = onStorage, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("downloads:storage")) { Text("Storage and Wi-Fi") }
        if (offline) OutlinedButton(onClick = onOnline, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("downloads:online")) { Text("Open the library") }
        if (view.library.recoverable) {
            Text("This list was saved by a newer version of Pictureshow or couldn't be read. Nothing was deleted. Update the app to get it back.")
        }
        if (view.library.needsPassword) OfflinePasswordTouch(controller)
        view.conflicts.keys.forEach { itemId ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(offlineConflictText(view.conflictTitle(itemId)))
                    TextButton(onClick = { controller.dismissConflict(itemId) }) { Text("OK") }
                }
            }
        }
        if (sections.isEmpty && !view.library.recoverable) {
            Text("Nothing downloaded yet. Open a movie or an episode and choose Download.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        listOf("In progress" to sections.active, "Needs attention" to sections.failed, "Ready to watch" to sections.ready).forEach { (title, rows) ->
            if (rows.isNotEmpty()) {
                TouchSection(title)
                rows.forEach { OfflineRowTouch(account, it, controller) }
            }
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfflineRowTouch(account: Account, row: OfflineRow, controller: OfflineDownloadsController) {
    val confirming = (controller.confirm as? OfflineConfirm.One)?.row?.entry?.id == row.entry.id
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().testTag("downloads:row:${row.entry.id}")) {
        val art = offlineArtwork(account.key, row.entry)
        Box(Modifier.size(width = 64.dp, height = 96.dp).background(Color(0xFF1F2433))) {
            if (art != null) AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleSmall)
            row.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(row.status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("downloads:status:${row.entry.id}"))
            row.fraction?.let { fraction -> LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth()) }
            row.entry.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (row.notDownloaded.isNotEmpty()) {
                Text("Not downloaded: ${row.notDownloaded.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (confirming) {
                Text(if (row.entry.job.phase == OfflinePhase.ready) "Delete this download from this device?" else "Stop and delete this download?")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { controller.confirm = null }, modifier = Modifier.heightIn(min = 48.dp).testTag("downloads:cancel")) { Text("Cancel") }
                    Button(onClick = { controller.confirmed(emptyList()) }, modifier = Modifier.heightIn(min = 48.dp).testTag("downloads:confirm")) { Text("Delete") }
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.actions.forEach { action ->
                        val modifier = Modifier.heightIn(min = 48.dp).testTag("downloads:${action.name.lowercase()}:${row.entry.id}")
                        if (action == OfflineAction.Play) {
                            Button(onClick = { controller.act(row, action) }, modifier = modifier) { Text(offlineActionLabel(action)) }
                        } else {
                            TextButton(onClick = { controller.act(row, action) }, modifier = modifier) { Text(offlineActionLabel(action)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflinePasswordTouch(controller: OfflineDownloadsController) {
    var password by remember { mutableStateOf("") }
    Text("This server has Quick Connect off. Downloads need your password once, to get their own sign-in. It isn't saved.")
    OutlinedTextField(
        password,
        { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
    TouchAction("Continue", password.isNotEmpty()) { controller.providePassword(password) }
}

@Composable
internal fun OfflineStorageTouchScreen(account: Account, onBack: () -> Unit) {
    val view = rememberOfflineDownloads(account) {}
    val controller = view.controller
    val engine: OfflineEngine = koinInject()
    val manifest = view.library.manifest
    var confirmAll by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    val preview = manifest.watchedPreview(rememberOfflineWatched(account, manifest.entries))
    TouchPage("Storage and Wi-Fi", onBack) {
        Text("Limit for this viewer: ${manifest.budget.capBytes?.let(::offlineBytes) ?: "not set. Choose one to download."}. Used: ${offlineBytes(manifest.budget.usedBytes)}.")
        offlineCapSteps(android.os.StatFs(engine.files.root.parentFile!!.path).totalBytes).forEach { step ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                RadioButton(selected = manifest.budget.capBytes == step, onClick = { controller.setCap(step) }, modifier = Modifier.testTag("storage:cap:$step"))
                Text(offlineBytes(step))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Wi-Fi or Ethernet only", Modifier.weight(1f))
            Switch(checked = manifest.wifiOnly, onCheckedChange = controller::setWifiOnly, modifier = Modifier.testTag("storage:wifi"))
        }
        TouchSection("Remove watched downloads")
        if (preview.entryIds.isEmpty()) {
            Text("Nothing here has been watched yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (showPreview) {
            Text("This removes ${offlineBytes(preview.bytes)}: ${preview.titles.joinToString(", ")}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showPreview = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                Button(onClick = {
                    showPreview = false
                    controller.deleteEntries(preview.entryIds)
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove") }
            }
        } else {
            OutlinedButton(onClick = { showPreview = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Review what would go") }
        }
        if (confirmAll) {
            Text("Delete all ${manifest.entries.size} downloads for this viewer?")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirmAll = false }, modifier = Modifier.heightIn(min = 48.dp).testTag("storage:cancel-all")) { Text("Cancel") }
                Button(onClick = {
                    confirmAll = false
                    controller.deleteEntries(manifest.entries.map(OfflineEntry::id))
                }, modifier = Modifier.heightIn(min = 48.dp).testTag("storage:confirm-all")) { Text("Delete all") }
            }
        } else if (manifest.entries.isNotEmpty()) {
            OutlinedButton(onClick = { confirmAll = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("storage:delete-all")) { Text("Delete all downloads") }
        }
    }
}

@Composable
internal fun OfflineDownloadTouchScreen(account: Account, itemId: String, seriesId: String?, seasonId: String?, title: String, onDone: () -> Unit, onBack: () -> Unit) {
    val controller = rememberOfflinePreview(account, itemId, seriesId, seasonId, title)
    TouchPage("Download $title", onBack) {
        when (val state = controller.state) {
            OfflinePreviewState.Loading -> Text("Checking what can be downloaded…")
            is OfflinePreviewState.Unavailable -> TouchMessage(state.message)
            is OfflinePreviewState.Ready -> {
                TouchSection("Quality")
                state.preview.choices.forEach { choice ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        RadioButton(
                            selected = controller.preset == choice.preset && choice.available,
                            onClick = { controller.preset = choice.preset },
                            enabled = choice.available,
                            modifier = Modifier.testTag("download:preset:${choice.preset}")
                        )
                        Column {
                            Text(offlinePresetLabel(choice.preset))
                            if (!choice.available) Text(offlinePresetUnavailable(choice.reason), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (state.preview.items.size > 1) {
                    TouchSection("Episodes")
                    state.preview.items.forEach { episode ->
                        TouchCheck((episode.episodeNumber?.let { "$it. " } ?: "") + episode.name, episode.id in controller.episodes, "download:episode:${episode.id}") { controller.toggleEpisode(episode.id) }
                    }
                }
                if (controller.preset == OfflinePreset.original) {
                    Text("All audio and subtitles in the file stay with it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    controller.warning?.let { Text(it) }
                } else {
                    TouchSection("Audio, up to $OFFLINE_MAX_CONVERTED_AUDIO")
                    controller.sourceTracks("Audio").forEach { track ->
                        TouchCheck(track.displayTitle ?: track.language ?: "Track ${track.index}", track.index in controller.audio, "download:audio:${track.index}") { controller.toggleAudio(track.index) }
                    }
                    TouchSection("Subtitles, up to $OFFLINE_MAX_CONVERTED_SUBTITLES")
                    controller.sourceTracks("Subtitle").forEach { track ->
                        TouchCheck(
                            (track.displayTitle ?: track.language ?: "Track ${track.index}") + if (track.isText) "" else ". Picture subtitles can't be converted.",
                            track.index in controller.subtitles,
                            "download:subtitle:${track.index}",
                            track.isText
                        ) { controller.toggleSubtitle(track.index) }
                    }
                }
                val estimate = state.preview.choices.firstOrNull { it.preset == controller.preset }?.estimateBytes
                val count = controller.selectedItems().size.coerceAtLeast(1)
                Text(offlineSizeText(controller.selectedItems(), controller.preset) { estimate?.let { total -> total / state.preview.items.size.coerceAtLeast(count) } })
                TouchCheck("Can use mobile data for this", controller.cellular, "download:cellular") { controller.cellular = !controller.cellular }
                TouchAction(
                    "Download",
                    !controller.started && controller.selectedItems().isNotEmpty() && state.preview.choices.any { it.preset == controller.preset && it.available }
                ) { controller.start(onDone) }
            }
        }
    }
}

@Composable
private fun TouchCheck(label: String, checked: Boolean, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Checkbox(checked = checked, onCheckedChange = { onClick() }, enabled = enabled, modifier = Modifier.testTag(tag))
        Text(label)
    }
}
