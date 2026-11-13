/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.sync

/** Shared group intent is independent of a decoder temporarily held for synchronization. */
fun syncPlayerPrimaryAction(
    participating: Boolean,
    usable: Boolean,
    queueEntries: Int,
    phase: String,
    localShowPlayButton: Boolean
): String? = when {
    !participating -> if (localShowPlayButton) "play" else "pause"
    !usable || queueEntries <= 0 -> null
    phase == "Playing" -> "pause"
    else -> "play"
}

data class SyncPlayerSpeedSelection(val selectionAllowed: Boolean, val explanationRequired: Boolean)

/** A group owns the timeline; personal speed selection resumes after leaving. */
fun syncPlayerSpeedSelection(participating: Boolean): SyncPlayerSpeedSelection =
    SyncPlayerSpeedSelection(selectionAllowed = !participating, explanationRequired = participating)
