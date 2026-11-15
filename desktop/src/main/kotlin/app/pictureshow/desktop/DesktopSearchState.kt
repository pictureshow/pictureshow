/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.HomeRow

data class DesktopSearchState(
    val query: String = "",
    val rows: List<HomeRow> = emptyList(),
    val loading: Boolean = false,
    val completed: Boolean = false,
    val someFailed: Boolean = false,
    val allFailed: Boolean = false,
    val libraries: Map<String, String> = emptyMap(),
    val libraryError: Boolean = false,
    val recent: List<String> = emptyList(),
    val storageError: String? = null
)
