/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import kotlin.math.abs
import kotlin.math.round

data class FrameRateDisplayMode(
    val id: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
    val hdrFormats: Set<HdrFormat> = emptySet()
)

/** Prefer one refresh per frame, then the lowest integer multiple, without changing resolution or known HDR support. */
object FrameRateMatch {
    fun select(receivedFrameRate: Float, current: FrameRateDisplayMode, supported: List<FrameRateDisplayMode>): FrameRateDisplayMode? {
        if (!receivedFrameRate.isFinite() || receivedFrameRate <= 0) return null
        return supported.mapNotNull { mode ->
            if (mode.id <= 0 ||
                mode.width != current.width ||
                mode.height != current.height ||
                !mode.refreshRate.isFinite() ||
                mode.refreshRate <= 0 ||
                !mode.hdrFormats.containsAll(current.hdrFormats)
            ) {
                return@mapNotNull null
            }
            val ratio = mode.refreshRate.toDouble() / receivedFrameRate
            val multiple = round(ratio)
            // Accept common 1000/1001 timing pairs (23.976/24, 59.94/60), but never 24fps at 60Hz.
            if (multiple < 1 || abs(ratio - multiple) > multiple * 0.005) null else mode to multiple
        }.minWithOrNull(
            compareBy<Pair<FrameRateDisplayMode, Double>> { it.second }
                .thenBy { abs(it.first.refreshRate - receivedFrameRate * it.second) }
                .thenBy { it.first.id }
        )?.first
    }
}
