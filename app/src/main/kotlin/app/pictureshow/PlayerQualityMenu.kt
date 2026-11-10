/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.pictureshow.core.playback.PlaybackQualityPresentation

/** Both presentations show all choices; opening the menu never changes the request. */
@Composable
internal fun PlayerQualityMenu(
    value: Int?,
    automaticContext: String,
    reductionNotice: String?,
    touch: Boolean,
    onSelect: (Int?) -> Unit,
    onClose: () -> Unit
) {
    val supporting: (Int?) -> String? = { choice ->
        if (choice == null) listOfNotNull(automaticContext, reductionNotice).joinToString("\n") else null
    }
    if (!touch) {
        TvSettingsChoicePicker(
            title = "Quality",
            value = value,
            values = PlaybackQualityPresentation.choices,
            label = PlaybackQualityPresentation::choiceLabel,
            onSelect = onSelect,
            onClose = onClose,
            supportingText = supporting
        )
        return
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Quality") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup().testTag("player:quality-menu")) {
                PlaybackQualityPresentation.choices.forEach { choice ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(selected = value == choice, role = Role.RadioButton, onClick = {
                                onSelect(choice)
                                onClose()
                            }).testTag("player:quality-choice:${choice ?: "auto"}").padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = value == choice, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(PlaybackQualityPresentation.choiceLabel(choice), style = MaterialTheme.typography.bodyLarge)
                            supporting(choice)?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}
