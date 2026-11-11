/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem

/** Recording detail keeps normal negotiation/actions, with its artwork behind the first-viewport content. */
@Composable
internal fun RecordingDetailBackdrop(item: MediaItem, account: Account, modifier: Modifier = Modifier) {
    Box(modifier) {
        CatalogArtwork(item, account, true, Modifier.fillMaxSize().alpha(.35f))
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(LiveBackground.copy(alpha = .95f), LiveBackground.copy(alpha = .4f)))
            )
        )
    }
}
