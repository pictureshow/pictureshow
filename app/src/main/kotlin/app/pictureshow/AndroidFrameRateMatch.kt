/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.view.Display
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.media3.common.C
import androidx.media3.common.Player
import app.pictureshow.core.playback.FrameRateDisplayMode
import app.pictureshow.core.playback.FrameRateMatch
import app.pictureshow.core.playback.HdrFormat

/** Own only this window's preference, not a claim that the OS/display switched to the requested mode. */
internal class FrameRateWindowRequest(private val window: Window) : AutoCloseable {
    private val previous = window.attributes.preferredDisplayModeId
    private var closed = false
    fun update(frameRate: Float, current: FrameRateDisplayMode, supported: List<FrameRateDisplayMode>) {
        if (closed) return
        val requested = FrameRateMatch.select(frameRate, current, supported)?.id ?: previous
        if (window.attributes.preferredDisplayModeId == requested) return
        window.attributes = window.attributes.apply { preferredDisplayModeId = requested }
        PlaybackDiagnostics.write("frame-rate requestedMode=$requested receivedFps=$frameRate")
    }
    override fun close() {
        if (closed) return
        closed = true
        if (window.attributes.preferredDisplayModeId != previous) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = previous }
        }
    }
}

private fun Context.frameRateActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.frameRateActivity() else null
    else -> null
}

private fun Display.Mode.frameRateMode() = FrameRateDisplayMode(
    modeId,
    physicalWidth,
    physicalHeight,
    refreshRate,
    if (Build.VERSION.SDK_INT >= 34) {
        supportedHdrTypes.map { type ->
            when (type) {
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> HdrFormat.HDR10
                Display.HdrCapabilities.HDR_TYPE_HLG -> HdrFormat.HLG
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> HdrFormat.DOLBY_VISION
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> HdrFormat.HDR10_PLUS
                else -> null
            }
        }.filterNotNull().toSet()
    } else {
        emptySet()
    }
)

/** Media3's Surface request handles seamless switches; this TV-only window preference also allows discrete HDMI modes. */
@Composable
internal fun AndroidFrameRateMatch(player: Player?, enabled: Boolean) {
    val context = LocalContext.current
    val activity = context.frameRateActivity()
    val view = LocalView.current
    val television = LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    DisposableEffect(player, enabled, activity, television) {
        val active = player
        if (!enabled || active == null || activity == null || !television) return@DisposableEffect onDispose {}
        val request = FrameRateWindowRequest(activity.window)
        fun update() {
            val display = view.display ?: return
            val selected = active.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
            val format = selected?.let { group ->
                (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat)
            }
            request.update(format?.frameRate ?: -1f, display.mode.frameRateMode(), display.supportedModes.map { it.frameRateMode() })
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = update()
        }
        active.addListener(listener)
        update()
        onDispose {
            active.removeListener(listener)
            request.close()
        }
    }
}
