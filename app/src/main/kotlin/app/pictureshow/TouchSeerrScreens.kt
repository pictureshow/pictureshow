/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.normalizeSearch
import app.pictureshow.core.seerr.SeerrStatus
import app.pictureshow.core.seerr.SeerrTitle
import app.pictureshow.core.seerr.seerrCanRequest
import app.pictureshow.core.seerr.seerrMovieRequestable
import app.pictureshow.core.seerr.seerrRequestDisplay
import app.pictureshow.core.seerr.seerrSelectableSeasons
import app.pictureshow.core.seerr.seerrTakenSeasons
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Requests (Seerr) on phone and tablet: the Requests tab, a title with its Request action, and the connect page. */
internal fun NavGraphBuilder.seerrTouchScreens(nav: NavHostController, account: () -> Account?) {
    composable(SEERR_ROUTE) {
        account()?.let { current ->
            val vm: SeerrViewModel = koinViewModel(key = "seerr:${current.key}", parameters = { parametersOf(current) })
            TouchSeerrHub(vm, current) { type, id -> nav.navigate(seerrTitleRoute(type, id)) }
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
            TouchSeerrTitle(vm, current) { nav.popBackStack() }
        }
    }
    composable(SEERR_CONNECT_ROUTE) {
        account()?.let { current ->
            val vm: SeerrViewModel = koinViewModel(key = "seerr:${current.key}", parameters = { parametersOf(current) })
            TouchSeerrConnect(vm, current, onRequests = {
                nav.navigate(SEERR_ROUTE) { popUpTo(SEERR_CONNECT_ROUTE) { inclusive = true } }
            }) { nav.popBackStack() }
        }
    }
}

private val SeerrTouchTabs = listOf("discover", "search", "mine")

@Composable
private fun TouchSeerrHub(vm: SeerrViewModel, account: Account, onOpen: (String, Int) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("discover") }
    LaunchedEffect(tab, state.connected) {
        if (!state.connected) return@LaunchedEffect
        when (tab) {
            "discover" -> vm.loadTrending()
            "mine" -> vm.loadRequests()
        }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxSize().testTag("touch:seerr")) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TouchCatalogHeading(stringResource(R.string.seerr_nav))
        }
        PrimaryTabRow(SeerrTouchTabs.indexOf(tab)) {
            SeerrTouchTabs.forEach { next ->
                Tab(
                    selected = tab == next,
                    onClick = { tab = next },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("touch:seerr:tab:$next")
                ) {
                    Text(
                        stringResource(
                            when (next) {
                                "discover" -> R.string.seerr_tab_discover
                                "search" -> R.string.seerr_tab_search
                                else -> R.string.seerr_tab_mine
                            }
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(top = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            when (tab) {
                "discover" -> {
                    item(key = "status") {
                        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.trendingLoading &&
                                state.trending.isEmpty()
                            ) {
                                TouchCatalogLoading(stringResource(R.string.seerr_loading))
                            }
                            TouchCatalogError(
                                state.trendingError?.let {
                                    stringResource(seerrErrorText(it))
                                }
                            ) { vm.loadTrending(force = true) }
                        }
                    }
                    listOf("movie" to R.string.seerr_trending_movies, "tv" to R.string.seerr_trending_series).forEach { (type, label) ->
                        val titles = state.trending.filter { it.mediaType == type }
                        if (titles.isNotEmpty()) {
                            item(key = type) {
                                val lines = titles.associate { (it.mediaType to it.id) to seerrTouchLine(it) }
                                SeerrTouchShelf(stringResource(label), titles.map(::seerrCardItem), account, seerrArtwork(titles), {
                                    lines[seerrKey(it)]
                                }) { item -> seerrKey(item)?.let { (t, id) -> onOpen(t, id) } }
                            }
                        }
                    }
                }
                "search" -> {
                    item(key = "query") {
                        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.query,
                                onValueChange = { vm.search(it) },
                                label = { Text(stringResource(R.string.seerr_search_label)) },
                                placeholder = { Text(stringResource(R.string.seerr_search_hint)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    keyboard?.hide()
                                    vm.search(state.query, immediate = true)
                                }),
                                modifier = Modifier.fillMaxWidth().testTag("touch:seerr:query")
                            )
                            val term = normalizeSearch(state.query)
                            val searchError = state.searchError
                            when {
                                state.searching -> TouchCatalogLoading(stringResource(R.string.seerr_loading))
                                searchError != null -> TouchMessage(stringResource(seerrErrorText(searchError)))
                                term.length < 2 -> Text(
                                    stringResource(R.string.seerr_search_short),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                state.searched == term && state.results.isEmpty() -> Text(stringResource(R.string.seerr_search_none, term))
                            }
                        }
                    }
                    if (state.results.isNotEmpty()) {
                        item(key = "results") {
                            val lines = state.results.associate { (it.mediaType to it.id) to seerrTouchLine(it) }
                            SeerrTouchShelf(
                                stringResource(
                                    R.string.seerr_search_label
                                ),
                                state.results.map(::seerrCardItem),
                                account,
                                seerrArtwork(state.results),
                                {
                                    lines[seerrKey(it)]
                                }
                            ) { item ->
                                keyboard?.hide()
                                seerrKey(item)?.let { (t, id) -> onOpen(t, id) }
                            }
                        }
                    }
                }
                else -> {
                    item(key = "status") {
                        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.requestsLoading &&
                                state.requests.isEmpty()
                            ) {
                                TouchCatalogLoading(stringResource(R.string.seerr_loading))
                            }
                            TouchCatalogError(state.requestsError?.let { stringResource(seerrErrorText(it)) }, vm::loadRequests)
                            if (!state.requestsLoading && state.requestsError == null && state.requests.isEmpty()) {
                                Text(stringResource(R.string.seerr_mine_none))
                            }
                        }
                    }
                    if (state.requests.isNotEmpty()) {
                        item(key = "mine") {
                            val titles = state.requests.map { it.title ?: SeerrTitle(it.request.media.tmdbId, it.request.type) }
                            val lines = state.requests.associate { row ->
                                val title = row.title
                                (row.request.type to row.request.media.tmdbId) to listOf(
                                    title?.year.orEmpty(),
                                    stringResource(seerrStatusText(seerrRequestDisplay(row.request)))
                                ).filter(String::isNotBlank).joinToString(" · ")
                            }
                            SeerrTouchShelf(
                                stringResource(
                                    R.string.seerr_tab_mine
                                ),
                                titles.map(::seerrCardItem),
                                account,
                                seerrArtwork(titles),
                                {
                                    lines[seerrKey(it)]
                                }
                            ) { item -> seerrKey(item)?.let { (t, id) -> onOpen(t, id) } }
                        }
                    }
                }
            }
        }
    }
}

/** The (type, TMDB id) a [seerrCardItem] stands for. */
private fun seerrKey(item: MediaItem): Pair<String, Int>? {
    val rest = item.id.removePrefix(SEERR_ITEM_PREFIX).takeIf { item.id.startsWith(SEERR_ITEM_PREFIX) } ?: return null
    val id = rest.substringAfter('-').toIntOrNull() ?: return null
    return rest.substringBefore('-') to id
}

private fun seerrArtwork(titles: List<SeerrTitle>): (MediaItem) -> String? {
    val posters = titles.associate { seerrCardItem(it).id to it.poster.orEmpty() }
    return { posters[it.id] }
}

@Composable
private fun seerrTouchLine(title: SeerrTitle): String {
    val kind = stringResource(if (title.mediaType == "tv") R.string.seerr_series else R.string.seerr_movie)
    val status = when (title.status) {
        SeerrStatus.UNKNOWN, SeerrStatus.DELETED -> null
        else -> stringResource(seerrStatusText(title.status))
    }
    return listOfNotNull(title.year.takeIf(String::isNotBlank), kind, status).joinToString(" · ")
}

/** A shelf of Seerr titles as the library's own cards, posters from TMDB without server credentials. */
@Composable
internal fun SeerrTouchShelf(
    title: String,
    items: List<MediaItem>,
    account: Account,
    artwork: (MediaItem) -> String?,
    subtitle: (MediaItem) -> String?,
    onOpen: (MediaItem) -> Unit
) {
    val shortWindow = isShortTouchWindow()
    Column(verticalArrangement = Arrangement.spacedBy(if (shortWindow) 8.dp else 12.dp)) {
        TouchCatalogHeading(title, Modifier.padding(horizontal = 20.dp), section = true)
        LazyRow(
            state = rememberLazyListState(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = MediaItem::id) { item ->
                val line = subtitle(item)
                app.pictureshow.ui.CatalogCard(
                    item,
                    account,
                    false,
                    { onOpen(item) },
                    Modifier.width(if (shortWindow) 116.dp else 136.dp),
                    tag = "touch:seerr:card:${item.id}",
                    announcement = if (line.isNullOrBlank()) catalogAnnouncement(item) else "${catalogAnnouncement(item)}, $line",
                    subtitle = line,
                    artworkUrl = artwork(item)
                )
            }
        }
    }
}

@Composable
private fun TouchSeerrTitle(vm: SeerrViewModel, account: Account, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val state by vm.state.collectAsStateWithLifecycle()
    val title = state.title
    if (title == null) {
        TouchPage(stringResource(R.string.seerr_nav), onBack) {
            if (state.titleLoading) TouchCatalogLoading(stringResource(R.string.seerr_loading))
            TouchMessage(state.titleError?.let { stringResource(seerrErrorText(it)) })
        }
        return
    }
    val canRequest = seerrCanRequest(state.config?.user?.permissions ?: 0L, title.mediaType)
    val selectable = seerrSelectableSeasons(title)
    val taken = seerrTakenSeasons(title)
    TouchPage(title.label, onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            app.pictureshow.ui.CatalogArtwork(
                seerrCardItem(title),
                account,
                false,
                Modifier.width(120.dp).aspectRatio(2f / 3f),
                artworkUrl = title.poster.orEmpty()
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(seerrTouchLine(title), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        title.overview?.takeIf(String::isNotBlank)?.let { Text(it) }
        when {
            !canRequest -> Text(stringResource(R.string.seerr_no_permission_type))
            title.mediaType == "movie" -> if (seerrMovieRequestable(title)) {
                TouchAction(
                    stringResource(if (state.sending) R.string.seerr_requesting else R.string.seerr_request),
                    Modifier.testTag("touch:seerr:request"),
                    enabled = !state.sending,
                    onClick = vm::request
                )
            } else if (!state.sent) {
                Text(stringResource(R.string.seerr_already_have))
            }
            // Right after our own request the confirmation below says it; "already requested" would repeat it.
            selectable.isEmpty() -> if (!state.sent) Text(stringResource(R.string.seerr_nothing_left))
            else -> {
                val count = state.chosen.count { it in selectable }
                Text(stringResource(R.string.seerr_choose_seasons), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TouchSwitchRow(
                    stringResource(R.string.seerr_all_seasons),
                    null,
                    selectable.all { it in state.chosen },
                    "touch:seerr:season:all",
                    enabled = !state.sending
                ) { vm.toggleAll() }
                title.seasons.filter { it.seasonNumber > 0 && it.episodeCount > 0 }.sortedBy { it.seasonNumber }.forEach { season ->
                    val reason = taken[season.seasonNumber]
                    TouchSwitchRow(
                        stringResource(R.string.seerr_season, season.seasonNumber),
                        reason?.let { stringResource(seerrStatusText(it)) }
                            ?: pluralStringResource(R.plurals.seerr_season_episodes, season.episodeCount, season.episodeCount),
                        season.seasonNumber in state.chosen && reason == null,
                        "touch:seerr:season:${season.seasonNumber}",
                        enabled = reason == null && !state.sending
                    ) { vm.toggleSeason(season.seasonNumber) }
                }
                TouchAction(
                    if (state.sending) {
                        stringResource(
                            R.string.seerr_requesting
                        )
                    } else {
                        pluralStringResource(R.plurals.seerr_request_seasons, count, count)
                    },
                    Modifier.testTag("touch:seerr:request"),
                    enabled = !state.sending && count > 0,
                    onClick = vm::request
                )
            }
        }
        if (state.sent) Text(stringResource(R.string.seerr_requested_done))
        TouchMessage(state.sendError?.let { stringResource(seerrErrorText(it)) })
    }
}

@Composable
private fun TouchSeerrConnect(vm: SeerrViewModel, account: Account, onRequests: () -> Unit, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val config = state.config
    val keyboard = LocalSoftwareKeyboardController.current
    TouchPage(stringResource(R.string.seerr_connect_title), onBack) {
        if (!state.ready) {
            TouchCatalogLoading(stringResource(R.string.seerr_loading))
            return@TouchPage
        }
        if (config != null) {
            Text(stringResource(R.string.seerr_connected_as, config.url.substringAfter("://"), config.user.name))
            val requester = seerrCanRequest(config.user.permissions, "movie") || seerrCanRequest(config.user.permissions, "tv")
            if (!requester) TouchMessage(stringResource(R.string.seerr_connected_cannot_request))
            TouchAction(stringResource(R.string.seerr_open_requests), Modifier.testTag("touch:seerr:open"), onClick = onRequests)
            TouchRemoveAction(stringResource(R.string.seerr_disconnect), onClick = vm::disconnect)
            Text(stringResource(R.string.seerr_disconnect_note, account.user.name), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@TouchPage
        }
        var address by rememberSaveable { mutableStateOf("") }
        var useKey by rememberSaveable { mutableStateOf(false) }
        var username by rememberSaveable { mutableStateOf(account.user.name) }
        // Secrets stay out of saved instance state.
        var password by remember { mutableStateOf("") }
        var key by remember { mutableStateOf("") }
        var reveal by remember { mutableStateOf(false) }
        val ready = address.isNotBlank() && (if (useKey) key.isNotBlank() else username.isNotBlank())
        fun connect() {
            keyboard?.hide()
            if (ready) vm.connect(address, key.takeIf { useKey }, username, password)
        }
        Text(stringResource(R.string.seerr_connect_intro))
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text(stringResource(R.string.seerr_connect_address)) },
            supportingText = { Text(stringResource(R.string.seerr_connect_address_hint)) },
            singleLine = true,
            enabled = !state.connecting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag("touch:seerr:address")
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false to R.string.seerr_connect_method_jellyfin, true to R.string.seerr_connect_method_key).forEachIndexed {
                    index,
                    (key, label)
                ->
                SegmentedButton(
                    selected = useKey == key,
                    onClick = { useKey = key },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    enabled = !state.connecting,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(if (key) "touch:seerr:method:key" else "touch:seerr:method:jellyfin")
                ) { Text(stringResource(label), maxLines = 2) }
            }
        }
        val secretTransform = if (reveal) VisualTransformation.None else PasswordVisualTransformation()
        val revealButton: @Composable () -> Unit = {
            TextButton(onClick = { reveal = !reveal }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (reveal) "Hide" else "Show") }
        }
        if (useKey) {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(stringResource(R.string.seerr_connect_key)) },
                supportingText = { Text(stringResource(R.string.seerr_connect_key_note)) },
                singleLine = true,
                enabled = !state.connecting,
                visualTransformation = secretTransform,
                trailingIcon = revealButton,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false
                ),
                keyboardActions = KeyboardActions(onDone = { connect() }),
                modifier = Modifier.fillMaxWidth().testTag("touch:seerr:key")
            )
        } else {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.seerr_connect_username)) },
                singleLine = true,
                enabled = !state.connecting,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("touch:seerr:username")
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.seerr_connect_password)) },
                supportingText = { Text(stringResource(R.string.seerr_connect_password_note)) },
                singleLine = true,
                enabled = !state.connecting,
                visualTransformation = secretTransform,
                trailingIcon = revealButton,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false
                ),
                keyboardActions = KeyboardActions(onDone = { connect() }),
                modifier = Modifier.fillMaxWidth().testTag("touch:seerr:password")
            )
        }
        TouchAction(
            stringResource(if (state.connecting) R.string.seerr_connect_busy else R.string.seerr_connect_action),
            Modifier.testTag("touch:seerr:connect"),
            enabled = !state.connecting && ready,
            onClick = ::connect
        )
        TouchMessage(state.connectError?.let { stringResource(seerrErrorText(it)) })
    }
}
