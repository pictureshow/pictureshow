/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

/**
 * Stream C1 (catalog) state: what the Home layout, a library's filters and letter rail, a title's discovery rows and
 * the photo viewer show. It rides on [DesktopState], so Back restores a page with the filters and rows it had.
 */
data class DesktopCatalogState(
    /** HOME-01..13: this viewer's layout; null until the server or local copy has resolved (editor stays closed). */
    val preferences: HomePreferences? = null,
    val preferenceStatus: String? = null,
    val retrySave: Boolean = false,
    val saving: Boolean = false,
    /** Every row Home could show before customization (empty ones omitted), for the editor and its pin labels. */
    val availableRows: List<HomeRow> = emptyList(),
    /** The viewer's libraries in server order, independent of the customizable Libraries row (HOME-08). */
    val libraries: List<MediaItem> = emptyList(),
    /** HOME-09: the line above Home when the server is out of reach and the page keeps what it showed. */
    val homeNotice: String? = null,
    /** CAT-30..32 for [discoveryFor]: rows are dropped when their read fails. */
    val discoveryFor: String? = null,
    val trailers: List<TrailerChoice> = emptyList(),
    val extras: List<MediaItem> = emptyList(),
    val similar: List<MediaItem> = emptyList(),
    val trailerMessage: String? = null,
    /** BROWSE-01..03: the current library's filters, carried by every page read. */
    val filters: LibraryFilters = LibraryFilters(),
    /** HOME-06: list rows instead of the poster grid. */
    val listView: Boolean = false,
    val facets: LibraryFacets? = null,
    val facetsError: String? = null,
    val people: List<MediaItem> = emptyList(),
    val peopleError: String? = null,
    /** BROWSE-05: ids and sort names of every title in the grid's order; null while not loaded or not title-sorted. */
    val railIds: List<String>? = null,
    val railNames: List<String> = emptyList(),
    /** A title the grid scrolls to and focuses once (letter jump). */
    val jumpTarget: String? = null,
    /** BROWSE-06. */
    val queueBusy: Boolean = false,
    val queueMessage: String? = null,
    /** PHOTO-01/02. */
    val photos: List<MediaItem> = emptyList(),
    val photoIndex: Int = 0
)

/** Non-UI session data for the stream: owned by [DesktopController.catalogSession]. */
internal class DesktopCatalogSession(val preferences: HomePreferencesStore) {
    /** BROWSE-06: the titles a Play or Shuffle queued; empty for an ordinary viewing. */
    @Volatile var queue: List<MediaItem> = emptyList()
    /** Stop returns to the page that started the viewing (queues, trailers, extras). */
    @Volatile var returnToOpener = false
    /** HOME-05: the account whose landing view this app session already applied. */
    @Volatile var landingAppliedFor: String? = null
    /** BROWSE-01: filters remembered per viewer and library for this app session. */
    val filters = ConcurrentHashMap<String, LibraryFilters>()
    @Volatile var sideJob: Job? = null
    @Volatile var railJob: Job? = null
    @Volatile var pickerJob: Job? = null
    @Volatile var saveJob: Job? = null
}

/** Libraries for the sidebar and library-name lookups: the full list, falling back to the Libraries row. */
internal fun DesktopState.sidebarLibraries(): List<MediaItem> =
    catalog.libraries.ifEmpty { homeRows.filter { it.library }.flatMap { it.items } }

/** BROWSE-06: the title after [current] in the queue, or null at its end (a queue's last title ends it). */
internal fun desktopQueueNext(queue: List<MediaItem>, current: MediaItem): MediaItem? {
    val index = queue.indexOfFirst { it.id == current.id }
    return if (index < 0) null else queue.getOrNull(index + 1)
}

internal val DESKTOP_SORTS = listOf("SortName" to "Title", "DateCreated" to "Date added", "PremiereDate" to "Release date", "CommunityRating" to "Rating")
private val QUEUE_PLAYABLE = setOf("Movie", "Episode", "Video")

/** BROWSE-04 scope plus sort, filters and paging: the one query every library page, refresh and jump uses. */
internal fun libraryQuery(library: MediaItem, sortBy: String, descending: Boolean, filters: LibraryFilters, startIndex: Int = 0, limit: Int = 60): BrowseQuery {
    val scope = if (library.type in setOf("CollectionFolder", "UserView")) libraryBrowseScope(library.collectionType) else LibraryBrowseScope(false)
    return BrowseQuery(library.id, includeItemTypes = scope.includeItemTypes, recursive = scope.recursive, sortBy = sortBy,
        descending = descending, startIndex = startIndex, limit = limit, filters = filters)
}

/** Whether a library page offers filters: title libraries and plain folders of video, not music, photos or books. */
internal fun libraryFiltersOffered(library: MediaItem?): Boolean = library != null &&
    library.type in setOf("CollectionFolder", "UserView") &&
    library.collectionType !in setOf("music", "photos", "books", "livetv", "playlists")

private fun filterKey(account: Account, libraryId: String) = "${account.key}:$libraryId"

/** HOME-13 looks at every title row (a 4K and an HD library each have a Latest row), never the Libraries row. */
private fun HomeRow.mixesLibraries() = !library

// ---------------------------------------------------------------- Home (HOME-01..13)

internal suspend fun DesktopController.loadCatalogHome(g: Long) = coroutineScope {
    val a = mutable.value.account ?: return@coroutineScope
    publish(g) { it.copy(route = DesktopRoute.Home, root = DESKTOP_ROOT_HOME) }
    val prefsRead = async { catalogSession.preferences.read(a) }
    val configuration = async { (client.configuration(a) as? ClientResult.Success)?.value?.fields }
    val viewsRead = async { client.views(a) }
    val resumeRead = async { client.resume(a) }
    val nextRead = async { client.nextUp(a) }
    val views = viewsRead.await()
    val libraries = (views as? ClientResult.Success)?.value?.items.orEmpty()
    val latest = libraries.filter { it.collectionType in listOf("movies", "tvshows") }
        .map { library -> async { library.id to client.latest(a, library.id) } }.awaitAll().toMap()
    val read = prefsRead.await()
    val preferences = read.preferences
    val pins = preferences.pins.distinctBy { it.key }.map { pin -> async { pinRow(a, pin) } }.awaitAll()
    val resume = resumeRead.await(); val next = nextRead.await()
    val failures = (listOf<ClientResult<*>>(views, resume, next) + latest.values + pins.mapNotNull { it.second })
        .filterIsInstance<ClientResult.Failure>()
    failures.firstOrNull { it.error.kind in setOf(ErrorKind.EXPIRED, ErrorKind.INVALID_CREDENTIALS) }?.let { expired ->
        attempt<Unit>(g, a) { expired }; return@coroutineScope
    }
    // HOME-09: the views read decides reach; a server that refused keeps its own error.
    val unreachable = (views as? ClientResult.Failure)?.error?.kind == ErrorKind.NETWORK
    val raw = configuredHome(libraries, (resume as? ClientResult.Success)?.value?.items.orEmpty(),
        (next as? ClientResult.Success)?.value?.items.orEmpty(),
        latest.mapValues { (_, r) -> (r as? ClientResult.Success)?.value.orEmpty() }, configuration.await()) + pins.mapNotNull { it.first }
    val customized = homeWithLibraryNames(a, customizedHome(raw, preferences), libraries)
    currentCoroutineContext().ensureActive()
    // An unreachable server with nothing earlier on the page, or a refusal, is the page's error (Retry).
    val previous = mutable.value
    val keep = unreachable && previous.homeRows.isNotEmpty()
    val notice = homeNotice(unreachable, null, keep, hasDownloads = false, serverName = a.server.name)
    if (!keep) (listOf<ClientResult<*>>(views, resume, next) + latest.values).firstOrNull { it is ClientResult.Failure }
        ?.let { failure -> attempt<Unit>(g, a) { failure as ClientResult.Failure } }
    publish(g) { s ->
        s.copy(homeRows = if (keep) s.homeRows else customized,
            catalog = s.catalog.copy(preferences = preferences, preferenceStatus = read.status, retrySave = read.retrySave,
                availableRows = if (keep) s.catalog.availableRows else raw,
                libraries = if (keep) s.catalog.libraries else libraries,
                homeNotice = notice.text.takeIf { notice.kind == HomeNoticeKind.ShowingEarlier }))
    }
    // HOME-05: once per account in this app session, after its preferences resolved. Explicit Home never redirects.
    if (catalogSession.landingAppliedFor != a.key && generation.get() == g) {
        catalogSession.landingAppliedFor = a.key
        val landing = homeLanding(true, false, preferences.landing, libraries.map { it.id }.toSet())
        val library = landing?.takeIf { it.startsWith("library:") }?.removePrefix("library:")?.let { id -> libraries.firstOrNull { it.id == id } }
        if (library != null) {
            events.log("home landing=library")
            browseRoot(library)
        }
    }
}

/** A pinned row from fresh, viewer-authorized reads only; a failed read leaves the row out (HOME-02). */
private suspend fun DesktopController.pinRow(a: Account, pin: HomePin): Pair<HomeRow?, ClientResult.Failure?> {
    val container = if (pin.kind == HomePinKind.COLLECTION || pin.kind == HomePinKind.PLAYLIST) {
        when (val r = client.item(a, pin.value)) {
            is ClientResult.Success -> r.value
            is ClientResult.Failure -> return null to r.takeIf { it.error.kind != ErrorKind.SERVER }
        }
    } else null
    return when (val r = client.items(a, pin.query())) {
        is ClientResult.Failure -> null to r
        is ClientResult.Success -> HomeRow(pin.key, container?.name ?: if (pin.kind == HomePinKind.FAVORITES) "Favorites" else pin.value, r.value.items) to null
    }
}

/** HOME-13: rows that mix libraries name the library of a title that also appears from another one. */
private suspend fun DesktopController.homeWithLibraryNames(a: Account, rows: List<HomeRow>, libraries: List<MediaItem>): List<HomeRow> = coroutineScope {
    val mixed = rows.filter { it.mixesLibraries() }
    val duplicates = homeDuplicateIds(mixed).take(40)
    if (duplicates.isEmpty()) return@coroutineScope rows
    val names = duplicates.map { id -> async { id to (client.searchAncestors(a, id) as? ClientResult.Success)?.value?.let { searchLibraryName(it, libraries) } } }
        .awaitAll().mapNotNull { (id, name) -> name?.let { id to it } }.toMap()
    rows.map { row -> if (!row.mixesLibraries()) row else row.copy(libraryNames = row.items.mapNotNull { item -> names[item.id]?.let { item.id to it } }.toMap()) }
}

/** The editor's row list: every key the active watching mode can show, hidden ones included. */
internal fun homeEditorKeys(state: DesktopState): List<String> {
    val prefs = state.catalog.preferences ?: return emptyList()
    val keys = buildList {
        add("watching"); add("resume"); add("nextup"); add("libraries")
        state.sidebarLibraries().filter { it.collectionType in listOf("movies", "tvshows") }.forEach { add("latest:${it.id}") }
        prefs.pins.forEach { add(it.key) }
    }
    return editableHomeKeys(keys, prefs)
}

internal fun homeEditorLabel(key: String, state: DesktopState): String {
    val prefs = state.catalog.preferences
    return when {
        key == "watching" || key == "resume" -> "Continue watching"
        key == "nextup" -> "Next up"
        key == "libraries" -> "Libraries"
        key.startsWith("latest:") -> state.sidebarLibraries().firstOrNull { it.id == key.removePrefix("latest:") }?.let { "Latest in ${it.name}" } ?: "Latest"
        key.startsWith("pin:") -> prefs?.pins?.firstOrNull { it.key == key }?.let { homeEditorPinLabel(it, state.catalog.availableRows) } ?: "Pinned row"
        else -> key
    }
}

fun DesktopController.openHomeEditor() {
    val s = mutable.value
    if (!canOpenHomeEditor(s.catalog.preferences != null) || s.account == null) return
    page(DesktopRoute.HomeEditor, remember = s.route != DesktopRoute.HomeEditor) { _ ->
        val c = mutable.value.catalog
        if (retryHomeSaveOnOpen(c.preferences != null, c.retrySave, c.saving)) changeHome { it }
    }
}

/** One serialized change: local commit first, then the server (HomePreferencesStore). Rows rebuild after it. */
fun DesktopController.changeHome(transform: (HomePreferences) -> HomePreferences) {
    val a = mutable.value.account ?: return
    if (mutable.value.catalog.preferences == null) return
    updateState { it.copy(catalog = it.catalog.copy(saving = true)) }
    catalogSession.saveJob = scope.launch {
        val read = try { catalogSession.preferences.change(a, transform) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        updateState { s ->
            if (s.account?.key != a.key) s
            else if (read == null) s.copy(catalog = s.catalog.copy(saving = false, preferenceStatus = "Could not save the layout. Try again.", retrySave = true))
            else s.copy(homeRows = if (s.route == DesktopRoute.Home) s.homeRows else customizedHome(s.catalog.availableRows, read.preferences),
                catalog = s.catalog.copy(saving = false, preferences = read.preferences, preferenceStatus = read.status, retrySave = read.retrySave))
        }
        if (read != null) refreshHomeRowsQuietly(a)
    }
}

/** After a layout change: rebuild Home's rows (pins need fresh reads) without moving the viewer. */
private suspend fun DesktopController.refreshHomeRowsQuietly(a: Account) = coroutineScope {
    val prefs = mutable.value.catalog.preferences ?: return@coroutineScope
    val base = mutable.value.catalog.availableRows.filterNot { it.key.startsWith("pin:") }
    val pins = prefs.pins.distinctBy { it.key }.map { pin -> async { pinRow(a, pin).first } }.awaitAll().filterNotNull()
    val raw = base + pins
    val rows = homeWithLibraryNames(a, customizedHome(raw, prefs), mutable.value.sidebarLibraries())
    updateState { s -> if (s.account?.key != a.key) s else s.copy(homeRows = rows, catalog = s.catalog.copy(availableRows = raw)) }
}

/** HOME-02 picker: one bounded, searchable page read as the viewer. */
fun DesktopController.loadPinChoices(kind: HomePinKind, term: String) {
    val a = mutable.value.account ?: return
    catalogSession.pickerJob?.cancel()
    updateState { it.copy(catalog = it.catalog.copy(people = emptyList(), peopleError = null)) }
    val wire = when (kind) { HomePinKind.COLLECTION -> "BoxSet"; HomePinKind.PLAYLIST -> "Playlist"; HomePinKind.GENRE -> "Genres"; HomePinKind.STUDIO -> "Studios"; HomePinKind.FAVORITES -> return }
    catalogSession.pickerJob = scope.launch {
        if (term.isNotBlank()) delay(300)
        val r = client.namedItems(a, wire, "", term.trim())
        ensureActive()
        updateState { s -> s.copy(catalog = s.catalog.copy(people = (r as? ClientResult.Success)?.value?.items.orEmpty(),
            peopleError = (r as? ClientResult.Failure)?.error?.message)) }
    }
}

fun DesktopController.addPin(pin: HomePin) = changeHome { p -> if (p.pins.any { it.key == pin.key }) p else p.copy(pins = p.pins + pin) }
fun DesktopController.removePin(pin: HomePin) = changeHome { p ->
    p.copy(pins = p.pins.filterNot { it.key == pin.key }, order = p.order.filterNot { it == pin.key }, hidden = p.hidden - pin.key)
}

// ---------------------------------------------------------------- Library (BROWSE-01..06, HOME-06)

internal fun DesktopController.browseLibrary(item: MediaItem, sortBy: String?, descending: Boolean?) {
    if (isLiveTvView(item)) { openLiveTv(item); return }
    val before = mutable.value
    val display = before.catalog.preferences?.libraries?.get(item.id) ?: LibraryDisplay()
    val sort = (sortBy ?: display.sortBy).takeIf { s -> DESKTOP_SORTS.any { it.first == s } } ?: "SortName"
    val direction = descending ?: display.descending
    val account = before.account
    val sameLibrary = before.route == DesktopRoute.Library && before.library?.id == item.id
    val filters = when {
        sameLibrary -> before.catalog.filters
        account != null -> catalogSession.filters[filterKey(account, item.id)] ?: LibraryFilters()
        else -> LibraryFilters()
    }
    // HOME-06: an explicit sort change is remembered for this viewer and library.
    if ((sortBy != null || descending != null) && (sort != display.sortBy || direction != display.descending)) {
        changeHome { p -> p.copy(libraries = p.libraries + (item.id to (p.libraries[item.id] ?: LibraryDisplay()).copy(sortBy = sort, descending = direction))) }
    }
    loadLibrary(item, sort, direction, filters, display.list.takeUnless { sameLibrary } ?: before.catalog.listView, remember = before.library?.id != item.id || before.route != DesktopRoute.Library)
}

/** BROWSE-03: a sort or filter change starts a new result generation (page() cancels the old reads). */
private fun DesktopController.loadLibrary(item: MediaItem, sort: String, descending: Boolean, filters: LibraryFilters, list: Boolean, remember: Boolean) {
    page(DesktopRoute.Library, remember = remember) { g ->
        val a = mutable.value.account ?: return@page
        catalogSession.filters[filterKey(a, item.id)] = filters
        publish(g) { it.copy(library = item, sortBy = sort, descending = descending, items = emptyList(), total = 0,
            catalog = it.catalog.copy(filters = filters, listView = list, railIds = null, railNames = emptyList(), jumpTarget = null,
                queueMessage = null, facets = if (it.library?.id == item.id) it.catalog.facets else null, facetsError = null)) }
        if (isLiveTvView(item)) return@page
        loadRail(g, a, item, sort, descending, filters)
        val p = attempt(g, a) { client.items(a, libraryQuery(item, sort, descending, filters)) } ?: return@page
        if (generation.get() == g) setNextPage(p.startIndex + p.items.size)
        publish(g) { it.copy(items = p.items.distinctBy(MediaItem::id), total = p.totalRecordCount) }
    }
}

private fun DesktopController.loadRail(g: Long, a: Account, item: MediaItem, sort: String, descending: Boolean, filters: LibraryFilters) {
    catalogSession.railJob?.cancel()
    if (sort != "SortName") return
    catalogSession.railJob = scope.launch {
        val r = client.sortNames(a, libraryQuery(item, sort, descending, filters))
        if (r is ClientResult.Success) publish(g) { it.copy(catalog = it.catalog.copy(railIds = r.value.items.map(MediaItem::id),
            railNames = r.value.items.map { i -> i.sortName ?: i.name })) }
    }
}

fun DesktopController.setLibraryFilters(filters: LibraryFilters) {
    val s = mutable.value
    val library = s.library ?: return
    if (s.route != DesktopRoute.Library) return
    loadLibrary(library, s.sortBy, s.descending, filters, s.catalog.listView, remember = false)
}

fun DesktopController.setLibraryListView(list: Boolean) {
    val library = mutable.value.library ?: return
    updateState { it.copy(catalog = it.catalog.copy(listView = list)) }
    changeHome { p -> p.copy(libraries = p.libraries + (library.id to (p.libraries[library.id] ?: LibraryDisplay()).copy(list = list))) }
}

/** BROWSE-01 choices: user/library-scoped facets, authorized through the library item first (core). */
fun DesktopController.loadFacets() {
    val s = mutable.value; val a = s.account ?: return; val library = s.library ?: return
    if (s.catalog.facets != null) return
    catalogSession.sideJob = scope.launch {
        when (val r = client.facets(a, library.id)) {
            is ClientResult.Success -> updateState { if (it.library?.id == library.id) it.copy(catalog = it.catalog.copy(facets = r.value, facetsError = null)) else it }
            is ClientResult.Failure -> updateState { if (it.library?.id == library.id) it.copy(catalog = it.catalog.copy(facetsError = "Filter choices could not load. ${r.error.message}")) else it }
        }
    }
}

/** BROWSE-01 person choices: viewer-scoped people under this library. */
fun DesktopController.searchPeople(term: String) {
    val s = mutable.value; val a = s.account ?: return; val library = s.library ?: return
    catalogSession.pickerJob?.cancel()
    catalogSession.pickerJob = scope.launch {
        if (term.isNotBlank()) delay(300)
        val r = client.namedItems(a, "Persons", library.id, term.trim())
        ensureActive()
        updateState { it.copy(catalog = it.catalog.copy(people = (r as? ClientResult.Success)?.value?.items.orEmpty(),
            peopleError = (r as? ClientResult.Failure)?.error?.message)) }
    }
}

/** BROWSE-05: the list stays whole; pages load through the letter's first title, which takes focus. */
fun DesktopController.jumpToLetter(letter: String) {
    val s = mutable.value; val ids = s.catalog.railIds ?: return; val a = s.account ?: return; val library = s.library ?: return
    val index = letterJump(s.catalog.railNames, letter).index
    if (index < 0) return
    val target = ids[index]
    val g = generation.get()
    scope.launch {
        var guard = 0
        while (mutable.value.items.none { it.id == target } && guard++ < 60 && generation.get() == g) {
            val loaded = mutable.value.items.size
            if (loaded >= mutable.value.total && mutable.value.total > 0) break
            val p = attempt(g, a) { client.items(a, libraryQuery(library, s.sortBy, s.descending, s.catalog.filters, startIndex = loaded, limit = 200)) } ?: return@launch
            if (p.items.isEmpty()) break
            setNextPage(loaded + p.items.size)
            publish(g) { it.copy(items = (it.items + p.items).distinctBy(MediaItem::id), total = p.totalRecordCount) }
        }
        publish(g) { it.copy(catalog = it.catalog.copy(jumpTarget = target)) }
    }
}

fun DesktopController.jumpDone() { updateState { it.copy(catalog = it.catalog.copy(jumpTarget = null)) } }

/** BROWSE-06: Play or Shuffle a box set, or a Movies library under its filters. At most [QUEUE_LIMIT] titles. */
fun DesktopController.playCollection(shuffle: Boolean) {
    val s = mutable.value
    val parent = s.library ?: return; val a = s.account ?: return
    val scope = collectionPlayScope(parent.type, parent.collectionType) ?: return
    if (s.catalog.queueBusy || s.busy) return
    updateState { it.copy(catalog = it.catalog.copy(queueBusy = true, queueMessage = null)) }
    val g = generation.get()
    this.scope.launch {
        try {
            val queued = mutableListOf<MediaItem>()
            while (queued.size < QUEUE_LIMIT) {
                val p = attempt(g, a) {
                    client.items(a, BrowseQuery(parent.id, includeItemTypes = scope.includeItemTypes, recursive = scope.recursive,
                        sortBy = if (shuffle) "Random" else s.sortBy, descending = !shuffle && s.descending, startIndex = queued.size,
                        limit = minOf(200, QUEUE_LIMIT - queued.size), filters = if (scope.filtered) s.catalog.filters else LibraryFilters()))
                } ?: return@launch
                queued += p.items
                if (p.items.isEmpty() || queued.size >= p.totalRecordCount) break
            }
            val playable = queued.filter { it.type in QUEUE_PLAYABLE && it.locationType != "Virtual" && !it.isFolder }.distinctBy { it.id }.take(QUEUE_LIMIT)
            if (generation.get() != g) return@launch
            if (playable.isEmpty()) {
                updateState { it.copy(catalog = it.catalog.copy(queueMessage = "Nothing here can be played.")) }
                return@launch
            }
            events.log("queue start shuffle=$shuffle size=${playable.size}")
            updateState { it.copy(catalog = it.catalog.copy(queueBusy = false)) }
            play(playable.first(), fromStart = true, queue = playable, returnToOpener = true)
        } finally {
            updateState { it.copy(catalog = it.catalog.copy(queueBusy = false)) }
        }
    }
}

// ---------------------------------------------------------------- Title pages (CAT-30..32)

internal fun DesktopController.loadDetailDiscovery(g: Long, a: Account, item: MediaItem) {
    publish(g) { it.copy(catalog = it.catalog.copy(discoveryFor = item.id, trailers = emptyList(), extras = emptyList(), similar = emptyList(), trailerMessage = null)) }
    val reads = detailDiscoveryReads(item.type)
    if (!reads.any) return
    catalogSession.sideJob = scope.launch {
        // The rows never hold the page: each read publishes on its own and a failed one leaves its row out.
        launch {
            if (!reads.trailers) return@launch
            val local = (client.localTrailers(a, item.id) as? ClientResult.Success)?.value.orEmpty()
            val choices = trailerChoices(local, item.remoteTrailers)
            publish(g) { if (it.catalog.discoveryFor == item.id) it.copy(catalog = it.catalog.copy(trailers = choices)) else it }
        }
        launch {
            if (!reads.extras) return@launch
            val r = client.specialFeatures(a, item.id) as? ClientResult.Success ?: return@launch
            publish(g) { if (it.catalog.discoveryFor == item.id) it.copy(catalog = it.catalog.copy(extras = specialFeatureExtras(r.value))) else it }
        }
        launch {
            if (!reads.similar) return@launch
            val r = client.similar(a, item.id) as? ClientResult.Success ?: return@launch
            publish(g) { if (it.catalog.discoveryFor == item.id) it.copy(catalog = it.catalog.copy(similar = moreLikeThis(item, r.value.items))) else it }
        }
    }
}

/** The line under a trailer card (CAT-30): "Trailer · 2m", "YouTube" or the site's host. */
internal fun trailerLine(choice: TrailerChoice): String = when {
    choice.item != null -> listOf("Trailer", runtimeLabel(choice.item?.runTimeTicks)).filter(String::isNotBlank).joinToString(" · ")
    choice.youTubeId != null -> "YouTube"
    else -> choice.url?.let { runCatching { java.net.URI(it).host?.removePrefix("www.") }.getOrNull() } ?: "Online"
}

internal fun extraLine(extra: MediaItem): String =
    listOfNotNull(extraLabel(extra.extraType), runtimeLabel(extra.runTimeTicks).takeIf(String::isNotBlank)).joinToString(" · ")

/** A local trailer plays from its start; a remote one opens in the browser (CAT-30). */
fun DesktopController.openTrailer(choice: TrailerChoice, browse: (String) -> Boolean = ::desktopBrowse) {
    choice.item?.let { play(it, fromStart = true, returnToOpener = true); return }
    val url = choice.url ?: return
    val opened = runCatching { browse(url) }.getOrDefault(false)
    updateState { it.copy(catalog = it.catalog.copy(trailerMessage = if (opened) null else
        "This trailer is online. Install a web browser on this computer to watch it.")) }
}

/** An extra plays, resuming where the viewer left it (CAT-31). */
fun DesktopController.playExtra(extra: MediaItem) = play(extra, returnToOpener = true)

internal fun desktopBrowse(url: String): Boolean {
    val uri = java.net.URI(url)
    if (uri.scheme !in setOf("http", "https")) return false
    if (!java.awt.Desktop.isDesktopSupported()) return false
    val desktop = java.awt.Desktop.getDesktop()
    if (!desktop.isSupported(java.awt.Desktop.Action.BROWSE)) return false
    desktop.browse(uri)
    return true
}

/** Stop from a viewing a queue, trailer or extra started returns to that page (BROWSE-06), not to the title played. */
internal fun DesktopController.returnToOpener(): Boolean {
    if (!catalogSession.returnToOpener) return false
    catalogSession.returnToOpener = false
    catalogSession.queue = emptyList()
    val previous = synchronized(history) { if (history.isEmpty()) null else history.removeAt(history.lastIndex) } ?: return false
    page(previous.route) { g ->
        publish(g) { previous.copy(busy = true, playback = null, settings = it.settings, error = null, errorKind = null,
            search = it.search, catalog = previous.catalog.copy(preferences = it.catalog.preferences, queueBusy = false)) }
        val a = mutable.value.account ?: return@page
        when (previous.route) {
            DesktopRoute.Detail -> previous.detail?.let { loadDetail(g, it, previous.selectedSeason) }
            DesktopRoute.Library -> previous.library?.let { library ->
                val r = attempt(g, a) { client.items(a, libraryQuery(library, previous.sortBy, previous.descending, previous.catalog.filters, limit = maxOf(60, previous.items.size).coerceAtMost(200))) }
                if (r != null) { setNextPage(r.startIndex + r.items.size); publish(g) { it.copy(items = r.items.distinctBy(MediaItem::id), total = r.totalRecordCount) } }
            }
            DesktopRoute.Home -> loadCatalogHome(g)
            else -> Unit
        }
    }
    return true
}

// ---------------------------------------------------------------- Photos (PHOTO-01/02)

/** A photo opens among its folder's photos, in the grid's order; a failed folder read still shows it. */
fun DesktopController.openPhoto(item: MediaItem) {
    val before = mutable.value
    page(DesktopRoute.Photo, remember = true) { g ->
        val a = mutable.value.account ?: return@page
        publish(g) { it.copy(catalog = it.catalog.copy(photos = listOf(item), photoIndex = 0)) }
        // Jellyfin leaves ParentId out of list responses unless asked, so a grid's photo usually lacks it: read the item.
        val parent = item.parentId
            ?: (client.item(a, item.id) as? ClientResult.Success)?.value?.parentId
            ?: return@page
        val fromGrid = before.route == DesktopRoute.Library && before.library?.id == parent
        val r = client.photos(a, parent, if (fromGrid) before.sortBy else "SortName", fromGrid && before.descending)
        val photos = (r as? ClientResult.Success)?.value?.items.orEmpty().filter { it.type == "Photo" }
        if (photos.isEmpty()) return@page
        val list = if (photos.any { it.id == item.id }) photos else listOf(item) + photos
        publish(g) { it.copy(catalog = it.catalog.copy(photos = list, photoIndex = list.indexOfFirst { p -> p.id == item.id }.coerceAtLeast(0))) }
    }
}

/** PHOTO-02: manual moves stop at the ends; a running slideshow wraps. False when there is nowhere to go. */
fun DesktopController.stepPhoto(forward: Boolean, slideshow: Boolean): Boolean {
    val c = mutable.value.catalog
    val next = photoStep(c.photoIndex, c.photos.size, forward, slideshow) ?: return false
    updateState { it.copy(catalog = it.catalog.copy(photoIndex = next)) }
    return true
}
