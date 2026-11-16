/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.subtitles.AdoptedSubtitle
import app.pictureshow.core.subtitles.RemoteSubtitle
import app.pictureshow.core.subtitles.RemoteSubtitleAccess
import app.pictureshow.core.subtitles.RemoteSubtitleClient
import app.pictureshow.core.subtitles.RemoteSubtitleEmpty
import app.pictureshow.core.subtitles.RemoteSubtitlePhase
import app.pictureshow.core.subtitles.RemoteSubtitlePolicy
import app.pictureshow.core.subtitles.RemoteSubtitleSearch
import app.pictureshow.core.subtitles.RemoteSubtitleState
import app.pictureshow.core.subtitles.RemoteSubtitleTarget
import io.ktor.client.HttpClient
import java.text.NumberFormat
import java.util.Locale
import org.koin.compose.koinInject

/** Item types the server can attach a subtitle file to. */
internal fun subtitleDownloadOffered(type: String?): Boolean = type in setOf("Movie", "Episode", "Video", "MusicVideo")

/** One search per panel; null until the device identity is read. */
@Composable
internal fun rememberRemoteSubtitleSearch(account: Account): RemoteSubtitleSearch? {
    val http: HttpClient = koinInject()
    val identity: DeviceIdentityStore = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var search by remember(account.key) { mutableStateOf<RemoteSubtitleSearch?>(null) }
    LaunchedEffect(account.key) {
        val client = RemoteSubtitleClient(
            http,
            ClientIdentity(
                deviceDisplayName(context),
                account.deviceId?.takeIf(String::isNotBlank) ?: identity.getOrCreate(),
                BuildConfig.VERSION_NAME
            )
        )
        search = RemoteSubtitleSearch(client, account, scope, Locale.getDefault().language)
    }
    DisposableEffect(search) { onDispose { search?.close() } }
    return search
}

/** The line under a result's name, in the viewer's words (SUB-DL-03). */
internal fun subtitleResultDetails(resources: Resources, result: RemoteSubtitle): String =
    RemoteSubtitlePolicy.details(result).map { token ->
        when {
            token == "hash-match" -> resources.getString(R.string.subtitle_detail_hash_match)
            token == "hearing-impaired" -> resources.getString(R.string.subtitle_detail_hearing_impaired)
            token == "forced" -> resources.getString(R.string.subtitle_detail_forced)
            token == "machine-translated" -> resources.getString(R.string.subtitle_detail_machine)
            token.startsWith("downloads:") -> {
                val count = token.removePrefix("downloads:").toInt()
                resources.getQuantityString(R.plurals.subtitle_detail_downloads, count, NumberFormat.getIntegerInstance().format(count))
            }
            else -> token.substringAfter(':')
        }
    }.joinToString(" · ")

/** The status sentence for the panel's current state, or null when the results speak for themselves. */
internal fun subtitleStatusText(resources: Resources, state: RemoteSubtitleState, inPlayer: Boolean): String? = when (state.phase) {
    RemoteSubtitlePhase.Checking -> resources.getString(R.string.subtitle_download_checking)
    RemoteSubtitlePhase.Blocked -> when (state.access) {
        RemoteSubtitleAccess.NotAllowed -> resources.getString(R.string.subtitle_download_not_allowed)
        RemoteSubtitleAccess.NoProvider -> resources.getString(R.string.subtitle_download_no_provider)
        else -> resources.getString(R.string.subtitle_download_unavailable)
    }
    RemoteSubtitlePhase.Searching -> resources.getString(R.string.subtitle_download_searching, state.languageName)
    RemoteSubtitlePhase.Results -> when (state.empty) {
        RemoteSubtitleEmpty.None -> null
        RemoteSubtitleEmpty.NoMatches -> resources.getString(R.string.subtitle_download_no_matches, state.languageName)
        RemoteSubtitleEmpty.NotAllowed -> resources.getString(R.string.subtitle_download_not_allowed)
        RemoteSubtitleEmpty.NoProvider -> resources.getString(R.string.subtitle_download_no_provider)
        RemoteSubtitleEmpty.Failed -> resources.getString(R.string.subtitle_download_failed)
        RemoteSubtitleEmpty.Unreachable -> resources.getString(R.string.subtitle_download_unreachable)
    }
    RemoteSubtitlePhase.Downloading ->
        resources.getString(R.string.subtitle_download_downloading, state.chosen?.let(RemoteSubtitlePolicy::title).orEmpty())
    RemoteSubtitlePhase.Added -> if (inPlayer) {
        resources.getString(R.string.subtitle_download_added_player, state.languageName)
    } else {
        resources.getString(R.string.subtitle_download_added_detail)
    }
    RemoteSubtitlePhase.NotListed -> resources.getString(R.string.subtitle_download_not_listed)
}

/** Whether the panel offers Try again: a failure that a second attempt could fix. */
private fun RemoteSubtitleState.retryable() = phase == RemoteSubtitlePhase.Results &&
    empty in setOf(RemoteSubtitleEmpty.Failed, RemoteSubtitleEmpty.Unreachable)

/** Whether the panel is finished and only Close is left. */
private fun RemoteSubtitleState.finished() =
    phase in setOf(RemoteSubtitlePhase.Blocked, RemoteSubtitlePhase.Added, RemoteSubtitlePhase.NotListed)

/**
 * SUB-DL on the TV: a language button, then results as rows. Up/Down moves through them; the first useful target takes
 * focus whenever the phase changes, so the remote never lands on nothing.
 */
@Composable
internal fun TvSubtitleDownloadContent(
    search: RemoteSubtitleSearch,
    target: RemoteSubtitleTarget,
    onAdopted: ((AdoptedSubtitle) -> Unit)?,
    onClose: () -> Unit
) {
    val state by search.state.collectAsState()
    val resources = LocalContext.current.resources
    var choosingLanguage by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(search, target) { search.open(target) }
    Column(Modifier.testTag("subtitle-download"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        subtitleStatusText(resources, state, onAdopted != null)?.let {
            androidx.tv.material3.Text(
                it,
                style = androidx.tv.material3.MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("subtitle-download:status").semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        when {
            state.finished() -> androidx.tv.material3.Button(
                onClick = onClose,
                modifier = Modifier.focusRequester(first).testTag("subtitle-download:close")
            ) {
                androidx.tv.material3.Text(stringResource(R.string.subtitle_download_close))
            }
            state.phase == RemoteSubtitlePhase.Checking -> Unit
            choosingLanguage -> LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.languages, key = { it.threeLetter.orEmpty() }) { culture ->
                    val code = culture.threeLetter.orEmpty().lowercase()
                    androidx.tv.material3.Button(
                        onClick = {
                            choosingLanguage = false
                            search.language(code)
                        },
                        modifier = (if (code == state.language) Modifier.focusRequester(first) else Modifier)
                            .testTag("subtitle-download:language:$code")
                    ) { androidx.tv.material3.Text(culture.displayName) }
                }
            }
            else -> {
                // Rows stay enabled while a download runs and ignore presses instead: disabling the focused row would
                // drop focus, and the Close that follows came up looking unfocused (OBSERVED, leg 163).
                androidx.tv.material3.Button(
                    onClick = { if (state.phase != RemoteSubtitlePhase.Downloading) choosingLanguage = true },
                    modifier = (if (state.results.isEmpty()) Modifier.focusRequester(first) else Modifier)
                        .testTag("subtitle-download:language")
                ) { androidx.tv.material3.Text(stringResource(R.string.subtitle_download_language, state.languageName)) }
                if (state.retryable()) {
                    androidx.tv.material3.Button(onClick = search::retry, modifier = Modifier.testTag("subtitle-download:retry")) {
                        androidx.tv.material3.Text(stringResource(R.string.subtitle_download_retry))
                    }
                }
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.results, key = { it.id }) { result ->
                        androidx.tv.material3.Button(
                            onClick = {
                                if (state.phase == RemoteSubtitlePhase.Results) {
                                    search.download(result) { adopted -> onAdopted?.invoke(adopted) }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                                .then(if (result == state.results.first()) Modifier.focusRequester(first) else Modifier)
                                .testTag("subtitle-download:result:${result.id}")
                        ) {
                            Column {
                                androidx.tv.material3.Text(RemoteSubtitlePolicy.title(result), maxLines = 2)
                                androidx.tv.material3.Text(
                                    subtitleResultDetails(resources, result),
                                    style = androidx.tv.material3.MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(state.phase, state.results.size, choosingLanguage) {
        withFrameNanos { }
        // While downloading, focus stays on the chosen row.
        if (state.phase == RemoteSubtitlePhase.Downloading) return@LaunchedEffect
        // When the focused row leaves (Results to Added), Compose had already parked focus on Close without its focused
        // look, and asking again changed nothing (OBSERVED on the emulator, leg 163). Let go first, then take it.
        if (state.finished()) focusManager.clearFocus(force = true)
        runCatching { first.requestFocus() }
    }
}

/** SUB-DL on a TV title page: the same panel in a dialog (Back closes it). */
@Composable
internal fun TvSubtitleDownloadDialog(account: Account, item: app.pictureshow.core.MediaItem, onClose: () -> Unit) {
    val search = rememberRemoteSubtitleSearch(account) ?: return
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.width(640.dp).background(Color(0xFF1B2130), RoundedCornerShape(16.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            androidx.tv.material3.Text(
                stringResource(R.string.subtitle_download_title),
                style = androidx.tv.material3.MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )
            androidx.tv.material3.Text(item.name, color = Color(0xFFB9C1D6))
            TvSubtitleDownloadContent(
                search,
                RemoteSubtitleTarget(item.id, item.type, subtitleDownloadOffered(item.type)),
                onAdopted = null,
                onClose = onClose
            )
        }
    }
}

/** SUB-DL on a phone: the same flow as rows in a sheet or dialog. */
@Composable
internal fun TouchSubtitleDownloadContent(
    search: RemoteSubtitleSearch,
    target: RemoteSubtitleTarget,
    onAdopted: ((AdoptedSubtitle) -> Unit)?,
    onClose: () -> Unit
) {
    val state by search.state.collectAsState()
    val resources = LocalContext.current.resources
    var choosingLanguage by remember { mutableStateOf(false) }
    LaunchedEffect(search, target) { search.open(target) }
    Column(Modifier.fillMaxWidth().testTag("subtitle-download"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        subtitleStatusText(resources, state, onAdopted != null)?.let {
            androidx.compose.material3.Text(
                it,
                Modifier.padding(vertical = 8.dp).testTag("subtitle-download:status").semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        if (state.phase in setOf(RemoteSubtitlePhase.Searching, RemoteSubtitlePhase.Downloading, RemoteSubtitlePhase.Checking)) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        when {
            state.finished() -> androidx.compose.material3.TextButton(
                onClick = onClose,
                modifier = Modifier.heightIn(min = 48.dp).testTag("subtitle-download:close")
            ) { androidx.compose.material3.Text(stringResource(R.string.subtitle_download_close)) }
            state.phase == RemoteSubtitlePhase.Checking -> Unit
            choosingLanguage -> {
                androidx.compose.material3.Text(
                    stringResource(R.string.subtitle_download_choose_language),
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                )
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(state.languages, key = { it.threeLetter.orEmpty() }) { culture ->
                        val code = culture.threeLetter.orEmpty().lowercase()
                        TouchRadioRow(code == state.language, Modifier.testTag("subtitle-download:language:$code"), onClick = {
                            choosingLanguage = false
                            search.language(code)
                        }) { androidx.compose.material3.Text(culture.displayName, Modifier.weight(1f)) }
                    }
                }
            }
            else -> {
                androidx.compose.material3.OutlinedButton(
                    onClick = { choosingLanguage = true },
                    enabled = state.phase != RemoteSubtitlePhase.Downloading,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("subtitle-download:language")
                ) { androidx.compose.material3.Text(stringResource(R.string.subtitle_download_language, state.languageName)) }
                if (state.retryable()) {
                    androidx.compose.material3.TextButton(
                        onClick = search::retry,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("subtitle-download:retry")
                    ) { androidx.compose.material3.Text(stringResource(R.string.subtitle_download_retry)) }
                }
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(state.results, key = { it.id }) { result ->
                        androidx.compose.material3.ListItem(
                            headlineContent = { androidx.compose.material3.Text(RemoteSubtitlePolicy.title(result), maxLines = 2) },
                            supportingContent = { androidx.compose.material3.Text(subtitleResultDetails(resources, result)) },
                            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth()
                                .clickable(enabled = state.phase == RemoteSubtitlePhase.Results) {
                                    search.download(result) { adopted -> onAdopted?.invoke(adopted) }
                                }
                                .testTag("subtitle-download:result:${result.id}")
                        )
                    }
                }
            }
        }
    }
}

/** SUB-DL on a phone title page. */
@Composable
internal fun TouchSubtitleDownloadDialog(account: Account, item: app.pictureshow.core.MediaItem, onClose: () -> Unit) {
    val search = rememberRemoteSubtitleSearch(account) ?: return
    val state by search.state.collectAsState()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {},
        // A visible way out while searching or choosing; once finished the content shows its own Close.
        dismissButton = if (state.finished()) {
            null
        } else {
            {
                androidx.compose.material3.TextButton(onClick = onClose, modifier = Modifier.testTag("subtitle-download:dismiss")) {
                    androidx.compose.material3.Text(stringResource(R.string.subtitle_download_close))
                }
            }
        },
        title = { androidx.compose.material3.Text(stringResource(R.string.subtitle_download_title)) },
        text = {
            TouchSubtitleDownloadContent(
                search,
                RemoteSubtitleTarget(item.id, item.type, subtitleDownloadOffered(item.type)),
                onAdopted = null,
                onClose = onClose
            )
        }
    )
}
