/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Tab as TvTab
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.livetv.LiveProgram
import app.pictureshow.core.livetv.LiveSchedule
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Platform components share one destination and the same policy/write owner. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val compactHeight = with(LocalDensity.current) { liveCompactHeight(windowHeight.toDp().value.toInt(), tv) }
    val appBarScroll = androidx.compose.material3.TopAppBarDefaults.enterAlwaysScrollBehavior()
    var scrollingFilters: (@Composable () -> Unit)? = null
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
    var pulledRefresh by remember { mutableStateOf(false) }
    LaunchedEffect(pulledRefresh) {
        if (pulledRefresh) {
            // A fast request can finish between UI frames; observe the owner's current state directly.
            vm.state.first { !it.loading }
            pulledRefresh = false
        }
    }
    val refreshPresentation = liveRefreshPresentation(state.loading, state.writing, tv, pulledRefresh)
    val scrollOwner = liveScrollOwner(tab, linear)
    val list = androidx.compose.runtime.key(scrollOwner) { rememberLazyListState() }
    val platformBringIntoView = LocalBringIntoViewSpec.current
    val density = LocalDensity.current
    val recordingMargin = with(density) { FocusedCardMargin.toPx() }
    val recordingPadding = with(density) { 12.dp.toPx() }
    val recordingFocus = remember(account, platformBringIntoView, recordingMargin, recordingPadding) {
        LiveRecordingFocus(platformBringIntoView, recordingMargin, recordingPadding)
    }
    val guideList = androidx.compose.runtime.key(scrollOwner) { rememberLazyListState() }
    val guideScroll = rememberScrollState()
    val guideScope = rememberCoroutineScope()
    val guideAtStart by remember { derivedStateOf { guideScroll.value == 0 } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    fun resetGuideNow() {
        guideScope.launch { guideScroll.scrollTo(0) }
        vm.refresh(liveGuideWindowStart(now))
    }

    LaunchedEffect(state.fetchedAt) {
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
    val detail = draft != null || schedule != null || program != null
    BackHandler(enabled = detail || recording != null) { closeDetail() }
    val title = when {
        draft != null -> if (state.draftSeries) "Record series" else "Record once"
        schedule != null -> if (cancelling) "Cancel ${if (selectedSeries) "series" else "recording"}?" else "Recording schedule"
        program != null -> "Program details"
        else -> "Live TV"
    }
    val guideHeader = tv && !detail && tab in listOf("Channels", "Guide")
    val focusedChannel = state.channels.firstOrNull { "c:${it.id}" == returnFocus }
    val preview = if (focusedChannel != null) {
        state.programs.firstOrNull { it.channelId == focusedChannel.id && app.pictureshow.core.livetv.canTune(it, now) }
            ?: focusedChannel.currentProgram?.takeIf { app.pictureshow.core.livetv.canTune(it, now) }
    } else {
        state.programs.firstOrNull { "p:${it.id}" == returnFocus }
            ?: state.programs.firstOrNull { app.pictureshow.core.livetv.canTune(it, now) }
            ?: state.programs.firstOrNull()
    }
    val guideUnavailable = state.guideUnavailable
    val canAct = state.actionsAvailable(now)
    val channels = state.channels.filter {
        (!favorites || it.userData.isFavorite) &&
            (search.isBlank() || it.name.contains(search, true) || it.number.orEmpty().contains(search, true))
    }
    Column(
        Modifier.fillMaxSize().then(
            if (compactHeight && !detail) Modifier.nestedScroll(appBarScroll.nestedScrollConnection) else Modifier
        ).background(LiveBackground).padding(horizontal = if (tv) 32.dp else 0.dp, vertical = if (tv) 16.dp else 0.dp)
            .testTag(if (tv) "live:tv" else "live:touch")
    ) {
        if (tv) {
            Row(
                Modifier.fillMaxWidth().height(
                    if (!detail) (60 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp else 48.dp
                ).testTag("live:toolbar"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (guideHeader) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().testTag("live:guide-preview"),
                        verticalArrangement = Arrangement.Center
                    ) {
                        androidx.tv.material3.Text(
                            preview?.name ?: focusedChannel?.name ?: "Live TV guide",
                            Modifier.semantics { heading() },
                            style = androidx.tv.material3.MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        if (preview == null && focusedChannel != null) {
                            LiveText(
                                liveChannelIdleLabel(
                                    state.programs.any { it.channelId == focusedChannel.id },
                                    state.guideUnavailable
                                ),
                                true,
                                small = true
                            )
                        }
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
                    liveGuideFreshness(state.fetchedAt, state.offline, time)?.let { LiveText(it, true, small = true) }
                    if (tab in listOf("Channels", "Guide")) LiveIcon("search", "Search channels", true) { showSearch = !showSearch }
                }
                if (!detail ||
                    state.guideReferenceNotice(now) != null
                ) {
                    LiveIcon("refresh", "Refresh", true, !state.loading && !state.writing) { vm.refresh() }
                }
            }
        } else if (!tv) {
            androidx.compose.material3.TopAppBar(
                modifier = Modifier.testTag("live:touch-app-bar"),
                title = {
                    if (compactHeight && !detail) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            androidx.compose.material3.Text(title, maxLines = 1)
                            liveGuideFreshness(state.fetchedAt, state.offline, time)?.let {
                                androidx.compose.material3.Text(
                                    it,
                                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                        }
                    } else {
                        Column {
                            androidx.compose.material3.Text(title, maxLines = 1)
                            if (!detail) {
                                liveGuideFreshness(state.fetchedAt, state.offline, time)?.let {
                                    androidx.compose.material3.Text(
                                        it,
                                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = { LiveIcon("back", "Back", false) { if (detail) closeDetail() else onBack() } },
                actions = {
                    if (!detail) {
                        if (tab in listOf("Channels", "Guide")) LiveIcon("search", "Search channels", false) { showSearch = !showSearch }
                    }
                    if (!detail &&
                        state.guideReferenceNotice(now) == null
                    ) {
                        LiveIcon("refresh", "Refresh", false, !state.loading && !state.writing) { vm.refresh() }
                    }
                },
                scrollBehavior = if (compactHeight && !detail) appBarScroll else null
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
                                ).testTag("live:tab:$next"),
                                colors = androidx.tv.material3.TabDefaults.pillIndicatorTabColors(inactiveContentColor = LiveMuted)
                            ) {
                                Box(Modifier.fillMaxHeight().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                                    androidx.tv.material3.Text(
                                        next,
                                        maxLines = 1,
                                        style = androidx.tv.material3.MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        }
                    }
                } else {
                    val touchTabs: @Composable () -> Unit = {
                        tabs.forEach { next ->
                            androidx.compose.material3.Tab(
                                selected = tab == next,
                                unselectedContentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
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
                        androidx.compose.material3.PrimaryScrollableTabRow(tabs.indexOf(tab), edgePadding = 16.dp) { touchTabs() }
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
                    Row(
                        Modifier.testTag(
                            "live:filters"
                        ).then(if (tv) Modifier else Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp)),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LiveChip(
                            "Favorites",
                            favorites,
                            tv,
                            controlHeight = if (tv) (36 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp else 40.dp
                        ) {
                            favorites = !favorites
                            returnFocus = null
                        }
                        if (tab == "Guide") {
                            LiveGuideViewMode(linear, tv) {
                                linear = it
                                returnFocus = null
                            }
                        }
                    }
                }
            }
            if (tv) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().testTag("live:navigation"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    navigation()
                    Spacer(Modifier.weight(1f))
                    filters()
                }
            } else {
                navigation()
                if (compactHeight && tab == "Channels") scrollingFilters = filters else filters()
            }
        }
        if (refreshPresentation.headerLoading) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).testTag("live:header-loading"))
        }
        state.error?.let {
            LiveText(it, tv, Modifier.padding(horizontal = if (tv) 0.dp else 16.dp, vertical = 4.dp), warning = true, small = true)
        }
        state.notice?.let { LiveText(it, tv, Modifier.padding(8.dp), small = true) }
        state.guideReferenceNotice(now)?.let { notice ->
            LiveGuideWarning(notice, tv, !state.loading && !state.writing, vm::refresh)
        }
        val body: @Composable () -> Unit = {
            if (!detail && tab == "Guide" && !linear && state.policy?.access != false && channels.isNotEmpty()) {
                Column(Modifier.padding(horizontal = if (tv) 0.dp else 16.dp)) {
                    if (!tv) {
                        LiveGuideDateBar(
                            state.windowStart,
                            now,
                            false,
                            !state.loading,
                            viewportAtNow =
                            state.windowStart == liveGuideWindowStart(now) && guideAtStart,
                            onNow = ::resetGuideNow,
                            onChange = vm::refresh
                        )
                    }
                    if (state.programs.isEmpty() && !state.loading) LiveText(liveGuideEmptyMessage(guideUnavailable), tv, small = true)
                    LiveGuide(
                        channels, state.programs, account, tv, state.windowStart, now, guideScroll, guideList,
                        returnFocus, restoreFocus, ::focused,
                        guideUnavailable = guideUnavailable,
                        channelHeader = {
                            LiveGuideDateBar(
                                state.windowStart,
                                now,
                                true,
                                !state.loading,
                                viewportAtNow =
                                state.windowStart == liveGuideWindowStart(now) && guideAtStart,
                                onNow = ::resetGuideNow,
                                onChange = vm::refresh
                            )
                        }
                    ) {
                        selectedProgram =
                            it
                    }
                }
            } else {
                Column {
                    if (!detail &&
                        tab == "Guide"
                    ) {
                        LiveGuideDateBar(
                            state.windowStart,
                            now,
                            tv,
                            !state.loading,
                            viewportAtNow =
                            state.windowStart == liveGuideWindowStart(now),
                            onNow = ::resetGuideNow,
                            onChange = vm::refresh
                        )
                    }
                    val recordingShelf = tv && !detail && tab == "Recordings"
                    val lastRecordingGroup = state.recordings.map { it.seriesName?.takeIf(String::isNotBlank) ?: "Recordings" }
                        .distinct().lastOrNull()
                    val recordingBottomPadding = if (recordingShelf) {
                        with(density) { recordingFocus.trailingSpace(lastRecordingGroup).toDp() }
                    } else {
                        12.dp
                    }
                    LazyColumn(
                        state = if (detail) rememberLazyListState() else list,
                        contentPadding = PaddingValues(
                            start = if (tv || !detail) 0.dp else 16.dp,
                            end = if (tv || !detail) 0.dp else 16.dp,
                            top = 12.dp,
                            bottom = recordingBottomPadding
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                            .testTag(if (recordingShelf) "live:recording-viewport" else "live:content-list").then(
                                if (recordingShelf) {
                                    Modifier.revealFocusAfterFontChange(list)
                                        .onGloballyPositioned(recordingFocus::onViewportPositioned)
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        scrollingFilters?.let { renderer -> item(key = "live:channel-filters") { renderer() } }
                        if (tv && cancelling) {
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
                                    onClick = ::closeDetail
                                )
                            }
                        }
                        when {
                            draft != null -> item {
                                LiveScheduleEditor(draft, state.draftSeries, tv, state.writing, false) {
                                    vm.save(it, state.draftSeries, false)
                                }
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
                                        LiveStatus(
                                            liveScheduleStatus(schedule, selectedSeries),
                                            tv,
                                            schedule.fields["Status"]?.jsonPrimitive?.contentOrNull
                                        )
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

                            state.policy?.access == false -> Unit

                            tab in listOf("Channels", "Guide") -> {
                                if (!state.loading && state.channels.isEmpty() && state.error == null) {
                                    item {
                                        LiveListText(
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
                                    item { LiveListText(liveGuideEmptyMessage(guideUnavailable), tv) }
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
                                            guideUnavailable = guideUnavailable,
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
                                    item { LiveListText("No matching channels.", tv) }
                                }
                            }

                            tab == "Recordings" -> {
                                liveRecordingShelves(
                                    state,
                                    account,
                                    tv,
                                    restoreFocus,
                                    returnFocus,
                                    now,
                                    ::focused,
                                    onRecording,
                                    onOptions = { if (tv) recordingMenu = it else deleteRecording = it },
                                    focus = recordingFocus.takeIf { tv }
                                )
                            }

                            tab == "Schedules" -> {
                                if (state.policy?.manage !=
                                    true
                                ) {
                                    item {
                                        LiveListText(
                                            "Recording schedules require Live TV management permission. Ask your server administrator.",
                                            tv
                                        )
                                    }
                                } else {
                                    if (!state.loading &&
                                        state.timers.isEmpty() &&
                                        state.series.isEmpty()
                                    ) {
                                        item { LiveListText("No scheduled recordings.", tv) }
                                    }
                                    val groups = liveScheduleGroups(state.series, state.timers, now)
                                    val firstSchedule = groups.firstOrNull { it.schedules.isNotEmpty() }?.let { group ->
                                        liveScheduleFocusKey(group.schedules.first(), group.seriesRule)
                                    }
                                    groups.forEach { section ->
                                        val group = section.title
                                        val schedules = section.schedules
                                        if (schedules.isNotEmpty()) {
                                            item("schedule-heading:$group") {
                                                LiveListText(group, tv, heading = true)
                                            }
                                        }
                                        itemsIndexed(schedules, key = { _, timer ->
                                            liveScheduleFocusKey(timer, section.seriesRule)
                                        }) { _, timer ->
                                            val key = liveScheduleFocusKey(timer, section.seriesRule)
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
                                                                key == firstSchedule
                                                            )
                                                ) { focused(key) },
                                                seriesRule = section.seriesRule
                                            ) {
                                                if (canAct &&
                                                    !state.writing
                                                ) {
                                                    selectedSchedule = timer
                                                    selectedSeries = section.seriesRule
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
        }
        Box(Modifier.weight(1f)) {
            if (!tv && !detail) {
                androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    refreshPresentation.pullRefreshing,
                    {
                        pulledRefresh = true
                        vm.refresh()
                    },
                    Modifier.testTag("live:pull-refresh")
                ) { body() }
            } else if (tv && !detail && tab == "Recordings") {
                CompositionLocalProvider(LocalBringIntoViewSpec provides recordingFocus.verticalSpec) { body() }
            } else {
                body()
            }
        }
    }
    recording?.let { item ->
        LiveRecordingDeleteDialog(item.name, tv, state.writing, state.error, ::closeDetail) { vm.delete(item) }
    }
    recordingMenu?.let { item ->
        LiveMenu(
            item.name,
            tv,
            { recordingMenu = null },
            listOf(
                "Delete recorded file…" to {
                    recordingMenu = null
                    deleteRecording = item
                }
            ),
            destructiveLabels = setOf("Delete recorded file…")
        )
    }
}

internal const val NO_LIVE_GUIDE = "No guide data for this time. Channels can still be tuned. " +
    "An administrator can add an XMLTV guide in Jellyfin Dashboard → Live TV."
