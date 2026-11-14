/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The same native destinations occupy the toolbar on short screens and a full row otherwise. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiveTouchNavigation(tab: String, compact: Boolean = false, onTab: (String) -> Unit) {
    val tabs = listOf("Channels", "Guide", "Recordings", "Schedules")
    val content: @Composable () -> Unit = {
        tabs.forEach { next ->
            androidx.compose.material3.Tab(
                selected = tab == next,
                unselectedContentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { onTab(next) },
                modifier = Modifier.height(48.dp).testTag("live:tab:$next")
            ) {
                androidx.compose.material3.Text(
                    next,
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge
                )
            }
        }
    }
    if (compact || LocalDensity.current.fontScale > 1.1f) {
        androidx.compose.material3.PrimaryScrollableTabRow(tabs.indexOf(tab), edgePadding = if (compact) 0.dp else 16.dp) { content() }
    } else {
        androidx.compose.material3.PrimaryTabRow(tabs.indexOf(tab)) { content() }
    }
}
