/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:kotlinx.serialization.UseSerializers(app.pictureshow.core.control.DecimalLongSerializer::class)

package app.pictureshow.core.music

import kotlinx.serialization.Serializable

@Serializable
data class MusicViewing(val entry: String, val media: String, val serial: Long, val started: Boolean = false)

@Serializable
data class MusicReportState(val viewing: MusicViewing? = null, val serial: Long = 0, val effects: List<MusicReportEffect> = emptyList())

@Serializable
data class MusicReportEffect(
    val report: String,
    val media: String,
    val serial: Long,
    val ticks: Long = 0,
    val paused: Boolean = false,
    val failed: Boolean = false
)

@Serializable
data class MusicReportEvent(
    val op: String,
    val entry: String? = null,
    val media: String? = null,
    val ticks: Long = 0,
    val paused: Boolean = false,
    val failed: Boolean = false,
    /** On "load": the track before it ran to its end, so its stop reports [durationTicks] rather than the last position seen. */
    val natural: Boolean = false,
    val durationTicks: Long = 0
)

/**
 * One viewing per time a track is heard (MUS-08). "load" names what the output holds and reports nothing, so a queue
 * restored paused says nothing to the server until the first Play. A repeated track is a new load, so a new viewing.
 * Whatever is reported twice for a cause, such as a duplicate end, finds no viewing or an unstarted one and does nothing.
 */
fun reduceMusicReport(state: MusicReportState, event: MusicReportEvent): MusicReportState {
    val s = state.copy(effects = emptyList())
    val viewing = s.viewing
    fun close(failed: Boolean = false) = if (viewing?.started == true) {
        // A track that played out was heard to its end, though the last position seen is up to a second short of it.
        val at = if (event.natural && event.durationTicks > 0) event.durationTicks else event.ticks
        listOf(MusicReportEffect("stopped", viewing.media, viewing.serial, at, failed = failed))
    } else {
        emptyList()
    }
    return when (event.op) {
        "load" -> {
            val entry = event.entry ?: return s
            val media = event.media ?: return s
            if (viewing?.entry == entry && !viewing.started) return s
            val serial = s.serial + 1
            s.copy(viewing = MusicViewing(entry, media, serial), serial = serial, effects = close())
        }
        "playing" -> when {
            viewing == null -> s
            !viewing.started -> s.copy(
                viewing = viewing.copy(started = true),
                effects = listOf(MusicReportEffect("start", viewing.media, viewing.serial, event.ticks))
            )
            else -> s.copy(effects = listOf(MusicReportEffect("progress", viewing.media, viewing.serial, event.ticks, event.paused)))
        }
        "progress" -> if (viewing?.started == true) {
            s.copy(effects = listOf(MusicReportEffect("progress", viewing.media, viewing.serial, event.ticks, event.paused)))
        } else {
            s
        }
        "ended", "stop" -> s.copy(viewing = null, effects = close(event.failed))
        else -> error("Unknown music report operation: ${event.op}")
    }
}
