/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import app.pictureshow.core.AccountSnapshot
import app.pictureshow.core.normalizeSearch

const val ASSISTANT_SEARCH_ACTION = "com.google.android.gms.actions.SEARCH_ACTION"

fun parseSearchIntent(intent: Intent): String? {
    if (intent.action !in listOf(Intent.ACTION_SEARCH, ASSISTANT_SEARCH_ACTION)) return null
    return normalizeSearch(intent.getStringExtra(SearchManager.QUERY).orEmpty()).takeIf { it.isNotEmpty() }
}

internal fun searchRoute(query: String = "", keyboard: Boolean = true): String = "search?query=${Uri.encode(query)}&keyboard=$keyboard"

internal fun searchIntentDestination(snapshot: AccountSnapshot, query: String): String =
    if (snapshot.currentAccount?.expired == false) searchRoute(query, keyboard = false) else accountDestination(snapshot)
