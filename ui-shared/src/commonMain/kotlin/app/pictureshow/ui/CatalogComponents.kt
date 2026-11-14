/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.episodeLabel
import app.pictureshow.core.imageUrl
import app.pictureshow.core.isWatched
import app.pictureshow.core.progress
import coil3.compose.AsyncImage

@Composable
fun CatalogCard(
    item: MediaItem,
    account: Account,
    wide: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artHeight: Dp? = null,
    reserveLines: Boolean = true,
    announcement: String,
    tag: String = "touch:card:${item.id}",
    caption: (@Composable () -> Unit)? = null,
    artworkProgress: Float? = null,
    showProgress: Boolean = true
) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).testTag(tag).semantics(mergeDescendants = true) {
            contentDescription = announcement
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        val poster = !wide && item.type != "Episode"
        CatalogArtwork(
            item,
            account,
            wide,
            // An episode in a poster grid keeps its own shape; everything else takes the grid's one height.
            if (artHeight != null && (poster || wide)) {
                Modifier.fillMaxWidth().height(artHeight)
            } else {
                Modifier.fillMaxWidth().aspectRatio(if (poster) 2f / 3f else 16f / 9f)
            }
        )
        // Always 4 dp tall, so progress on one card doesn't step it out of its row.
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            val fraction = (artworkProgress ?: progress(item)).coerceIn(0f, 1f)
            if (showProgress && fraction > 0 && (artworkProgress != null || !item.isWatched)) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().clearAndSetSemantics {})
            }
        }
        if (caption != null) {
            caption()
        } else {
            val bodyHeight =
                with(LocalDensity.current) {
                    MaterialTheme.typography.titleSmall.lineHeight.toDp() * 2 + MaterialTheme.typography.bodySmall.lineHeight.toDp()
                } + 4.dp
            Column(
                Modifier.padding(12.dp).heightIn(min = if (reserveLines) bodyHeight else 0.dp).clearAndSetSemantics {
                },
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Reserve the whole body height, leaving spare space below metadata.
                // A short title stays next to its subtitle while row heights match.
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleSmall,
                    minLines = 1,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = if (item.type == "Episode") {
                    listOfNotNull(item.seriesName, episodeLabel(item)).joinToString(" · ")
                } else {
                    listOfNotNull(item.productionYear?.toString(), if (item.isWatched) "Watched" else null).joinToString(" · ")
                }
                if (reserveLines || subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun CatalogArtwork(
    item: MediaItem,
    account: Account,
    wide: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val url = imageUrl(account.server, item, 720, wide = wide)
        var failed by remember(url) { mutableStateOf(false) }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { failed = false },
                onError = { failed = true }
            )
        }
        if (url == null || failed) {
            Text(
                item.name.take(1).uppercase(),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clearAndSetSemantics {
                }
            )
        }
    }
}

@Composable
fun CatalogHeading(title: String, modifier: Modifier = Modifier, section: Boolean = false) {
    Text(
        title,
        modifier.semantics {
            heading()
        },
        style = if (section) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium
    )
}
