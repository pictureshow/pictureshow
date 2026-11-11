/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.playback.SeekSteps
import app.pictureshow.core.sync.SyncClockCalibration
import app.pictureshow.core.sync.SyncClockSample
import app.pictureshow.core.sync.SyncCommand
import app.pictureshow.core.sync.SyncDriftState
import app.pictureshow.core.sync.millisecondsToSyncTicks
import app.pictureshow.core.sync.stopSyncDrift
import app.pictureshow.core.sync.syncDrift
import app.pictureshow.core.sync.syncSameUnpauseTimeline
import app.pictureshow.core.sync.syncScheduledPlaying
import app.pictureshow.core.sync.syncScheduledPosition
import app.pictureshow.core.sync.syncTarget
import app.pictureshow.core.sync.syncTimelinePlaying
import app.pictureshow.core.sync.syncTransportApplied
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Expected numbers are independent, frozen contract data; unknown operations fail closed. */
class SyncTimingVectorsTest {
    private fun cases(name: String): List<JsonObject> = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader.getResourceAsStream("sync-vectors/$name.json")).bufferedReader().use { it.readText() }
    ).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    @Test fun clocks() {
        cases("sync-clock").forEach { row ->
            val expected = row.getValue("expected")
            when (row.getValue("op").jsonPrimitive.content) {
                "sample" -> {
                    val t = row.getValue("input").jsonArray.map { it.jsonPrimitive.long }
                    val value = SyncClockSample(t[0], t[1], t[2], t[3]).measurement()
                    if (expected is JsonNull) {
                        assertNull(value, row.toString())
                    } else {
                        assertNotNull(value)
                        val e = expected.jsonObject
                        assertEquals(e.getValue("rtt").jsonPrimitive.long, value.networkRttMillis, row.toString())
                        assertEquals(e.getValue("offsetTicks").jsonPrimitive.long, value.offsetTicks, row.toString())
                        assertEquals(e.getValue("uncertaintyTicks").jsonPrimitive.long, value.uncertaintyTicks)
                    }
                }

                "target" -> {
                    val i = row.getValue("input").jsonObject
                    val e = expected.jsonObject
                    val value = syncTarget(i.long("position"), i.long("when"), i.long("now"), i.bool("playing"), i.long("duration"))
                    assertNotNull(value)
                    assertEquals(e.long("position"), value.positionTicks, row.toString())
                    assertEquals(e.long("delay"), value.delayMillis, row.toString())
                }

                "scheduled-position" -> {
                    val i = row.getValue("input").jsonObject
                    val value = syncScheduledPosition(i.getValue("command").jsonPrimitive.content, i.long("current"), i.long("target"))
                    assertEquals(if (expected is JsonNull) null else expected.jsonPrimitive.long, value, row.toString())
                }

                "same-unpause-timeline" -> {
                    val i = row.getValue("input").jsonObject
                    fun command(value: kotlinx.serialization.json.JsonElement): SyncCommand? {
                        if (value is JsonNull) return null
                        val c = value.jsonObject
                        return SyncCommand(
                            c.getValue("group").jsonPrimitive.content,
                            c.getValue("entry").jsonPrimitive.content,
                            c.getValue("command").jsonPrimitive.content,
                            c.long("when"),
                            c.getValue("position").takeUnless { it is JsonNull }?.jsonPrimitive?.long,
                            c.long("emitted")
                        )
                    }
                    assertEquals(
                        expected.jsonPrimitive.boolean,
                        syncSameUnpauseTimeline(command(i.getValue("applied")), requireNotNull(command(i.getValue("received")))),
                        row.toString()
                    )
                }

                "seek-feedback" -> {
                    val i = row.getValue("input").jsonObject
                    val prior = i.getValue("previous").takeUnless { it is JsonNull }?.jsonPrimitive?.long
                    assertEquals(
                        if (expected is JsonNull) null else expected.jsonPrimitive.long,
                        SeekSteps.preview(prior, i.long("target"), i.bool("showFeedback")),
                        row.toString()
                    )
                }

                "timeline-playing" -> {
                    val i = row.getValue("input").jsonObject
                    assertEquals(
                        expected.jsonPrimitive.boolean,
                        syncTimelinePlaying(i.getValue("command").jsonPrimitive.content, i.bool("appliedPlaying")),
                        row.toString()
                    )
                }

                "scheduled-playing" -> {
                    val i = row.getValue("input").jsonObject
                    assertEquals(
                        expected.jsonPrimitive.boolean,
                        syncScheduledPlaying(i.bool("intended"), i.getValue("phase").jsonPrimitive.content),
                        row.toString()
                    )
                }

                else -> error("Unknown clock operation")
            }
        }
    }

    @Test fun drifts() {
        cases("sync-drift").forEach { row ->
            val i = row.getValue("input").jsonObject
            val e = row.getValue("expected").jsonObject
            val initialState = SyncDriftState(
                i["lastSeek"]?.jsonPrimitive?.long,
                i["rateStarted"]?.jsonPrimitive?.long,
                i["oldRate"]?.jsonPrimitive?.double ?: 1.0
            )
            val state = i["appliedSeek"]?.jsonPrimitive?.long?.let {
                syncTransportApplied(initialState, it, i["appliedPositionChanged"]?.jsonPrimitive?.boolean ?: true)
            } ?: initialState
            val result = syncDrift(
                state,
                50_000_000L + i.long("driftMs") * 10_000,
                50_000_000L,
                i.bool("playing"),
                i.long("now"),
                100_000_000L,
                i.bool("allowsRate"),
                i.getValue("rates").jsonArray.map {
                    it.jsonPrimitive.double
                }.toSet()
            )
            assertEquals(e.getValue("action").jsonPrimitive.content, result.action.name, row.toString())
            assertEquals(e.getValue("rate").jsonPrimitive.double, result.rate, row.toString())
            assertEquals(50_000_000L, result.targetTicks)
        }
    }

    @Test fun rollingCalibrationRejectsInvalidAndSelectsLowestRtt() {
        var c = SyncClockCalibration()
        (1L..6).forEach { c = c.add(SyncClockSample(1000, 1100, 1100, 1000 + it * 2)) }
        assertEquals(5, c.samples.size)
        assertEquals(4L, c.best?.networkRttMillis)
        assertSame(c, c.add(SyncClockSample(1000, 0, 0, 3000)))
        assertNull(SyncClockCalibration().anchor(1000, 0))
        val a = assertNotNull(c.anchor(1000, 100))
        assertNotNull(a.serverNowTicks(1200, 200)) // exactly +100ms wall step remains valid
        assertNull(a.serverNowTicks(1201, 200))
        assertNull(a.serverNowTicks(999, 400))
        assertFalse(a.needsRefresh(30099))
        assertTrue(a.needsRefresh(30100))
    }

    @Test fun overflowAndBoundsAreNotWrapped() {
        assertNull(millisecondsToSyncTicks(Long.MAX_VALUE))
        assertEquals(Long.MIN_VALUE / 10_000 * 10_000, millisecondsToSyncTicks(Long.MIN_VALUE / 10_000))
        assertNull(syncTarget(0, Long.MAX_VALUE, 0, true, 100))
        assertEquals(0L, syncTarget(-1, 0, 0, false, 100)?.positionTicks)
        assertEquals(SyncDriftState(), stopSyncDrift())
    }
    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean
}
