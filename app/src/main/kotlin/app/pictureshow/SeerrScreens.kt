/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab as TvTab
import androidx.tv.material3.TabDefaults
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import app.pictureshow.core.Account
import app.pictureshow.core.seerr.SeerrStatus
import app.pictureshow.core.seerr.SeerrTitle
import app.pictureshow.core.seerr.seerrCanRequest
import app.pictureshow.core.seerr.seerrMovieRequestable
import app.pictureshow.core.seerr.seerrRequestDisplay
import app.pictureshow.core.seerr.seerrSelectableSeasons
import app.pictureshow.core.seerr.seerrTakenSeasons
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Requests (Seerr) on TV: the hub, a title with its Request action, and the Settings connect page. */
internal fun NavGraphBuilder.seerrTvScreens(nav: NavHostController, account: () -> Account?) {
    composable(SEERR_ROUTE) {
        account()?.let { current ->
            val vm: SeerrViewModel = koinViewModel(key = "seerr:${current.key}", parameters = { parametersOf(current) })
            SeerrTvHub(vm, current, onOpen = { type, id -> nav.navigate(seerrTitleRoute(type, id)) }) { nav.popBackStack() }
        }
    }
    composable(
        SEERR_TITLE_ROUTE,
        arguments = listOf(navArgument("type") { type = NavType.StringType }, navArgument("id") { type = NavType.IntType })
    ) { entry ->
        account()?.let { current ->
            val vm: SeerrViewModel = koinViewModel(key = "seerr:${current.key}", parameters = { parametersOf(current) })
            val type = entry.arguments?.getString("type").orEmpty()
            val id = entry.arguments?.getInt("id") ?: 0
            LaunchedEffect(type, id) { vm.openTitle(type, id) }
            SeerrTvTitle(vm, current) { nav.popBackStack() }
        }
    }
    composable(SEERR_CONNECT_ROUTE) {
        account()?.let { current ->
            val vm: SeerrViewModel = koinViewModel(key = "seerr:${current.key}", parameters = { parametersOf(current) })
            SeerrTvConnect(vm, current, onRequests = {
                nav.navigate(SEERR_ROUTE) { popUpTo(SEERR_CONNECT_ROUTE) { inclusive = true } }
            }) { nav.popBackStack() }
        }
    }
}

private val SeerrTvTabs = listOf("discover", "search", "mine")

@Composable
private fun SeerrTvHub(vm: SeerrViewModel, account: Account, onOpen: (String, Int) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("discover") }
    val tabFocus = remember { SeerrTvTabs.associateWith { FocusRequester() } }
    LaunchedEffect(tab, state.connected) {
        if (!state.connected) return@LaunchedEffect
        when (tab) {
            "discover" -> vm.loadTrending()
            "mine" -> vm.loadRequests()
        }
    }
    // Back from a title returns to the card that opened it, when that card is still on screen; otherwise the tabs.
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    val returnFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (opened != null) {
            repeat(10) {
                if (runCatching { returnFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                withFrameNanos { }
            }
        }
        tabFocus.getValue(tab).requestFocus()
    }
    val open: (String, Int) -> Unit = { type, id ->
        opened = "$type-$id"
        onOpen(type, id)
    }
    Box(Modifier.fillMaxSize().background(CatalogBackground).padding(horizontal = 48.dp, vertical = 32.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                stringResource(R.string.seerr_nav),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() }
            )
            TabRow(
                SeerrTvTabs.indexOf(tab),
                modifier = Modifier.focusProperties { onEnter = { tabFocus[tab]?.requestFocus() } }.focusGroup()
            ) {
                SeerrTvTabs.forEach { next ->
                    TvTab(
                        tab == next,
                        onFocus = {},
                        onClick = { tab = next },
                        modifier = Modifier.height((36 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp)
                            .focusRequester(tabFocus.getValue(next)).testTag("seerr:tab:$next"),
                        colors = TabDefaults.pillIndicatorTabColors()
                    ) {
                        Box(Modifier.fillMaxHeight().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(seerrTabLabel(next)), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            CompositionLocalProvider(LocalSeerrReturn provides SeerrReturn(opened, returnFocus)) {
                when (tab) {
                    "discover" -> SeerrTvDiscover(state, account, vm, open)
                    "search" -> SeerrTvSearch(state, account, vm, open)
                    else -> SeerrTvMine(state, account, vm, open)
                }
            }
        }
    }
}

private class SeerrReturn(val key: String?, val focus: FocusRequester)

private val LocalSeerrReturn = staticCompositionLocalOf<SeerrReturn?> { null }

private fun seerrTabLabel(tab: String) = when (tab) {
    "discover" -> R.string.seerr_tab_discover
    "search" -> R.string.seerr_tab_search
    else -> R.string.seerr_tab_mine
}

@Composable
private fun SeerrTvDiscover(state: SeerrState, account: Account, vm: SeerrViewModel, onOpen: (String, Int) -> Unit) {
    if (state.trendingLoading && state.trending.isEmpty()) StatusLine(stringResource(R.string.seerr_loading))
    state.trendingError?.let { error ->
        Message(stringResource(seerrErrorText(error)))
        Action(stringResource(R.string.seerr_retry)) { vm.loadTrending(force = true) }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        listOf("movie" to R.string.seerr_trending_movies, "tv" to R.string.seerr_trending_series).forEach { (type, label) ->
            val titles = state.trending.filter { it.mediaType == type }
            if (titles.isNotEmpty()) {
                item(key = type) {
                    SeerrTvShelf(stringResource(label), titles, account, "discover:$type", {
                        seerrTitleLine(it)
                    }) { onOpen(it.mediaType, it.id) }
                }
            }
        }
    }
}

@Composable
private fun SeerrTvSearch(state: SeerrState, account: Account, vm: SeerrViewModel, onOpen: (String, Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RemoteField(stringResource(R.string.seerr_search_label), state.query, {
            vm.search(it)
        }, onDone = { vm.search(state.query, immediate = true) })
        val term = app.pictureshow.core.normalizeSearch(state.query)
        when {
            state.searching -> StatusLine(stringResource(R.string.seerr_loading))
            state.searchError != null -> Message(stringResource(seerrErrorText(state.searchError)))
            term.length < 2 -> StatusLine(stringResource(R.string.seerr_search_short), announce = false)
            state.searched == term && state.results.isEmpty() -> StatusLine(stringResource(R.string.seerr_search_none, term))
        }
        if (state.results.isNotEmpty()) {
            SeerrTvShelf(stringResource(R.string.seerr_search_label), state.results, account, "search", { seerrTitleLine(it) }) {
                onOpen(it.mediaType, it.id)
            }
        }
    }
}

@Composable
private fun SeerrTvMine(state: SeerrState, account: Account, vm: SeerrViewModel, onOpen: (String, Int) -> Unit) {
    if (state.requestsLoading && state.requests.isEmpty()) StatusLine(stringResource(R.string.seerr_loading))
    state.requestsError?.let { error ->
        Message(stringResource(seerrErrorText(error)))
        Action(stringResource(R.string.seerr_retry), onClick = vm::loadRequests)
    }
    if (!state.requestsLoading && state.requestsError == null && state.requests.isEmpty()) {
        StatusLine(stringResource(R.string.seerr_mine_none))
    }
    if (state.requests.isNotEmpty()) {
        val titles = state.requests.map { it.title ?: SeerrTitle(it.request.media.tmdbId, it.request.type) }
        val lines = state.requests.associate { row ->
            (row.request.type to row.request.media.tmdbId) to stringResource(seerrStatusText(seerrRequestDisplay(row.request)))
        }
        SeerrTvShelf(stringResource(R.string.seerr_tab_mine), titles, account, "mine", { title ->
            listOf(title.year, lines[title.mediaType to title.id].orEmpty()).filter(String::isNotBlank).joinToString(" · ")
        }) { onOpen(it.mediaType, it.id) }
    }
}

@Composable
private fun seerrTitleLine(title: SeerrTitle): String {
    val kind = stringResource(if (title.mediaType == "tv") R.string.seerr_series else R.string.seerr_movie)
    val status = when (title.status) {
        SeerrStatus.UNKNOWN, SeerrStatus.DELETED -> null
        else -> stringResource(seerrStatusText(title.status))
    }
    return listOfNotNull(title.year.takeIf(String::isNotBlank), kind, status).joinToString(" · ")
}

/** A row of Seerr titles as the library's own cards, with TMDB posters. */
@Composable
private fun SeerrTvShelf(
    title: String,
    titles: List<SeerrTitle>,
    account: Account,
    tag: String,
    line: @Composable (SeerrTitle) -> String,
    onOpen: (SeerrTitle) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            itemsIndexed(titles, key = { _, it -> "${it.mediaType}-${it.id}" }) { index, item ->
                val back = LocalSeerrReturn.current?.takeIf { it.key == "${item.mediaType}-${item.id}" }
                CatalogCard(
                    seerrCardItem(item),
                    account,
                    wide = false,
                    onClick = { onOpen(item) },
                    modifier = Modifier.testTag("seerr:$tag:${item.mediaType}-${item.id}")
                        .then(if (back != null) Modifier.focusRequester(back.focus) else Modifier)
                        .staysInRow(index == 0, index == titles.lastIndex),
                    subtitle = line(item),
                    announceSubtitle = true,
                    artworkUrl = item.poster.orEmpty()
                )
            }
        }
    }
}

@Composable
private fun SeerrTvTitle(vm: SeerrViewModel, account: Account, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val state by vm.state.collectAsStateWithLifecycle()
    val title = state.title
    if (title == null) {
        Page(stringResource(R.string.seerr_nav)) {
            if (state.titleLoading) StatusLine(stringResource(R.string.seerr_loading))
            state.titleError?.let { Message(stringResource(seerrErrorText(it))) }
            Action(stringResource(R.string.seerr_back), initial = !state.titleLoading, onClick = onBack)
        }
        return
    }
    val permissions = state.config?.user?.permissions ?: 0L
    val canRequest = seerrCanRequest(permissions, title.mediaType)
    val selectable = seerrSelectableSeasons(title)
    val taken = seerrTakenSeasons(title)
    Page(title.label) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            CatalogArtwork(
                seerrCardItem(title),
                account,
                false,
                Modifier.width(200.dp).aspectRatio(2f / 3f),
                artworkUrl = title.poster.orEmpty()
            )
            Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(seerrTitleLine(title), color = MaterialTheme.colorScheme.onSurfaceVariant)
                title.overview?.takeIf(String::isNotBlank)?.let { Text(it, maxLines = 8) }
            }
        }
        when {
            !canRequest -> StatusLine(stringResource(R.string.seerr_no_permission_type))
            title.mediaType == "movie" -> {
                if (seerrMovieRequestable(title)) {
                    Action(
                        stringResource(if (state.sending) R.string.seerr_requesting else R.string.seerr_request),
                        Modifier.testTag("seerr:request"),
                        initial = true,
                        enabled = !state.sending,
                        onClick = vm::request
                    )
                } else if (!state.sent) {
                    StatusLine(stringResource(R.string.seerr_already_have))
                }
            }
            // Right after our own request the confirmation below says it; "already requested" would repeat it.
            selectable.isEmpty() -> if (!state.sent) StatusLine(stringResource(R.string.seerr_nothing_left))
            else -> {
                val count = state.chosen.count { it in selectable }
                Action(
                    if (state.sending) {
                        stringResource(R.string.seerr_requesting)
                    } else {
                        pluralStringResource(R.plurals.seerr_request_seasons, count, count)
                    },
                    Modifier.testTag("seerr:request"),
                    initial = true,
                    enabled = !state.sending && count > 0,
                    onClick = vm::request
                )
                StatusLine(stringResource(R.string.seerr_choose_seasons), announce = false)
                Column(Modifier.widthIn(max = 640.dp)) {
                    TvSettingsToggleRow(
                        stringResource(R.string.seerr_all_seasons),
                        selectable.all { it in state.chosen },
                        Modifier.testTag("seerr:season:all"),
                        enabled = !state.sending
                    ) { vm.toggleAll() }
                    title.seasons.filter { it.seasonNumber > 0 && it.episodeCount > 0 }.sortedBy { it.seasonNumber }.forEach { season ->
                        val reason = taken[season.seasonNumber]
                        TvSettingsToggleRow(
                            stringResource(R.string.seerr_season, season.seasonNumber),
                            season.seasonNumber in state.chosen && reason == null,
                            Modifier.testTag("seerr:season:${season.seasonNumber}"),
                            supportingText = reason?.let { stringResource(seerrStatusText(it)) }
                                ?: pluralStringResource(R.plurals.seerr_season_episodes, season.episodeCount, season.episodeCount),
                            enabled = reason == null && !state.sending
                        ) { vm.toggleSeason(season.seasonNumber) }
                    }
                }
            }
        }
        if (state.sent) StatusLine(stringResource(R.string.seerr_requested_done))
        state.sendError?.let { Message(stringResource(seerrErrorText(it))) }
        Action(stringResource(R.string.seerr_back), initial = !canRequest, onClick = onBack)
    }
}

@Composable
private fun SeerrTvConnect(vm: SeerrViewModel, account: Account, onRequests: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val state by vm.state.collectAsStateWithLifecycle()
    val config = state.config
    if (!state.ready) {
        Page(stringResource(R.string.seerr_connect_title)) { StatusLine(stringResource(R.string.seerr_loading)) }
        return
    }
    Page(stringResource(R.string.seerr_connect_title)) {
        if (config != null) {
            Text(stringResource(R.string.seerr_connected_as, config.url.substringAfter("://"), config.user.name))
            val requester = seerrCanRequest(config.user.permissions, "movie") || seerrCanRequest(config.user.permissions, "tv")
            if (!requester) StatusLine(stringResource(R.string.seerr_connected_cannot_request))
            Action(stringResource(R.string.seerr_open_requests), Modifier.testTag("seerr:open"), initial = true, onClick = onRequests)
            Action(stringResource(R.string.seerr_disconnect), Modifier.testTag("seerr:disconnect"), onClick = vm::disconnect)
            StatusLine(stringResource(R.string.seerr_disconnect_note, account.user.name), announce = false)
            Action(stringResource(R.string.seerr_back), onClick = onBack)
            return@Page
        }
        var address by rememberSaveable { mutableStateOf("") }
        var useKey by rememberSaveable { mutableStateOf(false) }
        var username by rememberSaveable { mutableStateOf(account.user.name) }
        // Secrets stay out of saved instance state.
        var password by remember { mutableStateOf("") }
        var key by remember { mutableStateOf("") }
        fun connect() = vm.connect(address, key.takeIf { useKey }, username, password)
        Text(stringResource(R.string.seerr_connect_intro), Modifier.widthIn(max = 720.dp))
        RemoteField(stringResource(R.string.seerr_connect_address), address, { address = it }, initial = true, enabled = !state.connecting)
        StatusLine(stringResource(R.string.seerr_connect_address_hint), announce = false)
        Column(Modifier.widthIn(max = 640.dp)) {
            TvSettingsRow(
                stringResource(R.string.seerr_connect_method_jellyfin),
                null,
                Modifier.testTag("seerr:method:jellyfin"),
                enabled = !state.connecting,
                selected = !useKey,
                onClick = { useKey = false },
                leading = { TvSettingsRadioIndicator(!useKey) }
            )
            TvSettingsRow(
                stringResource(R.string.seerr_connect_method_key),
                null,
                Modifier.testTag("seerr:method:key"),
                enabled = !state.connecting,
                selected = useKey,
                onClick = { useKey = true },
                leading = { TvSettingsRadioIndicator(useKey) }
            )
        }
        if (useKey) {
            RemoteField(stringResource(R.string.seerr_connect_key), key, {
                key = it
            }, password = true, enabled = !state.connecting) { connect() }
            StatusLine(stringResource(R.string.seerr_connect_key_note), announce = false)
        } else {
            RemoteField(stringResource(R.string.seerr_connect_username), username, { username = it }, enabled = !state.connecting)
            RemoteField(stringResource(R.string.seerr_connect_password), password, {
                password = it
            }, password = true, enabled = !state.connecting) {
                connect()
            }
            StatusLine(stringResource(R.string.seerr_connect_password_note), announce = false)
        }
        Action(
            stringResource(if (state.connecting) R.string.seerr_connect_busy else R.string.seerr_connect_action),
            Modifier.testTag("seerr:connect"),
            enabled = !state.connecting && address.isNotBlank() && (if (useKey) key.isNotBlank() else username.isNotBlank()),
            onClick = ::connect
        )
        state.connectError?.let { Message(stringResource(seerrErrorText(it))) }
        Action(stringResource(R.string.seerr_back), onClick = onBack)
    }
}
