/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.view.Display
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.playback.AudioCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** No account, client identity, device name or server information enters this capture. */
internal suspend fun captureThisTv(context: Context, preferences: PlaybackPreferences): ThisTvReport = withContext(Dispatchers.IO) {
    val capabilities = DeviceCapabilityProbe(context, preferences.deviceSettings()).use { it.snapshot() }
    val outputs = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .map { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headphones"
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
                AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital line"
                else -> "Other output (type ${device.type})"
            }
        }.distinct().joinToString(", ").ifEmpty { "Not reported" }
    val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    val currentMode =
        display?.mode?.let { "${it.physicalWidth} × ${it.physicalHeight} at ${displayRate(it.refreshRate.toDouble())} Hz" }
            ?: "Not reported"
    val ffmpeg = mapOf(
        AudioCodec.AAC to "audio/mp4a-latm", AudioCodec.AC3 to "audio/ac3", AudioCodec.EAC3 to "audio/eac3",
        AudioCodec.TRUEHD to "audio/true-hd", AudioCodec.DTS to "audio/vnd.dts", AudioCodec.FLAC to "audio/flac",
        AudioCodec.ALAC to "audio/alac", AudioCodec.OPUS to "audio/opus", AudioCodec.VORBIS to "audio/vorbis",
        AudioCodec.MP3 to "audio/mpeg", AudioCodec.MP2 to "audio/mpeg-L2"
    ).filterValues(DeviceCapabilityProbe::ffmpegSupports).keys.map { it.name }
    thisTvReport(
        capabilities,
        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "Media3 default route; connected outputs: $outputs. Active AudioTrack route unavailable outside playback.\nCurrent display: $currentMode",
        ffmpeg
    )
}

internal fun thisTvShareIntent(report: ThisTvReport): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_SUBJECT, "Pictureshow · This TV")
    putExtra(Intent.EXTRA_TEXT, report.sharedText)
}

/**
 * Count word-aware rows to bound focus blocks, but let Text wrap each original line naturally.
 * A mid-line block split retains its space. At an original line boundary, the gap between Text
 * nodes represents that newline, matching short-line diagnostics without adding blank rows.
 */
internal fun diagnosticTextChunks(text: String, fontScale: Float): List<String> {
    val rows = text.split('\n').flatMapIndexed { lineIndex, line ->
        buildList {
            var start = 0
            do {
                val limit = minOf(start + 80, line.length)
                var end = limit
                if (limit < line.length) {
                    var space = limit - 1
                    while (space >= start && line[space] != ' ') space--
                    if (space >= start) end = space + 1
                }
                add(DiagnosticRow(line.substring(start, end), start == 0 && lineIndex > 0))
                start = end
            } while (start < line.length)
        }
    }
    // Word wrapping can use three visual lines per budget row at large text.
    return rows.chunked(if (fontScale > 1.15f) 5 else 12).map { block ->
        block.mapIndexed { index, row ->
            if (index > 0 && row.startsLine) "\n${row.text}" else row.text
        }.joinToString("")
    }
}

private data class DiagnosticRow(val text: String, val startsLine: Boolean)

@Composable
internal fun ThisTvScreen(
    preferences: PlaybackPreferencesStore,
    capture: suspend (Context, PlaybackPreferences) -> ThisTvReport = ::captureThisTv,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val fontScale = LocalDensity.current.fontScale
    val first = remember { FocusRequester() }
    var report by remember { mutableStateOf<ThisTvReport?>(null) }
    var error by remember { mutableStateOf(false) }
    var generation by remember { mutableStateOf(0) }
    var showJson by remember { mutableStateOf(false) }
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
    val list = rememberLazyListState()
    val toggle = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val captured = report
    val chunks = remember(captured, showJson, fontScale) {
        captured?.let {
            if (showJson) {
                diagnosticTextChunks(it.profileJson, fontScale)
            } else {
                it.summary.split("\n\n").flatMap { section -> diagnosticTextChunks(section, fontScale) }
            }
        }.orEmpty()
    }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().background(Color(0xFF10131C)),
        contentPadding = PaddingValues(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "title") {
            Text("This TV", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        }
        item(key = "description") {
            Text("Reported capabilities, not a playback benchmark. Shared diagnostics contain no account or server information.")
        }
        item(key = "refresh") {
            Button(onClick = { generation++ }, modifier = Modifier.focusRequester(first)) { Text("Refresh capabilities") }
        }
        if (captured != null) {
            item(key = "share") {
                Button(onClick = { context.startActivity(Intent.createChooser(thisTvShareIntent(captured), "Share This TV")) }) {
                    Text("Share diagnostics and profile JSON")
                }
            }
            item(key = "json-toggle") {
                Button(onClick = {
                    val hiding = showJson
                    showJson = !showJson
                    if (hiding) {
                        scope.launch {
                            list.scrollToItem(0)
                            withFrameNanos { }
                            toggle.requestFocus()
                        }
                    }
                }, modifier = Modifier.focusRequester(toggle)) {
                    Text(if (showJson) "Hide device profile JSON" else "View device profile JSON")
                }
            }
            if (showJson) {
                item(key = "json-heading") {
                    Text(
                        "Full device profile JSON",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() }
                    )
                }
            }
            itemsIndexed(chunks, key = { index, _ -> "${if (showJson) "json" else "summary"}:$index" }) { _, chunk ->
                Text(chunk, modifier = Modifier.readableFocus())
            }
        } else {
            item(key = "reading") { Text(if (error) "Capabilities unavailable" else "Reading device capabilities…") }
        }
        if (error) item(key = "error") { Text("Could not read capabilities. Select Refresh to try again.") }
        item(key = "back") { Button(onClick = onBack) { Text("Back") } }
    }
    LaunchedEffect(Unit) {
        // Lazy items attach during measurement, after the screen's initial composition effect.
        snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.key == "refresh" } }.first { it }
        withContext(Dispatchers.Main.immediate) { first.requestFocus() }
    }
}
