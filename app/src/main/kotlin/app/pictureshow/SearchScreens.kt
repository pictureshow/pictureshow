/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.view.KeyEvent.KEYCODE_SEARCH
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.normalizeSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Applied around the catalog graph, excluding player and sign-in routes. Both key halves are consumed. */
internal fun isCatalogSearchRoute(route: String?): Boolean = route?.substringBefore('?') in listOf("home", "item", "search")

internal fun Modifier.catalogSearchKey(enabled: Boolean, onSearch: () -> Unit): Modifier = onPreviewKeyEvent {
    if (enabled && it.nativeKeyEvent.keyCode == KEYCODE_SEARCH) {
        if (it.type == KeyEventType.KeyUp) onSearch()
        true
    } else {
        false
    }
}

@Composable
fun SearchScreen(
    state: SearchState,
    account: Account,
    openKeyboard: Boolean,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onRecent: (String) -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    keyboardRequest: Int = 0
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val field = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val recentFirst = remember { FocusRequester() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by rememberSaveable { mutableStateOf(openKeyboard) }
    var fieldFocused by remember { mutableStateOf(false) }
    val navigation = remember { RemoteFieldNavigation(setOf(Key.DirectionDown)) }
    var selected by rememberSaveable { mutableStateOf("") }
    var selections by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    var focusResults by rememberSaveable { mutableStateOf(!openKeyboard) }
    var restored by remember { mutableStateOf(false) }
    val returning = remember { selected.isNotEmpty() }
    var interacted by remember { mutableStateOf(false) }
    val returnRowIndex = state.rows.indexOfFirst { row -> row.key == selected.substringBefore(':') }.coerceAtLeast(0)
    val returnRow = state.rows.getOrNull(returnRowIndex)
    val returnItem = returnRow?.items?.let { items -> items.firstOrNull { "${returnRow.key}:${it.id}" == selected } ?: items.firstOrNull() }
    val returnTarget = returnItem?.let { "${returnRow.key}:${it.id}" }
    val returningToField = state.rows.isEmpty() && state.completed && !state.allFailed
    val returningToRetry = state.rows.isEmpty() && state.allFailed
    val returnLabel = returnItem?.let(::catalogAnnouncement) ?: when {
        returningToRetry -> "Retry"
        returningToField -> "Search your video library"
        else -> null
    }
    var openerHasInput by remember { mutableStateOf(false) }
    val handoff = rememberCatalogFocusHandoff(returning, returnLabel, restored && openerHasInput, interacted)
    fun arrivalTarget(modifier: Modifier, matches: Boolean) = modifier.catalogFocusTarget(
        !handoff || matches,
        { !returning || (restored && !handoff) || interacted || matches }
    ).onFocusChanged {
        if (matches) {
            openerHasInput = it.isFocused
            if (returning && it.isFocused && lifecycleState == Lifecycle.State.RESUMED) restored = true
        }
    }
    var handledKeyboardRequest by rememberSaveable { mutableStateOf(keyboardRequest) }
    val requests = mutableMapOf<String, FocusRequester>()
    val rowStates = mutableMapOf<String, LazyListState>()
    state.rows.forEach { row ->
        key(row.key) {
            rowStates[row.key] = rememberLazyListState()
            row.items.forEach { item -> key(item.id) { requests["${row.key}:${item.id}"] = remember { FocusRequester() } } }
        }
    }
    fun focusField() {
        editing = false
        keyboard?.hide()
        field.requestFocus()
    }
    suspend fun focusRow(index: Int, target: String? = null): Boolean {
        val row = state.rows.getOrNull(index) ?: return false
        val itemIndex = row.items.indexOfFirst { "${row.key}:${it.id}" == (target ?: selections[row.key]) }.coerceAtLeast(0)
        list.scrollToItem(index)
        val horizontal = rowStates.getValue(row.key)
        return withTimeoutOrNull(1_500) {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.index == index } }.first { it }
            horizontal.scrollToItem(itemIndex)
            snapshotFlow { horizontal.layoutInfo.visibleItemsInfo.any { it.index == itemIndex } }.first { it }
            while (true) {
                withFrameNanos { }
                if (withContext(Dispatchers.Main.immediate) {
                        requests.getValue("${row.key}:${row.items[itemIndex].id}").requestFocus()
                    }
                ) {
                    return@withTimeoutOrNull true
                }
            }
        } == true
    }
    fun down() {
        editing = false
        keyboard?.hide()
        when {
            state.allFailed || state.someFailed -> retry.requestFocus()
            state.rows.isNotEmpty() -> scope.launch { focusRow(0) }
            normalizeSearch(state.query).isEmpty() && state.recent.isNotEmpty() -> recentFirst.requestFocus()
        }
    }
    // The IME consumes Back before the app. A floating TV IME can report zero insets,
    // so editing is not evidence that a keyboard is still visible when Back reaches us.
    val arriving = returning && !restored && lifecycleState != Lifecycle.State.RESUMED
    var entryBackSwallowed by remember { mutableStateOf(false) }
    BackHandler(
        enabled = if (arriving) {
            !entryBackSwallowed
        } else {
            lifecycleState == Lifecycle.State.RESUMED &&
                !fieldFocused &&
                (!returning || restored)
        }
    ) {
        if (arriving) entryBackSwallowed = true else focusField()
    }
    CatalogLoadingFocus(returning && !restored) {
        interacted = true
        restored = true
        focusResults = false
        scope.launch {
            withFrameNanos { }
            focusField()
        }
    }
    Column(
        Modifier.fillMaxSize().background(
            Color(0xFF10131C)
        ).padding(horizontal = 48.dp, vertical = 24.dp).testTag("search").accessibilityGroup().onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key != Key.Back && it.key != Key.Escape) interacted = true
            false
        },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Search",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.fillMaxWidth().catalogFocusTarget(!handoff).semantics {
                heading()
                traversalIndex =
                    -2f
            }
        )
        InterceptPlatformTextInput(interceptor = { request, next -> next.startInputMethod(searchInputRequest(request)) }) {
            BasicTextField(
                value = state.query,
                onValueChange = {
                    focusResults = false
                    selected = ""
                    selections = emptyMap()
                    onQuery(it)
                },
                readOnly = !editing,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
                cursorBrush = SolidColor(Color(0xFFB8C4FF)),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        onSubmit()
                        focusResults = true
                        down()
                    },
                    onDone = {
                        onSubmit()
                        focusResults = true
                        down()
                    }
                ),
                modifier = arrivalTarget(Modifier.fillMaxWidth(), returningToField)
                    .focusRequester(field).testTag("search:field")
                    .semantics {
                        contentDescription = "Search your video library"
                        traversalIndex = -1f
                    }
                    .editWithKeyboard {
                        field.requestFocus()
                        editing = true
                        keyboard?.show()
                    }
                    .onFocusChanged {
                        // Attachment reports false before the first focus request. Preserve entry-time editing.
                        if (fieldFocused && !it.isFocused) editing = false
                        if (!it.isFocused) navigation.clear()
                        fieldFocused = it.isFocused
                    }
                    .onPreviewKeyEvent {
                        when {
                            it.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter) -> {
                                if (it.type == KeyEventType.KeyUp) {
                                    editing = true
                                    keyboard?.show()
                                }
                                true
                            }
                            navigation.handle(it) { down() } -> true
                            else -> false
                        }
                    }
                    .border(if (fieldFocused) 3.dp else 1.dp, Color(0xFFB8C4FF)).padding(16.dp)
            )
        }
        state.storageError?.let { Text(it, modifier = Modifier.catalogFocusTarget(!handoff)) }
        if (normalizeSearch(state.query).isEmpty()) {
            if (state.recent.isNotEmpty()) {
                Text("Recent searches", modifier = Modifier.catalogFocusTarget(!handoff))
                LazyRow(
                    modifier = Modifier.catalogFocusTarget(!handoff) { !returning || (restored && !handoff) || interacted },
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    itemsIndexed(state.recent, key = { _, term -> term }) { index, term ->
                        Button(
                            onClick = {
                                focusResults = true
                                selected = ""
                                selections = emptyMap()
                                onRecent(term)
                                focusField()
                            },
                            modifier = (if (index == 0) Modifier.focusRequester(recentFirst) else Modifier)
                                .catalogFocusTarget(!handoff) {
                                    !returning || (restored && !handoff) || interacted
                                }.testTag("recent:$index")
                                .onPreviewKeyEvent {
                                    if (it.key == Key.DirectionUp && it.type == KeyEventType.KeyDown) {
                                        focusField()
                                        true
                                    } else {
                                        false
                                    }
                                }
                        ) { Text(term) }
                    }
                    item {
                        Button(
                            onClick = {
                                focusField()
                                selected = ""
                                selections = emptyMap()
                                onClear()
                            },
                            modifier = Modifier.catalogFocusTarget(
                                !handoff,
                                { !returning || (restored && !handoff) || interacted }
                            ).testTag("search:clear")
                        ) {
                            Text("Clear")
                        }
                    }
                }
            } else {
                Text(
                    "Type or use your keyboard’s microphone to search movies, shows and people.",
                    modifier = Modifier.catalogFocusTarget(!handoff)
                )
            }
        } else if (normalizeSearch(state.query).length < 2) {
            Text("Enter at least 2 characters.", modifier = Modifier.catalogFocusTarget(!handoff))
        }
        if (state.loading) Text("Searching…", modifier = Modifier.catalogFocusTarget(!handoff))
        if (state.allFailed || state.someFailed || state.refreshError != null) {
            Text(
                state.refreshError
                    ?: if (state.allFailed) "Could not search your library. Try again." else "Some results could not load. Try again.",
                modifier = Modifier.catalogFocusTarget(!handoff)
            )
            Button(
                onClick = {
                    focusField()
                    focusResults = true
                    onRetry()
                },
                modifier = arrivalTarget(Modifier, returningToRetry).focusRequester(retry).testTag("search:retry").onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (it.key) {
                            Key.DirectionUp -> {
                                focusField()
                                true
                            }
                            Key.DirectionDown -> {
                                scope.launch { focusRow(0) }
                                true
                            }
                            else -> false
                        }
                    }
                }
            ) { Text("Retry") }
        } else if (state.completed && state.rows.isEmpty()) {
            Text("No results for “${normalizeSearch(state.query)}”", modifier = Modifier.catalogFocusTarget(!handoff))
        }
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).semantics {
                traversalIndex = 1f
            },
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            state.rows.forEachIndexed { rowIndex, row ->
                item(key = row.key) {
                    Column(modifier = Modifier.accessibilityGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            row.title,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.fillMaxWidth().catalogFocusTarget(!handoff).semantics {
                                heading()
                            }
                        )
                        LazyRow(
                            state = rowStates.getValue(row.key),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            itemsIndexed(row.items, key = { _, item -> item.id }) { _, item ->
                                val target = "${row.key}:${item.id}"
                                CatalogCard(
                                    item,
                                    account,
                                    row.wide,
                                    onClick = {
                                        editing = false
                                        keyboard?.hide()
                                        selected = target
                                        selections = selections + (row.key to target)
                                        focusResults = false
                                        onOpen(item)
                                    },
                                    modifier = arrivalTarget(Modifier, target == returnTarget)
                                        .focusRequester(requests.getValue(target)).testTag("search:$target")
                                        .onFocusChanged {
                                            if (it.isFocused && (!returning || (restored && !handoff) || interacted)) {
                                                selected = target
                                                selections =
                                                    selections + (row.key to target)
                                            }
                                        }
                                        .onPreviewKeyEvent {
                                            if (it.type != KeyEventType.KeyDown) {
                                                false
                                            } else {
                                                when (it.key) {
                                                    Key.DirectionUp -> {
                                                        if (rowIndex ==
                                                            0
                                                        ) {
                                                            if (state.someFailed || state.allFailed) retry.requestFocus() else focusField()
                                                        } else {
                                                            scope.launch { focusRow(rowIndex - 1) }
                                                        }
                                                        true
                                                    }
                                                    Key.DirectionDown -> {
                                                        if (rowIndex <
                                                            state.rows.lastIndex
                                                        ) {
                                                            scope.launch { focusRow(rowIndex + 1) }
                                                        }
                                                        true
                                                    }
                                                    else -> false
                                                }
                                            }
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(keyboardRequest, lifecycleState) {
        if (lifecycleState != Lifecycle.State.RESUMED) return@LaunchedEffect
        if (keyboardRequest != handledKeyboardRequest || selected.isEmpty()) {
            if (keyboardRequest != handledKeyboardRequest) {
                interacted = true
                restored = true
                focusResults = false
                editing = true
                handledKeyboardRequest = keyboardRequest
            }
            awaitCatalogFocus(field)
        }
    }
    LaunchedEffect(lifecycleState, state.completed, state.allFailed, state.rows, focusResults) {
        if (lifecycleState != Lifecycle.State.RESUMED) return@LaunchedEffect
        if (state.rows.isNotEmpty() && (!restored || focusResults)) {
            val focused = if (selected.isNotEmpty() || focusResults) focusRow(returnRowIndex, returnTarget) else true
            focusResults = false
            restored = focused
            if (!focused) {
                interacted = true
                restored = true
                list.scrollToItem(0)
                editing = false
                keyboard?.hide()
                awaitCatalogFocus(field)
            }
        } else if (returning && (returningToField || returningToRetry) && !restored) {
            restored = awaitCatalogFocus(if (returningToRetry) retry else field)
            if (!restored) {
                interacted = true
                restored = true
                editing = false
                keyboard?.hide()
                awaitCatalogFocus(field)
            }
        }
    }
}

@Composable
fun PersonScreen(state: PersonState, onOpen: (MediaItem) -> Unit, onNearEnd: (Int) -> Unit, onRetry: () -> Unit, onBack: () -> Unit) {
    val back = remember { FocusRequester() }
    LaunchedEffect(state.grid.loading, state.grid.items.isEmpty(), state.grid.error) {
        if (!state.grid.loading && state.grid.items.isEmpty() && state.grid.error == null) back.requestFocus()
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF10131C))) {
        Row(Modifier.padding(start = 48.dp, top = 24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            CatalogArtwork(state.person, state.grid.account, false, Modifier.width(64.dp).height(96.dp))
            Column(modifier = Modifier.accessibilityGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.person.name, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.testTag("person:name"))
                Button(onClick = onBack, modifier = Modifier.focusRequester(back).testTag("person:back")) { Text("Back") }
            }
        }
        Box(Modifier.weight(1f)) {
            CatalogGrid(state.grid.copy(parent = state.person.copy(name = "Movies and shows")), onOpen, onNearEnd, { _, _ ->
            }, onRetry, showSort = false)
        }
    }
}
