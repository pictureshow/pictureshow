/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.livetv.LiveProgram
import app.pictureshow.core.livetv.LiveSchedule
import app.pictureshow.core.livetv.liveGuideCells
import app.pictureshow.core.livetv.liveGuideInitialFocus
import app.pictureshow.core.livetv.liveGuideTextWindow
import app.pictureshow.core.livetv.liveInstant
import app.pictureshow.core.livetv.liveRecordingMetadata
import app.pictureshow.core.livetv.liveRecordingProgress
import app.pictureshow.core.livetv.liveRecordingTitle
import app.pictureshow.core.livetv.liveScheduleGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Execute the existing Android presentation contract against the rules now reused directly by desktop. */
class LivePresentationVectorsTest {
    @Test fun sharedPresentationCases() {
        val resource = requireNotNull(javaClass.classLoader.getResource("android-native-vectors/live-presentation.json"))
        val cases = Json.parseToJsonElement(resource.readText()).jsonObject.getValue("cases").jsonArray
        var groups = 0
        var windows = 0
        var focus = 0
        var recordings = 0
        cases.map { it.jsonObject }.forEach { case ->
            val input = case.getValue("input").jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            when (case.getValue("operation").jsonPrimitive.content) {
                "schedule-group" -> {
                    groups++
                    assertEquals(
                        case.getValue("expected").jsonPrimitive.content,
                        liveScheduleGroup(
                            LiveSchedule(input.getValue("fields").jsonObject),
                            liveInstant(input.getValue("now").jsonPrimitive.content)!!
                        ),
                        id
                    )
                }
                "initial-focus" -> {
                    focus++
                    val programs = input.getValue("programs").jsonArray.map { it.jsonObject }.map { p ->
                        LiveProgram(
                            p.getValue("id").jsonPrimitive.content,
                            startDate = iso(p.getValue("start").jsonPrimitive.long),
                            endDate = iso(p.getValue("end").jsonPrimitive.long)
                        )
                    }
                    val cells =
                        liveGuideCells(programs, input.getValue("start").jsonPrimitive.long, input.getValue("end").jsonPrimitive.long)
                    assertEquals(
                        case.getValue("expected").jsonPrimitive.contentOrNull,
                        liveGuideInitialFocus(cells, input.getValue("now").jsonPrimitive.long),
                        id
                    )
                }
                "recording-metadata" -> {
                    recordings++
                    val item = Json { ignoreUnknownKeys = true }.decodeFromJsonElement<MediaItem>(input.getValue("item"))
                    val zone = java.time.ZoneId.of(input.getValue("zone").jsonPrimitive.content)
                    val locale = java.util.Locale.forLanguageTag(input.getValue("locale").jsonPrimitive.content)
                    val today = java.time.Instant.parse(input.getValue("now").jsonPrimitive.content).atZone(zone).toLocalDate()
                    val clock = java.time.format.DateTimeFormatter.ofPattern(
                        if (input.getValue("twentyFour").jsonPrimitive.boolean) "HH:mm" else "h:mm a",
                        locale
                    )
                    fun day(value: Long): String {
                        val date = java.time.Instant.ofEpochMilli(value).atZone(zone).toLocalDate()
                        return when (date) {
                            today -> "Today"
                            today.plusDays(1) -> "Tomorrow"
                            else -> java.time.format.DateTimeFormatter.ofPattern("EEE d", locale).format(date)
                        }
                    }
                    val expected = case.getValue("expected").jsonObject
                    assertEquals(expected.getValue("title").jsonPrimitive.content, liveRecordingTitle(item), id)
                    assertEquals(
                        expected.getValue("metadata").jsonPrimitive.content,
                        liveRecordingMetadata(item, ::day) { clock.format(java.time.Instant.ofEpochMilli(it).atZone(zone)) },
                        id
                    )
                    assertEquals(expected.getValue("progress").jsonPrimitive.floatOrNull, liveRecordingProgress(item), id)
                }
                "guide-text-window" -> {
                    windows++
                    fun value(key: String) = input.getValue(key).jsonPrimitive.float
                    val actual = liveGuideTextWindow(value("cellX"), value("cellWidth"), value("viewportStart"), value("viewportWidth"))
                    val expected = case.getValue("expected").jsonObject
                    assertEquals(expected.getValue("inset").jsonPrimitive.float, actual.inset, id)
                    assertEquals(expected.getValue("width").jsonPrimitive.float, actual.width, id)
                }
            }
        }
        assertEquals(13, groups)
        assertEquals(8, windows)
        assertEquals(5, focus)
        assertEquals(11, recordings)
    }

    @Test fun cellsPreserveGapsOverlapsAndBoundaries() {
        val p =
            listOf(
                LiveProgram("a", startDate = iso(0), endDate = iso(60000)),
                LiveProgram("b", startDate = iso(30000), endDate = iso(90000)),
                LiveProgram("c", startDate = iso(100000), endDate = iso(110000))
            )
        val cells = liveGuideCells(p + p[0], 0, 120000)
        assertEquals(listOf(0, 1, 0), cells.map { it.lane })
        assertEquals(listOf(.0f, .25f, 100000f / 120000f), cells.map { it.startFraction })
        assertEquals("b", liveGuideInitialFocus(cells, 60000))
        assertTrue(liveGuideCells(p, 110000, 120000).isEmpty())
    }
    private fun iso(value: Long) = kotlin.time.Instant.fromEpochMilliseconds(value).toString()
}
