/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.pictureshow.core.Account
import app.pictureshow.core.HomeRow
import app.pictureshow.core.MediaItem
import app.pictureshow.core.homeCardSubtitle
import app.pictureshow.core.missingItemLabel
import kotlinx.coroutines.launch

/** The viewer's local date, `yyyy-MM-dd`, for "Aired" or "Airs" on a missing episode (CAT-21). */
internal fun localToday(): String = java.time.LocalDate.now().toString()

/**
 * The line under a Home card's title: a missing episode says so (CAT-21); otherwise the merged row's "Next · S1:E2"
 * and, for a title also on Home from another library, that library's name (HOME-12, HOME-13). Null keeps the card's own.
 */
internal fun homeRowSubtitle(row: HomeRow, item: MediaItem): String? = missingItemLabel(item, localToday())
    ?: row.captions[item.id].let { caption ->
        val library = row.libraryNames[item.id]
        if (caption == null && library == null) null else homeCardSubtitle(item, caption, library)
    }

/**
 * HOME-12's Settings switch: on gives Next up its own Home row; off (the default) folds it into Continue watching.
 * Null until the viewer's Home layout has been read, so the switch never shows a guess.
 */
@Composable
internal fun rememberSeparateNextUp(catalog: CatalogSession, account: Account?): Pair<Boolean?, (Boolean) -> Unit> {
    var separate: Boolean? by remember(account?.key) { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(catalog, account?.key) {
        separate = account?.let { !catalog.homePreferences.read(it).preferences.mergeWatching }
    }
    return separate to { value: Boolean ->
        if (account != null) {
            separate = value
            scope.launch {
                separate = !catalog.homePreferences.change(account) { it.copy(mergeWatching = !value) }.preferences.mergeWatching
            }
        }
    }
}
