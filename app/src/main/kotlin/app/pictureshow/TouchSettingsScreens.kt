/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
    onBack: (() -> Unit)? = null,
    onRemote: () -> Unit = {},
    onSyncPlay: () -> Unit = {},
    onThisDevice: () -> Unit = {},
    /** Null where Cast can't be used on this device. */
    castEnabled: Boolean? = null,
    onCastEnabled: (Boolean) -> Unit = {},
    /** Set while Google Play services need updating before Cast can run. */
    onUpdateServices: (() -> Unit)? = null
) {
    val local = state.local
    val device = touchDeviceName(LocalConfiguration.current.smallestScreenWidthDp)
    val configuration = state.configuration
    val ready = !state.loading && !state.saving && configuration != null
    TouchPage("Settings", onBack) {
        TouchSection {
            TouchNavRow("Accounts", onClick = onAccounts)
            TouchNavRow("Remote for a TV", onClick = onRemote)
            TouchNavRow("Watch together", onClick = onSyncPlay)
            castEnabled?.let {
                TouchSwitchRow(
                    "Google Cast",
                    if (onUpdateServices != null) {
                        "Google Cast can't run until Google Play services is updated. This setting applies after that."
                    } else {
                        "Lists Cast devices in Play on. Turning it off leaves anything already playing on one alone."
                    },
                    it,
                    "touch:settings:google-cast",
                    onCastEnabled
                )
                onUpdateServices?.let { update ->
                    Box(Modifier.testTag("touch:settings:update-play-services")) {
                        TouchNavRow("Update Google Play services", onClick = update)
                    }
                }
            }
        }
        TouchSection(
            "Playback on ${device.replaceFirstChar(Char::lowercase)}",
            "Changes are saved immediately and apply the next time you open the player."
        ) {
            TouchChoice(
                "Max streaming bitrate",
                local.bitrateMbps,
                PlaybackPreferences.BITRATES,
                { it?.let { value -> "$value Mbps" } ?: "Auto (200 Mbps)" }
            ) { value -> onLocal { it.copy(bitrateMbps = value) } }
            TouchSwitchRow(
                "Audio passthrough",
                "Audio output accepts: $acceptedAudio",
                local.passthrough,
                "touch:settings:passthrough"
            ) { checked -> onLocal { it.copy(passthrough = checked) } }
            TouchSwitchRow(
                "Conversion notice",
                "Shows a short note when the server has to convert a video or a song for ${device.replaceFirstChar(Char::lowercase)}.",
                local.conversionNotice,
                "touch:settings:conversion-notice"
            ) { checked -> onLocal { it.copy(conversionNotice = checked) } }
            TouchChoice("Music quality", local.musicQuality, musicQualityOptions.map { it.first }, ::musicQualityLabel) { value ->
                onLocal { it.copy(musicQuality = value) }
            }
            TouchChoice("Subtitle size", local.subtitleSize, SubtitleSize.entries, { settingLabel(it.name) }) { value ->
                onLocal { it.copy(subtitleSize = value) }
            }
            TouchChoice("Subtitle background", local.subtitleBackground, SubtitleBackground.entries, { settingLabel(it.name) }) { value ->
                onLocal { it.copy(subtitleBackground = value) }
            }
            TouchHelper("System follows Android caption settings. Bitmap subtitles keep their authored appearance.")
        }
        TouchSection("Skip segments") {
            SegmentType.entries.filter { it != SegmentType.Unknown }.forEach { type ->
                TouchChoice(
                    segmentLabel(type).replaceFirstChar { it.uppercase() },
                    local.segmentModes[type] ?: SegmentMode.Ask,
                    SegmentMode.entries,
                    { if (it == SegmentMode.Automatic) "Skip automatically" else it.name }
                ) { value -> onLocal { it.copy(segmentModes = it.segmentModes + (type to value)) } }
            }
            TouchHelper("Ask shows a skip button during playback.")
        }
        TouchSection("Your server preferences", "These follow your account to other Jellyfin apps.") {
            if (state.loading) TouchHelper("Loading server preferences…")
            if (state.saving) TouchHelper("Saving server preferences…")
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
            if (state.error != null) TouchAction("Retry", enabled = !state.saving, onClick = onRetry)
        }
        TouchSection("Troubleshooting") { TouchNavRow(device, onClick = onThisDevice) }
        TouchSection("Pictureshow") {
            TouchNavRow(stringResource(R.string.supporter_title), onClick = onSupport)
            TouchNavRow("About Pictureshow", onClick = onAbout)
        }
    }
}

/** A heading owns the gap above it and the rows under it, so a row's spacing never comes from the page's column. */
@Composable
private fun TouchSection(title: String? = null, caption: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = if (title == null) 0.dp else 12.dp)) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
        }
        if (caption != null) TouchHelper(caption)
        content()
    }
}

@Composable
private fun TouchHelper(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

/** The same width split Android itself documents for tablets; TV uses its own screen. */
internal fun touchDeviceName(smallestWidthDp: Int): String = if (smallestWidthDp >= 600) "This tablet" else "This phone"

/** The TV report in touch form: same capture and privacy (capabilities only), named for this device. */
@Composable
internal fun TouchThisDevice(
    preferences: PlaybackPreferencesStore,
    capture: suspend (Context, PlaybackPreferences) -> ThisTvReport = ::captureThisTv,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val device = touchDeviceName(LocalConfiguration.current.smallestScreenWidthDp)
    var report by remember { mutableStateOf<ThisTvReport?>(null) }
    var error by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var showJson by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(generation) {
        error = false
        try {
            report = capture(context, preferences.read())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = true
        }
    }
    TouchPage(device, onBack) {
        Text("Reported capabilities, not a playback benchmark. Shared diagnostics contain no account or server information.")
        TouchSecondaryAction("Refresh capabilities") { generation++ }
        val captured = report
        if (captured != null) {
            TouchAction("Share diagnostics and profile JSON") {
                context.startActivity(Intent.createChooser(thisTvShareIntent(captured, device), "Share $device"))
            }
            TouchSecondaryAction(if (showJson) "Hide device profile JSON" else "View device profile JSON") { showJson = !showJson }
            SelectionContainer {
                Text(
                    if (showJson) captured.profileJson else captured.summary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("touch:this-device:report")
                )
            }
        } else if (!error) {
            Text("Reading device capabilities…")
        }
        TouchMessage("Could not read capabilities. Select Refresh to try again.".takeIf { error })
    }
}

private fun settingLabel(value: String): String = when (value) {
    "System" -> "System (default)"
    "ExtraLarge" -> "Extra large"
    else -> value
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
    TouchValueRow(title, label(value), enabled) { open = true }
    if (open) {
        val shortWindow = isShortTouchWindow()
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                LazyColumn {
                    item(key = "scroll-hint") {
                        if (shortWindow) Text("Scroll for more options", style = MaterialTheme.typography.bodySmall)
                    }
                    items(values) { option ->
                        TouchRadioRow(option == value, enabled = enabled, onClick = {
                            onSelect(option)
                            open = false
                        }) { Text(label(option), modifier = Modifier.weight(1f)) }
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
        Column {
            TouchNavRow("Source code") { uri.openUri("https://github.com/pictureshow/pictureshow") }
            TouchNavRow("Privacy policy") { uri.openUri("https://pictureshow.app/privacy/") }
            TouchNavRow("Open-source licenses", onClick = onLicenses)
        }
    }
}

@Composable
internal fun TouchLicenses(libraries: List<LicenseEntry>, onOpen: (Int) -> Unit, onBack: () -> Unit) {
    TouchPage("Open-source licenses", onBack) {
        Text("${libraries.size} libraries. Tap a library to read its license and notices.")
        Column {
            libraries.forEachIndexed { index, library ->
                TouchNavRow("${library.name} ${library.version} · ${library.license}") { onOpen(index) }
            }
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
