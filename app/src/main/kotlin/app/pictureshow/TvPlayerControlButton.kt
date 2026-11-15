/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** Fixed slots keep both rows still when focus reveals a caption. Speech always names the action. */
@Composable
internal fun TvPlayerControlButton(
    icon: String,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    state: String? = null,
    caption: String = label,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(104.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            enabled = enabled,
            scale = playerButtonScale(),
            contentPadding = PaddingValues(12.dp),
            modifier = modifier.size(48.dp).playerTouchClick {
                if (enabled) onClick()
            }.onFocusChanged { focused = it.isFocused }.semantics {
                contentDescription = label
                state?.let { stateDescription = it }
            }
        ) { Icon(playerActionIcon(icon), null, Modifier.size(24.dp)) }
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.TopCenter) {
            Text(
                caption,
                style = MaterialTheme.typography.labelMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp).alpha(if (focused) 1f else 0f).clearAndSetSemantics {}
            )
        }
    }
}
