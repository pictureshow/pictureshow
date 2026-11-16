/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:kotlinx.serialization.UseSerializers(DecimalLongSerializer::class)

package app.pictureshow.core.control

import kotlinx.serialization.Serializable

/** Queue entries identify occurrences, so two copies of one episode or song remain distinct. */
@Serializable
data class CastQueueInput(
    val entries: List<String>,
    val media: List<String>,
    val current: String?,
    val receiverOrder: List<String> = emptyList(),
    val repeat: String = "off"
)

@Serializable
data class CastQueueProjection(
    val order: List<String>,
    val item: String?,
    val next: String?,
    val previous: String?,
    val repeat: String,
    val reason: String? = null
)

/** Trust receiver order only within the privately saved queue; media identity always comes from that record. */
fun castQueue(input: CastQueueInput): CastQueueProjection {
    val repeat = input.repeat.takeIf { it in listOf("off", "one", "all") } ?: "off"
    val valid = input.entries.size == input.media.size && input.entries.distinct().size == input.entries.size
    val known = input.receiverOrder.isEmpty() ||
        (input.receiverOrder.distinct().size == input.receiverOrder.size && input.receiverOrder.all { it in input.entries })
    val order = if (valid && known) input.receiverOrder.ifEmpty { input.entries } else input.entries
    val index = order.indexOf(input.current)
    val item = input.entries.indexOf(input.current).takeIf { valid && it >= 0 }?.let(input.media::get)
    return CastQueueProjection(
        order = order,
        item = item,
        next = if (index < 0) null else order.getOrNull(index + 1) ?: order.firstOrNull().takeIf { repeat == "all" },
        previous = if (index < 0) null else order.getOrNull(index - 1),
        repeat = repeat,
        reason = when {
            !valid -> "invalid-record"
            !known -> "foreign-queue"
            item == null -> "unknown-entry"
            else -> null
        }
    )
}

@Serializable
data class CastTrackReloadInput(val ticks: Long, val paused: Boolean, val available: Boolean, val durationTicks: Long? = null)

@Serializable
data class CastTrackReload(val allowed: Boolean, val ticks: Long, val paused: Boolean, val action: String)

/** Audio selection on DMR is a reload at the same observed moment, including a deliberately paused viewing. */
fun castTrackReload(input: CastTrackReloadInput): CastTrackReload = CastTrackReload(
    input.available,
    input.ticks.coerceAtLeast(0).let { value -> input.durationTicks?.takeIf { it > 0 }?.let { minOf(value, it) } ?: value },
    input.paused,
    if (input.available) "renegotiate-and-load" else "unavailable"
)
