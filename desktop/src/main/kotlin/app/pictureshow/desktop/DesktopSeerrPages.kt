/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pictureshow.ui.CatalogHeading
import app.pictureshow.core.normalizeSearch
import app.pictureshow.core.seerr.*
import coil3.compose.AsyncImage

fun DesktopController.openRequests() = openPage(DesktopRoute.Requests) { seerr.tab(seerr.state.value.tab) }
fun DesktopController.openSeerrTitle(title: SeerrTitle) = openPage(DesktopRoute.SeerrTitle) { seerr.openTitle(title.mediaType, title.id) }

/** SEERR-01: Settings → Requests (Seerr). */
@Composable
internal fun SeerrSettingsSection(state: DesktopState, c: DesktopController) {
    val account = state.account?.takeIf { it.signedIn } ?: return
    val seerr by c.seerr.state.collectAsState()
    SettingsSection("Requests") {
        val config = seerr.config
        SettingRow("Requests (Seerr)", config?.let { "Connected as ${it.user.name}" } ?: "Ask for movies and shows your library doesn't have yet") {
            if (config == null) OutlinedButton(onClick = c.seerr::openConnect, modifier = Modifier.controlFocus().probe("settings:seerr-connect")) { Text("Connect") }
            else OutlinedButton(onClick = c.seerr::disconnect, modifier = Modifier.controlFocus().probe("settings:seerr-disconnect")) { Text("Disconnect") }
        }
        if (config != null) Text("The Seerr sign-in is saved on this computer for ${account.user.name} only, and removed when ${account.user.name} signs out.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (seerr.connectOpen) SeerrConnectDialog(account.user.name, seerr, c)
}

@Composable
private fun SeerrConnectDialog(name: String, seerr: DesktopSeerrState, c: DesktopController) {
    var url by remember { mutableStateOf("") }
    var apiMode by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf(name) }
    var password by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val submit = { c.seerr.connect(url, key.takeIf { apiMode }, username, password) { k -> c.state.value.accounts.any { it.key == k && it.signedIn } } }
    AlertDialog(onDismissRequest = c.seerr::closeConnect, modifier = Modifier.probe("seerr-connect"),
        title = { Text("Connect to Seerr") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text("Seerr address") }, singleLine = true, enabled = !seerr.connecting, placeholder = { Text("https://requests.example.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth().focusRequester(first).probe("seerr-url"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!apiMode, { apiMode = false }, { Text("Sign in with Jellyfin") }, modifier = Modifier.controlFocus(allowInModal = true).probe("seerr-mode-jellyfin"))
                    FilterChip(apiMode, { apiMode = true }, { Text("Use an API key") }, modifier = Modifier.controlFocus(allowInModal = true).probe("seerr-mode-key"))
                }
                if (!apiMode) {
                    Text("Use the Jellyfin username and password you sign in to Seerr with.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(username, { username = it }, label = { Text("Jellyfin username") }, singleLine = true, enabled = !seerr.connecting, modifier = Modifier.fillMaxWidth().probe("seerr-username"))
                    OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, enabled = !seerr.connecting, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }), modifier = Modifier.fillMaxWidth().probe("seerr-password"))
                } else {
                    Text("Seerr's API key, from Seerr's Settings. Requests are made as your own Seerr user, so your Jellyfin user must be imported in Seerr.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(key, { key = it }, label = { Text("API key") }, singleLine = true, enabled = !seerr.connecting, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }), modifier = Modifier.fillMaxWidth().probe("seerr-key"))
                }
                seerr.connectError?.let { Text(desktopSeerrErrorText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.probe("seerr-connect-error")) }
            }
        },
        confirmButton = { Button(onClick = { submit() }, enabled = !seerr.connecting && url.isNotBlank() && (if (apiMode) key.isNotBlank() else username.isNotBlank() && password.isNotEmpty()), modifier = Modifier.controlFocus(allowInModal = true).probe("seerr-connect-submit")) { Text(if (seerr.connecting) "Connecting…" else "Connect") } },
        dismissButton = { TextButton(onClick = c.seerr::closeConnect, modifier = Modifier.controlFocus(allowInModal = true).probe("seerr-connect-cancel")) { Text("Cancel") } })
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
}

@Composable
private fun SeerrPoster(title: SeerrTitle, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(2f / 3f).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val poster = title.poster
        if (poster != null) AsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(title.label, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** A Seerr title as a card: poster, name, year · kind · status. Opens the Seerr title page. */
@Composable
internal fun SeerrCard(title: SeerrTitle, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, line: String = desktopSeerrCardLine(title)) {
    Column(modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClick).controlFocus(MaterialTheme.shapes.small).probe(tag)
        .semantics(mergeDescendants = true) { contentDescription = "${title.label}. $line"; role = Role.Button }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SeerrPoster(title, Modifier.fillMaxWidth())
        Text(title.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SeerrMessage(error: SeerrError?, loading: Boolean, empty: Boolean, emptyText: String, retry: () -> Unit) {
    when {
        loading && empty -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(desktopSeerrErrorText(error), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (error !in setOf(SeerrError.EXPIRED, SeerrError.STORAGE)) OutlinedButton(onClick = retry, modifier = Modifier.controlFocus().probe("seerr-retry")) { Text("Retry") }
        }
        empty -> Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** SEERR-03/04/05: the Requests destination. */
@Composable
internal fun DesktopRequestsPage(c: DesktopController) {
    val s by c.seerr.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CatalogHeading("Requests")
        if (!s.connected) { Text("Connect Seerr in Settings to ask for movies and shows.", color = MaterialTheme.colorScheme.onSurfaceVariant); return@Column }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(DesktopRequestsTab.Trending to "Trending", DesktopRequestsTab.Search to "Search", DesktopRequestsTab.Mine to "My requests").forEach { (tab, label) ->
                FilterChip(s.tab == tab, { c.seerr.tab(tab) }, { Text(label) }, modifier = Modifier.controlFocus().probe("requests:tab:$label"))
            }
        }
        val grid = Modifier.fillMaxSize().probe("requests-grid")
        val cells = GridCells.Adaptive(150.dp)
        when (s.tab) {
            DesktopRequestsTab.Trending -> {
                SeerrMessage(s.trendingError, s.trendingLoading, s.trending.isEmpty(), "Nothing is trending right now.") { c.seerr.loadTrending(force = true) }
                LazyVerticalGrid(cells, grid, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(s.trending, key = { "${it.mediaType}:${it.id}" }) { t -> SeerrCard(t, "requests:trending:${t.mediaType}:${t.id}", { c.openSeerrTitle(t) }) }
                }
            }
            DesktopRequestsTab.Search -> {
                val field = remember { FocusRequester() }
                OutlinedTextField(s.query, { c.seerr.search(it) }, singleLine = true, label = { Text("Search Seerr") }, placeholder = { Text("Movies and shows") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { c.seerr.search(s.query, immediate = true) }),
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().focusRequester(field).probe("requests-search"))
                LaunchedEffect(Unit) { withFrameNanos { }; runCatching { field.requestFocus() } }
                if (s.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                val term = normalizeSearch(s.query)
                SeerrMessage(s.searchError, false, s.searched != null && s.results.isEmpty(), "No results for “$term”.") { c.seerr.search(s.query, immediate = true) }
                LazyVerticalGrid(cells, grid, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(s.results, key = { "${it.mediaType}:${it.id}" }) { t -> SeerrCard(t, "requests:search:${t.mediaType}:${t.id}", { c.openSeerrTitle(t) }) }
                }
            }
            DesktopRequestsTab.Mine -> {
                SeerrMessage(s.requestsError, s.requestsLoading, s.requests.isEmpty(), "You haven't requested anything yet.") { c.seerr.loadRequests() }
                LazyVerticalGrid(cells, grid, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(s.requests, key = { it.request.id }) { row ->
                        val t = row.title ?: SeerrTitle(row.request.media.tmdbId, row.request.type, title = "Title ${row.request.media.tmdbId}")
                        val line = listOfNotNull(t.year.takeIf { it.isNotBlank() }, desktopSeerrStatusText(seerrRequestDisplay(row.request)),
                            row.request.seasons.takeIf { it.isNotEmpty() }?.let { s -> if (s.size == 1) "Season ${s[0].seasonNumber}" else "${s.size} seasons" }).joinToString(" · ")
                        SeerrCard(t, "requests:mine:${row.request.id}", { c.openSeerrTitle(t) }, line = line)
                    }
                }
            }
        }
    }
}

/** SEERR-06: a title from Seerr, with Request for a movie or chosen seasons. */
@Composable
internal fun DesktopSeerrTitlePage(c: DesktopController) {
    val s by c.seerr.state.collectAsState()
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val title = s.title
            if (title == null) { SeerrMessage(s.titleError, s.titleLoading, true, "") {}; return@Column }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SeerrPoster(title, Modifier.width(180.dp))
                Column(Modifier.weight(1f).widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CatalogHeading(title.label)
                    Text(desktopSeerrCardLine(title), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.probe("seerr-title-status"))
                    title.overview?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    val user = s.config?.user
                    val canRequest = user?.canRequest(title.mediaType) != false
                    if (title.mediaType == "tv") {
                        val selectable = seerrSelectableSeasons(title)
                        val taken = seerrTakenSeasons(title)
                        if (selectable.isEmpty()) Text("Every season is already requested or available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else {
                            Text("Choose the seasons to request.")
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.controlFocus(MaterialTheme.shapes.small).clickable { c.seerr.toggleAll() }.probe("seerr-season-all")) {
                                Checkbox(s.chosen.containsAll(selectable), null); Text("All seasons")
                            }
                        }
                        title.seasons.filter { it.seasonNumber > 0 }.sortedBy { it.seasonNumber }.forEach { season ->
                            val n = season.seasonNumber
                            val state = taken[n]
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.controlFocus(MaterialTheme.shapes.small, enabled = state == null)
                                .clickable(enabled = state == null) { c.seerr.toggleSeason(n) }.probe("seerr-season:$n")) {
                                Checkbox(state == null && n in s.chosen, null, enabled = state == null)
                                Text("Season $n" + (state?.let { " · ${desktopSeerrStatusText(it)}" } ?: ""))
                            }
                        }
                    }
                    val enabled = canRequest && !s.sending && !s.sent && when (title.mediaType) {
                        "movie" -> seerrMovieRequestable(title)
                        else -> seerrSelectableSeasons(title).any { it in s.chosen }
                    }
                    if (!canRequest) Text(desktopSeerrErrorText(SeerrError.PERMISSION), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (title.mediaType != "movie" || seerrMovieRequestable(title) || s.sent) Button(onClick = c.seerr::request, enabled = enabled, modifier = Modifier.controlFocus().probe("seerr-request")) {
                        Text(if (s.sending) "Requesting…" else "Request")
                    }
                    if (s.sent) Text("Requested. You'll find it in My requests.", color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.probe("seerr-sent"))
                    s.sendError?.let { Text(desktopSeerrErrorText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.probe("seerr-send-error")) }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/**
 * SEERR-07: search's "Not in your library" row, below the library results. Titles the library already has (same name
 * and year, core's rule) are left out.
 */
internal fun LazyListScope.seerrOffersRow(c: DesktopController, search: DesktopSearchState) {
    item(key = "seerr-offers") {
        val seerr by c.seerr.state.collectAsState()
        LaunchedEffect(search.query, seerr.connected) { c.seerr.searchOffers(search.query) }
        val term = normalizeSearch(search.query)
        if (!seerr.connected || term.length < 2 || seerr.offersTerm != term) return@item
        val library = search.rows.flatMap { it.items }.filter { it.type in setOf("Movie", "Series") }.map { it.name to it.productionYear }
        val offers = seerrSearchOffers(seerr.offers, library)
        if (offers.isEmpty()) return@item
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.probe("search-seerr")) {
            CatalogHeading("Not in your library", section = true)
            val lane = rememberLazyListState()
            LazyRow(state = lane, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(offers, key = { "${it.mediaType}:${it.id}" }) { t -> SeerrCard(t, "search-seerr:${t.mediaType}:${t.id}", { c.openSeerrTitle(t) }, Modifier.width(150.dp)) }
            }
            HorizontalScrollbar(rememberScrollbarAdapter(lane), Modifier.fillMaxWidth().height(8.dp))
        }
    }
}
