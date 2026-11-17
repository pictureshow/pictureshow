/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.music

import app.pictureshow.core.control.QueueState

/** What the queue does with a track it couldn't get from the server for want of a connection (MUS-10). */
data class MusicUnreachable(
    /** "skip": move on through the queue's error step, which skips each entry once per traversal. "pause": stay here, saved. */
    val step: String,
    val message: String
)

const val MUSIC_UNREACHABLE_TEXT = "Can't reach the server. Your queue is saved: press Play to try again."

/**
 * The current track isn't downloaded and the server can't be reached. A queue with a downloaded track still ahead on this traversal
 * (the wrap included under Repeat All, entries already skipped left out) skips it, saying why: the queue's error step marks it, so it
 * is skipped once per traversal and a queue with nothing left to play stops instead of spinning. Nothing downloaded ahead: stay on
 * it, paused and saved, and say whether that's because nothing here is downloaded at all or only nothing after it.
 * [local] holds the media ids that have a ready download.
 */
fun musicUnreachable(q: QueueState, local: Set<String>, title: String): MusicUnreachable {
    val media = q.entries.associate { it.id to it.media }
    val name = title.ifBlank { "This track" }
    if (q.entries.none { it.media in local }) return MusicUnreachable("pause", MUSIC_UNREACHABLE_TEXT)
    val at = q.order.indexOf(q.current)
    val ahead = when {
        at < 0 || q.repeat == "one" -> emptyList()
        else -> q.order.drop(at + 1) + if (q.repeat == "all") q.order.take(at) else emptyList()
    }
    val downloadedAhead = ahead.any { it !in q.failed && media[it] in local }
    return if (downloadedAhead) {
        MusicUnreachable("skip", "Skipped $name: it isn't downloaded, and the server can't be reached.")
    } else {
        MusicUnreachable("pause", "$name isn't downloaded, and the server can't be reached. Nothing after it is downloaded.")
    }
}
