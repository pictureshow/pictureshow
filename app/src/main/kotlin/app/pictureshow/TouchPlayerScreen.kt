/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.runtime.Composable
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem

/** Playback seam for the touch controls implementation. */
@Composable
internal fun TouchPlayerScreen(
    account: Account,
    item: MediaItem,
    startMs: Long,
    catalog: CatalogSession,
    onCurrentItem: (MediaItem) -> Unit = {},
    onExit: () -> Unit
) {
    PictureshowTheme { PlayerScreen(account, item, startMs, catalog, onCurrentItem, onExit) }
}
