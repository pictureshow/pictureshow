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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.playback.AudioCodec
import kotlinx.coroutines.Dispatchers
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
    val currentMode = display?.mode?.let { "${it.physicalWidth} × ${it.physicalHeight} at ${it.refreshRate} Hz" } ?: "Not reported"
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

@Composable
internal fun ThisTvScreen(preferences: PlaybackPreferencesStore, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    var report by remember { mutableStateOf<ThisTvReport?>(null) }
    var error by remember { mutableStateOf(false) }
    var generation by remember { mutableStateOf(0) }
    var showJson by remember { mutableStateOf(false) }
    LaunchedEffect(generation) {
        error = false
        try {
            report = captureThisTv(context, preferences.read())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = true
        }
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF10131C)).verticalScroll(rememberScrollState()).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("This TV", style = MaterialTheme.typography.headlineLarge)
        Text("Reported capabilities, not a playback benchmark. Shared diagnostics contain no account or server information.")
        Button(onClick = { generation++ }, modifier = Modifier.focusRequester(first)) { Text("Refresh capabilities") }
        report?.let { captured ->
            Button(onClick = { context.startActivity(Intent.createChooser(thisTvShareIntent(captured), "Share This TV")) }) {
                Text("Share diagnostics and profile JSON")
            }
            Button(onClick = { showJson = !showJson }) { Text(if (showJson) "Hide device profile JSON" else "View device profile JSON") }
            captured.summary.split("\n\n").forEach { Text(it, modifier = Modifier.focusable()) }
            if (showJson) {
                Text("Full device profile JSON", style = MaterialTheme.typography.headlineSmall)
                captured.profileJson.lines().chunked(12).forEach { Text(it.joinToString("\n"), modifier = Modifier.focusable()) }
            }
        } ?: Text(if (error) "Capabilities unavailable" else "Reading device capabilities…")
        if (error) Text("Could not read capabilities. Select Refresh to try again.")
        Button(onClick = onBack) { Text("Back") }
    }
    LaunchedEffect(Unit) { first.requestFocus() }
}
