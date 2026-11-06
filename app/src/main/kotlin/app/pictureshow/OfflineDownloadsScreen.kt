/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.Account
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.OfflineResourceRole
import app.pictureshow.core.offline.watchedPreview
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private val OfflineMuted = Color(0xFFB9C1D6)
private val OfflinePage = Color(0xFF10131C)
private val OfflineCard = Color(0xFF1F2433)
private val OfflineSelected = Color(0xFF9DB7FF)

/** Home's error coral, so anything that can't be undone or went wrong reads the same across the app. */
internal val OfflineErrorTone = Color(0xFFFFCCB8)

@Composable
private fun offlineDestructiveColors() = ButtonDefaults.colors(
    contentColor = OfflineErrorTone,
    focusedContainerColor = OfflineErrorTone,
    focusedContentColor = OfflinePage
)

internal fun offlineActionLabel(action: OfflineAction, tv: Boolean = false): String = when (action) {
    OfflineAction.Play -> "Play"
    OfflineAction.Pause -> "Pause"
    OfflineAction.Resume -> "Resume"
    OfflineAction.Retry -> "Retry"
    OfflineAction.Redownload -> "Download again"
    // A TV's only metered link is something like a hotspot; it has no mobile data to speak of.
    OfflineAction.AllowCellular -> if (tv) "Use this connection" else "Use mobile data"
    OfflineAction.Delete -> "Delete"
}

internal fun offlineConflictText(title: String): String =
    "$title: your place changed on the server while you were offline, so the server's place was kept."

@Composable
internal fun offlineArtwork(accountKey: String, entry: OfflineEntry): File? {
    val engine: OfflineEngine = koinInject()
    val art = entry.resources.firstOrNull { it.role == OfflineResourceRole.artwork && it.complete } ?: return null
    return engine.resourceFile(accountKey, entry, art).takeIf(File::exists)
}

/** The spacer, the heading and the buttons. */
private const val OFFLINE_TV_ITEMS_ABOVE_NOTICES = 3

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OfflineDownloadsTvScreen(
    account: Account,
    offline: Boolean,
    onPlay: (OfflineEntry) -> Unit,
    onStorage: () -> Unit,
    onOnline: () -> Unit,
    onBack: () -> Unit
) {
    // Survive a trip to the player or to Storage, so Back returns the remote to where it left from.
    var returnToRow by rememberSaveable { mutableStateOf<String?>(null) }
    var returnToStorage by rememberSaveable { mutableStateOf(false) }
    val view = rememberOfflineDownloads(account, tv = true) { entry ->
        returnToRow = entry.id
        returnToStorage = false
        onPlay(entry)
    }
    val controller = view.controller
    val sections = view.sections
    val storage = remember { FocusRequester() }
    val firstRow = remember { FocusRequester() }
    val conflictFocus = remember { FocusRequester() }
    val passwordNeeded = view.library.passwordNeeded()
    val list = rememberLazyListState()
    val conflictIds = view.conflicts.keys.toList()
    // Where the remote goes after a press that takes the focused control away: a delete, or a notice's OK.
    var moveTo by remember { mutableStateOf<OfflineFirstFocus?>(null) }
    var afterMove by remember { mutableStateOf<(() -> Unit)?>(null) }
    val moved = moveTo?.takeIf { it !is OfflineFirstFocus.Row || offlineRowPosition(sections, it.entryId) != null }
    val target = when {
        passwordNeeded -> OfflineFirstFocus.Password
        moved != null -> moved
        returnToStorage -> OfflineFirstFocus.Storage
        sections.ready.any { it.entry.id == returnToRow } -> OfflineFirstFocus.Row(returnToRow.orEmpty(), OfflineAction.Play)
        else -> offlineFirstFocus(false, sections, conflictIds)
    }
    // The first key press hands focus to the viewer: a list that changes afterwards mustn't pull the remote away.
    var viewerMoved by remember { mutableStateOf(false) }
    // A focused control that goes away takes focus with it, and that's the one time focus moves again on its own.
    var refocus by remember { mutableIntStateOf(0) }
    var handled by remember { mutableIntStateOf(0) }
    var askedForPassword by remember { mutableStateOf(false) }
    LaunchedEffect(passwordNeeded) {
        if (askedForPassword && !passwordNeeded) refocus++
        askedForPassword = passwordNeeded
    }
    BackHandler(controller.confirm != null) { controller.confirm = null }
    BackHandler(controller.confirm == null, onBack)
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().background(OfflinePage).padding(horizontal = 48.dp).semantics {
            testTagsAsResourceId = true
        }.onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown) viewerMoved = true
            false
        },
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "top") { Box(Modifier.height(24.dp)) }
        item(key = "heading") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Downloads", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                // An unreadable list has nothing to measure, and "0 bytes used" beside "nothing was deleted" reads as a lie.
                if (!view.library.recoverable) {
                    Text(
                        sections.capBytes?.let { "${offlineBytes(sections.usedBytes)} of ${offlineBytes(it)} used" }
                            ?: "${offlineBytes(sections.usedBytes)} used",
                        color = OfflineMuted
                    )
                }
                if (view.network == OfflineNetwork.none) Text("No connection. Everything saved below still plays.", color = OfflineMuted)
            }
        }
        item(key = "buttons") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        returnToStorage = true
                        onStorage()
                    },
                    modifier = Modifier.focusRequester(storage).testTag("downloads:storage")
                ) {
                    Text("Storage and Wi-Fi")
                }
                if (offline) Button(onClick = onOnline, modifier = Modifier.testTag("downloads:online")) { Text("Open the library") }
            }
        }
        if (view.library.recoverable) {
            item(key = "recoverable") { Text(OFFLINE_RECOVERABLE_TEXT, color = OfflineMuted) }
        }
        if (passwordNeeded) item(key = "password") { OfflinePasswordTv(controller, initial = !viewerMoved) }
        view.conflicts.keys.forEach { itemId ->
            item(key = "conflict:$itemId") {
                OfflineConflictTv(
                    offlineConflictText(view.conflictTitle(itemId)),
                    focus = conflictFocus.takeIf { target == OfflineFirstFocus.Conflict(itemId) }
                ) {
                    moveTo = offlineFirstFocus(false, sections, conflictIds - itemId)
                    refocus++
                    controller.dismissConflict(itemId)
                }
            }
        }
        if (sections.isEmpty && !view.library.recoverable) {
            item(key = "empty") { Text("Nothing downloaded yet. Open a movie or an episode and choose Download.", color = OfflineMuted) }
        }
        sections.ordered.forEach { (title, rows) ->
            item(key = "section:$title") {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }.padding(top = 10.dp))
            }
            items(rows, key = { it.entry.id }) { row ->
                OfflineRowTv(
                    account,
                    row,
                    controller,
                    focus = firstRow.takeIf { target is OfflineFirstFocus.Row && target.entryId == row.entry.id },
                    focusAction = (target as? OfflineFirstFocus.Row)?.action,
                    onStorage = {
                        returnToStorage = true
                        onStorage()
                    },
                    // The next row takes the remote before this one changes, so focus never falls to the top of the page.
                    onDeleteConfirmed = { delete ->
                        moveTo = offlineFocusAfterDelete(sections, row.entry.id)
                        afterMove = delete
                        refocus++
                    }
                )
            }
        }
        item(key = "end") { Box(Modifier.height(32.dp)) }
    }
    LaunchedEffect(target, refocus) {
        if (viewerMoved && refocus == handled) return@LaunchedEffect
        withFrameNanos { }
        handled = refocus
        // The form's field takes its own focus.
        if (target != OfflineFirstFocus.Password) {
            // Only what's on screen can take focus, and a notice shows whole, from the top of the page.
            val rowsStart = OFFLINE_TV_ITEMS_ABOVE_NOTICES +
                (if (view.library.recoverable) 1 else 0) +
                (if (passwordNeeded) 1 else 0) +
                conflictIds.size +
                (if (sections.isEmpty && !view.library.recoverable) 1 else 0)
            when (val aim = target) {
                is OfflineFirstFocus.Row -> offlineRowPosition(sections, aim.entryId)?.let { position ->
                    val index = rowsStart + position
                    if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                        list.scrollToItem(index)
                        withFrameNanos { }
                    }
                }
                is OfflineFirstFocus.Conflict -> if (list.firstVisibleItemIndex != 0 || list.firstVisibleItemScrollOffset != 0) {
                    list.scrollToItem(0)
                    withFrameNanos { }
                }
                else -> Unit
            }
            val requester = when (target) {
                is OfflineFirstFocus.Row -> firstRow
                is OfflineFirstFocus.Conflict -> conflictFocus
                else -> storage
            }
            if (!runCatching { requester.requestFocus() }.getOrDefault(false)) runCatching { storage.requestFocus() }
        }
        afterMove?.invoke()
        afterMove = null
    }
}

/** The card and the OK beside its sentence, so the answer is where the eyes already are. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OfflineConflictTv(text: String, focus: FocusRequester? = null, onDismiss: () -> Unit) {
    val card = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Row(
        Modifier
            .bringIntoViewRequester(card)
            .background(OfflineCard, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, modifier = Modifier.weight(1f, fill = false).testTag("downloads:conflict-text"))
        Button(
            onClick = onDismiss,
            modifier = Modifier
                .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                // Arriving from below shows the sentence too, not only the button that answers it.
                .onFocusChanged { if (it.isFocused) scope.launch { card.bringIntoView() } }
                .testTag("downloads:conflict-ok")
        ) { Text("OK") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OfflineRowTv(
    account: Account,
    row: OfflineRow,
    controller: OfflineDownloadsController,
    focus: FocusRequester? = null,
    focusAction: OfflineAction? = null,
    onStorage: () -> Unit = {},
    onDeleteConfirmed: (delete: () -> Unit) -> Unit = { it() }
) {
    val confirming = (controller.confirm as? OfflineConfirm.One)?.row?.entry?.id == row.entry.id
    val deleteFocus = remember { FocusRequester() }
    // Set while asking, so every way out of the question but Delete itself (Cancel, Back) returns here.
    var returnToDelete by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().testTag("downloads:row:${row.entry.id}")) {
        val art = offlineArtwork(account.key, row.entry)
        Box(Modifier.size(width = 80.dp, height = 120.dp).background(OfflineCard)) {
            if (art != null) AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
            row.subtitle?.let { Text(it, color = OfflineMuted, style = MaterialTheme.typography.bodyMedium) }
            Text(
                row.status,
                color = if (row.statusIsError) OfflineErrorTone else Color.Unspecified,
                modifier = Modifier.testTag("downloads:status:${row.entry.id}")
            )
            row.fraction?.let { fraction ->
                Box(Modifier.fillMaxWidth(0.6f).height(6.dp).background(Color(0xFF2B3144))) {
                    Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(OfflineSelected))
                }
            }
            if (row.indeterminate) {
                // A still line reads as stuck from a couch. Explicit colours: this is compose-material3, outside the TV theme.
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(0.6f).height(6.dp).testTag("downloads:working:${row.entry.id}"),
                    color = OfflineSelected,
                    trackColor = Color(0xFF2B3144)
                )
            }
            row.entry.warning?.let { Text(it, color = OfflineMuted) }
            row.notDownloadedSummary?.let { Text(it, color = OfflineMuted, style = MaterialTheme.typography.bodyMedium) }
            if (confirming) {
                val cancel = remember { FocusRequester() }
                Text(
                    if (row.entry.job.phase ==
                        app.pictureshow.core.offline.OfflinePhase.ready
                    ) {
                        "Delete this download from this device?"
                    } else {
                        "Stop and delete this download?"
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        controller.confirm = null
                    }, modifier = Modifier.focusRequester(cancel).testTag("downloads:cancel")) { Text("Cancel") }
                    Button(
                        onClick = {
                            returnToDelete = false
                            onDeleteConfirmed { controller.confirmed(emptyList()) }
                        },
                        colors = offlineDestructiveColors(),
                        modifier = Modifier.testTag("downloads:confirm")
                    ) { Text("Delete") }
                }
                LaunchedEffect(Unit) {
                    returnToDelete = true
                    runCatching { cancel.requestFocus() }
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.actions.forEach { action ->
                        Button(
                            onClick = { controller.act(row, action) },
                            modifier = Modifier
                                .then(if (focus != null && action == focusAction) Modifier.focusRequester(focus) else Modifier)
                                .then(if (action == OfflineAction.Delete) Modifier.focusRequester(deleteFocus) else Modifier)
                                .testTag("downloads:${action.name.lowercase()}:${row.entry.id}")
                        ) { Text(offlineActionLabel(action, tv = true)) }
                    }
                    if (offlineOffersStorage(row.entry.job)) {
                        Button(onClick = onStorage, modifier = Modifier.testTag("downloads:open-storage:${row.entry.id}")) {
                            Text("Storage and Wi-Fi")
                        }
                    }
                }
                LaunchedEffect(returnToDelete) {
                    if (returnToDelete) {
                        withFrameNanos { }
                        runCatching { deleteFocus.requestFocus() }
                        // Last: changing this restarts the effect, and earlier that cancelled it before focus moved.
                        returnToDelete = false
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflinePasswordTv(controller: OfflineDownloadsController, initial: Boolean) {
    var password by remember { mutableStateOf("") }
    val submit = { if (password.isNotEmpty()) controller.providePassword(password) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(OFFLINE_PASSWORD_REASON)
        RemoteField("Password", password, { password = it }, initial = initial, password = true, onDone = submit)
        Button(onClick = submit, enabled = password.isNotEmpty(), modifier = Modifier.testTag("downloads:password-continue")) {
            Text("Continue")
        }
        if (controller.passwordFailed) Text(OFFLINE_PASSWORD_FAILED, color = OfflineErrorTone)
    }
}

/** Selected reads at a glance from across the room, not just by a small tick. */
@Composable
private fun offlineChoiceColors(selected: Boolean) =
    if (selected) ButtonDefaults.colors(containerColor = OfflineSelected, contentColor = OfflinePage) else ButtonDefaults.colors()

@Composable
internal fun OfflineStorageTvScreen(account: Account, onBack: () -> Unit) {
    val view = rememberOfflineDownloads(account, tv = true) {}
    val controller = view.controller
    val engine: OfflineEngine = koinInject()
    val manifest = view.library.manifest
    val first = remember { FocusRequester() }
    val deviceBytes = android.os.StatFs(engine.files.root.parentFile!!.path).totalBytes
    val steps = offlineCapChoices(offlineCapSteps(deviceBytes), manifest.budget.capBytes)
    var confirmAll by remember { mutableStateOf(false) }
    val played = rememberOfflineWatched(account, manifest.entries)
    val preview = manifest.watchedPreview(played)
    var showPreview by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    LazyColumn(
        Modifier.fillMaxSize().background(OfflinePage).padding(horizontal = 48.dp).semantics { testTagsAsResourceId = true },
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "top") { Box(Modifier.height(24.dp)) }
        item(key = "heading") {
            Text("Storage and Wi-Fi", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        }
        item(key = "limit") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Limit for this viewer: ${manifest.budget.capBytes?.let(
                        ::offlineBytes
                    ) ?: "not set. Choose one to download."}. Used: ${offlineBytes(manifest.budget.usedBytes)}."
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // First focus sits on what's set, so one OK on arrival changes nothing.
                    val focused = manifest.budget.capBytes ?: steps.first()
                    steps.forEach { step ->
                        Button(
                            onClick = { controller.setCap(step) },
                            colors = offlineChoiceColors(step == manifest.budget.capBytes),
                            modifier = (if (step == focused) Modifier.focusRequester(first) else Modifier).testTag("storage:cap:$step")
                        ) { Text((if (step == manifest.budget.capBytes) "✓ " else "") + offlineBytes(step)) }
                    }
                }
            }
        }
        item(key = "wifi") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (manifest.wifiOnly) {
                        "Downloads use Wi-Fi or Ethernet only."
                    } else {
                        "Downloads can use any connection, including a hotspot."
                    }
                )
                Button(onClick = { controller.setWifiOnly(!manifest.wifiOnly) }, modifier = Modifier.testTag("storage:wifi")) {
                    Text(if (manifest.wifiOnly) "Allow any connection" else "Use Wi-Fi or Ethernet only")
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
                        }, colors = offlineDestructiveColors()) { Text("Remove") }
                        LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
                    }
                } else {
                    Button(onClick = { showPreview = true }) { Text("Review what would go") }
                }
            }
        }
        item(key = "all") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (manifest.entries.isNotEmpty()) {
                    Text(
                        "All downloads",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() }.padding(top = 10.dp)
                    )
                }
                if (confirmAll) {
                    Text("Delete all ${manifest.entries.size} downloads for this viewer?")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val cancel = remember { FocusRequester() }
                        Button(onClick = {
                            confirmAll = false
                        }, modifier = Modifier.focusRequester(cancel).testTag("storage:cancel-all")) { Text("Cancel") }
                        Button(onClick = {
                            confirmAll = false
                            controller.deleteEntries(manifest.entries.map(OfflineEntry::id))
                        }, colors = offlineDestructiveColors(), modifier = Modifier.testTag("storage:confirm-all")) { Text("Delete all") }
                        LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
                    }
                } else if (manifest.entries.isNotEmpty()) {
                    Button(onClick = {
                        confirmAll = true
                    }, colors = offlineDestructiveColors(), modifier = Modifier.testTag("storage:delete-all")) {
                        Text("Delete all downloads")
                    }
                }
            }
        }
        item(key = "back") { Button(onClick = onBack) { Text("Back") } }
        item(key = "end") { Box(Modifier.height(32.dp)) }
    }
    LaunchedEffect(Unit) {
        // The list's items attach during layout, so a request before the first frame finds nothing and focus is simply lost.
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
}

@Composable
internal fun OfflineDownloadTvScreen(
    account: Account,
    itemId: String,
    seriesId: String?,
    seasonId: String?,
    title: String,
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    OfflineDownloadTvContent(rememberOfflinePreview(account, itemId, seriesId, seasonId, title), title, onDone, onBack)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OfflineDownloadTvContent(controller: OfflinePreviewController, title: String, onDone: () -> Unit, onBack: () -> Unit) {
    val first = remember { FocusRequester() }
    val fallback = remember { FocusRequester() }
    BackHandler(onBack = onBack)
    LazyColumn(
        Modifier.fillMaxSize().background(OfflinePage).padding(horizontal = 48.dp).semantics { testTagsAsResourceId = true },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "top") { Box(Modifier.height(24.dp)) }
        item(key = "heading") {
            Text("Download $title", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        }
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
                val canStart = !controller.started &&
                    controller.selectedItems().isNotEmpty() &&
                    state.preview.choices.any { it.preset == controller.preset && it.available }
                item(key = "quality") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Quality", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.preview.choices.forEach { choice ->
                                val selected = controller.preset == choice.preset && choice.available
                                Button(
                                    onClick = { controller.preset = choice.preset },
                                    enabled = choice.available,
                                    colors = offlineChoiceColors(selected),
                                    modifier = Modifier.testTag("download:preset:${choice.preset}")
                                ) {
                                    Text(
                                        (if (selected) "✓ " else "") +
                                            offlinePresetLabel(choice.preset) +
                                            if (choice.available) "" else ". " + offlinePresetUnavailable(choice.reason)
                                    )
                                }
                            }
                        }
                    }
                }
                // Download sits above the long lists: what's chosen is already a good answer, so one OK starts it.
                item(key = "start") {
                    val estimate = state.preview.choices.firstOrNull { it.preset == controller.preset }?.estimateBytes
                    val count = controller.selectedItems().size.coerceAtLeast(1)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { controller.start(onDone) },
                            enabled = canStart,
                            modifier = Modifier.focusRequester(first).testTag("download:start")
                        ) { Text("Download") }
                        Text(
                            offlineSizeText(controller.selectedItems(), controller.preset) {
                                estimate?.let { total ->
                                    total /
                                        state.preview.items.size.coerceAtLeast(count)
                                }
                            },
                            modifier = Modifier.testTag("download:size")
                        )
                    }
                    LaunchedEffect(Unit) {
                        withFrameNanos { }
                        // Nothing to start can't take focus: the Wi-Fi control below is the only thing left to reach.
                        runCatching { (if (canStart) first else fallback).requestFocus() }
                    }
                }
                item(key = "cellular") {
                    Button(
                        onClick = { controller.cellular = !controller.cellular },
                        modifier = Modifier.focusRequester(fallback).testTag("download:cellular")
                    ) {
                        Text(if (controller.cellular) "Wi-Fi or Ethernet only: Off" else "Wi-Fi or Ethernet only: On")
                    }
                }
                if (state.preview.items.size > 1) {
                    item(key = "episodes") { Text("Episodes", style = MaterialTheme.typography.titleMedium) }
                    items(state.preview.items, key = { "episode:" + it.id }) { episode ->
                        Button(onClick = {
                            controller.toggleEpisode(episode.id)
                        }, modifier = Modifier.testTag("download:episode:${episode.id}")) {
                            Text(
                                (if (episode.id in controller.episodes) "✓ " else "") + (episode.episodeNumber?.let { "$it. " } ?: "") +
                                    episode.name
                            )
                        }
                    }
                }
                if (controller.preset == OfflinePreset.original) {
                    item(key = "tracks") { Text("All audio and subtitles in the file stay with it.", color = OfflineMuted) }
                    controller.warning?.let { warning -> item(key = "warning") { Text(warning) } }
                } else {
                    item(key = "audio") { Text("Audio, up to $OFFLINE_MAX_CONVERTED_AUDIO", style = MaterialTheme.typography.titleMedium) }
                    items(controller.sourceTracks("Audio"), key = { "audio:" + it.index }) { track ->
                        Button(onClick = {
                            controller.toggleAudio(track.index)
                        }, modifier = Modifier.testTag("download:audio:${track.index}")) {
                            Text(
                                (if (track.index in controller.audio) "✓ " else "") +
                                    (track.displayTitle ?: track.language ?: "Track ${track.index}")
                            )
                        }
                    }
                    item(key = "subs") {
                        Text("Subtitles, up to $OFFLINE_MAX_CONVERTED_SUBTITLES", style = MaterialTheme.typography.titleMedium)
                    }
                    items(controller.sourceTracks("Subtitle"), key = { "subtitle:" + it.index }) { track ->
                        Button(
                            onClick = { controller.toggleSubtitle(track.index) },
                            enabled = track.isText,
                            modifier = Modifier.testTag("download:subtitle:${track.index}")
                        ) {
                            Text(
                                (if (track.index in controller.subtitles) "✓ " else "") +
                                    (track.displayTitle ?: track.language ?: "Track ${track.index}") +
                                    if (track.isText) "" else ". Picture subtitles can't be converted."
                            )
                        }
                    }
                }
            }
        }
        item(key = "end") { Box(Modifier.height(32.dp)) }
    }
}
