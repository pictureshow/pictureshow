/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.livetv.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Execute the existing Android presentation contract against the rules now reused directly by desktop. */
class LivePresentationVectorsTest {
    @Test fun sharedPresentationCases() {
        val resource = requireNotNull(javaClass.classLoader.getResource("android-native-vectors/live-presentation.json"))
        val cases = Json.parseToJsonElement(resource.readText()).jsonObject.getValue("cases").jsonArray
        var groups = 0; var windows = 0; var focus = 0
        cases.map { it.jsonObject }.forEach { case ->
            val input = case.getValue("input").jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            when (case.getValue("operation").jsonPrimitive.content) {
                "schedule-group" -> {
                    groups++
                    assertEquals(case.getValue("expected").jsonPrimitive.content,
                        liveScheduleGroup(LiveSchedule(input.getValue("fields").jsonObject), liveInstant(input.getValue("now").jsonPrimitive.content)!!), id)
                }
                "initial-focus" -> {
                    focus++
                    val programs = input.getValue("programs").jsonArray.map { it.jsonObject }.map { p ->
                        LiveProgram(p.getValue("id").jsonPrimitive.content, startDate = iso(p.getValue("start").jsonPrimitive.long), endDate = iso(p.getValue("end").jsonPrimitive.long))
                    }
                    val cells = liveGuideCells(programs, input.getValue("start").jsonPrimitive.long, input.getValue("end").jsonPrimitive.long)
                    assertEquals(case.getValue("expected").jsonPrimitive.contentOrNull, liveGuideInitialFocus(cells, input.getValue("now").jsonPrimitive.long), id)
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
        assertEquals(13, groups); assertEquals(8, windows); assertEquals(5, focus)
    }
    @Test fun cellsPreserveGapsOverlapsAndBoundaries() {
        val p = listOf(LiveProgram("a", startDate = iso(0), endDate = iso(60000)), LiveProgram("b", startDate = iso(30000), endDate = iso(90000)), LiveProgram("c", startDate = iso(100000), endDate = iso(110000)))
        val cells = liveGuideCells(p + p[0], 0, 120000)
        assertEquals(listOf(0,1,0), cells.map { it.lane })
        assertEquals(listOf(.0f,.25f,100000f/120000f), cells.map { it.startFraction })
        assertEquals("b", liveGuideInitialFocus(cells, 60000))
        assertTrue(liveGuideCells(p, 110000, 120000).isEmpty())
    }
    private fun iso(value: Long) = kotlin.time.Instant.fromEpochMilliseconds(value).toString()
}
