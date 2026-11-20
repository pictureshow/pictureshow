/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pictureshow.ui.CatalogHeading

/** Stream C2's pages, for DesktopApp's route `when`, the Back button and entry focus. */
internal val DESKTOP_ACCOUNTS_ROUTES = setOf(DesktopRoute.Requests, DesktopRoute.SeerrTitle, DesktopRoute.About, DesktopRoute.Licenses,
    DesktopRoute.License, DesktopRoute.Supporter, DesktopRoute.ThisComputer)

internal const val DESKTOP_ROOT_REQUESTS = "requests"

@Composable
internal fun DesktopAccountsPage(state: DesktopState, c: DesktopController) = when (state.route) {
    DesktopRoute.Requests -> DesktopRequestsPage(c)
    DesktopRoute.SeerrTitle -> DesktopSeerrTitlePage(c)
    DesktopRoute.About -> DesktopAboutPage(c)
    DesktopRoute.Licenses -> DesktopLicensesPage(c)
    DesktopRoute.License -> DesktopLicensePage(c)
    DesktopRoute.Supporter -> DesktopSupporterPage()
    DesktopRoute.ThisComputer -> DesktopThisComputerPage(c)
    else -> Unit
}

internal fun desktopAccountsEntryTarget(state: DesktopState): String? = when (state.route) {
    DesktopRoute.Requests -> "requests:tab:Trending"
    DesktopRoute.SeerrTitle -> "seerr-request"
    DesktopRoute.About -> "about:licenses"
    DesktopRoute.Licenses -> "licenses:0"
    DesktopRoute.License -> "license-text"
    DesktopRoute.Supporter -> null
    DesktopRoute.ThisComputer -> "this-computer:details"
    else -> null
}

/** Any of this stream's dialogs is open: the page behind it leaves the focus order. */
@Composable
internal fun desktopAccountsModalOpen(c: DesktopController): Boolean {
    val pins by c.pins.state.collectAsState()
    val approval by c.approval.state.collectAsState()
    val seerr by c.seerr.state.collectAsState()
    return pins.prompt != null || pins.setup != null || approval.open || seerr.connectOpen
}

fun DesktopController.openAbout() = openPage(DesktopRoute.About)
fun DesktopController.openLicenses() = openPage(DesktopRoute.Licenses) { information.loadLicenses() }
fun DesktopController.openLicense(id: String) { information.openLicense(id); openPage(DesktopRoute.License) }
fun DesktopController.openSupporter() = openPage(DesktopRoute.Supporter)
fun DesktopController.openThisComputer() = openPage(DesktopRoute.ThisComputer) { information.loadReport() }

/** Settings: PIN, sign-in approval and Seerr for the current viewer (stream C2). */
@Composable
internal fun AccountsSettingsSections(state: DesktopState, c: DesktopController) {
    ProfilePinSettingsSection(state, c)
    if (state.account?.signedIn == true) SettingsSection("Sign in other devices") { QuickConnectApprovalSection(state, c) }
    SeerrSettingsSection(state, c)
}

/** Settings → About (INFO-01..08). */
@Composable
internal fun InformationSettingsSection(c: DesktopController) {
    SettingsSection("About") {
        SettingRow("About Pictureshow", "Version ${c.appVersion}, license, source code and privacy") {
            OutlinedButton(onClick = { rememberOpener("settings:about"); c.openAbout() }, modifier = Modifier.controlFocus().probe("settings:about")) { Text("About") }
        }
        SettingRow("This computer", "What this computer can play without the server converting it") {
            OutlinedButton(onClick = c::openThisComputer, modifier = Modifier.controlFocus().probe("settings:this-computer")) { Text("Open") }
        }
        SettingRow("Support Pictureshow", "How monthly supporters help") {
            OutlinedButton(onClick = c::openSupporter, modifier = Modifier.controlFocus().probe("settings:supporter")) { Text("Learn more") }
        }
    }
}

@Composable
private fun LinkRow(label: String, url: String, tag: String) {
    TextButton(onClick = { desktopOpenLink(url) }, modifier = Modifier.controlFocus().probe(tag).semantics { role = Role.Button }) { Text("$label: $url") }
}

internal fun desktopOpenLink(url: String) {
    runCatching { if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}

/** INFO-03. */
@Composable
internal fun DesktopAboutPage(c: DesktopController) {
    FormPage("Pictureshow", "Version ${c.appVersion}") {
        Text("Free and open-source under the Mozilla Public License 2.0")
        Text("Made by Legitimate LLC")
        LinkRow("Source code", PICTURESHOW_SOURCE_URL, "about:source")
        LinkRow("Privacy", PICTURESHOW_PRIVACY_URL, "about:privacy")
        Button(onClick = c::openLicenses, modifier = Modifier.controlFocus().probe("about:licenses")) { Text("Open-source licenses") }
    }
}

/** INFO-04: every library the app runs with, from the generated catalog, plus Pictureshow's own license. */
@Composable
internal fun DesktopLicensesPage(c: DesktopController) {
    val s by c.information.state.collectAsState()
    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().probe("licenses"), contentPadding = PaddingValues(32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { CatalogHeading("Open-source licenses") }
            item { Text("Pictureshow is MPL-2.0. It is built with these libraries; choose one for its full license and notices.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp)) }
            item {
                ListItem(headlineContent = { Text("Pictureshow") }, supportingContent = { Text("${c.appVersion} · MPL-2.0") },
                    modifier = Modifier.widthIn(max = 720.dp).controlFocus(MaterialTheme.shapes.small).clickable { c.openLicense(PICTURESHOW_LICENSE_ID) }.probe("licenses:pictureshow"))
            }
            val rows = s.licenses
            when {
                rows == null -> item { CircularProgressIndicator() }
                s.licensesMissing -> item { Text("The license list is missing from this build.", color = MaterialTheme.colorScheme.error) }
            }
            items(rows.orEmpty().withIndex().toList(), key = { it.value.id }) { (i, row) ->
                ListItem(headlineContent = { Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text("${row.version} · ${row.license}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.widthIn(max = 720.dp).controlFocus(MaterialTheme.shapes.small).clickable { c.openLicense(row.id) }.probe("licenses:$i"))
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(androidx.compose.ui.Alignment.CenterEnd).fillMaxHeight())
    }
}

internal const val PICTURESHOW_LICENSE_ID = "pictureshow"

/** INFO-05: the full text, selectable, keyboard-scrollable. */
@Composable
internal fun DesktopLicensePage(c: DesktopController) {
    val s by c.information.state.collectAsState()
    val own = remember { desktopOwnLicense() }
    val row = s.license ?: DesktopLicense(PICTURESHOW_LICENSE_ID, "Pictureshow", c.appVersion, "MPL-2.0", own ?: "The license text is missing from this build. It is at $PICTURESHOW_SOURCE_URL/blob/main/LICENSE")
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CatalogHeading(row.name)
            Text("${row.version} · ${row.license}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (row.id == "mac-playback") {
                desktopPlaybackSources()?.let { sources ->
                    Button(onClick = { java.awt.Desktop.getDesktop().open(sources) }, modifier = Modifier.controlFocus()) {
                        Text("Open playback source files")
                    }
                }
            }
            SelectionContainer {
                Text(row.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.widthIn(max = 900.dp).controlFocus(MaterialTheme.shapes.small).focusable().probe("license-text"))
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(androidx.compose.ui.Alignment.CenterEnd).fillMaxHeight())
    }
}

internal const val DESKTOP_SUPPORTER_NO_PURCHASE = "Subscribing isn't available in Pictureshow for computers. On Android, Pictureshow offers the Supporter subscription through Google Play."

/** INFO-06..08 on a computer: the honest explanation, no purchase, no outside payment links. */
@Composable
internal fun DesktopSupporterPage() {
    FormPage("Support Pictureshow", "Pictureshow is free and open source. Supporters pay monthly to have their feature requests read and considered first.") {
        Text("Requests are not guaranteed to be built or delivered by a particular date.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(DESKTOP_SUPPORTER_NO_PURCHASE, modifier = Modifier.probe("supporter-unavailable"))
        Text("Already a supporter? Email info@legitimateapps.com with your request and the supporter code from Pictureshow on Android.")
    }
}

/** INFO-01/02: what this computer reported, starting with the plain answer. */
@Composable
internal fun DesktopThisComputerPage(c: DesktopController) {
    val s by c.information.state.collectAsState()
    FormPage("This computer", "What Pictureshow found when it checked this computer just now.") {
        val report = s.report
        if (report == null || s.reportLoading) { CircularProgressIndicator(); return@FormPage }
        Text(desktopComputerVerdict(report), style = MaterialTheme.typography.titleMedium, modifier = Modifier.probe("this-computer:verdict").semantics { liveRegion = LiveRegionMode.Polite })
        SettingsSection("Video player") {
            InfoLine("libmpv", report.mpvVersion ?: "Not loaded")
            InfoLine("FFmpeg", report.ffmpegVersion ?: "Not reported")
            InfoLine("License of this libmpv", when (report.mpvLgpl) { true -> "LGPL (built without GPL parts)"; false -> "GPL (built with GPL parts)"; null -> "Not reported by this build" })
            InfoLine("Hardware decoding", report.hwdec?.let { if (it == "no") "Off" else it } ?: "Not reported")
        }
        SettingsSection("Displays") {
            if (report.displays.isEmpty()) Text("No display was reported.")
            report.displays.forEachIndexed { i, d ->
                InfoLine("Display ${i + 1}", listOfNotNull("${d.width} × ${d.height}", d.refreshHz?.let { "$it Hz" }, d.bitDepth?.let { "$it-bit" }, d.scale?.takeIf { it != 1.0 }?.let { "scaled ${"%.2f".format(it)}×" }).joinToString(" · "))
            }
            Text("HDR: Java doesn't report whether a display is in HDR. Playback info shows what the player sends while a video plays.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsSection("Formats it plays") {
            InfoLine("Video", report.videoDecoders.joinToString(", ") { it.codec }.ifEmpty { "None reported" })
            InfoLine("Audio", report.audioDecoders.joinToString(", ") { it.codec }.ifEmpty { "None reported" })
            Text("Audio output: mpv picks the computer's sound device when a video plays; surround passthrough isn't measured here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsSection("System") {
            InfoLine("Operating system", report.os)
            InfoLine("Java", report.java)
            InfoLine("Pictureshow", c.appVersion)
        }
        var details by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { details = !details }, modifier = Modifier.controlFocus().probe("this-computer:details")) { Text(if (details) "Hide technical details" else "Technical details") }
        if (details) {
            Text("The device profile Pictureshow sends your server, so it knows what to convert:", style = MaterialTheme.typography.bodySmall)
            SelectionContainer { Text(report.profileJson ?: "No profile: libmpv isn't loaded.", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.probe("this-computer:profile")) }
        }
        OutlinedButton(onClick = { c.information.loadReport() }, modifier = Modifier.controlFocus().probe("this-computer:refresh")) { Text("Check again") }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.width(180.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer(Modifier.weight(1f)) { Text(value) }
    }
}

/**
 * A pointer click doesn't move focus, so Back would restore the page's entry control and scroll to it. Recording
 * the opener as the page's last focus brings Back to where the viewer was (pilot 172: Settings → About → Back).
 */
internal fun rememberOpener(tag: String) {
    DesktopUiProbe.pageOfTag[tag]?.takeIf { it.isNotBlank() }?.let { DesktopUiProbe.lastFocus[it] = tag }
}
