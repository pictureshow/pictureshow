/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.HomeRow
import app.pictureshow.core.MediaItem
import app.pictureshow.core.normalizeSearch

@Composable
internal fun TouchSearchScreen(
    state: SearchState,
    account: Account,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onRecent: (String) -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (MediaItem) -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val shortInput = isShortTouchWindow() && WindowInsets.ime.getBottom(LocalDensity.current) > 0
    fun finishInput() {
        keyboard?.hide()
        focus.clearFocus()
    }
    Column(Modifier.fillMaxSize().imePadding().testTag("touch:search")) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = if (shortInput) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!shortInput) TouchCatalogHeading("Search")
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                label = { Text("Search your library") },
                placeholder = { Text("Movies, shows, people…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    onSubmit()
                    finishInput()
                }),
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        TextButton(onClick = { onQuery("") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear") }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("touch:search:query")
            )
        }
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item(key = "status") {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.loading) TouchCatalogLoading("Searching…")
                    val failure = when {
                        state.allFailed -> "Could not search the server. Check your connection and try again."
                        state.someFailed -> "Some results could not be loaded."
                        else -> state.refreshError
                    }
                    TouchCatalogError(failure, onRetry)
                    state.storageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val term = normalizeSearch(state.query)
                    if (!state.loading && !state.allFailed && state.completed && state.rows.isEmpty()) Text("No results for “$term”.")
                    if (term.length < 2) {
                        Text("Search by title or person. Enter at least 2 characters.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.recent.isNotEmpty()) {
                            TouchCatalogHeading("Recent searches", section = true)
                            state.recent.forEach { recent ->
                                TextButton(onClick = {
                                    onRecent(recent)
                                    finishInput()
                                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(recent) }
                            }
                            TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear recent searches") }
                        }
                    }
                }
            }
            items(state.rows, key = HomeRow::key) { row ->
                TouchCatalogShelf(row.title, row.items, account, row.wide) {
                    finishInput()
                    onOpen(it)
                }
            }
        }
    }
}
