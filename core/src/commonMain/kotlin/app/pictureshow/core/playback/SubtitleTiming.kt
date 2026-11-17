/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/**
 * PLAY-11: one viewing's subtitle timing. Positive shows captions later than their authored time; 100 ms steps,
 * bounded to ±10 s. The wording is the web and Apple panel's ("Timing: +300 ms (later)").
 */
object SubtitleTiming {
    const val STEP_MS = 100
    const val LIMIT_MS = 10_000
    const val HELP = "Use Later if captions appear too early."
    const val BURNED_IN = "These subtitles are encoded into the video. Timing and appearance cannot be changed."

    /** What the timing section shows for the selected subtitle. */
    enum class Section { Hidden, BurnedIn, Controls }

    /** Off hides the section; a subtitle the server draws into the video gets one line and no controls. */
    fun section(subtitleOn: Boolean, burnedIn: Boolean): Section = when {
        !subtitleOn -> Section.Hidden
        burnedIn -> Section.BurnedIn
        else -> Section.Controls
    }

    /** Any stored or typed value lands on the 100 ms grid inside the bounds (toward zero). */
    fun normalize(ms: Int): Int = ms.coerceIn(-LIMIT_MS, LIMIT_MS) / STEP_MS * STEP_MS

    /** The delay after one Earlier (negative) or Later (positive) press. */
    fun step(ms: Int, direction: Int): Int = normalize(normalize(ms) + direction.coerceIn(-1, 1) * STEP_MS)

    /** False at a bound, so the button can say it can't go further. */
    fun canStep(ms: Int, direction: Int): Boolean = direction != 0 && step(ms, direction) != normalize(ms)

    /** "Timing: in sync", or the signed delay and which way it moves the captions. */
    fun label(ms: Int): String {
        val value = normalize(ms)
        if (value == 0) return "Timing: in sync"
        val amount = grouped(kotlin.math.abs(value))
        return if (value > 0) "Timing: +$amount ms (later)" else "Timing: −$amount ms (earlier)"
    }

    private fun grouped(value: Int): String = value.toString().reversed().chunked(3).joinToString(",").reversed()
}
