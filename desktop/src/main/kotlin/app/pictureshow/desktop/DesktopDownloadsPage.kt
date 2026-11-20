/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.music.trackIsAvailable
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePreset

internal fun desktopOfflineActionLabel(action: OfflineAction): String = when (action) {
    OfflineAction.Play -> "Play"
    OfflineAction.Pause -> "Pause"
    OfflineAction.Resume -> "Resume"
    OfflineAction.Retry -> "Retry"
    OfflineAction.Redownload -> "Download again"
    // A computer's metered link is a hotspot or a tethered phone, not "mobile data".
    OfflineAction.AllowCellular -> "Use this connection"
    OfflineAction.Delete -> "Delete"
    OfflineAction.Tracks -> "Show tracks"
}

internal fun desktopOfflineConflictText(title: String): String =
    "$title: your place changed on the server while you were offline, so the server's place was kept."

/** The page's first focus target, as a probe tag: what needs the viewer first, then the first row's main action. */
internal fun desktopDownloadsFirstFocus(s: DesktopOfflineState): String = when (val first = offlineFirstFocus(s.passwordNeeded, s.sections, s.conflicts.keys)) {
    OfflineFirstFocus.Password -> "downloads:password"
    is OfflineFirstFocus.Conflict -> "downloads:conflict:${first.itemId}"
    is OfflineFirstFocus.Row -> "downloads:${first.action.name.lowercase()}:${first.entryId}"
    OfflineFirstFocus.Storage -> "downloads:cap"
}

/** OFF-04: every download this viewer has, grouped by what needs them; it opens and plays with no server at all. */
@Composable
internal fun DesktopDownloadsPage(c: DesktopController) {
    val s by c.offline.state.collectAsState()
    LaunchedEffect(Unit) { c.offline.refresh() }
    DownloadsPage(s, c.offline, play = c::playDownload, retryServer = c::retryServer)
}

@Composable
internal fun DownloadsPage(s: DesktopOfflineState, owner: DesktopOffline, play: (OfflineEntry) -> Unit, retryServer: () -> Unit) {
    val sections = s.sections
    val first = desktopDownloadsFirstFocus(s)
    LaunchedEffect(first) {
        withFrameNanos { }
        DesktopUiProbe.requestFocus[first]?.let { runCatching { it.requestFocus() } }
    }
    CompositionLocalProvider(LocalModalOpen provides (LocalModalOpen.current || s.confirm != null)) {
        LazyColumn(Modifier.fillMaxSize().focusProperties {
            onExit = {
                if (requestedFocusDirection == FocusDirection.Up || requestedFocusDirection == FocusDirection.Down) cancelFocusChange()
            }
        }.focusGroup().padding(horizontal = 20.dp), state = rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)) {
            item(key = "title") { Text("Downloads", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium) }
            if (s.serverUnreachable) item(key = "unreachable") {
                Notice(OFFLINE_UNREACHABLE_TEXT, "Try the server again", "downloads:retry-server", retryServer)
            } else if (s.network == OfflineNetwork.none) item(key = "no-network") {
                Text("No connection. Everything saved below still plays.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            s.notice?.let { notice -> item(key = "notice") { Notice(notice, "OK", "downloads:notice-ok", owner::clearNotice) } }
            if (s.library.recoverable) item(key = "recoverable") { Text(OFFLINE_RECOVERABLE_TEXT, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (s.passwordNeeded) item(key = "password") { PasswordForm(s.passwordFailed, owner::providePassword) }
            s.conflicts.keys.forEach { itemId ->
                item(key = "conflict:$itemId") {
                    val title = s.library.manifest.entries.firstOrNull { it.itemId == itemId }?.labels?.title ?: "A download"
                    Notice(desktopOfflineConflictText(title), "OK", "downloads:conflict:$itemId") { owner.dismissConflict(itemId) }
                }
            }
            item(key = "storage") { StorageRow(s, owner) }
            if (sections.isEmpty && !s.library.recoverable) item(key = "empty") {
                Text("Nothing downloaded yet. Open a movie or an episode and choose Download.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.probe("downloads:empty"))
            }
            sections.ordered.forEach { (name, rows) ->
                item(key = "section:$name") {
                    Text(name, Modifier.padding(top = 8.dp).semantics { heading() }.probe("downloads:section:$name"), style = MaterialTheme.typography.titleMedium)
                }
                items(rows, key = { "row:${it.key}" }) { row -> DownloadRow(row, s.watched, onAct = { action -> owner.act(row, action, play) }) }
            }
        }
    }
    when (val confirm = s.confirm) {
        null -> Unit
        else -> {
            val cancel = remember { FocusRequester() }
            LaunchedEffect(Unit) { withFrameNanos { }; runCatching { cancel.requestFocus() } }
            val (title, text, button) = when (confirm) {
                OfflineConfirm.All -> Triple("Delete all downloads?", "Delete all ${s.library.manifest.entries.size} downloads for this viewer from this computer? You can still stream them from your server.", "Delete all")
                is OfflineConfirm.One -> Triple("Delete download?", if (confirm.row.entry.job.phase == OfflinePhase.ready) {
                    "Delete ${confirm.row.title} from this computer? You can still stream it from your server."
                } else "Stop downloading ${confirm.row.title} and delete what's saved so far?", "Delete")
            }
            AlertDialog(onDismissRequest = owner::cancelConfirm, title = { Text(title) }, text = { Text(text) },
                dismissButton = { TextButton(owner::cancelConfirm, Modifier.focusRequester(cancel).controlFocus(allowInModal = true).probe("downloads:cancel")) { Text("Cancel") } },
                confirmButton = { TextButton(owner::confirmed, Modifier.controlFocus(allowInModal = true).probe("downloads:confirm")) { Text(button) } })
        }
    }
}

@Composable
private fun Notice(text: String, action: String, tag: String, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick, Modifier.controlFocus().probe(tag)) { Text(action) }
        }
    }
}

@Composable
private fun PasswordForm(failed: Boolean, submit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(OFFLINE_PASSWORD_STATUS, style = MaterialTheme.typography.titleMedium)
            Text(OFFLINE_PASSWORD_REASON, style = MaterialTheme.typography.bodyMedium)
            if (failed) Text(OFFLINE_PASSWORD_FAILED, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (password.isNotEmpty()) { submit(password); password = "" } }),
                    modifier = Modifier.widthIn(max = 360.dp).weight(1f, fill = false).controlFocus(RoundedCornerShape(4.dp)).probe("downloads:password"))
                Button({ submit(password); password = "" }, enabled = password.isNotEmpty(), modifier = Modifier.controlFocus().probe("downloads:password-submit")) { Text("Continue") }
            }
        }
    }
}

@Composable
private fun StorageRow(s: DesktopOfflineState, owner: DesktopOffline) {
    val sections = s.sections
    FlowRow(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Text(listOfNotNull("${offlineBytes(sections.usedBytes)} used", sections.capBytes?.let { "limit ${offlineBytes(it)}" }).joinToString(" of "),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.probe("downloads:used"))
        val choices = remember(sections.capBytes) { owner.capChoices() }
        Choice("Storage limit", choices.map { it to offlineBytes(it) }, sections.capBytes ?: choices.lastOrNull() ?: 0L, "downloads:cap",
            buttonWidth = 260.dp) { owner.setCap(it) }
        if (s.library.manifest.entries.isNotEmpty()) {
            OutlinedButton(owner::confirmDeleteAll, Modifier.controlFocus().probe("downloads:delete-all")) { Text("Delete all") }
        }
    }
}

@Composable
private fun DownloadRow(row: OfflineRow, watched: Set<String>, onAct: (OfflineAction) -> Unit) {
    // A track under its open album or playlist sits indented beneath it.
    Surface(Modifier.padding(start = if (row.parent != null) 32.dp else 0.dp), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(10.dp)) {
        FlowRow(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.widthIn(min = 240.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                row.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                val status = if (row.entry.job.phase == OfflinePhase.ready && row.entry.itemId in watched) "${row.status} · Watched" else row.status
                Text(status, style = MaterialTheme.typography.bodyMedium, color = if (row.statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.probe("downloads:status:${row.key}").semantics { liveRegion = LiveRegionMode.Polite })
                row.notDownloadedSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                when {
                    row.indeterminate -> LinearProgressIndicator(Modifier.widthIn(max = 360.dp).fillMaxWidth().height(3.dp))
                    row.fraction != null && row.entry.job.phase != OfflinePhase.ready ->
                        LinearProgressIndicator(progress = { row.fraction }, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().height(3.dp))
                }
            }
            row.actions.filter { it != OfflineAction.Tracks || row.collection != null }.forEachIndexed { index, action ->
                val tag = "downloads:${action.name.lowercase()}:${row.key}"
                // An album or playlist row opens to its tracks in their saved order (MUS-10).
                val label = if (action == OfflineAction.Tracks && row.open) "Hide tracks" else desktopOfflineActionLabel(action)
                if (index == 0 && action != OfflineAction.Delete) {
                    Button({ onAct(action) }, Modifier.controlFocus().probe(tag).semantics { contentDescription = "$label ${row.title}" }) { Text(label) }
                } else {
                    OutlinedButton({ onAct(action) }, Modifier.controlFocus().probe(tag).semantics { contentDescription = "$label ${row.title}" }) { Text(label) }
                }
            }
        }
    }
}

/** OFF-04a: the title page's own download, its state and its actions; Play itself goes through the page's main button. */
@Composable
internal fun DesktopDetailDownload(item: MediaItem, s: DesktopOfflineState, owner: DesktopOffline) {
    if (item.type !in setOf("Movie", "Episode", "Video", "Series", "Season")) return
    val entries = s.entriesFor(item.id).filter { it.detailVisible() }
    val rows = (s.sections.ordered.flatMap { it.second }).filter { r -> entries.any { it.id == r.entry.id } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Downloaded copy · ${offlinePresetLabel(row.entry.preset)} · ${row.status}", style = MaterialTheme.typography.bodyMedium,
                    color = if (row.statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false).probe("detail:download-status:${row.entry.preset.name}"))
                row.actions.filter { it != OfflineAction.Play && it != OfflineAction.Tracks }.forEach { action ->
                    TextButton({ owner.act(row, action) {} }, Modifier.controlFocus().probe("detail:download-${action.name.lowercase()}:${row.entry.preset.name}")) {
                        Text(if (action == OfflineAction.Delete) "Remove download" else desktopOfflineActionLabel(action))
                    }
                }
            }
        }
        if (rows.isEmpty() || item.type == "Series" || item.type == "Season") {
            OutlinedButton({ owner.openPreview(item) }, enabled = !s.serverUnreachable, modifier = Modifier.controlFocus().probe("detail:download")) {
                Icon(DesktopGlyph.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(when (item.type) { "Series" -> "Download series"; "Season" -> "Download season"; else -> "Download" })
            }
        }
    }
    s.preview?.let { DownloadDialog(it, owner) }
}

/** OFF-01/02: what to download, in which quality, with which tracks; sizes are the server's own when it gives them. */
@Composable
private fun DownloadDialog(p: DesktopOfflinePreview, owner: DesktopOffline) {
    val first = remember { FocusRequester() }
    LaunchedEffect(p.state is OfflinePreviewState.Ready) { withFrameNanos { }; runCatching { first.requestFocus() } }
    AlertDialog(onDismissRequest = owner::closePreview, modifier = Modifier.widthIn(max = 640.dp).probe("download-dialog"),
        title = { Text("Download ${p.title}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val state = p.state) {
                    OfflinePreviewState.Loading -> Text("Checking what can be downloaded…", Modifier.probe("download:loading"))
                    is OfflinePreviewState.Unavailable -> Text(state.message, Modifier.probe("download:unavailable"))
                    is OfflinePreviewState.Ready -> {
                        val preview = state.preview
                        Text("Quality", style = MaterialTheme.typography.titleSmall)
                        preview.choices.forEachIndexed { index, choice ->
                            val size = offlineChoiceSize(choice)
                            Row(Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                .controlFocus(RoundedCornerShape(6.dp), allowInModal = true, enabled = choice.available)
                                .selectableRow(p.preset == choice.preset, choice.available) { owner.choosePreset(choice.preset) }
                                .probe("download:preset:${choice.preset.name}"), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(p.preset == choice.preset, null, enabled = choice.available)
                                Column {
                                    Text(listOfNotNull(offlinePresetLabel(choice.preset), size).joinToString(" · "))
                                    if (!choice.available) Text(offlinePresetUnavailable(choice.reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (preview.items.size > 1) {
                            Text("Episodes", style = MaterialTheme.typography.titleSmall)
                            preview.items.forEach { episode ->
                                CheckRow(listOfNotNull(episode.seasonEpisodeLabel(), episode.name).joinToString(" · "), episode.id in p.episodes,
                                    "download:episode:${episode.id}") { owner.toggleEpisode(episode.id) }
                            }
                        }
                        if (p.preset == OfflinePreset.original) {
                            Text("All audio and subtitles in the file stay with it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (p.preset.isConvertedVideo) {
                            Text("Audio, up to $OFFLINE_MAX_CONVERTED_AUDIO", style = MaterialTheme.typography.titleSmall)
                            p.sourceTracks("Audio").forEach { t -> CheckRow(t.label(), t.index in p.audio, "download:audio:${t.index}") { owner.toggleAudio(t.index) } }
                            val subs = p.sourceTracks("Subtitle").filter { it.isText }
                            if (subs.isNotEmpty()) {
                                Text("Subtitles, up to $OFFLINE_MAX_CONVERTED_SUBTITLES", style = MaterialTheme.typography.titleSmall)
                                subs.forEach { t -> CheckRow(t.label(), t.index in p.subtitles, "download:subtitle:${t.index}") { owner.toggleSubtitle(t.index) } }
                            }
                        }
                        Text(offlineSizeText(p.selectedItems(), p.preset) { item ->
                            offlinePreviewChoices(preview.policy, listOf(item)).firstOrNull { it.preset == p.preset }?.estimateBytes
                        }, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.probe("download:size"))
                    }
                }
            }
        },
        dismissButton = { TextButton(owner::closePreview, Modifier.then(if (p.state !is OfflinePreviewState.Ready) Modifier.focusRequester(first) else Modifier)
            .controlFocus(allowInModal = true).probe("download:cancel")) { Text("Cancel") } },
        confirmButton = { Button(owner::startPreview, enabled = p.canStart, modifier = Modifier.controlFocus(allowInModal = true).probe("download:start")) { Text("Download") } })
}

@Composable
private fun CheckRow(text: String, checked: Boolean, tag: String, toggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().controlFocus(RoundedCornerShape(6.dp), allowInModal = true).selectableRow(checked, true, toggle).probe(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null)
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun Modifier.selectableRow(selected: Boolean, enabled: Boolean, onClick: () -> Unit): Modifier =
    selectable(selected = selected, enabled = enabled, onClick = onClick)

private fun DesktopOfflineStream.label(): String = displayTitle ?: title ?: language ?: "Track ${index}"

private fun DesktopOfflineItem.seasonEpisodeLabel(): String? =
    if (seasonNumber != null && episodeNumber != null) "S$seasonNumber E$episodeNumber" else null

/**
 * MUS-02/MUS-10: an album or playlist page's download, its state and its actions. Albums and playlists download whole in their
 * original quality; an artist, genre or single song has no Download here (a track downloads with its album or playlist).
 */
@Composable
internal fun DesktopMusicDownload(c: DesktopController, account: Account, s: DesktopMusicDetailState) {
    if (desktopMusicDownloadKind(s) == null) return
    val offline by c.offline.state.collectAsState()
    val downloader = remember(account.key, s.item.id) { c.offline.musicDownloader(account) }
    val ask by downloader.ask.collectAsState()
    val library = if (offline.accountKey == account.key) offline.library else c.offline.engine.libraries.collectAsState().value[account.key]
    val status = desktopMusicDownloadStatus(library, s.item.id, offline.network)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        status?.let {
            Text(it.text, style = MaterialTheme.typography.bodyMedium, color = if (it.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.probe("music:download-status").semantics { liveRegion = LiveRegionMode.Polite })
            it.fraction?.let { f -> LinearProgressIndicator(progress = { f }, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().height(3.dp)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status == null) {
                OutlinedButton({ downloader.request(s) }, enabled = s.tracks.any(::trackIsAvailable) && s.tracksComplete && !s.busy && ask != DesktopMusicDownloadAsk.Checking,
                    modifier = Modifier.controlFocus().probe("music:download")) {
                    Icon(DesktopGlyph.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (ask == DesktopMusicDownloadAsk.Checking) "Checking…" else "Download")
                }
            } else {
                OutlinedButton({ downloader.askRemove(s, status.ready) }, Modifier.controlFocus().probe("music:download-remove")) {
                    Text(if (status.ready) "Remove download" else "Stop download")
                }
                TextButton(c::downloads, Modifier.controlFocus().probe("music:downloads")) { Text("Open Downloads") }
            }
        }
    }
    val question = ask
    if (question != null && question != DesktopMusicDownloadAsk.Checking) {
        val cancel = remember { FocusRequester() }
        LaunchedEffect(question) { withFrameNanos { }; runCatching { cancel.requestFocus() } }
        CompositionLocalProvider(LocalModalOpen provides true) {
            when (question) {
                is DesktopMusicDownloadAsk.Confirm -> AlertDialog(onDismissRequest = downloader::dismiss, title = { Text("Download ${s.item.name}?") }, text = { Text(question.text) },
                    dismissButton = { TextButton(downloader::dismiss, Modifier.focusRequester(cancel).controlFocus(allowInModal = true).probe("music:download:cancel")) { Text("Cancel") } },
                    confirmButton = { TextButton({ downloader.start(question.items) }, Modifier.controlFocus(allowInModal = true).probe("music:download:start")) { Text("Download") } })
                is DesktopMusicDownloadAsk.Remove -> AlertDialog(onDismissRequest = downloader::dismiss, title = { Text(question.confirm + "?") }, text = { Text(question.text) },
                    dismissButton = { TextButton(downloader::dismiss, Modifier.focusRequester(cancel).controlFocus(allowInModal = true).probe("music:download:cancel")) { Text("Cancel") } },
                    confirmButton = { TextButton(downloader::remove, Modifier.controlFocus(allowInModal = true).probe("music:download:remove")) { Text(question.confirm) } })
                is DesktopMusicDownloadAsk.Notice -> AlertDialog(onDismissRequest = downloader::dismiss, title = { Text("Download") }, text = { Text(question.text) },
                    confirmButton = { TextButton(downloader::dismiss, Modifier.focusRequester(cancel).controlFocus(allowInModal = true).probe("music:download:ok")) { Text("OK") } })
                DesktopMusicDownloadAsk.Checking -> Unit
            }
        }
    }
}
