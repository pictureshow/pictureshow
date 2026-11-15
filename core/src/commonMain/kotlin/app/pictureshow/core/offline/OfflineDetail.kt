/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.offline

/** What the server looked like from here when the viewer acted. Unknown means nothing has failed yet: try it. */
@Suppress("ktlint:standard:enum-entry-name-case", "EnumEntryName")
enum class OfflineServerReach { reachable, unreachable, unknown }

/** Where a detail page's Play goes. */
@Suppress("ktlint:standard:enum-entry-name-case", "EnumEntryName")
enum class OfflineDetailSource { local, stream, unavailable }

/**
 * A title's own page plays its ready download, online or not, so a viewer on a plane never needs to know Downloads exists, and an
 * online viewing doesn't spend data on a copy already here. Streaming instead is a deliberate choice ([explicitStream]); with the
 * server known to be out of reach it is unavailable and says why, rather than opening a player that can only fail.
 */
fun offlineDetailPlaySource(ready: Boolean, server: OfflineServerReach, explicitStream: Boolean): OfflineDetailSource = when {
    !explicitStream && ready -> OfflineDetailSource.local
    server == OfflineServerReach.unreachable -> OfflineDetailSource.unavailable
    else -> OfflineDetailSource.stream
}

/**
 * Where the detail page's local Play starts, and what its Resume label says. An unsent local place is newer than anything the server
 * holds, so it wins. With nothing unsent, a fresh server read is the newest truth (another device may have moved it), and a conflict's
 * place is never a resume point. With neither, the device's last-known place, as for Downloads.
 */
fun offlineDetailStartTicks(fromStart: Boolean, outbox: OfflineOutboxEntry?, lastKnown: OfflineUserData?, server: OfflineUserData?): Long {
    if (fromStart) return 0
    val unsent = outbox != null && outbox.phase != "synced" && outbox.phase != "conflict"
    if (!unsent && server != null) return if (server.played) 0 else server.positionTicks.coerceAtLeast(0)
    return offlineResumeTicks(outbox, lastKnown)
}
