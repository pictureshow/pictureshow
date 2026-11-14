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
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem

/** A viewport-owned backdrop, independent of the scrolling title/actions and their focus bounds. */
@Composable
internal fun RecordingDetailBackdrop(item: MediaItem, account: Account, modifier: Modifier = Modifier) {
    val artwork = if (item.backdropImageTags.isNotEmpty() || item.parentBackdropImageTags.isNotEmpty()) {
        item.copy(imageTags = emptyMap())
    } else {
        item
    }
    Box(modifier.clearAndSetSemantics {}) {
        CatalogArtwork(artwork, account, true, Modifier.fillMaxSize().alpha(.35f), showPlaceholder = false)
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(LiveBackground.copy(alpha = .95f), LiveBackground.copy(alpha = .4f)))
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to LiveBackground.copy(alpha = 0f),
                    .4f to LiveBackground.copy(alpha = 0f),
                    1f to LiveBackground
                )
            )
        )
    }
}
