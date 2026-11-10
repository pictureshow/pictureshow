/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.view.KeyEvent.KEYCODE_SEARCH
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
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
import app.pictureshow.core.fixedHeadCollapses
import app.pictureshow.core.fixedHeadInList
import app.pictureshow.core.normalizeSearch
import kotlin.math.abs
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

@OptIn(ExperimentalFoundationApi::class)
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
    var headControl by remember { mutableStateOf(field) }
    var headFocused by remember { mutableStateOf(false) }
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
    val assistiveService = rememberAssistiveServiceInUse()
    val headInList = fixedHeadInList(LocalDensity.current.fontScale, assistiveService)
    val headItems = if (headInList) 1 else 0
    var landings by remember { mutableIntStateOf(0) }

    // In the list the head may have scrolled away, and a lazy item that far off isn't composed.
    fun focusInHead(requester: FocusRequester) {
        if (headInList && list.layoutInfo.visibleItemsInfo.none { it.index == 0 }) {
            scope.launch {
                list.jumpTo(0)
                awaitCatalogFocus(requester)
            }
        } else {
            requester.requestFocus()
        }
    }
    fun focusField() {
        editing = false
        keyboard?.hide()
        focusInHead(field)
    }
    suspend fun focusRow(index: Int, target: String? = null): Boolean {
        val row = state.rows.getOrNull(index) ?: return false
        val itemIndex = row.items.indexOfFirst { "${row.key}:${it.id}" == (target ?: selections[row.key]) }.coerceAtLeast(0)
        val position = index + headItems
        // The first row stays under the head while the card fits there.
        list.jumpTo(if (index == 0) 0 else position)
        val horizontal = rowStates.getValue(row.key)
        return withTimeoutOrNull(1_500) {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.index == position } }.first { it }
            horizontal.jumpTo(itemIndex)
            snapshotFlow { horizontal.layoutInfo.visibleItemsInfo.any { it.index == itemIndex } }.first { it }
            while (true) {
                withFrameNanos { }
                if (withContext(Dispatchers.Main.immediate) {
                        requests.getValue("${row.key}:${row.items[itemIndex].id}").requestFocus()
                    }
                ) {
                    // Jumped to the head, so a tall card may still run past the bottom.
                    if (headInList) landings++
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
        var resultsFocused by remember { mutableStateOf(false) }
        // A long title at the largest sizes is taller than the space under the field: it goes while results hold focus.
        val headCollapsed = fixedHeadCollapses(LocalDensity.current.fontScale, resultsFocused, assistiveService)
        // Text can arrive from outside the keyboard (a recent search, a phone remote). The caret goes after it.
        var fieldValue by remember { mutableStateOf(TextFieldValue(state.query, TextRange(state.query.length))) }
        var seenQuery by remember { mutableStateOf(state.query) }
        if (state.query != seenQuery) {
            seenQuery = state.query
            if (state.query != fieldValue.text) fieldValue = TextFieldValue(state.query, TextRange(state.query.length))
        }
        // The head is composed anew when it changes place, so whichever of its controls held focus takes it back, and a
        // viewer typing keeps the keyboard. A recent search or Clear comes back to the first recent search.
        val headHeld = remember(headInList) { headControl.takeIf { headFocused } }
        val headWasEditing = remember(headInList) { editing && fieldFocused }
        LaunchedEffect(headInList) {
            val control = headHeld ?: return@LaunchedEffect
            if (headInList) list.jumpTo(0)
            awaitCatalogFocus(control)
            if (headWasEditing && control == field) {
                editing = true
                keyboard?.show()
            }
        }
        val head: @Composable () -> Unit = {
            Column(
                Modifier.collapsedOffScreen(headCollapsed).onFocusChanged { headFocused = it.hasFocus },
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
                        value = fieldValue,
                        onValueChange = {
                            val changed = it.text != fieldValue.text
                            fieldValue = it
                            if (changed) {
                                focusResults = false
                                selected = ""
                                selections = emptyMap()
                                onQuery(it.text)
                            }
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
                                if (it.isFocused) headControl = field
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
                                        .onFocusChanged { if (it.isFocused) headControl = recentFirst }
                                        .staysInRow(index == 0, last = false)
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
                                    ).testTag("search:clear").onFocusChanged { if (it.isFocused) headControl = recentFirst }
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
                            ?: if (state.allFailed) {
                                "Could not search your library. Try again."
                            } else {
                                "Some results could not load. Try again."
                            },
                        modifier = Modifier.catalogFocusTarget(!handoff)
                    )
                    Button(
                        onClick = {
                            focusField()
                            focusResults = true
                            onRetry()
                        },
                        modifier = arrivalTarget(
                            Modifier,
                            returningToRetry
                        ).focusRequester(retry).testTag("search:retry").onFocusChanged {
                            if (it.isFocused) headControl = retry
                        }.onPreviewKeyEvent {
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
            }
        }
        if (!headInList) {
            Box {
                head()
                if (headCollapsed) {
                    ContextStrip(
                        "Results for “${normalizeSearch(state.query)}”",
                        null,
                        Modifier.pinnedToScreenTop(48.dp, 24.dp, 16.dp).testTag("search:strip")
                    )
                }
            }
        }
        val platformBringIntoView = LocalBringIntoViewSpec.current
        val focusMargin = with(LocalDensity.current) { FocusedCardMargin.toPx() }
        val reveal = Modifier.revealFocusAfterFontChange(list, key = headInList to landings)
        val focusedResult = remember { FocusedResult() }
        val resultsBringIntoView = remember(platformBringIntoView, focusMargin) {
            object : BringIntoViewSpec {
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                    val distance = focusedCardScrollDistance(
                        offset,
                        size,
                        containerSize,
                        focusMargin,
                        platformBringIntoView.calculateScrollDistance(offset, size, containerSize)
                    )
                    val above = focusedResult.cardTopInRow(size) ?: return distance
                    val gap = above - focusedResult.titleHeight
                    return sectionTitleScrollDistance(distance, offset, size, containerSize, focusMargin, above, gap)
                }
            }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides resultsBringIntoView) {
            LazyColumn(
                state = list,
                modifier = reveal.weight(1f).testTag("search:results").onFocusChanged { resultsFocused = it.hasFocus }.semantics {
                    traversalIndex = 1f
                },
                // Room for the last row's focus scale, which this list would otherwise clip.
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                if (headInList) item(key = "search:head") { head() }
                state.rows.forEachIndexed { rowIndex, row ->
                    item(key = row.key) {
                        val section = remember { ShelfSection() }
                        Column(
                            modifier = Modifier.accessibilityGroup().onGloballyPositioned { section.row = it },
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                row.title,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.fillMaxWidth()
                                    .onSizeChanged { section.titleHeight = it.height }
                                    .catalogFocusTarget(!handoff)
                                    .semantics { heading() }
                            )
                            // Only the outer vertical list keeps a whole card; shelves retain platform scrolling.
                            CompositionLocalProvider(LocalBringIntoViewSpec provides platformBringIntoView) {
                                ShelfWithEnds(row.title, rowStates.getValue(row.key), row.items.lastIndex) { endCard, rowSemantics ->
                                    LazyRow(
                                        modifier = rowSemantics,
                                        state = rowStates.getValue(row.key),
                                        contentPadding = PaddingValues(16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(18.dp)
                                    ) {
                                        itemsIndexed(row.items, key = { _, item -> item.id }) { itemIndex, item ->
                                            val target = "${row.key}:${item.id}"
                                            endCard(itemIndex) { endModifier ->
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
                                                        .staysInRow(itemIndex == 0, itemIndex == row.items.lastIndex)
                                                        .then(endModifier)
                                                        .onGloballyPositioned { focusedResult.cards[target] = it }
                                                        .onFocusChanged {
                                                            if (it.isFocused) {
                                                                focusedResult.row = section.row
                                                                focusedResult.titleHeight = section.titleHeight
                                                                focusedResult.target = target
                                                            }
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
                                                                            if (state.someFailed || state.allFailed) {
                                                                                focusInHead(retry)
                                                                            } else {
                                                                                focusField()
                                                                            }
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
                list.jumpTo(0)
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
    var gridFocused by remember { mutableStateOf(false) }
    val assistiveService = rememberAssistiveServiceInUse()
    val fontScale = LocalDensity.current.fontScale
    val headCollapsed = fixedHeadCollapses(fontScale, gridFocused, assistiveService)
    val headInList = fixedHeadInList(fontScale, assistiveService)
    var backFocused by remember { mutableStateOf(false) }
    // Back is composed anew when the head changes place, so it takes focus back if it held it.
    val backHeldFocus = remember(headInList) { backFocused }
    val grid = rememberLazyGridState()
    LaunchedEffect(headInList) {
        if (backHeldFocus) {
            awaitCatalogFocus(back)
            // The way into the grid is its focus restorer, which picks the last card. From inside, Back takes it.
            if (headInList) back.requestFocus()
        }
    }
    val head: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            CatalogArtwork(state.person, state.grid.account, false, Modifier.width(64.dp).height(96.dp))
            Column(modifier = Modifier.accessibilityGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.person.name, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.testTag("person:name"))
                Button(
                    onClick = onBack,
                    modifier = Modifier.focusRequester(back).testTag("person:back").onFocusChanged { backFocused = it.isFocused }
                ) { Text("Back") }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF10131C))) {
        if (!headInList) head(Modifier.collapsedOffScreen(headCollapsed).padding(start = 48.dp, top = 24.dp))
        Box(Modifier.weight(1f).onFocusChanged { gridFocused = it.hasFocus }) {
            CatalogGrid(
                state.grid.copy(parent = state.person.copy(name = "Movies and shows")),
                onOpen,
                onNearEnd,
                { _, _ -> },
                onRetry,
                showSort = false,
                stripTitle = state.person.name.takeIf { headCollapsed },
                pinStrip = headCollapsed,
                head = if (headInList) ({ head(Modifier) }) else null,
                headFocus = back.takeIf { headInList },
                revealKey = headInList,
                grid = grid
            )
        }
    }
}

/** Where the focused result card sits in its shelf, read when the list scrolls to it. */
private class FocusedResult {
    var row: LayoutCoordinates? = null
    var titleHeight = 0
    var target: String? = null
    val cards = mutableMapOf<String, LayoutCoordinates>()

    /** The card's top below its row's top, if the request is for the focused card. */
    fun cardTopInRow(size: Float): Float? {
        val card = cards[target ?: return null]?.takeIf { it.isAttached && abs(it.size.height - size) < 1f } ?: return null
        val row = row?.takeIf { it.isAttached } ?: return null
        return row.localPositionOf(card, Offset.Zero).y
    }
}

/** A shelf's row and title, measured as laid out. */
private class ShelfSection {
    var row: LayoutCoordinates? = null
    var titleHeight = 0
}
