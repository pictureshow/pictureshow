/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.ui.geometry.Rect
import java.io.File
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class PlayerOverlayLayoutVectorsTest {
    @Test fun everyNativeOverlayLayoutVector() {
        val file = listOf(File("../spec/vectors/player-overlay-layout.json"), File("spec/vectors/player-overlay-layout.json"))
            .first { it.exists() }
        val envelope = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(1, envelope.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("player-overlay-layout", envelope.getValue("suite").jsonPrimitive.content)
        val cases = envelope.getValue("cases").jsonArray
        assertEquals(8, cases.size)
        fun rect(element: JsonElement): Rect {
            val p = element.jsonArray.map { it.jsonPrimitive.float }
            return Rect(p[0], p[1], p[2], p[3])
        }
        for (element in cases) {
            val case = element.jsonObject
            assertEquals("transport-placement", case.getValue("operation").jsonPrimitive.content)
            val c = case.getValue("input").jsonObject
            fun value(key: String) = c.getValue(key).jsonPrimitive.int
            val actual = touchTransportPlacement(
                value("width"),
                value("height"),
                value("panelHeight"),
                rect(c.getValue("video")),
                c.getValue("blockers").jsonArray.map(::rect),
                value("transportWidth"),
                value("transportHeight"),
                c["safeArea"]?.let(::rect) ?: Rect(0f, 0f, value("width").toFloat(), value("height").toFloat())
            )
            val expected = case.getValue("expected").jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            assertEquals(expected.getValue("x").jsonPrimitive.int, actual.x, id)
            assertEquals(expected.getValue("y").jsonPrimitive.int, actual.y, id)
        }
    }
}
