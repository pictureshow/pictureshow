/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.pictureshow.core.playback.PlaybackQualityPresentation

internal fun qualityScrollCues(offsetDp: Float, remainingDp: Float): Pair<Boolean, Boolean> = (offsetDp > 8f) to (remainingDp > 8f)

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
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints {
            val compact = maxHeight < 480.dp
            Box(Modifier.padding(horizontal = 24.dp, vertical = if (compact) 16.dp else 24.dp)) {
                Surface(
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().semantics { paneTitle = "Quality" },
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 640.dp)
                            .padding(
                                start = 24.dp,
                                end = 24.dp,
                                top = if (compact) 20.dp else 24.dp,
                                bottom = if (compact) 12.dp else 24.dp
                            )
                    ) {
                        val scroll = rememberScrollState()
                        val selected = remember { BringIntoViewRequester() }
                        val density = LocalDensity.current
                        val cues by remember(scroll, density) {
                            derivedStateOf {
                                with(density) {
                                    qualityScrollCues(scroll.value.toDp().value, (scroll.maxValue - scroll.value).toDp().value)
                                }
                            }
                        }
                        LaunchedEffect(value, scroll.maxValue) {
                            if (scroll.maxValue > 0) selected.bringIntoView()
                        }
                        Text(
                            "Quality",
                            Modifier.semantics { heading() }.padding(bottom = if (compact) 8.dp else 12.dp),
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Box(Modifier.weight(1f, fill = false)) {
                            Column(
                                Modifier.fillMaxWidth().verticalScroll(scroll)
                                    .selectableGroup().testTag("player:quality-menu")
                                    .semantics {
                                        if (scroll.canScrollBackward ||
                                            scroll.canScrollForward
                                        ) {
                                            stateDescription = "Scroll for more options"
                                        }
                                    }
                            ) {
                                PlaybackQualityPresentation.choices.forEach { choice ->
                                    val note = supporting(choice)
                                    TouchRadioRow(
                                        selected = value == choice,
                                        modifier = Modifier.heightIn(min = 56.dp)
                                            .then(if (value == choice) Modifier.bringIntoViewRequester(selected) else Modifier)
                                            .testTag(
                                                "player:quality-choice:${choice ?: "auto"}"
                                            ).padding(vertical = if (compact) 0.dp else 4.dp),
                                        onClick = {
                                            onSelect(choice)
                                            onClose()
                                        },
                                        alignToFirstLine = note != null
                                    ) {
                                        Column(
                                            Modifier.weight(1f).then(
                                                if (note !=
                                                    null
                                                ) {
                                                    Modifier.alignBy(TouchRadioLabelCenter)
                                                } else {
                                                    Modifier
                                                }
                                            )
                                        ) {
                                            Text(
                                                PlaybackQualityPresentation.choiceLabel(choice),
                                                modifier = Modifier.testTag(
                                                    "player:quality-label:${choice ?: "auto"}"
                                                ).touchRadioLabelCenter(),
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            note?.let {
                                                Text(
                                                    it,
                                                    modifier = Modifier.padding(top = 4.dp),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            listOf(true to cues.first, false to cues.second).forEach { (above, visible) ->
                                if (visible) {
                                    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
                                    Box(
                                        Modifier.align(if (above) Alignment.TopCenter else Alignment.BottomCenter)
                                            .fillMaxWidth().height(24.dp)
                                            .background(
                                                Brush.verticalGradient(
                                                    if (above) {
                                                        listOf(surface, surface.copy(alpha = 0f))
                                                    } else {
                                                        listOf(surface.copy(alpha = 0f), surface)
                                                    }
                                                )
                                            ).testTag(if (above) "player:quality-more-above" else "player:quality-more-below")
                                            .clearAndSetSemantics {}
                                    )
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                        }
                    }
                }
            }
        }
    }
}
