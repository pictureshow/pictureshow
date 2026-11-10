/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.imageUrl
import app.pictureshow.core.livetv.LiveChannel
import app.pictureshow.core.livetv.LiveProgram
import app.pictureshow.core.livetv.LiveSchedule
import app.pictureshow.core.livetv.canTune
import app.pictureshow.core.livetv.editSchedule
import app.pictureshow.core.livetv.recordingStatus
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val LiveBackground = Color(0xFF10131C)
private val LiveMuted = Color(0xFFC3C9D8)

/** Identical destination/actions for TV and touch; platform buttons keep their native focus/reader behavior. */
@Composable
internal fun LiveTvScreen(
    vm: LiveTvViewModel,
    account: Account,
    tv: Boolean,
    onPlay: (MediaItem) -> Unit,
    onRecording: (MediaItem) -> Unit,
    onBack: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("Channels") }
    var search by rememberSaveable { mutableStateOf("") }
    var favorites by rememberSaveable { mutableStateOf(false) }
    var linear by rememberSaveable { mutableStateOf(false) }
    var selectedProgram by remember { mutableStateOf<LiveProgram?>(null) }
    var selectedSchedule by remember { mutableStateOf<LiveSchedule?>(null) }
    var selectedSeries by remember { mutableStateOf(false) }
    var deleteRecording by remember { mutableStateOf<MediaItem?>(null) }
    var cancelling by remember { mutableStateOf(false) }
    var returnFocus by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreFocus by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val guideScroll = rememberScrollState()
    var chosenTime by rememberSaveable { mutableStateOf("") }
    var timeError by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    fun closeDetail() {
        selectedProgram = null
        selectedSchedule = null
        deleteRecording = null
        cancelling = false
        vm.dismissDraft()
        restoreFocus = true
    }
    LaunchedEffect(state.policy?.access) { if (state.policy?.access == false) closeDetail() }
    LaunchedEffect(state.writeVersion) { if (state.writeVersion > 0 && state.lastWriteOutcome == "ok") closeDetail() }
    LaunchedEffect(state.channels, state.policy) {
        if (selectedProgram?.channelId?.let { id -> state.channels.none { it.id == id } } == true ||
            state.policy?.manage == false &&
            (selectedSchedule != null || state.draft != null)
        ) {
            closeDetail()
        }
    }
    val draft = state.draft
    val schedule = selectedSchedule
    val program = selectedProgram
    val recording = deleteRecording
    BackHandler(enabled = draft != null || schedule != null || program != null || recording != null) { closeDetail() }
    val title = when {
        draft != null -> if (state.draftSeries) "Record series" else "Record once"
        schedule != null -> if (cancelling) "Cancel ${if (selectedSeries) "series" else "recording"}?" else "Recording schedule"
        program != null -> program.name
        recording != null -> "Delete recording?"
        else -> "Live TV"
    }
    val canAct = state.actionsAvailable(now)
    val detail = draft != null || schedule != null || program != null || recording != null
    LazyColumn(
        state = if (detail) rememberLazyListState() else list,
        modifier = Modifier.fillMaxSize().background(LiveBackground).testTag(if (tv) "live:tv" else "live:touch"),
        contentPadding = PaddingValues(horizontal = if (tv) 48.dp else 20.dp, vertical = if (tv) 32.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item("heading") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LiveText(title, tv, heading = true)
                if (state.loading || state.writing) LiveText(if (state.writing) "Saving…" else "Refreshing Live TV…", tv)
                state.error?.let { LiveText(it, tv, warning = true) }
                state.notice?.let { LiveText(it, tv) }
                if (detail) {
                    LiveButton(
                        if (recording != null || cancelling || draft != null) "Cancel" else "Back",
                        tv,
                        initial = true,
                        enabled = !state.writing,
                        onClick = ::closeDetail
                    )
                } else {
                    LiveButton("Back", tv, onClick = onBack)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("Channels", "Guide", "Recordings", "Schedules").forEach { next ->
                            LiveButton(next, tv, selected = tab == next, enabled = !state.writing) { tab = next }
                        }
                    }
                    state.fetchedAt?.let {
                        LiveText("${if (state.offline) "Offline · last loaded" else "Updated"} ${liveTime(it)}", tv)
                    }
                    if (state.offline) LiveText("Saved guide is for reference. Reconnect before scheduling or tuning.", tv, warning = true)
                    LiveButton("Refresh", tv, enabled = !state.loading && !state.writing) { vm.refresh() }
                }
            }
        }
        if (draft != null) {
            item("form") {
                LiveScheduleEditor(draft, state.draftSeries, tv, state.writing, false) { vm.save(it, state.draftSeries, false) }
            }
        } else if (schedule != null) {
            if (cancelling) {
                item("cancel-scope") {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        LiveText(
                            if (selectedSeries) {
                                "Cancel this series rule and its future recordings: ${schedule.name}. Completed recordings are retained."
                            } else {
                                "Cancel only this occurrence: ${schedule.name}, ${liveDates(
                                    schedule.startDate,
                                    schedule.endDate
                                )}. Completed recordings are retained."
                            },
                            tv
                        )
                        LiveButton(
                            if (selectedSeries) "Confirm cancel series" else "Confirm cancel occurrence",
                            tv,
                            enabled = !state.writing
                        ) {
                            vm.cancel(schedule, selectedSeries)
                        }
                    }
                }
            } else {
                item("schedule-form") {
                    LiveScheduleEditor(schedule, selectedSeries, tv, state.writing, true) {
                        vm.save(it, selectedSeries, true)
                    }
                }
                item("status") {
                    LiveText(
                        "${recordingStatus(schedule.status)}${schedule.string("ErrorMessage")?.let {
                            ": $it"
                        }.orEmpty()}. Server status is authoritative; contact the administrator for tuner/storage errors.",
                        tv
                    )
                }
                item("cancel") {
                    LiveButton(if (selectedSeries) "Cancel series…" else "Cancel occurrence…", tv, enabled = !state.writing) {
                        cancelling =
                            true
                    }
                }
            }
        } else if (program != null) {
            item("program") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LiveText(liveDates(program.startDate, program.endDate), tv)
                    program.overview?.takeIf { it.isNotBlank() }?.let { LiveText(it, tv) }
                    val channel = state.channels.firstOrNull { it.id == program.channelId }
                    channel?.let { LiveText("${it.number.orEmpty()} ${it.name}", tv) }
                    LiveButton("Tune now", tv, enabled = canAct && channel != null && canTune(program, now)) {
                        channel?.let { onPlay(it.mediaItem()) }
                    }
                    if (!canTune(program, now)) LiveText("Tune is available only while this program is on air.", tv)
                    if (state.policy?.manage == true) {
                        LiveButton("Record once…", tv, enabled = canAct && !state.writing && liveInstant(program.endDate) > now) {
                            vm.defaults(program, false)
                        }
                        LiveButton(
                            "Record series…",
                            tv,
                            enabled =
                            canAct && !state.writing && program.isSeries && liveInstant(program.endDate) > now
                        ) {
                            vm.defaults(program, true)
                        }
                        if (!program.isSeries) LiveText("This program has no server series identity.", tv)
                    } else {
                        LiveText("This viewer can watch Live TV. Recording management requires server permission.", tv)
                    }
                }
            }
        } else if (recording != null) {
            item("delete-scope") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LiveText("Permanently delete the recorded file “${recording.name}”. Its recording schedule is a separate action.", tv)
                    LiveButton("Delete recorded file", tv, enabled = !state.writing) {
                        vm.delete(recording)
                    }
                }
            }
        } else if (state.policy?.access != false) {
            when (tab) {
                "Channels", "Guide" -> {
                    item("filters") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            LiveField("Search channels", search, tv) { search = it }
                            LiveButton(if (favorites) "Show all channels" else "Favorites only", tv) { favorites = !favorites }
                            if (tab == "Guide") {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    LiveButton("Earlier", tv, enabled = !state.loading) {
                                        vm.refresh(
                                            state.windowStart - LiveTvViewModel.WINDOW
                                        )
                                    }
                                    LiveButton("Now", tv, enabled = !state.loading) { vm.refresh(System.currentTimeMillis()) }
                                    LiveButton("Later", tv, enabled = !state.loading) {
                                        vm.refresh(
                                            state.windowStart + LiveTvViewModel.WINDOW
                                        )
                                    }
                                    if (tv) {
                                        LiveButton(if (linear) "Grid guide" else "Linear guide for screen readers", tv) {
                                            linear =
                                                !linear
                                        }
                                    }
                                }
                                LiveField("Guide date/time (ISO 8601 with UTC offset)", chosenTime, tv) { chosenTime = it }
                                LiveButton("Show date/time", tv, enabled = !state.loading) {
                                    val instant = runCatching { java.time.OffsetDateTime.parse(chosenTime).toInstant() }.getOrNull()
                                    if (instant ==
                                        null
                                    ) {
                                        timeError = "Enter a time such as 2026-11-10T20:00:00-05:00."
                                    } else {
                                        timeError = null
                                        vm.refresh(instant.toEpochMilli())
                                    }
                                }
                                timeError?.let { LiveText(it, tv, warning = true) }
                                LiveText("${liveTime(state.windowStart)} – ${liveTime(state.windowStart + LiveTvViewModel.WINDOW)}", tv)
                            }
                            if (!state.loading && state.channels.isEmpty() && state.error == null) {
                                LiveText(
                                    if (state.tunerEnabled ==
                                        false
                                    ) {
                                        "No tuner configured. Ask your server administrator to add an M3U or HDHomeRun tuner in Jellyfin Dashboard → Live TV."
                                    } else {
                                        "No channels available. Ask your server administrator to configure a tuner in Jellyfin Dashboard → Live TV. No tuner hardware is needed on this device."
                                    },
                                    tv
                                )
                            }
                            if (!state.loading &&
                                state.channels.isNotEmpty() &&
                                state.programs.isEmpty()
                            ) {
                                LiveText(
                                    "No guide data for this time. Channels can still be tuned. An administrator can add an XMLTV guide in Jellyfin Dashboard → Live TV.",
                                    tv
                                )
                            }
                        }
                    }
                    val channels = state.channels.filter {
                        (!favorites || it.userData.isFavorite) &&
                            (search.isBlank() || it.name.contains(search, true) || it.number.orEmpty().contains(search, true))
                    }
                    items(channels, key = { "channel:${it.id}" }) { channel ->
                        val programs = state.programs.filter { it.channelId == channel.id }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                imageUrl(account.server, channel.mediaItem(), 96)?.let { url ->
                                    AsyncImage(url, null, Modifier.size(48.dp))
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LiveButton(
                                        "${channel.number.orEmpty()} ${channel.name}",
                                        tv,
                                        focusKey = "c:${channel.id}",
                                        restore = restoreFocus && returnFocus == "c:${channel.id}",
                                        onFocused = {
                                            returnFocus = "c:${channel.id}"
                                            restoreFocus = false
                                        },
                                        enabled = canAct
                                    ) { onPlay(channel.mediaItem()) }
                                    if (state.policy?.userData ==
                                        true
                                    ) {
                                        LiveButton(
                                            if (channel.userData.isFavorite) "Remove favorite" else "Add favorite",
                                            tv,
                                            enabled =
                                            !state.writing && canAct
                                        ) { vm.favorite(channel) }
                                    }
                                }
                            }
                            if (tab == "Channels") {
                                val current = programs.firstOrNull { canTune(it, now) }
                                val next = programs.firstOrNull { liveInstant(it.startDate) > now }
                                if (current ==
                                    null
                                ) {
                                    LiveText("No guide data", tv)
                                } else {
                                    LiveButton(
                                        "Now · ${current.name} · ${liveDates(current.startDate, current.endDate)}",
                                        tv,
                                        focusKey = "p:${current.id}",
                                        restore = restoreFocus && returnFocus == "p:${current.id}",
                                        onFocused = {
                                            returnFocus = "p:${current.id}"
                                            restoreFocus = false
                                        }
                                    ) { selectedProgram = current }
                                }
                                next?.let {
                                    LiveButton("Next · ${it.name} · ${liveDates(it.startDate, it.endDate)}", tv) {
                                        selectedProgram =
                                            it
                                    }
                                }
                            } else if (!tv || linear) {
                                if (programs.isEmpty()) LiveText("No guide data", tv)
                                programs.forEach { show ->
                                    LiveButton(
                                        "${show.name} · ${liveDates(show.startDate, show.endDate)}",
                                        tv,
                                        focusKey = "p:${show.id}",
                                        restore = restoreFocus && returnFocus == "p:${show.id}",
                                        onFocused = {
                                            returnFocus = "p:${show.id}"
                                            restoreFocus = false
                                        }
                                    ) { selectedProgram = show }
                                }
                            } else {
                                val cells = liveGuideCells(programs, state.windowStart, state.windowStart + LiveTvViewModel.WINDOW)
                                Column(
                                    Modifier.horizontalScroll(guideScroll).width(1080.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        (0..6).forEach { tick ->
                                            LiveText(
                                                java.time.format.DateTimeFormatter.ofPattern(
                                                    "HH:mm xxx"
                                                ).withZone(ZoneId.systemDefault()).format(
                                                    Instant.ofEpochMilli(
                                                        state.windowStart + tick * 30 * 60_000L
                                                    )
                                                ),
                                                tv
                                            )
                                        }
                                    }
                                    if (cells.isEmpty()) LiveText("No guide data", tv)
                                    (0..(cells.maxOfOrNull { it.lane } ?: -1)).forEach { lane ->
                                        Box(Modifier.fillMaxWidth().heightIn(min = 100.dp)) {
                                            cells.filter { it.lane == lane }.forEach { cell ->
                                                val show = cell.program
                                                LiveButton(
                                                    "${liveDates(show.startDate, show.endDate)}\n${show.name}",
                                                    tv,
                                                    modifier = Modifier.offset(x = (cell.startFraction * 1080).dp).width(
                                                        (
                                                            cell.widthFraction *
                                                                1080
                                                            ).dp
                                                    ),
                                                    focusKey = "p:${show.id}",
                                                    restore = restoreFocus && returnFocus == "p:${show.id}",
                                                    onFocused = {
                                                        returnFocus = "p:${show.id}"
                                                        restoreFocus = false
                                                    }
                                                ) {
                                                    selectedProgram =
                                                        show
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!state.loading && state.channels.isNotEmpty() && channels.isEmpty()) item { LiveText("No matching channels.", tv) }
                }
                "Recordings" -> {
                    if (!state.loading && state.recordings.isEmpty()) item { LiveText("No recordings available.", tv) }
                    items(state.recordings, key = { it.id }) { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            LiveText(
                                if (item.isInProgress) {
                                    "Recording now · seekable range depends on the stream"
                                } else {
                                    item.recordingStatus?.let(::recordingStatus)
                                        ?: "Recorded media"
                                },
                                tv
                            )
                            LiveButton(item.name, tv, enabled = canAct) { onRecording(item) }
                            LiveText("Open recording with normal playback, resume and track controls.", tv)
                            if (item.canDelete == true ||
                                state.policy?.deleteContent == true &&
                                item.canDelete != false
                            ) {
                                LiveButton("Delete recorded file…", tv) {
                                    deleteRecording =
                                        item
                                }
                            }
                        }
                    }
                }
                "Schedules" -> {
                    if (state.policy?.manage !=
                        true
                    ) {
                        item {
                            LiveText("Recording schedules require Live TV management permission. Ask your server administrator.", tv)
                        }
                    }
                    if (!state.loading &&
                        state.timers.isEmpty() &&
                        state.series.isEmpty() &&
                        state.policy?.manage == true
                    ) {
                        item { LiveText("No scheduled recordings.", tv) }
                    }
                    items(state.timers, key = { "timer:${it.id}" }) { timer ->
                        LiveButton(
                            "${timer.name} · ${recordingStatus(
                                timer.status
                            )}\n${liveDates(timer.startDate, timer.endDate)} · Channel ${timer.channelName ?: timer.channelId}",
                            tv,
                            enabled = canAct && !state.writing
                        ) {
                            selectedSchedule = timer
                            selectedSeries = false
                        }
                    }
                    items(state.series, key = { "series:${it.id}" }) { timer ->
                        LiveButton(
                            "Series · ${timer.name}\n${liveDates(timer.startDate, timer.endDate)} · ${recordingStatus(timer.status)}",
                            tv,
                            enabled = canAct && !state.writing
                        ) {
                            selectedSchedule = timer
                            selectedSeries = true
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveScheduleEditor(
    original: LiveSchedule,
    series: Boolean,
    tv: Boolean,
    busy: Boolean,
    existing: Boolean,
    onSave: (LiveSchedule) -> Unit
) {
    var edits by remember(original) { mutableStateOf<Map<String, JsonElement>>(emptyMap()) }
    val merged = JsonObject(original.fields + edits)
    var confirmed by remember(original) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        LiveText("${original.name} · ${liveDates(original.startDate, original.endDate)} · Channel ${original.channelId}", tv)
        LiveText(
            if (series) {
                "This schedules a server series rule. Completed recordings are retained when the rule is cancelled."
            } else {
                "This schedules one broadcast on the server. Closing Pictureshow does not cancel it."
            },
            tv
        )
        val numeric =
            listOf(
                "PrePaddingSeconds" to "Start padding (seconds)",
                "PostPaddingSeconds" to "End padding (seconds)",
                "Priority" to "Priority"
            ) +
                if (series) listOf("KeepUpTo" to "Keep at most (0 = server default)") else emptyList()
        numeric.forEach { (key, label) ->
            if (key in original.fields) {
                val value = merged[key]?.jsonPrimitive?.content.orEmpty()
                LiveField(label, value, tv, enabled = !confirmed && !busy) { text ->
                    text.toIntOrNull()?.takeIf { key == "Priority" || it >= 0 }?.let { edits = edits + (key to JsonPrimitive(it)) }
                }
            } else {
                LiveText("$label unavailable: the provider supplied no setting. Existing server values are preserved.", tv)
            }
        }
        if (series) {
            if ("KeepUntil" in original.fields) {
                val retention = merged["KeepUntil"]?.jsonPrimitive?.contentOrNull.orEmpty()
                LiveText("Retention: $retention", tv)
                listOf(
                    "UntilDeleted" to "Keep until deleted",
                    "UntilSpaceNeeded" to "Keep until space is needed",
                    "UntilWatched" to "Keep until watched"
                ).forEach { (value, label) ->
                    LiveButton(label, tv, selected = retention == value, enabled = !confirmed && !busy) {
                        edits =
                            edits + ("KeepUntil" to JsonPrimitive(value))
                    }
                }
                if (retention ==
                    "UntilDate"
                ) {
                    LiveText(
                        "The provider's existing keep-until date is preserved. " +
                            "Date editing is unavailable on this client.",
                        tv
                    )
                }
            } else {
                LiveText("Retention policy unavailable from this provider.", tv)
            }
            listOf(
                "RecordNewOnly" to "New episodes only",
                "RecordAnyTime" to "Any time",
                "RecordAnyChannel" to "Any channel",
                "SkipEpisodesInLibrary" to "Skip episodes already in library"
            ).forEach { (key, label) ->
                if (key in original.fields) {
                    val enabled = merged[key]?.jsonPrimitive?.booleanOrNull == true
                    LiveButton("$label: ${if (enabled) "On" else "Off"}", tv, enabled = !confirmed && !busy) {
                        edits =
                            edits + (key to JsonPrimitive(!enabled))
                    }
                } else {
                    LiveText("$label unavailable from this provider.", tv)
                }
            }
            if ("Days" in original.fields) {
                val days = (merged["Days"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday").forEach { day ->
                        LiveButton("$day: ${if (day in days) "On" else "Off"}", tv, enabled = !confirmed && !busy) {
                            val next = if (day in days) days - day else days + day
                            edits = edits + ("Days" to JsonArray(next.map(::JsonPrimitive)))
                        }
                    }
                }
            } else {
                LiveText("Recording days unavailable from this provider.", tv)
            }
        }
        LiveText(
            "Padding ${merged["PrePaddingSeconds"] ?: "provider default"} / ${merged["PostPaddingSeconds"] ?: "provider default"} seconds · Priority ${merged["Priority"] ?: "provider default"}",
            tv
        )
        if (confirmed) {
            LiveText(
                "Confirm ${if (series) "series rule" else "one recording"}: ${original.name}, ${liveDates(
                    original.startDate,
                    original.endDate
                )}, channel ${original.channelId}. Recording availability depends on the server tuner and storage.",
                tv
            )
            LiveButton(if (existing) "Confirm save changes" else "Confirm recording", tv, enabled = !busy) {
                onSave(editSchedule(original, JsonObject(edits), series))
            }
            LiveButton("Review settings", tv, enabled = !busy) { confirmed = false }
        } else {
            LiveButton("Review ${if (existing) "changes" else "recording"}", tv, enabled = !busy) { confirmed = true }
        }
    }
}

@Composable
private fun LiveText(text: String, tv: Boolean, heading: Boolean = false, warning: Boolean = false) {
    val modifier = Modifier.then(if (heading) Modifier.semantics { heading() } else Modifier)
    if (tv) {
        androidx.tv.material3.Text(
            text,
            modifier,
            color = if (warning) Color(0xFFFFCCB8) else LiveMuted,
            style = if (heading) {
                androidx.tv.material3.MaterialTheme.typography.headlineMedium
            } else {
                androidx.tv.material3.MaterialTheme.typography.bodyLarge
            }
        )
    } else {
        androidx.compose.material3.Text(
            text,
            modifier,
            color = if (warning) Color(0xFFFFCCB8) else LiveMuted,
            style = if (heading) {
                androidx.compose.material3.MaterialTheme.typography.headlineMedium
            } else {
                androidx.compose.material3.MaterialTheme.typography.bodyLarge
            }
        )
    }
}

@Composable
private fun LiveButton(
    label: String,
    tv: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    initial: Boolean = false,
    selected: Boolean = false,
    focusKey: String = label,
    restore: Boolean = false,
    onFocused: () -> Unit = {},
    onClick: () -> Unit
) {
    val focus = remember(focusKey) { FocusRequester() }
    LaunchedEffect(focus, initial, restore) {
        if (tv && (initial || restore)) {
            delay(40)
            runCatching { focus.requestFocus() }
        }
    }
    val target = modifier.heightIn(min = 48.dp).focusRequester(focus).onFocusChanged { if (it.isFocused) onFocused() }
        .semantics { if (selected) stateDescription = "Selected" }.testTag("live:$focusKey")
    if (tv) {
        androidx.tv.material3.Button(onClick, target, enabled = enabled) { androidx.tv.material3.Text(label) }
    } else {
        androidx.compose.material3.OutlinedButton(onClick, target, enabled = enabled) { androidx.compose.material3.Text(label) }
    }
}

@Composable
private fun LiveField(label: String, value: String, tv: Boolean, enabled: Boolean = true, onChange: (String) -> Unit) {
    if (tv) {
        RemoteField(label, value, onChange, enabled = enabled)
    } else {
        androidx.compose.material3.OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled, label = {
            androidx.compose.material3.Text(label)
        }, singleLine = true)
    }
}

private fun LiveChannel.mediaItem() = MediaItem(id, name, type = "TvChannel", imageTags = imageTags, userData = userData)
private fun liveInstant(value: String?): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)
private fun liveTime(value: Long): String =
    DateTimeFormatter.ofPattern("EEE d MMM HH:mm xxx").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
private fun liveDates(start: String?, end: String?): String = if (start == null ||
    end == null
) {
    "Time unavailable"
} else {
    runCatching { "${liveTime(liveInstant(start))} – ${liveTime(liveInstant(end))}" }.getOrDefault("Time unavailable")
}
