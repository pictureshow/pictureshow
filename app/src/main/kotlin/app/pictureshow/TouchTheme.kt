/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
internal fun TouchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFD0BCFF),
            secondary = Color(0xFFCCC2DC),
            background = Color(0xFF10131C),
            surface = Color(0xFF10131C),
            surfaceContainer = Color(0xFF202330)
        )
    ) {
        Surface(Modifier.fillMaxSize(), content = content)
    }
}

@Composable
internal fun isShortTouchWindow(): Boolean = with(LocalDensity.current) {
    LocalWindowInfo.current.containerSize.height.toDp() < 480.dp
}

/** Insets belong to the shell; this page scrolls into the remaining space above the IME. */
@Composable
internal fun TouchPage(title: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shortWindow = isShortTouchWindow()
    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = if (shortWindow) 12.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (shortWindow) 12.dp else 16.dp)
        ) {
            if (shortWindow && onBack != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TouchBack(onBack)
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                }
            } else {
                if (onBack != null) TouchBack(onBack)
                Text(
                    title,
                    style = if (shortWindow) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() }
                )
            }
            content()
        }
    }
}

@Composable
internal fun TouchMessage(message: String?) {
    if (message != null) {
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
            }
        )
    }
}

@Composable
internal fun TouchAction(label: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
}

/** Takes something away, after its own confirmation: quiet, in the error colour, and never mistaken for the way forward. */
@Composable
internal fun TouchRemoveAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) { Text(label) }
}

/** Its text starts on the page edge, the line everything below it hangs from; the target stays 48 dp. */
@Composable
internal fun TouchBack(onBack: () -> Unit) {
    TextButton(
        onClick = onBack,
        modifier = Modifier.heightIn(min = 48.dp).testTag("touch:back"),
        // The end padding fills TextButton's minimum width, which would otherwise centre the word off the page margin.
        contentPadding = PaddingValues(start = 0.dp, end = 18.dp, top = 8.dp, bottom = 8.dp)
    ) { Text("‹ Back") }
}

/** The theme's outline, not its fainter variant: a secondary control has to read as a control beside a filled one. */
@Composable
internal fun touchOutlineBorder(enabled: Boolean = true) = BorderStroke(
    1.dp,
    if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
)

/** A secondary action. A disabled one stays readable, since its label can be what the state has to say. */
@Composable
internal fun TouchSecondaryAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        border = touchOutlineBorder(enabled),
        colors = ButtonDefaults.outlinedButtonColors(disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    ) { Text(label) }
}

@Composable
private fun RowScope.TouchRowText(title: String, supporting: String?, enabled: Boolean) {
    val alpha = if (enabled) 1f else 0.6f
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
            )
        }
    }
}

/** A list row with a current value under its name; it opens the choice. */
@Composable
internal fun TouchValueRow(title: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { TouchRowText(title, value, enabled) }
}

/** A list row that goes somewhere: a name, an optional line, and a chevron. */
@Composable
internal fun TouchNavRow(title: String, supporting: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TouchRowText(title, supporting, true)
        Icon(
            touchActionIcon("chevron-right"),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The whole row toggles, and it is the one named switch a reader meets. */
@Composable
internal fun TouchSwitchRow(title: String, caption: String?, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .semantics { stateDescription = if (checked) "On" else "Off" }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TouchRowText(title, caption, true)
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * The one choice row, in sheets and dialogs: a ring on the content's left edge, plain text, and no button colour or
 * padding leaking in. [content] is the label, and may lead with a thumbnail. The ring's box carries 2 dp of its own.
 */
@Composable
internal fun TouchRadioRow(
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String? = null,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { description?.let { contentDescription = it } }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.offset(x = (-2).dp))
        content()
    }
}
