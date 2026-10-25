/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.pictureshow.core.SegmentMode
import app.pictureshow.core.SegmentType

@Composable
internal fun TouchSettings(
    state: PlaybackSettingsState,
    acceptedAudio: String,
    onLocal: ((PlaybackPreferences) -> PlaybackPreferences) -> Unit,
    onServer: (String, String) -> Unit,
    onRetry: () -> Unit,
    onAccounts: () -> Unit,
    onAbout: () -> Unit,
    onSupport: () -> Unit,
    onBack: () -> Unit
) {
    val local = state.local
    val configuration = state.configuration
    val ready = !state.loading && !state.saving && configuration != null
    TouchPage("Settings", onBack) {
        TouchAction("Accounts", onClick = onAccounts)
        TouchHeading("Playback on this device")
        Text("Changes are saved immediately and apply the next time you open the player.")
        TouchChoice(
            "Max streaming bitrate",
            local.bitrateMbps,
            PlaybackPreferences.BITRATES,
            { it?.let { value -> "$value Mbps" } ?: "Auto (200 Mbps)" }
        ) { value -> onLocal { it.copy(bitrateMbps = value) } }
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text("Audio passthrough", modifier = Modifier.weight(1f))
            Switch(
                checked = local.passthrough,
                onCheckedChange = { checked -> onLocal { it.copy(passthrough = checked) } },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Audio passthrough" }
            )
        }
        Text("Audio output accepts: $acceptedAudio", style = MaterialTheme.typography.bodySmall)
        TouchChoice("Subtitle size", local.subtitleSize, SubtitleSize.entries, { settingLabel(it.name) }) { value ->
            onLocal { it.copy(subtitleSize = value) }
        }
        TouchChoice("Subtitle background", local.subtitleBackground, SubtitleBackground.entries, { settingLabel(it.name) }) { value ->
            onLocal { it.copy(subtitleBackground = value) }
        }
        Text("System follows Android caption settings. Bitmap subtitles keep their authored appearance.")
        TouchHeading("Skip segments")
        SegmentType.entries.filter { it != SegmentType.Unknown }.forEach { type ->
            TouchChoice(
                segmentLabel(type).replaceFirstChar { it.uppercase() },
                local.segmentModes[type] ?: SegmentMode.Ask,
                SegmentMode.entries,
                { if (it == SegmentMode.Automatic) "Skip automatically" else it.name }
            ) { value -> onLocal { it.copy(segmentModes = it.segmentModes + (type to value)) } }
        }
        Text("Ask shows a skip button during playback.")
        TouchHeading("Your server preferences")
        Text("These follow your account to other Jellyfin apps.")
        if (state.loading) Text("Loading server preferences…")
        if (state.saving) Text("Saving server preferences…")
        TouchChoice(
            "Audio language",
            configuration?.audioLanguage.orEmpty(),
            preferenceLanguages,
            ::preferenceLanguageLabel,
            ready
        ) { onServer("AudioLanguagePreference", it) }
        TouchChoice(
            "Subtitle language",
            configuration?.subtitleLanguage.orEmpty(),
            preferenceLanguages,
            ::preferenceLanguageLabel,
            ready
        ) { onServer("SubtitleLanguagePreference", it) }
        TouchChoice("Subtitle mode", configuration?.subtitleMode ?: "Default", subtitleModes, {
            it
        }, ready) { onServer("SubtitleMode", it) }
        TouchMessage(state.error)
        if (state.error != null) TouchAction("Retry", !state.saving, onRetry)
        TouchHeading("Pictureshow")
        TouchAction(stringResource(R.string.supporter_title), onClick = onSupport)
        TouchAction("About Pictureshow", onClick = onAbout)
    }
}

private fun settingLabel(value: String): String = when (value) {
    "System" -> "System (default)"
    "ExtraLarge" -> "Extra large"
    else -> value
}

@Composable
private fun TouchHeading(label: String) {
    Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
}

@Composable
private fun <T> TouchChoice(
    title: String,
    value: T,
    values: List<T>,
    label: (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit
) {
    var open by rememberSaveable { mutableStateOf(false) }
    TouchAction("$title: ${label(value)}", enabled) { open = true }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                LazyColumn {
                    items(values) { option ->
                        TextButton(
                            enabled = enabled,
                            onClick = {
                                onSelect(option)
                                open = false
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            RadioButton(selected = option == value, onClick = null)
                            Text(label(option), modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } }
        )
    }
}

@Composable
internal fun TouchAbout(onLicenses: () -> Unit, onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    TouchPage("About Pictureshow", onBack) {
        Text("Pictureshow", style = MaterialTheme.typography.headlineLarge)
        Text("Your Jellyfin library, wherever you watch.")
        Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Text("Free and open-source under the Mozilla Public License 2.0")
        Text("Made by Legitimate LLC")
        TouchAction("Source code") { uri.openUri("https://github.com/pictureshow/pictureshow") }
        TouchAction("Privacy policy") { uri.openUri("https://pictureshow.app/privacy/") }
        TouchAction("Open-source licenses", onClick = onLicenses)
    }
}

@Composable
internal fun TouchLicenses(libraries: List<LicenseEntry>, onOpen: (Int) -> Unit, onBack: () -> Unit) {
    TouchPage("Open-source licenses", onBack) {
        Text("${libraries.size} libraries. Tap a library to read its license and notices.")
        libraries.forEachIndexed { index, library ->
            TouchAction("${library.name} ${library.version} · ${library.license}") { onOpen(index) }
        }
    }
}

@Composable
internal fun TouchLicense(library: LicenseEntry, onBack: () -> Unit) {
    TouchPage(library.name, onBack) {
        Text("${library.name} ${library.version}", style = MaterialTheme.typography.titleLarge)
        Text(library.license)
        SelectionContainer { Text(library.text) }
    }
}

@Composable
internal fun TouchSupporter(state: SupporterState, onPurchase: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val management = remember { supporterManagementIntent() }
    var canManage by remember { mutableStateOf(management.resolveActivity(context.packageManager) != null) }
    TouchPage(stringResource(R.string.supporter_title), onBack) {
        Text(stringResource(R.string.supporter_description))
        Text(stringResource(R.string.supporter_no_guarantee))
        when (state) {
            SupporterState.Loading -> Text(stringResource(R.string.supporter_loading))
            is SupporterState.Offer -> {
                Text(stringResource(R.string.supporter_price_month, state.product.price), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.supporter_renewal))
                TouchAction(stringResource(R.string.supporter_subscribe), onClick = onPurchase)
            }
            SupporterState.Pending -> Text(stringResource(R.string.supporter_pending))
            is SupporterState.Active -> {
                Text(stringResource(R.string.supporter_thanks), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.supporter_code_label))
                SelectionContainer {
                    Text(
                        state.orderId ?: stringResource(R.string.supporter_code_unavailable),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("touch:supporter-code")
                    )
                }
                Text(stringResource(R.string.supporter_request))
                if (canManage) {
                    TouchAction(stringResource(R.string.supporter_manage)) {
                        try {
                            context.startActivity(management)
                        } catch (_: ActivityNotFoundException) {
                            canManage = false
                        } catch (_: SecurityException) {
                            canManage = false
                        }
                    }
                } else {
                    Text(stringResource(R.string.supporter_manage_fallback))
                }
            }
            is SupporterState.Unavailable -> TouchMessage(
                when (state.reason) {
                    BillingFailure.Unavailable ->
                        "Subscribing needs Google Play billing, which is unavailable on this device or its Google account."
                    BillingFailure.Network -> stringResource(R.string.supporter_network_unavailable)
                    BillingFailure.ProductMissing -> stringResource(R.string.supporter_product_unavailable)
                    BillingFailure.Other -> stringResource(R.string.supporter_error)
                }
            )
        }
    }
}
