/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.json.JSONObject

internal data class LicenseEntry(val id: String, val name: String, val version: String, val license: String, val text: String)

/** Explicit JSON fields, no reflective serialization and no R8 keep rules required. */
internal fun readLicenseCatalog(context: Context): List<LicenseEntry> {
    val root = JSONObject(context.assets.open("licenses/catalog.json").bufferedReader().use { it.readText() })
    val rows = root.getJSONArray("libraries")
    return (0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        LicenseEntry(row.getString("id"), row.getString("name"), row.getString("version"), row.getString("license"), row.getString("text"))
    }
}

/** Used by the real activity and the remote regression test's settings graph. */
internal fun NavGraphBuilder.aboutScreens(nav: NavHostController) {
    composable("about") {
        AboutScreen(onLicenses = { nav.navigate("licenses") }, onBack = { nav.popBackStack() })
    }
    composable("licenses") {
        LicenseListScreen(onOpen = { nav.navigate("license/$it") }, onBack = { nav.popBackStack() })
    }
    composable("license/{index}") { entry ->
        val context = LocalContext.current
        val library = remember(entry) { readLicenseCatalog(context)[entry.arguments!!.getString("index")!!.toInt()] }
        LicenseTextScreen(library, onBack = { nav.popBackStack() })
    }
}

@Composable
private fun AboutScreen(onLicenses: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val first = remember { FocusRequester() }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF10131C)).verticalScroll(rememberScrollState()).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Pictureshow", style = MaterialTheme.typography.headlineLarge)
        Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Text("Free and open-source under the Mozilla Public License 2.0")
        Text("Made by Legitimate LLC")
        Text("https://github.com/pictureshow/pictureshow")
        Text("https://pictureshow.app/privacy/")
        Button(onClick = onLicenses, modifier = Modifier.focusRequester(first).testTag("about:licenses")) { Text("Open-source licenses") }
        Button(onClick = onBack) { Text("Back") }
    }
    LaunchedEffect(Unit) { first.requestFocus() }
}

@Composable
private fun LicenseListScreen(onOpen: (Int) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val libraries = remember { readLicenseCatalog(context) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val restoreIndex = remember { selected }
    val first = remember { FocusRequester() }
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = restoreIndex)
    Column(Modifier.fillMaxSize().background(Color(0xFF10131C)).padding(48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Open-source licenses", style = MaterialTheme.typography.headlineLarge)
        Text("${libraries.size} libraries · Press center to read a license and notices. Back returns to About.")
        LazyColumn(state = scroll, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(libraries, key = { _, library -> library.id }) { index, library ->
                Button(
                    onClick = { onOpen(index) },
                    // Wide rows use the TV focus color without scaling beyond the list clip.
                    scale = ButtonDefaults.scale(focusedScale = 1f),
                    modifier = Modifier.fillMaxWidth()
                        .then(if (index == restoreIndex) Modifier.focusRequester(first) else Modifier)
                        .onFocusChanged { if (it.isFocused) selected = index }
                        .testTag("licenses:$index")
                ) {
                    Column {
                        Text("${library.name} ${library.version}")
                        Text(library.id, style = MaterialTheme.typography.bodySmall)
                        Text(library.license, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (index == restoreIndex) LaunchedEffect(Unit) { first.requestFocus() }
            }
            item { Button(onClick = onBack) { Text("Back") } }
        }
    }
}

/** Wrap long notice/source lines too: every focused block fits on a small TV viewport. */
internal fun licenseTextChunks(text: String): List<String> = text.lines().flatMap { line ->
    if (line.isEmpty()) listOf("") else line.chunked(80)
}.chunked(7).map { it.joinToString("\n") }

@Composable
private fun LicenseTextScreen(library: LicenseEntry, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val first = remember { FocusRequester() }
    val chunks = remember(library.id) { licenseTextChunks(library.text) }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF10131C)).verticalScroll(rememberScrollState()).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("${library.name} ${library.version}", style = MaterialTheme.typography.headlineSmall)
        Text(library.license)
        Text("D-pad Down/Up scrolls the full license and notices. Back returns to the library list.")
        chunks.forEachIndexed { index, chunk ->
            var focused by remember { mutableStateOf(false) }
            Text(
                chunk,
                modifier = Modifier.fillMaxWidth()
                    .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                    .onFocusChanged { focused = it.isFocused }
                    .border(2.dp, if (focused) Color.White else Color.Transparent)
                    .padding(12.dp)
                    .testTag("license:chunk:$index")
                    .focusable()
            )
        }
        Button(onClick = onBack) { Text("Back") }
    }
    LaunchedEffect(Unit) { first.requestFocus() }
}
