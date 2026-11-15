/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package app.pictureshow.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.pictureshow.core.*
import app.pictureshow.core.livetv.*
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

/** Native desktop tabs, list rows and time cells; the API owner never tunes on focus or selection. */
@Composable
fun DesktopLiveTvPage(c: DesktopController) {
    val state by c.liveTv.state.collectAsState()
    val account = c.state.value.account
    LiveTvPage(state, account, c.liveTv, c::tuneChannel, c::play, c::back, c::detail)
}

@Composable
internal fun LiveTvPage(s: DesktopLiveState, account: Account?, owner: DesktopLiveTv,
                        tune: (LiveChannel) -> Unit, play: (MediaItem) -> Unit, back: () -> Unit, detail: (MediaItem) -> Unit = {}) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    val fresh = s.actionsAvailable(now) && !s.writing
    val channels = s.channels.filter { (!s.favorites || it.userData.isFavorite) &&
        (s.search.isBlank() || it.name.contains(s.search, true) || it.number?.contains(s.search, true) == true) }
    var confirmation by remember { mutableStateOf<LiveConfirmation?>(null) }
    val scroll = rememberLazyListState()
    LaunchedEffect(s.loading, s.tab, s.selected == null, s.draft == null) {
        if (!s.loading && s.selected == null && s.draft == null) {
            withFrameNanos { }
            val key = s.focusKey?.takeIf { DesktopUiProbe.requestFocus.containsKey(it) } ?: when (s.tab) {
                DesktopLiveTab.Channels -> channels.firstOrNull()?.let { "live:channel:${it.id}" }
                DesktopLiveTab.Guide -> liveGuideInitialFocus(liveGuideCells(s.programs.filter { it.channelId in channels.map { c -> c.id } }, s.start, s.start + DesktopLiveTv.WINDOW), now)?.let { "live:program:$it" }
                else -> null
            }
            key?.let { DesktopUiProbe.requestFocus[it]?.let { target -> runCatching { target.requestFocus() } } }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Live TV", Modifier.padding(top = 12.dp).semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        PrimaryTabRow(selectedTabIndex = s.tab.ordinal, modifier = Modifier.fillMaxWidth()) {
            DesktopLiveTab.entries.forEach { tab ->
                Tab(selected = s.tab == tab, onClick = { owner.tab(tab) }, text = { Text(tab.name) },
                    modifier = Modifier.controlFocus().probe("live:tab:${tab.name}"))
            }
        }
        if (s.policy?.access == false) {
            LiveEmpty("Live TV access is disabled", "Ask your server administrator to allow Live TV for this account.", "Back", back)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(s.search, owner::search, label = { Text(if (s.tab == DesktopLiveTab.Channels || s.tab == DesktopLiveTab.Guide) "Search channels" else "Search ${s.tab.name.lowercase()}") },
                    singleLine = true, modifier = Modifier.weight(1f).widthIn(max = 400.dp).controlFocus().probe("live:search"))
                if (s.tab in listOf(DesktopLiveTab.Channels, DesktopLiveTab.Guide)) FilterChip(s.favorites, owner::favorites,
                    label = { Text("Favorites") }, modifier = Modifier.controlFocus().probe("live:favorites"))
                OutlinedButton({ owner.refresh() }, enabled = !s.loading && !s.writing, modifier = Modifier.controlFocus().probe("live:refresh")) { Text("Refresh") }
            }
            if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).semantics { contentDescription = "Loading Live TV" })
            val stale = s.fetchedAt != null && !s.actionsAvailable(now)
            if (s.error != null || stale) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.error ?: "Guide is out of date. Refresh before tuning or scheduling.", Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
                    TextButton({ owner.refresh() }, enabled = !s.loading && !s.writing) { Text("Retry") }
                }
            }
            s.notice?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium) }
            s.fetchedAt?.let { Text("Guide updated ${liveClock(it)}${if (s.offline) " · Saved guide for reference" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val none = when {
                s.loading && s.fetchedAt == null -> "Loading Live TV…"
                s.offline && s.fetchedAt == null -> "Live TV couldn’t load"
                s.channels.isEmpty() && s.tab in listOf(DesktopLiveTab.Channels, DesktopLiveTab.Guide) -> if (s.tunerEnabled == false) "Live TV is not set up" else "No channels available"
                channels.isEmpty() && s.tab in listOf(DesktopLiveTab.Channels, DesktopLiveTab.Guide) -> if (s.favorites && s.search.isBlank()) "No favorite channels" else "No matching channels"
                else -> null
            }
            if (none != null) {
                val filtered = s.channels.isNotEmpty()
                LiveEmpty(none, when {
                    s.loading -> "Your channels and guide will appear here."
                    s.offline -> "Check the connection to your server, then retry."
                    filtered -> if (s.favorites) "Star channels to find them here." else "Try another channel name or number."
                    else -> "Your server administrator can add a tuner and channel source in Jellyfin’s Live TV settings."
                }, if (filtered) "Show all channels" else "Retry", { if (filtered) owner.clearFilters() else owner.refresh() })
            } else when (s.tab) {
                DesktopLiveTab.Channels -> LiveScroll(scroll) {
                    items(channels, key = { "channel:${it.id}" }) { channel ->
                        val programs = s.programs.filter { it.channelId == channel.id }.sortedBy { liveInstant(it.startDate) }
                        val current = programs.firstOrNull { canTune(it, now) }
                        val next = programs.firstOrNull { (liveInstant(it.startDate) ?: Long.MIN_VALUE) > now }
                        val focus = "live:channel:${channel.id}"
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                LiveChannelMark(channel, account)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(listOfNotNull(channel.number, channel.name).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
                                    Text(current?.let { "Now · ${it.name} · ${liveRange(it.startDate, it.endDate)}" } ?: if (s.offline) "Guide unavailable" else if (s.loading) "Updating guide…" else if (programs.isEmpty()) "No guide data" else "Nothing on now", style = MaterialTheme.typography.bodyMedium)
                                    next?.let { Text("Next · ${liveClock(liveInstant(it.startDate))} · ${it.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    current?.let { p ->
                                        val start = liveInstant(p.startDate)!!; val end = liveInstant(p.endDate)!!
                                        LinearProgressIndicator(progress = { ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f) }, modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth().height(3.dp))
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Button({ tune(channel) }, enabled = fresh, modifier = Modifier.controlFocus().probe(focus)) { Icon(DesktopGlyph.Play, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Watch") }
                                    current?.let { p -> TextButton({ owner.select(p, focus) }, modifier = Modifier.controlFocus().probe("live:details:${p.id}")) { Text("Program details") } }
                                }
                                IconToggleButton(channel.userData.isFavorite, { owner.favorite(channel) }, enabled = fresh && s.policy?.userData == true,
                                    modifier = Modifier.controlFocus().probe("live:favorite:${channel.id}")) {
                                    Text(if (channel.userData.isFavorite) "★" else "☆", Modifier.semantics { contentDescription = "${if (channel.userData.isFavorite) "Remove" else "Add"} ${channel.name} ${if (channel.userData.isFavorite) "from" else "to"} favorites" }, style = MaterialTheme.typography.headlineSmall)
                                }
                            }
                        }
                    }
                }
                DesktopLiveTab.Guide -> DesktopGuide(s, channels, account, now, owner)
                DesktopLiveTab.Recordings -> {
                    val recordings = s.recordings.filter { it.name.contains(s.search, true) }
                    if (recordings.isEmpty()) LiveEmpty("No recordings", "Finished recordings appear here when your server makes them available.", "Browse guide", { owner.tab(DesktopLiveTab.Guide) })
                    else LiveScroll(scroll) {
                        items(recordings, key = { "recording:${it.id}" }) { item ->
                            ListItem(headlineContent = { Text(item.name) }, supportingContent = { Text(listOfNotNull(if (item.isInProgress) "Recording now" else item.recordingStatus?.let(::recordingStatus), if (item.userData.playbackPositionTicks > 0) "Resume from ${clock(item.userData.playbackPositionTicks / 10_000_000.0)}" else "Recorded video").joinToString(" · ")) },
                                trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton({ detail(item) }, modifier = Modifier.controlFocus().probe("live:recording-details:${item.id}")) { Text("Details") }
                                    Button({ play(item) }, enabled = fresh, modifier = Modifier.controlFocus().probe("live:recording:${item.id}")) { Text(if (item.userData.playbackPositionTicks > 0) "Resume" else "Play") }
                                    if (item.canDelete == true || s.policy?.deleteContent == true && item.canDelete != false) TextButton({ confirmation = LiveConfirmation(item = item) }, enabled = fresh, modifier = Modifier.controlFocus()) { Text("Delete…") }
                                } })
                        }
                    }
                }
                DesktopLiveTab.Schedules -> {
                    if (s.policy?.manage != true) LiveEmpty("Recording management is disabled", "You can watch channels. Ask your server administrator for permission to manage recordings.", "Go to channels", { owner.tab(DesktopLiveTab.Channels) })
                    else if (s.timers.isEmpty() && s.series.isEmpty()) LiveEmpty("No schedules", "Choose a program in the guide to record it once or set up a series rule.", "Browse guide", { owner.tab(DesktopLiveTab.Guide) })
                    else LiveScroll(scroll) {
                        val groups = listOf("Recording now", "Upcoming", "Series rules", "Other schedules", "Past")
                        groups.forEach { group ->
                            val series = group == "Series rules"
                            val rows = (if (series) s.series else s.timers.filter { desktopScheduleGroup(it, now) == group }).filter { it.name.contains(s.search, true) }
                            if (rows.isNotEmpty()) {
                                item(key = "group:$group") { Text(group, Modifier.padding(top = 12.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge) }
                                items(rows, key = { "schedule:$series:${it.id}" }) { schedule ->
                                    ListItem(headlineContent = { Text(schedule.name) }, supportingContent = { Column {
                                        Text(listOfNotNull(schedule.channelName, liveRange(schedule.startDate, schedule.endDate), if (series) null else recordingStatus(schedule.status)).joinToString(" · "))
                                        schedule.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                    } }, trailingContent = { Row {
                                        TextButton({ owner.edit(schedule, series) }, enabled = fresh, modifier = Modifier.controlFocus().probe("live:schedule:$series:${schedule.id}")) { Text("Edit options") }
                                        TextButton({ confirmation = LiveConfirmation(schedule = schedule, series = series) }, enabled = fresh, modifier = Modifier.controlFocus()) { Text(if (series) "Cancel series…" else "Cancel airing…") }
                                    } })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    s.selected?.takeIf { s.draft == null }?.let { program ->
        val channel = s.channels.firstOrNull { it.id == program.channelId }
        AlertDialog(onDismissRequest = owner::dismissProgram,
            title = { Text(program.name) },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${channel?.name ?: program.channelName.orEmpty()} · ${liveRange(program.startDate, program.endDate)}")
                val ended = liveInstant(program.endDate)?.let { it <= now } == true
                if (ended) Text("Ended at ${liveClock(liveInstant(program.endDate))}")
                else if (!canTune(program, now)) Text("Upcoming")
                program.episodeTitle?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                program.overview?.let { Text(it) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (channel != null && canTune(program, now)) Button({ owner.dismissProgram(); tune(channel) }, enabled = fresh, modifier = Modifier.controlFocus().probe("live:program:watch")) { Text("Watch now") }
                    if (s.policy?.manage == true) {
                        if (!program.timerId.isNullOrBlank() || liveInstant(program.endDate)?.let { it > now } == true) OutlinedButton({ owner.defaults(program, false) }, enabled = fresh, modifier = Modifier.controlFocus().probe("live:program:record")) { Text(if (program.timerId.isNullOrBlank()) "Record once" else "Manage recording") }
                        if (!program.seriesTimerId.isNullOrBlank() || program.isSeries && liveInstant(program.endDate)?.let { it > now } == true) OutlinedButton({ owner.defaults(program, true) }, enabled = fresh, modifier = Modifier.controlFocus().probe("live:program:series")) { Text(if (program.seriesTimerId.isNullOrBlank()) "Record series" else "Manage series") }
                    }
                }
                if (!fresh) Text("Refresh Live TV before tuning or scheduling.")
            } }, confirmButton = { TextButton(owner::dismissProgram, modifier = Modifier.controlFocus()) { Text("Close") } })
    }
    s.draft?.let { draft -> DesktopLiveScheduleEditor(draft, s.draftSeries, s.draftExisting, s.writing, fresh,
        onDismiss = owner::dismissDraft, onSave = { owner.save(it, s.draftSeries, s.draftExisting) }, onRefresh = { owner.refresh() }, refreshing = s.loading) }
    confirmation?.let { confirm ->
        val title = if (confirm.item != null) "Delete recorded file?" else if (confirm.series) "Cancel series rule?" else "Cancel this airing?"
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { cancelFocus.requestFocus() } }
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(title) }, text = { Text("${confirm.item?.name ?: confirm.schedule?.name}\n\n" + when {
            confirm.item != null -> "Deletes this recorded file from the server for every viewer. This does not cancel its recording schedule."
            confirm.series -> "Stops future recordings for this series rule. Completed recordings are kept."
            else -> "Cancels only this scheduled airing. The series rule and completed recordings are kept."
        }) }, dismissButton = { TextButton({ confirmation = null }, modifier = Modifier.focusRequester(cancelFocus).controlFocus()) { Text("Keep it") } },
            confirmButton = { TextButton({ confirmation = null; if (confirm.item != null) owner.delete(confirm.item) else owner.cancel(confirm.schedule!!, confirm.series) }, enabled = fresh, modifier = Modifier.controlFocus()) { Text(if (confirm.item != null) "Delete file" else "Confirm cancellation") } })
    }
}

private data class LiveConfirmation(val item: MediaItem? = null, val schedule: LiveSchedule? = null, val series: Boolean = false)

@Composable private fun ColumnScope.LiveEmpty(title: String, message: String, action: String, click: () -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(message, Modifier.widthIn(max = 560.dp).padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(click, modifier = Modifier.controlFocus().probe("live:empty-action")) { Text(action) }
    }
}
@Composable private fun ColumnScope.LiveScroll(state: LazyListState, content: LazyListScope.() -> Unit) {
    Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp), state = state, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp), content = content)
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
@Composable private fun LiveChannelMark(channel: LiveChannel, account: Account?) {
    var loaded by remember(channel.id, channel.imageTags) { mutableStateOf(false) }
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        if (!loaded) Text(channel.name.take(1), style = MaterialTheme.typography.titleLarge)
        if (account != null && channel.imageTags["Primary"] != null) AsyncImage(
            model = "${account.server.url.trimEnd('/')}/Items/${channel.id}/Images/Primary?tag=${channel.imageTags["Primary"]}&maxWidth=96",
            contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(), onSuccess = { loaded = true }, onError = { loaded = false })
    }
}

@Composable private fun ColumnScope.DesktopGuide(s: DesktopLiveState, channels: List<LiveChannel>, account: Account?, now: Long, owner: DesktopLiveTv) {
    val horizontal = rememberScrollState()
    val vertical = rememberLazyListState()
    var linear by remember { mutableStateOf(false) }
    var datePicker by remember { mutableStateOf(false) }
    var lastStart by rememberSaveable { mutableStateOf(s.start) }
    LaunchedEffect(s.start) { if (lastStart != s.start) { horizontal.scrollTo(0); lastStart = s.start } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton({ owner.moveWindow(s.start - DesktopLiveTv.WINDOW) }, enabled = !s.loading, modifier = Modifier.controlFocus()) { Text("Earlier") }
        TextButton({ datePicker = true }, modifier = Modifier.controlFocus()) { Text(liveDay(s.start) + " · " + liveClock(s.start)) }
        OutlinedButton({ owner.moveWindow(s.start + DesktopLiveTv.WINDOW) }, enabled = !s.loading, modifier = Modifier.controlFocus()) { Text("Later") }
        Button(owner::jumpNow, enabled = !s.loading, modifier = Modifier.controlFocus().probe("live:now")) { Text("Now") }
        FilterChip(linear, { linear = !linear }, label = { Text("List view") }, modifier = Modifier.controlFocus().probe("live:guide:list"))
    }
    val programs = guidePrograms(s.programs, channels.map { it.id }.toSet(), iso(s.start), iso(s.start + DesktopLiveTv.WINDOW))
    if (programs.isEmpty() && s.offline) LiveEmpty("Guide unavailable", "The guide couldn’t be loaded. Retry before tuning or scheduling.", "Retry", { owner.refresh() })
    else if (programs.isEmpty()) LiveEmpty("No guide data", "You can still watch channels. Your server administrator can add a guide source in Jellyfin’s Live TV settings.", "Go to channels", { owner.tab(DesktopLiveTab.Channels) })
    else if (linear) LiveScroll(vertical) {
        channels.forEach { channel ->
            item(key = "heading:${channel.id}") { Text(listOfNotNull(channel.number, channel.name).joinToString(" · "), Modifier.padding(top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium) }
            items(programs.filter { it.channelId == channel.id }, key = { "linear:${it.id}" }) { program ->
                Surface(onClick = { owner.select(program, "live:program:${program.id}") }, modifier = Modifier.fillMaxWidth().controlFocus().probe("live:program:${program.id}"), shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.padding(12.dp)) { Text(program.name); Text(liveRange(program.startDate, program.endDate), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    } else {
        val channelWidth = 168.dp
        val timelineWidth = 1080.dp
        Row(Modifier.fillMaxWidth()) {
            Text("Channel", Modifier.width(channelWidth).padding(vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.weight(1f).horizontalScroll(horizontal).width(timelineWidth)) {
                repeat(6) { i -> Text(liveClock(s.start + i * 1_800_000L), Modifier.width(180.dp).padding(6.dp), style = MaterialTheme.typography.labelMedium) }
            }
        }
        LiveScroll(vertical) {
            items(channels, key = { "guide:${it.id}" }) { channel ->
                val cells = desktopGuideCells(programs.filter { it.channelId == channel.id }, s.start, s.start + DesktopLiveTv.WINDOW)
                val lanes = (cells.maxOfOrNull { it.lane } ?: 0) + 1
                Row(Modifier.fillMaxWidth().height((lanes * 72).dp)) {
                    Row(Modifier.width(channelWidth).fillMaxHeight().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiveChannelMark(channel, account)
                        Column { channel.number?.let { Text(it, style = MaterialTheme.typography.labelSmall) }; Text(channel.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
                    }
                    BoxWithConstraints(Modifier.weight(1f)) {
                    val viewportWidth = maxWidth
                    val viewportStart = with(LocalDensity.current) { horizontal.value.toDp() }
                    Box(Modifier.horizontalScroll(horizontal).requiredWidth(timelineWidth).fillMaxHeight()) {
                        cells.forEach { cell ->
                            val program = cell.program
                            val current = canTune(program, now)
                            val text = liveGuideTextWindow((timelineWidth * cell.startFraction).value, (timelineWidth * cell.widthFraction - 4.dp).value, viewportStart.value, viewportWidth.value)
                            Surface(onClick = { owner.select(program, "live:program:${program.id}") },
                                modifier = Modifier.offset(x = timelineWidth * cell.startFraction, y = (cell.lane * 72).dp).width((timelineWidth * cell.widthFraction - 4.dp).coerceAtLeast(1.dp)).height(68.dp).controlFocus(RoundedCornerShape(6.dp)).probe("live:program:${program.id}")
                                    .semantics { contentDescription = "${channel.name}, ${program.name}, ${liveRange(program.startDate, program.endDate)}${if (current) ", on now" else ""}" },
                                shape = RoundedCornerShape(6.dp), color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Column(Modifier.offset(x = text.inset.dp).width(text.width.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(program.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    Text(liveRange(program.startDate, program.endDate), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        if (now >= s.start && now < s.start + DesktopLiveTv.WINDOW) Box(Modifier.offset(x = timelineWidth * ((now - s.start).toFloat() / DesktopLiveTv.WINDOW)).width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary.copy(alpha = .6f)))
                    }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Spacer(Modifier.width(channelWidth)); HorizontalScrollbar(rememberScrollbarAdapter(horizontal), Modifier.weight(1f)) }
    }
    if (datePicker) {
        val zone = ZoneId.systemDefault()
        val initial = Instant.ofEpochMilli(s.start).atZone(zone).toLocalDate()
        var day by remember { mutableStateOf(initial) }
        var selected by remember { mutableStateOf(s.start) }
        var daysOpen by remember { mutableStateOf(false) }
        var timesOpen by remember { mutableStateOf(false) }
        val first = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // Enumerating instants offers both fall-back offsets and never offers a nonexistent spring hour.
        val times = remember(day) { generateSequence(first) { it + 1_800_000 }.takeWhile { it < end }.toList() }
        AlertDialog(onDismissRequest = { datePicker = false }, title = { Text("Guide date and time") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                OutlinedButton({ daysOpen = true }) { Text(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).format(day)) }
                DropdownMenu(daysOpen, { daysOpen = false }) {
                    val today = Instant.now().atZone(zone).toLocalDate()
                    (listOf(initial) + (0L..6L).map { today.plusDays(it) }).distinct().forEach { date ->
                        DropdownMenuItem(text = { Text(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).format(date)) }, onClick = { day = date; selected = date.atStartOfDay(zone).toInstant().toEpochMilli(); daysOpen = false })
                    }
                }
            }
            Box {
                OutlinedButton({ timesOpen = true }) { Text(liveClock(selected)) }
                DropdownMenu(timesOpen, { timesOpen = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                    times.forEach { instant -> DropdownMenuItem(text = { Text(liveClock(instant)) }, leadingIcon = { RadioButton(selected == instant, null) }, onClick = { selected = instant; timesOpen = false }) }
                }
            }
            Text("Local time · ${zone.id}", style = MaterialTheme.typography.bodySmall)
        } }, dismissButton = { TextButton({ datePicker = false }) { Text("Cancel") } }, confirmButton = { Button({ owner.moveWindow(selected); datePicker = false }) { Text("Show guide") } })
    }
}

// Native layout and schedules use the same core presentation rules as Android.
internal fun desktopGuideCells(programs: List<LiveProgram>, start: Long, end: Long) = liveGuideCells(programs, start, end)
internal fun desktopScheduleGroup(schedule: LiveSchedule, now: Long) = liveScheduleGroup(schedule, now)
internal fun liveClock(value: Long?): String {
    if (value == null) return "Time unavailable"
    val local = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault())
    return DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(value)) +
        if (local.zone.rules.getValidOffsets(local.toLocalDateTime()).size > 1) " ${local.offset}" else ""
}
internal fun liveDay(value: Long): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()))
internal fun liveRange(start: String?, end: String?): String {
    val from = liveInstant(start); val to = liveInstant(end)
    if (from == null || to == null || to <= from) return "Time unavailable"
    val today = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
    val day = Instant.ofEpochMilli(from).atZone(ZoneId.systemDefault()).toLocalDate()
    val endDay = Instant.ofEpochMilli(to).atZone(ZoneId.systemDefault()).toLocalDate()
    return (if (day != today) "${liveDay(from)} · " else "") + liveClock(from) + " – " + (if (endDay != day) "${liveDay(to)} · " else "") + liveClock(to)
}
