/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pictureshow.core.subtitles.*
import java.text.NumberFormat

/** SUB-DL-03: the line under a result's name, in the viewer's words (Android's wording). */
internal fun desktopSubtitleDetails(result: RemoteSubtitle): String = RemoteSubtitlePolicy.details(result).map { token ->
    when {
        token == "hash-match" -> "Made for this video"
        token == "hearing-impaired" -> "Hearing impaired"
        token == "forced" -> "Forced"
        token == "machine-translated" -> "Machine translated"
        token.startsWith("downloads:") -> {
            val count = token.removePrefix("downloads:").toInt()
            "${NumberFormat.getIntegerInstance(java.util.Locale.US).format(count)} ${if (count == 1) "download" else "downloads"}"
        }
        else -> token.substringAfter(':')
    }
}.joinToString(" · ")

/** The panel's status sentence, or null when the results speak for themselves (in the player). */
internal fun desktopSubtitleStatus(state: RemoteSubtitleState): String? = when (state.phase) {
    RemoteSubtitlePhase.Checking -> "Checking what your server can do…"
    RemoteSubtitlePhase.Blocked -> when (state.access) {
        RemoteSubtitleAccess.NotAllowed -> NOT_ALLOWED
        RemoteSubtitleAccess.NoProvider -> NO_PROVIDER
        else -> "Subtitles can only be downloaded for titles played from your server."
    }
    RemoteSubtitlePhase.Searching -> "Searching for ${state.languageName} subtitles…"
    RemoteSubtitlePhase.Results -> when (state.empty) {
        RemoteSubtitleEmpty.None -> null
        RemoteSubtitleEmpty.NoMatches -> "No ${state.languageName} subtitles were found for this title. Try another language."
        RemoteSubtitleEmpty.NotAllowed -> NOT_ALLOWED
        RemoteSubtitleEmpty.NoProvider -> NO_PROVIDER
        RemoteSubtitleEmpty.Failed -> "The server couldn't finish that. Try again, or pick another result."
        RemoteSubtitleEmpty.Unreachable -> "Couldn't reach your server. Check your connection and try again."
    }
    RemoteSubtitlePhase.Downloading -> "Downloading ${state.chosen?.let(RemoteSubtitlePolicy::title).orEmpty()}…"
    RemoteSubtitlePhase.Added -> "Added. Subtitles switched to ${state.languageName}."
    RemoteSubtitlePhase.NotListed -> "The server downloaded the file but hasn't listed it yet. It will appear under Subtitles once the server finishes scanning the title."
}

private const val NOT_ALLOWED = "Your account isn't allowed to download subtitles. A server admin can turn on “Allow subtitle management” for you in Jellyfin's Dashboard, Users."
private const val NO_PROVIDER = "Your server has no subtitle provider, so it can't search for subtitles. A server admin can install one, such as the OpenSubtitles plugin, from Dashboard, Plugins, Catalog in Jellyfin."

private fun RemoteSubtitleState.retryable() = phase == RemoteSubtitlePhase.Results && empty in setOf(RemoteSubtitleEmpty.Failed, RemoteSubtitleEmpty.Unreachable)

/**
 * SUB-DL in the desktop player: a language choice, then results. Choosing one downloads it; once the server lists it,
 * the viewing reopens at the same place with it selected. Escape or Close returns to the Subtitles button.
 */
@Composable
internal fun DesktopSubtitleDownloadDialog(c: DesktopController, close: () -> Unit) {
    val search = remember { c.subtitleSearch() }
    DisposableEffect(search) { onDispose { search?.close() } }
    if (search == null) { LaunchedEffect(Unit) { close() }; return }
    val state by search.state.collectAsState()
    val closeFocus = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    LaunchedEffect(state.phase, state.results.size) {
        withFrameNanos { }
        runCatching { if (state.phase == RemoteSubtitlePhase.Results && state.results.isNotEmpty()) firstResult.requestFocus() else closeFocus.requestFocus() }
    }
    AlertDialog(onDismissRequest = close, modifier = Modifier.widthIn(max = 640.dp).probe("subtitle-download-dialog").onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { close(); true } else false
    }, title = { Text("Download subtitles") }, text = {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.languages.isNotEmpty() && state.phase in setOf(RemoteSubtitlePhase.Searching, RemoteSubtitlePhase.Results)) {
                CompositionLocalProvider(LocalModalOpen provides false) {
                    Choice("Language", state.languages.mapNotNull { culture -> culture.threeLetter?.lowercase()?.let { it to culture.displayName } }.distinctBy { it.first },
                        state.language, "subtitle-download-language", buttonValue = "Language: ${state.languageName}", buttonWidth = 320.dp, menuMinWidth = 280.dp) { search.language(it) }
                }
            }
            desktopSubtitleStatus(state)?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.probe("subtitle-download-status")) }
            if (state.phase == RemoteSubtitlePhase.Results) state.results.forEachIndexed { i, result ->
                OutlinedCard(onClick = {
                    search.download(result) { adopted -> c.adoptDownloadedSubtitle(adopted.subtitleIndex, adopted.audioIndex) }
                }, modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(firstResult) else Modifier).controlFocus(androidx.compose.foundation.shape.RoundedCornerShape(12.dp), allowInModal = true).probe("subtitle-result:$i")) {
                    Column(Modifier.padding(12.dp)) {
                        Text(RemoteSubtitlePolicy.title(result), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        desktopSubtitleDetails(result).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }, confirmButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.retryable()) TextButton(onClick = search::retry, modifier = Modifier.controlFocus(allowInModal = true).probe("subtitle-download-retry")) { Text("Try again") }
            TextButton(onClick = close, modifier = Modifier.focusRequester(closeFocus).controlFocus(allowInModal = true).probe("subtitle-download-close")) { Text("Close") }
        }
    })
    // Once adopted, the viewing reopens with the new subtitle; the panel's job is done.
    LaunchedEffect(state.phase) { if (state.phase == RemoteSubtitlePhase.Added) { kotlinx.coroutines.delay(2000); close() } }
}
