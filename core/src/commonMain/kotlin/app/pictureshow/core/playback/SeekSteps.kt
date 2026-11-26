/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import app.pictureshow.core.displayRuntimeMillis

/** An unacknowledged viewing target survives decoder replacement and remains the base of the next step. */
object SeekSteps {
    const val BASE_STEP_MS = 10_000L
    const val HELD_REPEAT_INTERVAL_MS = 100L

    /** Automatic alignment preserves any deliberate preview without opening its own seek UI. */
    fun preview(previousMs: Long?, targetMs: Long, showFeedback: Boolean): Long? = if (showFeedback) targetMs else previousMs

    fun target(positionMs: Long, pendingMs: Long?, deltaMs: Long, durationMs: Long): Long =
        ((pendingMs ?: positionMs).coerceIn(0, durationMs.coerceAtLeast(0)) + deltaMs)
            .coerceIn(0, durationMs.coerceAtLeast(0))

    /**
     * PLAY-03: time-held seek step tiers (10 s / 30 s / 60 s / 120 s), capped at duration / 20 (floor 10 s) when
     * duration is known so short clips never jump disproportionately far.
     */
    fun heldStepMs(heldMs: Long, durationMs: Long): Long {
        val elapsed = heldMs.coerceAtLeast(0L)
        val tier = when {
            elapsed < 1_500L -> BASE_STEP_MS
            elapsed < 3_000L -> 30_000L
            elapsed < 5_000L -> 60_000L
            else -> 120_000L
        }
        return if (durationMs > 0L) minOf(tier, maxOf(BASE_STEP_MS, durationMs / 20L)) else tier
    }

    /** PLAY-03: initial presses always run; held repeats throttle to one step per [minIntervalMs]. */
    fun allowRepeat(repeatCount: Int, lastStepAtMs: Long?, eventTimeMs: Long, minIntervalMs: Long = HELD_REPEAT_INTERVAL_MS): Boolean {
        if (repeatCount <= 0 || lastStepAtMs == null) return true
        val elapsed = eventTimeMs - lastStepAtMs
        return elapsed < 0L || elapsed >= minIntervalMs
    }

    /** Wall-clock epoch when playback finishes at [speed], or null when runtime is unknown/implausible. */
    fun endsAtEpochMs(nowEpochMs: Long, positionMs: Long, durationMs: Long, speed: Double = 1.0): Long? {
        val total = displayRuntimeMillis(durationMs) ?: return null
        if (speed <= 0.0) return null
        val remaining = (total - positionMs.coerceIn(0L, total)).coerceAtLeast(0L)
        return nowEpochMs + (remaining / speed).toLong()
    }
}
