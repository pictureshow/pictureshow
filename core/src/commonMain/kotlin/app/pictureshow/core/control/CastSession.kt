/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:kotlinx.serialization.UseSerializers(DecimalLongSerializer::class)

package app.pictureshow.core.control

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
    "forget",
    "viewer-change",
    "sign-out"
)

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

        in CAST_SESSION_ENDS -> {
            val b = event.binding
            val known = state.records.any { it.server == b.server && it.viewer == b.viewer && it.device == b.device }
            // Every path that ends a viewing lands here, some of them twice; only the first has anything to end.
            if (known) temporary("end") else state.copy(effect = "none", reason = null)
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
    val method: String = "DirectPlay"
)

/**
 * Written to private storage at load. A receiver's status is readable and writable by anything on its network, so this
 * record, never the receiver's custom data, says what a viewing is and what gets reported about it.
 */
@Serializable
data class CastViewingRecord(
    /** Random for each load. It proves nothing to an observer who can read the receiver, only that the media is what we loaded. */
    val nonce: String,
    val server: String,
    val viewer: String,
    val route: String,
    val device: String,
    val session: String,
    val generation: Long,
    val items: List<CastViewedItem>
)

@Serializable
data class CastReattachInput(
    val record: CastViewingRecord? = null,
    val server: String,
    val viewer: String,
    val mediaSession: Long = -1,
    val playerState: String = "idle",
    /** The receiver's words, trusted for nothing but the comparison. */
    val customData: Map<String, String> = emptyMap()
)

/** The highest generation a sender can reach; above this the stored number itself is damaged. */
const val CAST_GENERATION_LIMIT: Long = 1L shl 53

@Serializable
data class CastReattach(
    /** adopt, ended (nothing is playing there) or foreign (read-only: no controls, no reports). */
    val decision: String,
    /** no-record, other-account, nonce, generation or entry when foreign. */
    val reason: String? = null,
    val generation: Long? = null,
    val entry: String? = null
)

/** After a process death: ours only if what the receiver shows is what private storage says we loaded, for this viewer. */
fun castReattach(input: CastReattachInput): CastReattach {
    val record = input.record ?: return CastReattach("foreign", "no-record")
    if (record.server != input.server || record.viewer != input.viewer) return CastReattach("foreign", "other-account")
    if (input.mediaSession < 0 || input.playerState == "idle") return CastReattach("ended")
    if (input.customData["nonce"] != record.nonce) return CastReattach("foreign", "nonce")
    val claimed = input.customData["generation"]?.toLongOrNull()
    if (claimed == null || claimed <= 0 || claimed > CAST_GENERATION_LIMIT || claimed != record.generation) {
        return CastReattach("foreign", "generation")
    }
    val entry = input.customData["entry"]
    if (entry == null || record.items.none { it.entry == entry }) return CastReattach("foreign", "entry")
    return CastReattach("adopt", generation = record.generation, entry = entry)
}
