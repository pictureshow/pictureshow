/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Frozen outcomes of a vertical move in a grid. Web and Apple owe them. */
class GridNavigationVectorsTest {
    private fun outcome(move: GridVerticalMove): JsonElement = buildJsonObject {
        when (move) {
            is GridVerticalMove.Card -> {
                put("outcome", "card")
                put("index", move.index)
            }

            GridVerticalMove.Hold -> put("outcome", "hold")

            GridVerticalMove.Stay -> put("outcome", "stay")

            GridVerticalMove.Above -> put("outcome", "above")

            GridVerticalMove.PageRetry -> put("outcome", "page-retry")
        }
    }

    private fun value(operation: String, input: JsonObject): JsonElement {
        fun int(name: String) = input.getValue(name).jsonPrimitive.int
        fun bool(name: String) = input.getValue(name).jsonPrimitive.boolean
        fun failed() = input["pageFailed"]?.jsonPrimitive?.boolean == true
        return when (operation) {
            "grid-vertical-outcome" ->
                outcome(gridVerticalMove(int("index"), int("columns"), int("count"), bool("down"), bool("loadingMore"), failed()))

            "grid-hold-release" -> outcome(gridHoldRelease(int("index"), int("columns"), int("count"), failed()))

            "grid-strip-detail" ->
                JsonPrimitive(gridStripDetail(input.getValue("sortBy").jsonPrimitive.content, bool("descending")))

            "fixed-head-collapses" ->
                JsonPrimitive(
                    fixedHeadCollapses(
                        input.getValue("fontScale").jsonPrimitive.float,
                        bool("contentFocused"),
                        bool("assistiveServiceInUse")
                    )
                )

            "fixed-head-in-list" ->
                JsonPrimitive(fixedHeadInList(input.getValue("fontScale").jsonPrimitive.float, bool("assistiveServiceInUse")))

            else -> error("Unknown operation $operation")
        }
    }

    @Test fun vectors() {
        val text = requireNotNull(javaClass.classLoader.getResourceAsStream("grid-vectors/grid-navigation.json"))
            .bufferedReader().use { it.readText() }
        val cases = Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty())
        for (case in cases.map { it.jsonObject }) {
            val id = case.getValue("id").jsonPrimitive.content
            val actual = value(case.getValue("operation").jsonPrimitive.content, case.getValue("input").jsonObject)
            assertEquals(case.getValue("expected"), actual, id)
        }
    }

    /** A hold always ends: whichever way it's released, the press never holds again. */
    @Test fun aReleaseNeverHolds() {
        for (count in 1..24) {
            for (index in 0 until count) {
                assertTrue(gridHoldRelease(index, 5, count) != GridVerticalMove.Hold, "$index of $count")
                assertTrue(gridHoldRelease(index, 5, count, pageFailed = true) != GridVerticalMove.Hold, "$index of $count, failed")
            }
        }
    }
}
