/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:kotlinx.serialization.UseSerializers(DecimalLongSerializer::class)

package app.pictureshow.core.control

import app.pictureshow.core.Account
import kotlinx.serialization.Serializable

/** What ends the temporary session behind a receiver's media URLs, and what deliberately doesn't. */
private val CAST_SESSION_ENDS = setOf(
    "stop-confirmed",
    "receiver-ended",
    "load-failed",
    "load-timeout",
    "cancel",
    "play-here-unconfirmed",
    "foreign-media",
    "replaced"
)

/** The viewer is leaving: the stored session goes whatever generation or sender the caller thinks it has. */
private val CAST_SESSION_LEAVES = setOf("forget", "viewer-change", "sign-out")

/** The receiver may still be fetching with it, or an unconfirmed Stop is still the viewer's to settle. */
private val CAST_SESSION_KEEPS = setOf("stop-unconfirmed", "disconnect", "link-lost", "link-suspended")

@Serializable
data class CastSessionEvent(
    val op: String,
    val binding: TemporarySession,
    val returnedViewer: String? = null,
    val secretRef: String? = null
)

/**
 * The Cast viewing's session, on top of [reduceTemporary]: the receiver reads media with a session of its own, so the
 * viewer's token is never put in a URL that others on the network can read, and ending the viewing can revoke it.
 */
fun reduceCastSession(state: TemporaryState, event: CastSessionEvent): TemporaryState {
    fun temporary(op: String) = reduceTemporary(state, TemporaryEvent(op, event.binding, event.returnedViewer, event.secretRef))
    return when (event.op) {
        "provision" -> temporary("create")

        "issued", "persisted", "logout-ok", "logout-failed", "reconnect" -> temporary(event.op)

        in CAST_SESSION_ENDS, in CAST_SESSION_LEAVES -> {
            val b = event.binding
            // Every path that ends a viewing lands here, some of them twice; only the first has anything to end.
            val record = state.records.firstOrNull { it.server == b.server && it.viewer == b.viewer && it.device == b.device }
                ?: return state.copy(effect = "none", reason = null)
            // The device id is stable per receiver, so a late end for an older viewing finds the newer viewing's record: it
            // is reported, not obeyed. The viewer leaving is not about one viewing, so that always acts on what's stored.
            val stale = record.generation != b.generation || record.sender != b.sender
            if (stale && event.op !in CAST_SESSION_LEAVES) return state.copy(effect = "none", reason = "stale-binding")
            reduceTemporary(state, TemporaryEvent("end", record))
        }

        in CAST_SESSION_KEEPS -> state.copy(effect = "keep", reason = null)

        else -> error("Unknown cast-session operation: ${event.op}")
    }
}

/** One item the viewing is about, as it was when we loaded it. */
@Serializable
data class CastViewedItem(
    val entry: String,
    val itemId: String,
    val mediaSourceId: String? = null,
    val playSessionId: String? = null,
    /** DirectPlay, DirectStream or Transcode. */
    val method: String = "DirectPlay",
    /** The media URL without its query, so without the token. */
    val url: String = ""
)

/**
 * Written to private storage at load. A receiver's status is readable and writable by anything on its network, so this
 * record, never the receiver's custom data, says what a viewing is and what gets reported about it.
 */
@Serializable
data class CastViewingRecord(
    /**
     * Random for each load. Not proof of ownership: anything that can read the receiver's status can replay it, and the
     * URL and route checks in [castReattach] only make that harder. Only a receiver of our own can authenticate a viewing.
     */
    val nonce: String,
    val server: String,
    val viewer: String,
    val route: String,
    val device: String,
    val session: String,
    val generation: Long,
    val items: List<CastViewedItem>,
    val title: String? = null,
    val deviceName: String? = null
)

@Serializable
data class CastReattachInput(
    val record: CastViewingRecord? = null,
    val server: String,
    val viewer: String,
    val mediaSession: Long = -1,
    val playerState: String = "idle",
    /** The receiver's words, trusted for nothing but the comparison. */
    val customData: Map<String, String> = emptyMap(),
    val contentUrl: String? = null,
    /** The route this sender is joined to, from its own side of the link. */
    val route: String? = null,
    val idleReason: String? = null,
    /** The status before this one was already empty. One empty status with no reason is not an answer. */
    val emptyBefore: Boolean = false
)

/** A media URL without its query, which is where the token is. */
fun castUrlBase(url: String): String = url.substringBefore('?')

/** The highest generation a sender can reach; above this the stored number itself is damaged. */
const val CAST_GENERATION_LIMIT: Long = 1L shl 53

@Serializable
data class CastReattach(
    /** adopt, ended (nothing is playing there), recheck (ask once more) or foreign (read-only: no controls, no reports). */
    val decision: String,
    /** no-record, other-account, route, nonce, generation, entry or url when foreign. */
    val reason: String? = null,
    val generation: Long? = null,
    val entry: String? = null
)

/**
 * After a process death: ours only if what the receiver shows matches what private storage says we loaded, for this viewer
 * on this route. This hardens but doesn't authenticate, since all of it can be read off the receiver and replayed; so a
 * match still reports only what's stored, and nothing is ever taken from the receiver.
 */
fun castReattach(input: CastReattachInput): CastReattach {
    val record = input.record ?: return CastReattach("foreign", "no-record")
    if (record.server != input.server || record.viewer != input.viewer) return CastReattach("foreign", "other-account")
    // A missing value on either side is never a match, so empty can't equal empty.
    if (record.route.isBlank() || input.route != record.route) return CastReattach("foreign", "route")
    if (input.mediaSession < 0 && input.idleReason == null && !input.emptyBefore) return CastReattach("recheck")
    if (input.mediaSession < 0 || input.playerState == "idle") return CastReattach("ended")
    if (input.customData["nonce"] != record.nonce) return CastReattach("foreign", "nonce")
    val claimed = input.customData["generation"]?.toLongOrNull()
    if (claimed == null || claimed <= 0 || claimed > CAST_GENERATION_LIMIT || claimed != record.generation) {
        return CastReattach("foreign", "generation")
    }
    val entry = input.customData["entry"]
    val item = record.items.firstOrNull { it.entry == entry } ?: return CastReattach("foreign", "entry")
    if (item.url.isBlank() || input.contentUrl == null || castUrlBase(input.contentUrl) != item.url) {
        return CastReattach("foreign", "url")
    }
    return CastReattach("adopt", generation = record.generation, entry = entry)
}

/** Private storage for the temporary sessions: their lifecycle, and each one's credential by reference. */
@Serializable
data class CastStoredSessions(val state: TemporaryState = TemporaryState(), val secrets: Map<String, Account> = emptyMap())
