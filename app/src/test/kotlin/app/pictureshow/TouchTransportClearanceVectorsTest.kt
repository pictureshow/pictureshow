/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.ui.geometry.Rect
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** New optional-clearance contract only; the eight legacy exact-offset vectors remain separate. */
class TouchTransportClearanceVectorsTest {
    @Test fun joinedLandscapePlacementHasClearanceInsideAnAdequateFreeBand() {
        val roots = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        val file = roots.map { File(it, "spec/vectors/touch-player-director-clearance-paint-edges.json") }.first { it.isFile }
        val cases = Json.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonArray
        val profile = cases.map { it.jsonObject }.single {
            it.getValue("id").jsonPrimitive.content == "joined-landscape-free-band-clearance"
        }
        val placement = profile.getValue("placementCase").jsonObject
        val expected = placement.getValue("expected").jsonObject
        fun number(key: String) = placement.getValue(key).jsonPrimitive.float
        fun expectation(key: String) = expected.getValue(key).jsonPrimitive.float
        fun rect(value: JsonElement): Rect {
            val p = value.jsonArray.map { it.jsonPrimitive.float }
            assertEquals(4, p.size)
            return Rect(p[0], p[1], p[2], p[3])
        }
        val blockers = placement.getValue("blockers").jsonArray.map(::rect)
        val safe = rect(placement.getValue("safeArea"))
        val width = number("width")
        val height = number("height")
        val rowWidth = number("transportWidth")
        val rowHeight = number("transportHeight")
        val offset = touchTransportPlacement(
            width.toInt(),
            height.toInt(),
            number("panelHeight").toInt(),
            rect(placement.getValue("video")),
            blockers,
            rowWidth.toInt(),
            rowHeight.toInt(),
            safe,
            clearance = number("clearance")
        )
        // Existing public result convention: x offsets the row's centre from window centre;
        // y is the row's top. Derive the actual returned footprint, not an alternative placer.
        val left = width / 2f + offset.x - rowWidth / 2f
        val row = Rect(left, offset.y.toFloat(), left + rowWidth, offset.y + rowHeight)
        val panelTop = height - number("panelHeight")
        assertTrue(
            row.left >= safe.left && row.right <= safe.right && row.top >= safe.top && row.bottom <= min(safe.bottom, panelTop),
            "Whole returned transport must fit safe picture above the panel: row=$row safe=$safe panelTop=$panelTop"
        )
        val clearance = expectation("minimumBlockerClearance")
        for (blocker in blockers) {
            assertTrue(
                row.right + clearance <= blocker.left ||
                    row.left >= blocker.right + clearance ||
                    row.bottom + clearance <= blocker.top ||
                    row.top >= blocker.bottom + clearance,
                "Returned transport must leave meaningful space from every blocker: row=$row blocker=$blocker clearance=$clearance"
            )
        }
        val lowerGap = panelTop - row.bottom
        assertTrue(lowerGap >= expectation("minimumPanelClearance"), "Transport must also clear panel: row=$row panelTop=$panelTop")
        val blockerBottom = blockers.maxOf { it.bottom }
        assertTrue(panelTop - blockerBottom >= rowHeight + 2 * clearance, "Profile must provide adequate vertical space")
        val upperGap = row.top - blockerBottom
        assertTrue(
            abs(upperGap - lowerGap) <= expectation("maximumFreeBandGapImbalance"),
            "Use the adequate free band rather than clamping to its edge: upperGap=$upperGap lowerGap=$lowerGap row=$row"
        )
    }
}
