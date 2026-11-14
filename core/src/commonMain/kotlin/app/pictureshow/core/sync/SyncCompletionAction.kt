/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.sync

enum class SyncCompletionAction { HOLD, NEXT, REPEAT, FINISH }

/** Natural completion is distinct from an explicit Next; the server still owns every continuation. */
fun syncCompletionAction(queue: SyncQueue?, hasSnapshot: Boolean, rendererMatches: Boolean, unpaused: Boolean): SyncCompletionAction {
    if (!hasSnapshot || !rendererMatches || !unpaused || queue?.current == null) return SyncCompletionAction.HOLD
    return when (queue.repeatMode) {
        "RepeatOne" -> SyncCompletionAction.REPEAT
        "RepeatAll" -> SyncCompletionAction.NEXT
        "RepeatNone" -> if (queue.currentIndex < queue.entries.lastIndex) SyncCompletionAction.NEXT else SyncCompletionAction.FINISH
        else -> SyncCompletionAction.HOLD
    }
}
