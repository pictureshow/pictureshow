/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.pictureshow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.pictureshow.core.Account
import app.pictureshow.core.CatalogService
import app.pictureshow.core.ClientResult
import app.pictureshow.core.GridSorts
import app.pictureshow.core.HomePin
import app.pictureshow.core.HomePinKind
import app.pictureshow.core.HomePreferences
import app.pictureshow.core.LibraryFacets
import app.pictureshow.core.LibraryFilters
import app.pictureshow.core.MediaItem
import app.pictureshow.core.VideoResolution
import app.pictureshow.core.editableHomeKeys
import app.pictureshow.core.homeEditorPinLabel
import app.pictureshow.core.imageUrl
import app.pictureshow.core.moveHomeRow
import app.pictureshow.core.namedPickerChoices
import coil3.compose.AsyncImage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Shared TV settings shell; touch uses a native app bar and a centred, bounded editor. */
@Composable
private fun CatalogEditor(
    title: String,
    close: () -> Unit,
    first: FocusRequester,
    list: LazyListState = rememberLazyListState(),
    footer: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    val tv = usesTvInterface(LocalContext.current)
    MaterialTheme(
        colorScheme = if (tv) darkColorScheme(background = CatalogBackground, surface = CatalogBackground) else MaterialTheme.colorScheme
    ) {
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            if (tv) {
                TvSettingsPanel(title, close, first, scrollContent = false, footer = footer, lazyState = list) {
                    LazyColumn(
                        state = list,
                        modifier = Modifier.fillMaxSize().testTag("catalog:editor"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        content = content
                    )
                }
            } else {
                Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                            TopAppBar(title = { Text(title) }, navigationIcon = {
                                IconButton(onClick = close, modifier = Modifier.testTag("catalog:back")) {
                                    Icon(touchActionIcon("back"), "Back")
                                }
                            })
                            LazyColumn(
                                state = list,
                                modifier = Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth().testTag("catalog:editor"),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                content = content
                            )
                            footer?.let {
                                Box(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) { it() }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogSection(title: String) {
    Text(title, Modifier.padding(top = 24.dp, bottom = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

/** These adapt touch roles to the same TV components used by the main settings page. */
@Composable
private fun <T> CatalogChoiceRow(
    title: String,
    value: T,
    values: List<T>,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    change: (T) -> Unit
) {
    val tv = usesTvInterface(LocalContext.current)
    if (tv) {
        TvSettingsChoiceRow(title, value, values, label, modifier, enabled = enabled, onSelect = change)
    } else {
        var choosing by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(label(value)) },
            trailingContent = { Icon(touchActionIcon("chevron-right"), null) },
            modifier = modifier.clickable(enabled = enabled) { choosing = true }.semantics { stateDescription = label(value) }
        )
        if (choosing) CatalogChoicePicker(title, value, values, label, change) { choosing = false }
    }
}

@Composable
private fun CatalogToggleRow(
    title: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    change: (Boolean) -> Unit
) {
    if (usesTvInterface(LocalContext.current)) {
        TvSettingsToggleRow(title, checked, modifier, enabled = enabled, onToggle = change)
    } else {
        ListItem(
            headlineContent = { Text(title) },
            trailingContent = { Switch(checked, onCheckedChange = null, enabled = enabled) },
            modifier = modifier.clickable(enabled = enabled, role = Role.Switch) { change(!checked) }.semantics {
                toggleableState =
                    if (checked) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off
                stateDescription = if (checked) "On" else "Off"
            }
        )
    }
}

@Composable
private fun CatalogActionRow(title: String, action: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    if (usesTvInterface(LocalContext.current)) {
        TvSettingsActionRow(title, modifier, enabled = enabled, onClick = action)
    } else {
        ListItem(
            headlineContent = { Text(title) },
            trailingContent = { Icon(touchActionIcon("chevron-right"), null) },
            modifier = modifier.clickable(enabled = enabled, onClick = action)
        )
    }
}

@Composable
private fun CatalogCommand(
    title: String,
    action: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    if (usesTvInterface(LocalContext.current)) {
        if (primary) {
            androidx.tv.material3.Button(onClick = action, modifier = modifier.heightIn(min = 48.dp), enabled = enabled) {
                androidx.tv.material3.Text(title)
            }
        } else {
            androidx.tv.material3.OutlinedButton(onClick = action, modifier = modifier.heightIn(min = 48.dp), enabled = enabled) {
                androidx.tv.material3.Text(title)
            }
        }
    } else if (primary) {
        Button(onClick = action, modifier = modifier.heightIn(min = 48.dp), enabled = enabled) { Text(title) }
    } else {
        TextButton(onClick = action, modifier = modifier.heightIn(min = 48.dp), enabled = enabled) { Text(title) }
    }
}

@Composable
private fun <T> CatalogChoicePicker(
    title: String,
    value: T,
    values: List<T>,
    label: (T) -> String,
    select: (T) -> Unit,
    close: () -> Unit
) {
    if (usesTvInterface(LocalContext.current)) {
        TvSettingsChoicePicker(title, value, values, label, select, close)
    } else {
        val first = remember { FocusRequester() }
        val index = values.indexOf(value).coerceAtLeast(0)
        val list = rememberLazyListState(initialFirstVisibleItemIndex = index)
        CatalogEditor(title, close, first, list) {
            items(values.indices.toList()) { i ->
                val selected = values[i] == value
                ListItem(
                    headlineContent = { Text(label(values[i])) },
                    trailingContent = { RadioButton(selected, onClick = null) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("catalog:option:$i")
                        .semantics {
                            this.selected = selected
                            role = Role.RadioButton
                            stateDescription = if (selected) "Selected" else ""
                        }
                        .clickable {
                            select(values[i])
                            close()
                        }
                )
            }
        }
    }
}

@Composable
internal fun CatalogSortPicker(state: CatalogGridState, sort: (String, Boolean) -> Unit, close: () -> Unit) {
    val options = GridSorts.flatMap { (key, label) ->
        listOf(
            Triple(key, false, "$label · Ascending"),
            Triple(key, true, "$label · Descending")
        )
    }
    val current = options.firstOrNull { it.first == state.sortBy && it.second == state.descending } ?: options.first()
    CatalogChoicePicker("Sort", current, options, { it.third }, { sort(it.first, it.second) }, close)
}

@Composable
internal fun HomeCustomization(
    state: CatalogHomeState,
    account: Account,
    service: CatalogService,
    change: ((HomePreferences) -> HomePreferences) -> Unit,
    close: () -> Unit
) {
    val tv = usesTvInterface(LocalContext.current)
    val first = remember { FocusRequester() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf<HomePinKind?>(null) }
    var moving by remember { mutableStateOf<String?>(null) }
    var draftOrder by remember { mutableStateOf<List<String>?>(null) }
    val preferences = state.preferences
    val defaults =
        listOf(
            "resume" to "Continue watching",
            "nextup" to "Next up",
            "watching" to "Continue watching and next up",
            "libraries" to "Libraries"
        ) +
            state.libraries.filter { it.collectionType in listOf("movies", "tvshows") }.map { "latest:${it.id}" to "Latest in ${it.name}" }
    val known = (
        defaults + state.availableRows.filterNot { it.key.startsWith("pin:") }.map { it.key to it.title } +
            preferences.pins.map { it.key to homeEditorPinLabel(it, state.availableRows) }
        ).distinctBy { it.first }.toMap()
    val rows = editableHomeKeys(known.keys.toList(), preferences.copy(order = draftOrder ?: preferences.order))
    fun finish() {
        draftOrder?.let { order -> if (order != preferences.order) change { it.copy(order = order) } }
        moving = null
        draftOrder = null
    }
    BackHandler(enabled = moving != null) {
        moving = null
        draftOrder = null
    }
    CatalogEditor("Customize home", {
        if (moving != null) {
            moving = null
            draftOrder = null
        } else {
            close()
        }
    }, first, list) {
        if (state.saving) item("saving") { Text("Saving layout…", Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        state.preferenceStatus?.let { status ->
            item("save-status") {
                Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (state.preferenceRetryable) {
                        CatalogCommand("Retry", {
                            change { it }
                        }, Modifier.testTag("home:retry-save"), enabled = !state.saving)
                    }
                }
            }
        }
        item("rows-heading") { CatalogSection("Rows") }
        items(rows, key = { it }) { key ->
            val index = rows.indexOf(key)
            HomeReorderRow(
                key, known.getValue(key), key !in preferences.hidden, tv, moving == key,
                !state.saving && (moving == null || moving == key),
                index > 0, index < rows.lastIndex,
                toggle = { if (moving == null) change { it.copy(hidden = if (key in it.hidden) it.hidden - key else it.hidden + key) } },
                pickOrDrop = {
                    if (moving == key) {
                        finish()
                    } else {
                        moving = key
                        draftOrder = preferences.order
                    }
                },
                move = { delta ->
                    draftOrder = moveHomeRow(draftOrder ?: preferences.order, rows, key, delta)
                    scope.launch {
                        list.animateScrollToItem(
                            (if (state.saving) 1 else 0) + (if (state.preferenceStatus != null) 1 else 0) + 1 +
                                (index + delta).coerceIn(rows.indices)
                        )
                    }
                }, endDrag = ::finish,
                unpin = if (key.startsWith("pin:")) {
                    (
                        {
                            change {
                                it.copy(
                                    pins = it.pins.filterNot { pin -> pin.key == key },
                                    order =
                                    it.order - key,
                                    hidden = it.hidden - key
                                )
                            }
                        }
                        )
                } else {
                    null
                },
                modifier = Modifier.animateItem().then(if (index == 0) Modifier.focusRequester(first) else Modifier)
            )
        }
        item("combine") {
            CatalogToggleRow(
                "Combine Continue watching and Next up",
                preferences.mergeWatching,
                Modifier.testTag("home:combine"),
                enabled = !state.saving && moving == null
            ) { value ->
                change { it.copy(mergeWatching = value) }
            }
        }
        item("landing") {
            val values = listOf("home" to "Home", "libraries" to "Libraries") + state.libraries.map { "library:${it.id}" to it.name }
            CatalogChoiceRow(
                "Landing view",
                preferences.landing,
                values.map { it.first },
                { key ->
                    values.firstOrNull { it.first == key }?.second
                        ?: "Home"
                },
                Modifier.testTag("home:landing"),
                enabled = !state.saving
            ) { value -> change { it.copy(landing = value) } }
        }
        item("add-heading") { CatalogSection("Add a row") }
        items(HomePinKind.entries) { kind ->
            val label = when (kind) {
                HomePinKind.COLLECTION -> "Add a collection"
                HomePinKind.PLAYLIST -> "Add a playlist"
                HomePinKind.FAVORITES -> "Add favorites"
                HomePinKind.GENRE -> "Add a genre"
                HomePinKind.STUDIO -> "Add a studio"
            }
            CatalogActionRow(label, {
                if (kind == HomePinKind.FAVORITES) {
                    change { it.copy(pins = (it.pins + HomePin(kind)).distinctBy(HomePin::key)) }
                } else {
                    picker = kind
                }
            }, enabled = !state.saving)
        }
        item("next-heading") { CatalogSection("Next up") }
        val episodes = state.availableRows.firstOrNull {
            it.key == "nextup"
        }?.items.orEmpty().filter { it.seriesId != null }.distinctBy { it.seriesId }
        if (episodes.isEmpty() && preferences.hiddenSeries.isEmpty()) item { Text("No series in Next up.") }
        items(episodes, key = { "hide:${it.seriesId}" }) { episode ->
            val id = requireNotNull(episode.seriesId)
            CatalogToggleRow(
                episode.seriesName ?: episode.name,
                id !in preferences.hiddenSeries,
                Modifier.testTag("home:nextup:$id"),
                enabled = !state.saving
            ) { show ->
                change { it.copy(hiddenSeries = if (show) it.hiddenSeries - id else it.hiddenSeries + id) }
            }
        }
        if (preferences.hiddenSeries.isNotEmpty()) {
            item {
                CatalogCommand("Restore removed series", { change { it.copy(hiddenSeries = emptySet()) } }, enabled = !state.saving)
            }
        }
        item { CatalogCommand("Restore default layout", { change { HomePreferences(libraries = it.libraries) } }, enabled = !state.saving) }
    }
    picker?.let { kind ->
        NamedCatalogPicker(
            account,
            service,
            when (kind) {
                HomePinKind.COLLECTION -> "BoxSet"
                HomePinKind.PLAYLIST -> "Playlist"
                HomePinKind.GENRE -> "Genres"
                HomePinKind.STUDIO -> "Studios"
                HomePinKind.FAVORITES -> error("Favorites needs no picker")
            },
            selectedKeys = preferences.pins.filter { it.kind == kind }.map { it.value }.toSet(),
            select = { item ->
                val pin = HomePin(kind, if (kind in listOf(HomePinKind.GENRE, HomePinKind.STUDIO)) item.name else item.id)
                change { it.copy(pins = (it.pins + pin).distinctBy(HomePin::key)) }
                picker = null
            },
            close = { picker = null }
        )
    }
}

@Composable
internal fun NamedCatalogPicker(
    account: Account,
    service: CatalogService,
    kind: String,
    parentId: String = "",
    select: (MediaItem) -> Unit,
    close: () -> Unit,
    selectedKeys: Set<String> = emptySet()
) {
    val tv = usesTvInterface(LocalContext.current)
    val first = remember { FocusRequester() }
    val choiceFocus = remember { FocusRequester() }
    val list = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    var requested by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var pageChoices by remember(account.key, kind, parentId) { mutableStateOf(emptyList<MediaItem>()) }
    var currentChoices by remember(account.key, kind, parentId) { mutableStateOf(emptyList<MediaItem>()) }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(account.key, kind, parentId, selectedKeys) {
        currentChoices = emptyList()
        val parentAllowed = parentId.isBlank() || service.item(account, parentId) is ClientResult.Success
        val authorized = if (!parentAllowed) {
            emptyList()
        } else {
            selectedKeys.take(60).mapNotNull { key ->
                if (kind in listOf("Genres", "Studios")) {
                    (service.namedItems(account, kind, parentId, key, 0) as? ClientResult.Success)?.value?.items
                        ?.firstOrNull { it.name == key }
                } else {
                    (service.item(account, key) as? ClientResult.Success)?.value?.takeIf { it.id == key && it.name.isNotBlank() }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        currentChoices = authorized
    }
    LaunchedEffect(query) {
        delay(300)
        page = 0
        requested = query.trim()
    }
    LaunchedEffect(kind, parentId, requested, page, retry) {
        loading = true
        pageChoices = emptyList()
        error = null
        val result = service.namedItems(account, kind, parentId, requested, page)
        currentCoroutineContext().ensureActive()
        when (result) {
            is ClientResult.Success -> {
                pageChoices = result.value.items.distinctBy(MediaItem::id)
                total = result.value.totalRecordCount
            }
            is ClientResult.Failure -> {
                pageChoices = emptyList()
                error = result.error.message
            }
        }
        loading = false
    }
    val choices = if (loading || error != null || query.trim() != requested) {
        emptyList()
    } else {
        namedPickerChoices(currentChoices, pageChoices, requested, page)
    }
    fun selected(item: MediaItem) = item.id in selectedKeys || item.name in selectedKeys
    val initial = choices.indexOfFirst(::selected).coerceAtLeast(0)
    val title = when (kind) {
        "BoxSet" -> "Choose a collection"
        "Playlist" -> "Choose a playlist"
        "Genres" -> "Choose a genre"
        "Studios" -> "Choose a studio"
        "Persons" -> "Choose a person"
        else -> "Choose a row"
    }
    CatalogEditor(title, close, first, list) {
        item("search") {
            OutlinedTextField(
                query,
                { query = it.take(200) },
                label = { Text("Find by name") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).focusRequester(first),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    requested = query.trim()
                    page = 0
                    keyboard?.hide()
                })
            )
        }
        if (loading) item("loading") { Text("Loading…") }
        error?.let {
            item("error") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(it)
                    CatalogCommand("Retry", { retry++ })
                }
            }
        }
        if (!loading &&
            error == null &&
            choices.isEmpty()
        ) {
            item("empty") { Text(if (requested.isBlank()) "No choices available" else "No matches for “$requested”") }
        }
        items(choices, key = MediaItem::id) { item ->
            val checked = selected(item)
            val support = (item.recursiveItemCount ?: item.childCount)?.let { "$it titles" }
            val modifier = (if (choices.indexOf(item) == initial) Modifier.focusRequester(choiceFocus) else Modifier)
                .testTag("catalog:choice:${item.id}").semantics {
                    this.selected = checked
                    stateDescription =
                        if (checked) "Selected" else ""
                }
            val avatar: (@Composable () -> Unit)? = if (kind == "Persons") ({ CatalogPersonAvatar(item, account) }) else null
            if (tv) {
                TvSettingsRow(
                    item.name,
                    support,
                    modifier,
                    selected = checked,
                    onClick = { select(item) },
                    leading = avatar,
                    trailing = { androidx.tv.material3.RadioButton(checked, onClick = null, modifier = Modifier.clearAndSetSemantics {}) }
                )
            } else {
                ListItem(
                    headlineContent = { Text(item.name) },
                    supportingContent = support?.let { { Text(it) } },
                    leadingContent = avatar,
                    trailingContent = { RadioButton(checked, onClick = null) },
                    modifier = modifier.clickable { select(item) }
                )
            }
        }
        if (page > 0) item { CatalogCommand("Previous choices", { page = (page - 60).coerceAtLeast(0) }, enabled = !loading) }
        if (page + pageChoices.size < total &&
            pageChoices.isNotEmpty()
        ) {
            item { CatalogCommand("More choices", { page += 60 }, enabled = !loading) }
        }
    }
    LaunchedEffect(loading, requested, page, currentChoices) {
        if (tv && !loading && choices.isNotEmpty() && query.isBlank()) {
            list.scrollToItem(initial + 1)
            withFrameNanos { }
            choiceFocus.requestFocus()
            keyboard?.hide()
        }
    }
}

@Composable
private fun CatalogPersonAvatar(item: MediaItem, account: Account) {
    Box(Modifier.size(40.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
        Text(item.name.split(' ').mapNotNull { it.firstOrNull() }.take(2).joinToString(""), style = MaterialTheme.typography.titleSmall)
        imageUrl(account.server, item, 80)?.let {
            AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
    }
}

@Composable
private fun CatalogYearPicker(year: Int?, years: List<Int>, select: (Int?) -> Unit, close: () -> Unit) {
    var decade by remember { mutableStateOf<Int?>(null) }
    val decades = years.map { it / 10 * 10 }.distinct().sortedDescending()
    if (decade == null) {
        CatalogChoicePicker(
            "Choose a decade",
            year?.let { it / 10 * 10 },
            listOf(null) + decades,
            { it?.let { start -> "$start–${start + 9}" } ?: "Any year" },
            { value ->
                if (value == null) {
                    select(null)
                    close()
                } else {
                    decade = value
                }
            },
            { if (decade == null) close() }
        )
        BackHandler { close() }
    } else {
        CatalogChoicePicker(
            "Choose a year",
            year,
            years.filter { it / 10 * 10 == decade }.sortedDescending().map { it as Int? },
            { it.toString() },
            {
                select(it)
                close()
            },
            { decade = null }
        )
    }
}

@Composable
internal fun LibraryFilterEditor(state: CatalogGridState, service: CatalogService, apply: (LibraryFilters) -> Unit, close: () -> Unit) {
    val first = remember { FocusRequester() }
    var draft by remember(state.parent.id) { mutableStateOf(state.filters) }
    var facets by remember { mutableStateOf(LibraryFacets()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var personPicker by remember { mutableStateOf(false) }
    var personLabel by remember { mutableStateOf("Selected person") }
    LaunchedEffect(state.account.key, state.parent.id, draft.personId) {
        personLabel = "Selected person"
        draft.personId?.let { id ->
            val parent = service.item(state.account, state.parent.id)
            val result = if (parent is ClientResult.Success) service.item(state.account, id) else parent
            currentCoroutineContext().ensureActive()
            if (result is ClientResult.Success && result.value.name.isNotBlank()) personLabel = result.value.name
        }
    }
    LaunchedEffect(state.parent.id, retry) {
        loading = true
        facets = LibraryFacets()
        error = null
        val result = service.facets(state.account, state.parent.id)
        currentCoroutineContext().ensureActive()
        when (result) {
            is ClientResult.Success -> facets = result.value
            is ClientResult.Failure -> error = result.error.message
        }
        loading = false
    }
    CatalogEditor("Filters", close, first, footer = {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CatalogCommand("Apply", {
                apply(draft)
                close()
            }, Modifier.testTag("filters:apply"), primary = true)
            CatalogCommand("Clear all", { draft = LibraryFilters() }, Modifier.testTag("filters:clear"))
        }
    }) {
        item {
            CatalogToggleRow("Unwatched", draft.unwatched, Modifier.focusRequester(first).testTag("filters:unwatched")) {
                draft =
                    draft.copy(unwatched = it)
            }
        }
        item { CatalogToggleRow("Favorites", draft.favorite, Modifier.testTag("filters:favorites")) { draft = draft.copy(favorite = it) } }
        item {
            CatalogChoiceRow(
                "Genre",
                draft.genre,
                listOf(null) + facets.genres.distinct(),
                { it ?: "Any" },
                Modifier.testTag("filters:genre"),
                enabled = !loading && error == null
            ) { draft = draft.copy(genre = it) }
        }
        item {
            var choosingYear by remember { mutableStateOf(false) }
            CatalogActionRow(
                "Year · ${draft.year ?: "Any"}",
                { choosingYear = true },
                Modifier.testTag("filters:year"),
                enabled =
                !loading && error == null
            )
            if (choosingYear) CatalogYearPicker(draft.year, facets.years, { draft = draft.copy(year = it) }) { choosingYear = false }
        }
        item {
            CatalogChoiceRow(
                "Rating",
                draft.officialRating,
                listOf(null) + facets.ratings.distinct(),
                { it ?: "Any" },
                Modifier.testTag("filters:rating"),
                enabled = !loading && error == null
            ) { draft = draft.copy(officialRating = it) }
        }
        item {
            CatalogChoiceRow(
                "Resolution",
                draft.resolution,
                VideoResolution.entries,
                {
                    if (it ==
                        VideoResolution.ANY
                    ) {
                        "Any"
                    } else {
                        it.label
                    }
                },
                Modifier.testTag("filters:resolution")
            ) { draft = draft.copy(resolution = it) }
        }
        item {
            CatalogActionRow("Person · ${if (draft.personId == null) "Any" else personLabel}", {
                personPicker = true
            }, Modifier.testTag("filters:person"))
        }
        if (draft.personId != null) item { CatalogCommand("Clear person", { draft = draft.copy(personId = null) }) }
        if (loading) item { Text("Loading choices…") }
        error?.let {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(it)
                    CatalogCommand("Retry", { retry++ })
                }
            }
        }
    }
    if (personPicker) {
        NamedCatalogPicker(state.account, service, "Persons", state.parent.id, {
            draft = draft.copy(personId = it.id)
            personLabel = it.name
            personPicker = false
        }, { personPicker = false }, selectedKeys = setOfNotNull(draft.personId))
    }
}
