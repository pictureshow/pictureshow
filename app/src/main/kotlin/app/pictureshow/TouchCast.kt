/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.control.CastCapability
import app.pictureshow.core.control.RemoteTarget
import app.pictureshow.core.imageUrl

private const val TEN_SECONDS_TICKS = 100_000_000L
private const val THIRTY_SECONDS_TICKS = 300_000_000L

internal fun castPlayItem(server: Server, item: MediaItem) = CastPlayItem(
    id = item.id,
    title = item.name,
    imageUrl = imageUrl(server, item, width = 960, wide = true),
    durationMs = item.runTimeTicks?.takeIf { it > 0 }?.let { it / 10_000 }
)

/** Same-named devices stay tellable apart by a fragment of their opaque id, never by an address. */
internal fun castRouteLabel(route: CastRoute, all: List<CastRoute>): String =
    if (all.count { it.name == route.name } > 1) "${route.name} (${route.id.takeLast(4)})" else route.name

/** Why the Cast section can't list devices, and what fixes it. Null when it can. */
internal fun castCapabilityMessage(state: String): String? = when (state) {
    "os-too-old" -> "Google Cast needs a newer version of Android on this device."
    "browser-unsupported" -> "Google Cast doesn't work in this browser."
    "services-missing" -> "Google Cast needs Google Play services, which this device doesn't have. Pictureshow TVs still work."
    "services-update-required" -> "Google Cast needs a newer version of Google Play services."
    "viewer-disabled" -> "Google Cast is turned off."
    "permission-denied" -> "Pictureshow needs permission to find devices on your local network."
    "searching" -> "Looking for Cast devices…"
    "no-targets" -> "No Cast devices found. Check that this device and the TV are on the same Wi-Fi."
    else -> null
}

/** Why the receiver can't reach this server. It fetches the video itself, from its own place on the network. */
internal fun castReachMessage(category: String): String = when (category) {
    "loopback" -> "This server's address only works on this device, so the TV can't reach it. Use an address the TV can reach."
    "link-local" -> "This server's address only works directly between two devices, so the TV can't reach it. Use its network address."
    "sender-vpn" -> "This server is reached through this device's VPN, and the TV isn't on that VPN. Use an address the TV can reach."
    else -> "The TV can't reach this server's address. Use an address the TV can reach."
}

/**
 * The likely reason a receiver never got going, and what to try. It's a guess and reads as one: this app can't see the
 * receiver's side of the network, and a working address on this device says nothing about that device.
 */
private fun castUnreachableHint(device: String) =
    "It may not be able to reach your server at the address this device uses. Try adding your server by an address $device can reach."

/** What happened to a handoff, in the viewer's terms. */
internal fun castRecoveryMessage(recovery: String?, device: String): String = when (recovery) {
    "unsupported-media" -> "$device can't play this."
    "receiver-load-failed" -> "$device couldn't load this. ${castUnreachableHint(device)}"
    "receiver-timeout" -> "$device didn't start in time. ${castUnreachableHint(device)}"
    "receiver-interrupted" -> "Something else started on $device."
    "server-unreachable" -> "Couldn't reach your server."
    "quick-connect-off" -> "Casting needs Quick Connect, and it's off on this server. Its admin can turn it on in the server's settings."
    "link-lost" -> "Lost the connection to $device."
    "connect-failed" -> "Couldn't connect to $device. Make sure it's on and on this Wi-Fi."
    "route-gone" -> "$device isn't available any more. Make sure it's on and on this Wi-Fi."
    "cancelled" -> "Cancelled."
    else -> "Couldn't play on $device."
}

internal fun castNoticeMessage(notice: String?, device: String): String? = when (notice) {
    "receiver-finished" -> "Finished playing on $device."
    "receiver-stopped" -> "Playback stopped on $device."
    "receiver-error" -> "$device stopped because of a playback error."
    "receiver-taken" -> "Something else is now playing on $device."
    "receiver-continues" -> "Still playing on $device. This device isn't connected to it."
    "link-lost" -> "Lost the connection to $device. It may still be playing."
    "connect-failed" -> "Couldn't connect to $device. Make sure it's on and on this Wi-Fi."
    else -> null
}

/** Why picking a device started nothing, and what to do first. */
internal fun castPickRefusalMessage(reason: String, device: String): String = when (reason) {
    "detached-elsewhere" -> "Still playing on $device. Open it from the Cast bar and reconnect or forget it before casting somewhere else."
    "link-not-ready" -> "Reconnecting to $device. Try again in a moment."
    "handoff-busy" -> "$device is still starting or stopping. Try again in a moment."
    else -> "Couldn't start casting. Try again in a moment."
}

/** One line for the bar, read only from what the receiver last said. */
internal fun castStatusLine(ui: CastUi): String? {
    val cast = ui.cast ?: return null
    val device = ui.deviceName ?: "Cast device"
    val observed = cast.observed
    return when {
        cast.owner.candidate != null -> "Starting on $device…"

        cast.owner.owner.kind != "cast" -> null

        cast.owner.phase == "returning" -> "Stopping on $device…"

        cast.owner.phase == "disconnected" -> "$device: not connected"

        cast.link == "suspended" -> "$device: reconnecting…"

        observed == null -> "$device: checking…"

        else -> buildString {
            append(device).append(": ")
            append(ui.title ?: "Playing")
            when (observed.playerState) {
                "paused" -> append(", paused")
                "buffering", "loading" -> append(", loading")
            }
        }
    }
}

/** The mark fills only for this: a viewing on the Cast device, connected and answering, with nothing starting or stopping it. */
internal fun castIsCasting(ui: CastUi): Boolean {
    val cast = ui.cast ?: return false
    return cast.owner.candidate == null &&
        cast.owner.owner.kind == "cast" &&
        cast.owner.phase == "active" &&
        cast.observed != null &&
        cast.link != "suspended" &&
        cast.link != "lost"
}

internal fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

internal fun Context.openPlayServicesListing() {
    runCatching {
        startActivity(
            Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=com.google.android.gms".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * The chooser as the app shows it: what each answer does to the sender, the Remote host and navigation. The dialog keeps
 * the request it composed with, which is what [CastSenderHost.pickJellyfin] checks a late tap against.
 */
@Composable
internal fun TouchPlayOnDialog(
    remoteState: RemoteControllerUi,
    castState: CastUi,
    cast: CastSenderHost,
    attachRemote: (RemoteTarget) -> Unit,
    pendingRemote: () -> (() -> Unit)?,
    forgetPending: () -> Unit,
    openRemote: () -> Unit,
    openCast: () -> Unit
) {
    val castRequest = cast.chooserRequest
    fun close() {
        forgetPending()
        cast.closeChooser()
    }
    TouchCastChooser(
        remoteState,
        castState,
        showCast = castRequest != null,
        onThisDevice = castRequest?.let { request ->
            {
                cast.chooseThisDevice(request)
                close()
            }
        },
        onPickRemote = { target ->
            val picked = cast.pickJellyfin(castRequest) {
                attachRemote(target)
                pendingRemote()?.invoke()
            }
            if (picked) {
                close()
                openRemote()
            }
        },
        onPickCast = { target ->
            cast.pick(target)
            if (cast.state.value.blockedReach == null && cast.state.value.pickRefusal == null) {
                forgetPending()
                if (castRequest?.local == null) openCast()
            }
        },
        onSetCastEnabled = cast::setEnabled,
        onDismiss = ::close,
        title = stringResource(if (castRequest == null) R.string.remote_choose_tv else R.string.cast_play_on)
    )
}

/** One list of everywhere this can play: this device, Pictureshow TVs and Cast devices. Picking is the only way to start. */
@Composable
internal fun TouchCastChooser(
    remote: RemoteControllerUi,
    ui: CastUi,
    showCast: Boolean,
    onThisDevice: (() -> Unit)?,
    onPickRemote: (RemoteTarget) -> Unit,
    onPickCast: (CastRoute) -> Unit,
    onSetCastEnabled: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    TouchCastChooser(
        remote,
        ui,
        showCast,
        onThisDevice,
        onPickRemote,
        onPickCast,
        onSetCastEnabled,
        onDismiss,
        title = stringResource(R.string.cast_play_on)
    )
}

@Composable
internal fun TouchCastChooser(
    remote: RemoteControllerUi,
    ui: CastUi,
    showCast: Boolean,
    onThisDevice: (() -> Unit)?,
    onPickRemote: (RemoteTarget) -> Unit,
    onPickCast: (CastRoute) -> Unit,
    onSetCastEnabled: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    title: String
) {
    val castSection = showCast && ui.capability.visible
    // A short window can't stack the sections: the TVs and devices fall below the fold, where nothing says they are (CAST-01).
    val wide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() >= 600.dp }
    val columns = castSection && isShortTouchWindow() && wide
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = if (columns) Modifier.widthIn(max = 560.dp).fillMaxWidth() else Modifier,
        properties = DialogProperties(usePlatformDefaultWidth = !columns),
        title = { Text(title, modifier = Modifier.semantics { heading() }) },
        text = {
            val scroll = rememberScrollState()
            Box {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(scroll)) {
                    if (onThisDevice != null) {
                        OutlinedButton(
                            onClick = onThisDevice,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cast:this-device"),
                            colors = chooserDestinationColors()
                        ) { Text("This device", modifier = Modifier.fillMaxWidth()) }
                    }
                    val first = onThisDevice == null
                    if (columns) {
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f), Arrangement.spacedBy(8.dp)) { ChooserTvs(remote, first, onPickRemote) }
                            Column(Modifier.weight(1f), Arrangement.spacedBy(8.dp)) { ChooserCast(ui, first, onPickCast, onSetCastEnabled) }
                        }
                    } else {
                        ChooserTvs(remote, first, onPickRemote)
                        if (castSection) ChooserCast(ui, first = false, onPickCast, onSetCastEnabled)
                    }
                }
                // The edge says there's more, and only at an edge that can still scroll.
                if (scroll.canScrollBackward) ChooserEdge(Modifier.align(Alignment.TopCenter).testTag("cast:more-above"), top = true)
                if (scroll.canScrollForward) ChooserEdge(Modifier.align(Alignment.BottomCenter).testTag("cast:more-below"), top = false)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ChooserEdge(modifier: Modifier, top: Boolean) {
    val fade = listOf(Color.Transparent, AlertDialogDefaults.containerColor)
    // No pointer input, so a tap goes through to the row under it.
    Box(modifier.fillMaxWidth().height(16.dp).background(Brush.verticalGradient(if (top) fade.reversed() else fade))) {
        HorizontalDivider(
            Modifier.align(if (top) Alignment.TopCenter else Alignment.BottomCenter),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Composable
private fun ChooserTvs(remote: RemoteControllerUi, first: Boolean, onPickRemote: (RemoteTarget) -> Unit) {
    ChooserHeading("Pictureshow TVs", first)
    when {
        remote.listing -> Text("Looking for TVs…")

        // The list asks again by itself while this is open, so there's nothing to press.
        remote.listFailed && remote.targets.isEmpty() -> Text("Couldn't reach the server. Check your connection.")

        remote.targets.isEmpty() -> Text("None found. Open Pictureshow on your TV and sign in.")
    }
    remote.targets.forEach { target ->
        OutlinedButton(
            onClick = { onPickRemote(target) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("remote:target:${target.session}"),
            colors = chooserDestinationColors()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(remoteTargetLabel(target))
                RemoteTargetViewer(target)
                Text(remoteTargetDetail(target), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ChooserCast(ui: CastUi, first: Boolean, onPickCast: (CastRoute) -> Unit, onSetCastEnabled: (Boolean) -> Unit) {
    val context = LocalContext.current
    ChooserHeading("Google Cast", first)
    castCapabilityMessage(ui.capability.state)?.let {
        Text(it, modifier = Modifier.testTag("cast:capability").semantics { liveRegion = LiveRegionMode.Polite })
    }
    ui.blockedReach?.let {
        Text(
            castReachMessage(it.category),
            modifier = Modifier.testTag("cast:reach").semantics {
                liveRegion =
                    LiveRegionMode.Polite
            }
        )
    }
    ui.pickRefusal?.let {
        Text(
            castPickRefusalMessage(it, ui.deviceName ?: "the Cast device"),
            modifier = Modifier.testTag("cast:pick-refused").semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
    ui.routes.forEach { route ->
        OutlinedButton(
            onClick = { onPickCast(route) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cast:route:${route.id.takeLast(4)}"),
            colors = chooserDestinationColors()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(castRouteLabel(route, ui.routes))
                castRouteDetail(route)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    when (ui.capability.state) {
        "services-update-required" -> ChooserRecovery("Update Google Play services", context::openPlayServicesListing)

        "permission-denied" -> ChooserRecovery("Open app settings", context::openAppSettings)

        "viewer-disabled" -> ChooserRecovery("Turn on Google Cast", { onSetCastEnabled(true) }, Modifier.testTag("cast:enable"))
    }
}

/** 8 dp between lines inside a section, and this makes it 16 before a new one. */
@Composable
private fun ChooserHeading(text: String, first: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = if (first) 0.dp else 8.dp).semantics { heading() }
    )
}

/** Everywhere this can play reads as something to press. */
@Composable
private fun chooserDestinationColors() = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)

/** The way back to being able to cast, so it reads as an action too: on the body's left edge, with its text where the rest of the body's is. */
@Composable
private fun ChooserRecovery(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick,
        modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        contentPadding = PaddingValues(start = 0.dp, end = 12.dp)
    ) { Text(label) }
}

/** Some routes describe themselves with their own name, which says nothing twice. */
internal fun castRouteDetail(route: CastRoute): String? = route.detail?.takeIf { it.isNotBlank() && it != route.name }

/** What the Settings switch shows: the stored choice, or null where Cast can't be used here, so there is nothing to switch. */
internal fun castSwitchState(capability: CastCapability, stored: Boolean): Boolean? = when {
    !capability.visible -> null
    capability.state in listOf("os-too-old", "browser-unsupported", "services-missing") -> null
    else -> stored
}

/** Queue actions may discover a Pictureshow TV even while a Cast receiver is playing. */
@Suppress("UNUSED_PARAMETER")
internal fun offersTvQueue(ui: CastUi, tvAttached: Boolean): Boolean = true

/** Stays in reach while browsing. It shows what the receiver reports and opens the controls. */
@Composable
internal fun TouchCastBar(ui: CastUi, onOpen: () -> Unit) {
    val line = castStatusLine(ui) ?: return
    TextButton(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cast:bar").semantics { liveRegion = LiveRegionMode.Polite }
    ) { Text(line) }
}

/** The next episode is already starting, so a dialog would be in the way. */
@Composable
internal fun TouchCastDroppedNotice(ui: CastUi, host: CastSenderHost) {
    val context = LocalContext.current
    LaunchedEffect(ui.droppedFrom) {
        val device = ui.droppedFrom ?: return@LaunchedEffect
        Toast.makeText(context, "Didn't start on $device, because this title finished first.", Toast.LENGTH_LONG).show()
        host.droppedHandled()
    }
}

/**
 * What needs the viewer while a handoff runs or after it ends. It shows over every screen, the player included, because
 * local playback is waiting on this answer.
 */
@Composable
internal fun TouchCastAttention(ui: CastUi, host: CastSenderHost) {
    val cast = ui.cast ?: return
    val device = ui.deviceName ?: "the Cast device"
    fun dialog(
        tag: String,
        message: String,
        confirm: Pair<String, () -> Unit>?,
        dismiss: Pair<String, () -> Unit>?,
        back: (() -> Unit)? = dismiss?.second
    ): @Composable () -> Unit = {
        AlertDialog(
            onDismissRequest = { back?.invoke() },
            modifier = Modifier.testTag(tag),
            // These wait on, or ask about, a TV the viewer may not be looking at here. Only Back or a button answers.
            properties = DialogProperties(dismissOnClickOutside = false),
            text = { Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
            confirmButton = { confirm?.let { (label, action) -> TextButton(onClick = action) { Text(label) } } },
            dismissButton = dismiss?.let { (label, action) -> { TextButton(onClick = action) { Text(label) } } }
        )
    }
    when {
        cast.owner.candidate != null -> dialog("cast:starting", "Starting on $device…", null, "Cancel" to host::cancel)()

        cast.owner.phase == "failed" -> dialog(
            "cast:failed",
            castRecoveryMessage(cast.recovery, device),
            "Resume here" to { host.resumeHere() },
            "Close" to host::dismiss
        )()

        cast.owner.phase == "return-uncertain" -> dialog(
            "cast:stop-unknown",
            "$device hasn't confirmed it stopped, so it may still be playing.",
            "Try again" to host::retryStop,
            "Play here anyway" to { host.resumeHere(confirmed = true) },
            // Backing out isn't saying "play here": that revokes the session and starts this device while the TV may still be playing.
            back = null
        )()

        cast.owner.phase == "return-ready" && cast.stopPurpose == "ended" -> dialog(
            "cast:ended",
            castNoticeMessage(cast.notice, device) ?: "Playback ended on $device.",
            "Resume here" to { host.resumeHere() },
            "Close" to host::dismiss
        )()
    }
}

/** The controls for what the receiver is playing. Every value comes from its last accepted status. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TouchCastScreen(ui: CastUi, host: CastSenderHost, onBack: () -> Unit) {
    val castOn by host.castEnabled.collectAsState()
    val cast = ui.cast
    val observed = cast?.observed
    val controls = ui.controls
    val device = ui.deviceName ?: "Cast device"
    val active = cast?.owner?.owner?.kind == "cast" && cast.owner.phase == "active"
    val ready = active && cast?.commands?.fresh == true
    val paused = observed?.playerState == "paused"
    val returning = cast?.owner?.phase == "returning"
    LaunchedEffect(cast == null) { if (cast == null) onBack() }
    Column(
        modifier = Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 560.dp)
            .verticalScroll(rememberScrollState()).padding(16.dp).testTag("touch:cast"),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TouchBack(onBack)
            Text(device, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            ui.title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            when {
                cast == null -> Text("Nothing is casting.")
                cast.owner.phase == "disconnected" -> {
                    Text(castNoticeMessage(cast.notice, device) ?: "This device isn't connected to $device.")
                    if (castOn) {
                        Button(onClick = host::reconnect, modifier = Modifier.heightIn(min = 48.dp).testTag("cast:reconnect")) {
                            Text("Reconnect")
                        }
                    } else {
                        Text("Turn Google Cast on in Settings to reconnect.")
                    }
                    TextButton(onClick = host::dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Forget it") }
                }
                observed == null -> Text("Checking what $device is doing…")
                else -> {
                    val position = remoteClock(observed.ticks)
                    val line = when {
                        controls?.timeline == "live" -> "Live"
                        observed.durationTicks != null -> "$position of ${remoteClock(observed.durationTicks ?: 0)}"
                        else -> position
                    }
                    Text(line, modifier = Modifier.testTag("cast:position"))
                    when (observed.playerState) {
                        "buffering", "loading" -> Text("Loading…")
                        "paused" -> Text("Paused")
                    }
                }
            }
            if (active &&
                !ready
            ) {
                Text(
                    stringResource(R.string.cast_waiting, device),
                    modifier = Modifier.testTag("cast:waiting").semantics {
                        liveRegion =
                            LiveRegionMode.Polite
                    }
                )
            }
            if (ui.queueError) Text(stringResource(R.string.cast_queue_failed))
            if (cast?.commands?.pending?.result == "rejected") {
                Text("$device didn't accept that.", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (cast?.commands?.pending?.result == "unknown") {
                Text("Couldn't confirm that on $device.", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        if (active) {
            if (LocalDensity.current.fontScale > 1.2f) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CastTransportButtons(
                        host,
                        paused,
                        ready && controls?.pause == true,
                        ready && controls?.seek == true,
                        Modifier.fillMaxWidth()
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CastTransportButtons(
                        host,
                        paused,
                        ready && controls?.pause == true,
                        ready && controls?.seek == true,
                        Modifier.weight(1f)
                    )
                }
            }
            val volumeLabel = stringResource(R.string.cast_volume_device, device)
            val muteLabel = stringResource(R.string.cast_mute)
            val percentage = stringResource(R.string.cast_volume_percent, ((ui.volume ?: 0.0) * 100).toInt())
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("cast:volume")) {
                Text(volumeLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                if (controls?.volume == false) {
                    Text(
                        stringResource(R.string.cast_fixed_volume),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("cast:volume-reason")
                    )
                }
                Slider(
                    value = (ui.volume ?: 0.0).toFloat(),
                    onValueChange = { host.setVolume(it.toDouble()) },
                    enabled = ready && controls?.volume == true && ui.volume != null,
                    modifier = Modifier.fillMaxWidth().testTag("cast:volume-slider").semantics {
                        contentDescription = volumeLabel
                        stateDescription = percentage
                    }
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(muteLabel, style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = ui.muted,
                        onCheckedChange = host::setMuted,
                        enabled = ready && controls?.volume == true,
                        modifier = Modifier.testTag("cast:mute").semantics { contentDescription = muteLabel }
                    )
                }
            }
            if (ui.changingTrack) Text(stringResource(R.string.cast_changing_audio))
            ui.trackError?.let {
                Text(stringResource(R.string.cast_audio_failed), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            listOf("audio", "subtitle").forEach { type ->
                val tracks = controls?.tracks.orEmpty().filter { it.type == type }
                if (tracks.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(if (type == "audio") R.string.cast_audio else R.string.cast_subtitles),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() }
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (type == "subtitle") {
                                FilterChip(
                                    selected = tracks.none { it.selected },
                                    onClick = { host.setSubtitle(null) },
                                    enabled = ready && !ui.changingTrack && observed?.commands?.contains("tracks") == true,
                                    label = {
                                        Text(stringResource(R.string.cast_subtitles_off))
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                )
                            }
                            tracks.forEach { track ->
                                FilterChip(
                                    selected = track.selected && !ui.changingTrack,
                                    onClick = { if (type == "audio") host.setAudio(track.id) else host.setSubtitle(track.id) },
                                    enabled = ready && !ui.changingTrack && track.selectable,
                                    label = { Text(track.label) },
                                    leadingIcon = if (track.selected && !ui.changingTrack) ({ Text("✓") }) else null,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("cast:track:${track.id}")
                                )
                            }
                        }
                    }
                }
            }
            if (ui.queue.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.cast_queue),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() }
                    )
                    ui.queue.forEach { item ->
                        TextButton(
                            onClick = { host.jump(item.entry) },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
                            enabled = ready && !ui.changingTrack,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cast:queue:${item.entry}")
                                .semantics { selected = item.entry == observed?.entry }
                        ) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.title.orEmpty(), color = MaterialTheme.colorScheme.onSurface)
                                if (item.entry == observed?.entry) {
                                    Text(
                                        stringResource(R.string.cast_now_playing),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = host::stop, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cast:stop")) {
                    Text(stringResource(R.string.cast_stop))
                }
                OutlinedButton(
                    onClick = host::playHere,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cast:play-here")
                ) {
                    Text(stringResource(R.string.cast_play_here))
                }
                TextButton(
                    onClick = host::disconnect,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cast:disconnect")
                ) {
                    Text(stringResource(R.string.cast_leave_playing, device))
                }
            }
        }
        if (returning) Text("Stopping on $device…")
    }
}

@Composable
private fun CastTransportButtons(host: CastSenderHost, paused: Boolean, canPause: Boolean, canSeek: Boolean, modifier: Modifier) {
    val back = stringResource(R.string.cast_rewind_long)
    val forward = stringResource(R.string.cast_forward)
    val padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    Button(
        onClick = { if (paused) host.play() else host.pause() },
        enabled = canPause,
        contentPadding = padding,
        modifier = modifier.heightIn(min = 48.dp).testTag("cast:toggle")
    ) { Text(if (paused) "Play" else "Pause") }
    OutlinedButton(
        onClick = { host.seekBy(-TEN_SECONDS_TICKS) },
        enabled = canSeek,
        contentPadding = padding,
        modifier = modifier.heightIn(min = 48.dp).testTag("cast:back10").semantics { contentDescription = back }
    ) {
        Text(stringResource(R.string.cast_rewind))
    }
    OutlinedButton(
        onClick = { host.seekBy(THIRTY_SECONDS_TICKS) },
        enabled = canSeek,
        contentPadding = padding,
        modifier = modifier.heightIn(min = 48.dp).testTag("cast:forward30").semantics { contentDescription = forward }
    ) {
        Text(stringResource(R.string.cast_forward_short))
    }
}
