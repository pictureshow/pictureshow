/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Tab as TvTab
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.livetv.LiveProgram
import app.pictureshow.core.livetv.LiveSchedule
import app.pictureshow.core.livetv.recordingStatus
import kotlinx.coroutines.delay

/** Platform components share one destination and the same policy/write owner. */
@OptIn(ExperimentalMaterial3Api::class)
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
    val time = liveTime()
    var tab by rememberSaveable { mutableStateOf("Channels") }
    var search by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var favorites by rememberSaveable { mutableStateOf(false) }
    var linear by rememberSaveable { mutableStateOf(false) }
    var selectedProgram by remember { mutableStateOf<LiveProgram?>(null) }
    var selectedSchedule by remember { mutableStateOf<LiveSchedule?>(null) }
    var selectedSeries by remember { mutableStateOf(false) }
    var deleteRecording by remember { mutableStateOf<MediaItem?>(null) }
    var recordingMenu by remember { mutableStateOf<MediaItem?>(null) }
    var cancelling by remember { mutableStateOf(false) }
    var returnFocus by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreFocus by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val guideList = rememberLazyListState()
    val guideScroll = rememberScrollState()
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
    fun focused(key: String) {
        returnFocus = key
        restoreFocus = false
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
    val detail = draft != null || schedule != null || program != null || recording != null
    BackHandler(enabled = detail) { closeDetail() }
    val title = when {
        draft != null -> if (state.draftSeries) "Record series" else "Record once"
        schedule != null -> if (cancelling) "Cancel ${if (selectedSeries) "series" else "recording"}?" else "Recording schedule"
        program != null -> "Program details"
        recording != null -> "Delete recording?"
        else -> "Live TV"
    }
    val guideHeader = tv && !detail && tab in listOf("Channels", "Guide")
    val preview = state.programs.firstOrNull { "p:${it.id}" == returnFocus }
        ?: state.programs.firstOrNull { "c:${it.channelId}" == returnFocus && app.pictureshow.core.livetv.canTune(it, now) }
        ?: state.programs.firstOrNull { app.pictureshow.core.livetv.canTune(it, now) }
        ?: state.programs.firstOrNull()
    val canAct = state.actionsAvailable(now)
    val channels = state.channels.filter {
        (!favorites || it.userData.isFavorite) &&
            (search.isBlank() || it.name.contains(search, true) || it.number.orEmpty().contains(search, true))
    }
    Column(
        Modifier.fillMaxSize().background(LiveBackground).padding(horizontal = if (tv) 32.dp else 0.dp, vertical = if (tv) 16.dp else 0.dp)
            .testTag(if (tv) "live:tv" else "live:touch")
    ) {
        if (tv && program == null) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = if (!detail) (64 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp else 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (guideHeader) {
                    Column(Modifier.weight(1f).testTag("live:guide-preview")) {
                        LiveText(preview?.name ?: "Live TV guide", true, heading = true, maxLines = 1)
                        preview?.let { show ->
                            LiveText(
                                "${show.channelName ?: state.channels.firstOrNull { it.id == show.channelId }?.name.orEmpty()} · " +
                                    time.range(show.startDate, show.endDate) +
                                    show.seasonNumber?.let { season -> show.episodeNumber?.let { " · S$season E$it" } }.orEmpty() +
                                    show.episodeTitle?.let { " · $it" }.orEmpty(),
                                true,
                                small = true,
                                maxLines = 1
                            )
                            show.overview?.let { LiveText(it, true, small = true, maxLines = 1) }
                        }
                    }
                } else {
                    LiveText(
                        title,
                        true,
                        Modifier.weight(1f).padding(start = if (detail) 16.dp else 0.dp),
                        heading = !detail,
                        small = detail,
                        maxLines = 1
                    )
                }
                if (!detail) {
                    state.fetchedAt?.let {
                        LiveText("${if (state.offline) "Offline · last loaded" else "Updated"} ${time.clock(it)}", true, small = true)
                    }
                    if (tab in listOf("Channels", "Guide")) LiveIcon("search", "Search channels", true) { showSearch = !showSearch }
                    LiveIcon("refresh", "Refresh", true, !state.loading && !state.writing) { vm.refresh() }
                }
            }
        } else if (!tv) {
            androidx.compose.material3.TopAppBar(
                title = { androidx.compose.material3.Text(title, maxLines = 1) },
                navigationIcon = { LiveIcon("back", "Back", false) { if (detail) closeDetail() else onBack() } },
                actions = {
                    if (!detail) {
                        if (tab in listOf("Channels", "Guide")) LiveIcon("search", "Search channels", false) { showSearch = !showSearch }
                        LiveIcon("refresh", "Refresh", false, !state.loading && !state.writing) { vm.refresh() }
                    }
                }
            )
        }
        if (!detail) {
            val tabs = listOf("Channels", "Guide", "Recordings", "Schedules")
            fun select(next: String) {
                tab = next
                returnFocus = null
                restoreFocus = false
            }
            val navigation: @Composable () -> Unit = {
                if (tv) {
                    androidx.tv.material3.TabRow(tabs.indexOf(tab)) {
                        tabs.forEach { next ->
                            TvTab(
                                tab == next,
                                onFocus = {
                                },
                                onClick = {
                                    select(next)
                                },
                                modifier = Modifier.height(
                                    (36 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
                                ).testTag("live:tab:$next")
                            ) {
                                androidx.tv.material3.Text(
                                    next,
                                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    style = androidx.tv.material3.MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                } else {
                    val touchTabs: @Composable () -> Unit = {
                        tabs.forEach { next ->
                            androidx.compose.material3.Tab(
                                selected = tab == next,
                                onClick = { select(next) },
                                modifier = Modifier.height(48.dp).testTag("live:tab:$next")
                            ) {
                                androidx.compose.material3.Text(
                                    next,
                                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                    if (LocalDensity.current.fontScale > 1.1f) {
                        androidx.compose.material3.PrimaryScrollableTabRow(tabs.indexOf(tab), edgePadding = 8.dp) { touchTabs() }
                    } else {
                        androidx.compose.material3.PrimaryTabRow(tabs.indexOf(tab)) { touchTabs() }
                    }
                }
            }
            if (showSearch && tab in listOf("Channels", "Guide")) {
                if (tv) {
                    RemoteField("Search channels", search, {
                        search = it
                        returnFocus = null
                    })
                } else {
                    androidx.compose.material3.OutlinedTextField(search, {
                        search = it
                        returnFocus = null
                    }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = {
                        androidx.compose.material3.Text("Search channels")
                    }, singleLine = true)
                }
            }
            val filters: @Composable () -> Unit = {
                if (tab in listOf("Channels", "Guide")) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = if (tv) 0.dp else 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item {
                            LiveChip(
                                "Favorites",
                                favorites,
                                tv,
                                modifier = if (tv) {
                                    Modifier.height(
                                        (
                                            36 *
                                                LocalDensity.current.fontScale.coerceAtLeast(1f)
                                            ).dp
                                    )
                                } else {
                                    Modifier
                                }
                            ) {
                                favorites = !favorites
                                returnFocus = null
                            }
                        }
                        if (tab == "Guide") {
                            item {
                                LiveGuideViewMode(linear, tv) {
                                    linear = it
                                    returnFocus = null
                                }
                            }
                        } else if (!tv) {
                            item {
                                state.fetchedAt?.let {
                                    LiveText(
                                        "${if (state.offline) "Offline · last loaded" else "Updated"} ${time.clock(it)}",
                                        false,
                                        small = true
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (tv) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.width((410 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp)) { navigation() }
                    Box(Modifier.weight(1f)) { filters() }
                }
            } else {
                navigation()
                filters()
            }
        }
        if (state.loading || state.writing) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        }
        state.error?.let {
            LiveText(it, tv, Modifier.padding(horizontal = if (tv) 0.dp else 16.dp, vertical = 4.dp), warning = true, small = true)
        }
        state.notice?.let { LiveText(it, tv, Modifier.padding(8.dp), small = true) }
        if (!detail &&
            state.offline
        ) {
            LiveText(
                "Saved guide is for reference. Reconnect before scheduling or tuning.",
                tv,
                Modifier.padding(8.dp),
                warning = true,
                small = true
            )
        }
        val body: @Composable () -> Unit = {
            if (!detail && tab == "Guide" && !linear && state.policy?.access != false && channels.isNotEmpty()) {
                Column(Modifier.padding(horizontal = if (tv) 0.dp else 16.dp)) {
                    if (!tv) LiveGuideDateBar(state.windowStart, now, false, !state.loading, vm::refresh)
                    if (state.programs.isEmpty() && !state.loading) LiveText(NO_LIVE_GUIDE, tv, small = true)
                    LiveGuide(
                        channels, state.programs, account, tv, state.windowStart, now, guideScroll, guideList,
                        returnFocus, restoreFocus, ::focused,
                        channelHeader = { LiveGuideDateBar(state.windowStart, now, true, !state.loading, vm::refresh) }
                    ) {
                        selectedProgram =
                            it
                    }
                }
            } else {
                if (!detail && tab == "Guide") LiveGuideDateBar(state.windowStart, now, tv, !state.loading, vm::refresh)
                LazyColumn(
                    state = if (detail) rememberLazyListState() else list,
                    contentPadding = PaddingValues(horizontal = if (tv || !detail) 0.dp else 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (tv && (recording != null || cancelling)) {
                        item {
                            LiveAction(
                                if (recording != null ||
                                    cancelling ||
                                    draft != null
                                ) {
                                    "Cancel"
                                } else {
                                    "Back"
                                },
                                true,
                                Modifier.liveFocus("detail:cancel", recording != null || cancelling),
                                !state.writing,
                                ::closeDetail
                            )
                        }
                    }
                    when {
                        draft != null -> item {
                            LiveScheduleEditor(draft, state.draftSeries, tv, state.writing, false) { vm.save(it, state.draftSeries, false) }
                        }
                        schedule != null -> {
                            if (cancelling) {
                                item {
                                    LiveText(
                                        if (selectedSeries) {
                                            "Cancel this series rule and its future recordings: ${schedule.name}. " +
                                                "Completed recordings are retained."
                                        } else {
                                            "Cancel only this occurrence: ${schedule.name}, ${time.range(
                                                schedule.startDate,
                                                schedule.endDate
                                            )}. Completed recordings are retained."
                                        },
                                        tv
                                    )
                                    LiveAction(
                                        if (selectedSeries) "Confirm cancel series" else "Confirm cancel occurrence",
                                        tv,
                                        enabled = !state.writing
                                    ) {
                                        vm.cancel(schedule, selectedSeries)
                                    }
                                }
                            } else {
                                item {
                                    LiveScheduleEditor(schedule, selectedSeries, tv, state.writing, true) {
                                        vm.save(it, selectedSeries, true)
                                    }
                                }
                                item {
                                    LiveStatus(liveScheduleStatus(schedule, selectedSeries), tv)
                                    schedule.errorMessage?.let { LiveText(it, tv, warning = true) }
                                }
                                item {
                                    LiveAction(
                                        if (selectedSeries) "Cancel series…" else "Cancel occurrence…",
                                        tv,
                                        enabled = !state.writing
                                    ) {
                                        cancelling =
                                            true
                                    }
                                }
                            }
                        }
                        program != null -> item {
                            LiveProgramDetail(program, state, account, tv, canAct, now, onPlay, {
                                vm.defaults(program, false)
                            }, { vm.defaults(program, true) }) { timer, series ->
                                selectedSchedule =
                                    timer
                                selectedSeries = series
                            }
                        }
                        recording != null -> item {
                            LiveText(
                                "Permanently delete the recorded file “${recording.name}”. Its recording schedule is a separate action.",
                                tv
                            )
                            LiveAction("Delete recorded file", tv, enabled = !state.writing) { vm.delete(recording) }
                        }
                        state.policy?.access == false -> Unit
                        tab in listOf("Channels", "Guide") -> {
                            if (!state.loading && state.channels.isEmpty() && state.error == null) {
                                item {
                                    LiveText(
                                        if (state.tunerEnabled ==
                                            false
                                        ) {
                                            "No tuner configured. Ask your server administrator to add an M3U or HDHomeRun tuner " +
                                                "in Jellyfin Dashboard → Live TV."
                                        } else {
                                            "No channels available. Ask your server administrator to configure a tuner " +
                                                "in Jellyfin Dashboard → Live TV. " +
                                                "No tuner hardware is needed on this device."
                                        },
                                        tv
                                    )
                                }
                            }
                            if (!state.loading &&
                                state.channels.isNotEmpty() &&
                                state.programs.isEmpty()
                            ) {
                                item { LiveText(NO_LIVE_GUIDE, tv) }
                            }
                            itemsIndexed(channels, key = { _, channel -> "channel:${channel.id}" }) { index, channel ->
                                val key = "c:${channel.id}"
                                if (tab ==
                                    "Channels"
                                ) {
                                    LiveChannelRow(
                                        channel, state.programs.filter { it.channelId == channel.id }, account, tv, now,
                                        Modifier.liveFocus(
                                            key,
                                            tv &&
                                                !state.loading &&
                                                (returnFocus == key || returnFocus == null && index == 0),
                                            enabled = canAct
                                        ) {
                                            focused(key)
                                        },
                                        canFavorite = state.policy?.userData == true,
                                        favoriteEnabled = canAct && !state.writing,
                                        tuneEnabled = canAct,
                                        onFavorite = { vm.favorite(channel) }, onTune = { if (canAct) onPlay(channel.mediaItem()) }
                                    )
                                } else {
                                    LiveText("${channel.number.orEmpty()} ${channel.name}", tv, heading = true)
                                    state.programs.filter { it.channelId == channel.id }.forEachIndexed { programIndex, show ->
                                        val pkey = "p:${show.id}"
                                        LiveProgramRow(
                                            show,
                                            tv,
                                            Modifier.liveFocus(
                                                pkey,
                                                tv &&
                                                    (
                                                        restoreFocus &&
                                                            returnFocus == pkey ||
                                                            returnFocus == null &&
                                                            index == 0 &&
                                                            programIndex == 0
                                                        )
                                            ) { focused(pkey) }
                                        ) {
                                            selectedProgram =
                                                show
                                        }
                                    }
                                }
                            }
                            if (!state.loading &&
                                state.channels.isNotEmpty() &&
                                channels.isEmpty()
                            ) {
                                item { LiveText("No matching channels.", tv) }
                            }
                        }
                        tab == "Recordings" -> {
                            if (!state.loading && state.recordings.isEmpty()) item { LiveText("No recordings available.", tv) }
                            state.recordings.groupBy { it.seriesName ?: "Recordings" }.forEach { (group, recordings) ->
                                item("recording-heading:$group") { LiveText(group, tv, heading = true) }
                                item("recording-cards:$group") {
                                    LazyRow(contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                        itemsIndexed(recordings, key = { _, item -> item.id }) { index, item ->
                                            val key = "r:${item.id}"
                                            Column(
                                                Modifier.width(if (tv) 240.dp else 244.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                val target = Modifier.liveFocus(
                                                    key,
                                                    tv &&
                                                        !state.loading &&
                                                        (
                                                            restoreFocus &&
                                                                returnFocus == key ||
                                                                returnFocus == null &&
                                                                index == 0 &&
                                                                group ==
                                                                state.recordings.firstOrNull()?.let {
                                                                    it.seriesName ?: "Recordings"
                                                                }
                                                            )
                                                ) { focused(key) }
                                                if (tv) {
                                                    CatalogCard(item, account, true, { onRecording(item) }, target)
                                                } else {
                                                    TouchCatalogCard(item, account, true, { onRecording(item) }, target)
                                                }
                                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                    LiveStatus(
                                                        if (item.isInProgress) {
                                                            "● Recording now"
                                                        } else {
                                                            item.recordingStatus?.let(::recordingStatus)
                                                                ?: "Completed"
                                                        },
                                                        tv
                                                    )
                                                    Box(Modifier.weight(1f)) { }
                                                    if (item.canDelete == true ||
                                                        state.policy?.deleteContent == true &&
                                                        item.canDelete != false
                                                    ) {
                                                        LiveIcon("more", "Recording options for ${item.name}", tv) {
                                                            recordingMenu =
                                                                item
                                                        }
                                                    }
                                                }
                                                LiveText(
                                                    listOfNotNull(
                                                        item.premiereDate?.let {
                                                            time.day(liveInstant(it))
                                                        },
                                                        item.runTimeTicks?.let {
                                                            "${it / 600_000_000} min"
                                                        }
                                                    ).joinToString(" · "),
                                                    tv,
                                                    small = true
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        tab == "Schedules" -> {
                            if (state.policy?.manage !=
                                true
                            ) {
                                item {
                                    LiveText(
                                        "Recording schedules require Live TV management permission. Ask your server administrator.",
                                        tv
                                    )
                                }
                            } else {
                                if (!state.loading &&
                                    state.timers.isEmpty() &&
                                    state.series.isEmpty()
                                ) {
                                    item { LiveText("No scheduled recordings.", tv) }
                                }
                                listOf("Series rules" to state.series, "Upcoming" to state.timers).forEach { (group, schedules) ->
                                    if (schedules.isNotEmpty()) item("schedule-heading:$group") { LiveText(group, tv, heading = true) }
                                    itemsIndexed(schedules, key = { _, timer -> "$group:${timer.id}" }) { index, timer ->
                                        val key = "s:$group:${timer.id}"
                                        LiveScheduleRow(
                                            timer,
                                            tv,
                                            Modifier.liveFocus(
                                                key,
                                                tv &&
                                                    !state.loading &&
                                                    (
                                                        restoreFocus &&
                                                            returnFocus == key ||
                                                            returnFocus == null &&
                                                            index == 0 &&
                                                            (group == "Series rules" || state.series.isEmpty())
                                                        )
                                            ) { focused(key) },
                                            seriesRule = group == "Series rules"
                                        ) {
                                            if (canAct &&
                                                !state.writing
                                            ) {
                                                selectedSchedule = timer
                                                selectedSeries = group == "Series rules"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            if (!tv && !detail) {
                androidx.compose.material3.pulltorefresh.PullToRefreshBox(state.loading, { vm.refresh() }) { body() }
            } else {
                body()
            }
        }
    }
    recordingMenu?.let { item ->
        LiveMenu(
            item.name,
            tv,
            { recordingMenu = null },
            listOf(
                "Open recording" to {
                    recordingMenu = null
                    onRecording(item)
                },
                "Delete recorded file…" to {
                    recordingMenu = null
                    deleteRecording = item
                }
            )
        )
    }
}

internal const val NO_LIVE_GUIDE = "No guide data for this time. Channels can still be tuned. " +
    "An administrator can add an XMLTV guide in Jellyfin Dashboard → Live TV."
