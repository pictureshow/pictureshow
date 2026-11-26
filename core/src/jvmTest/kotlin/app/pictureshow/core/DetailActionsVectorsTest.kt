/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Executes spec/vectors/detail-actions.json: CAT-05/06 TV detail, series and season action order and presentation. */
class DetailActionsVectorsTest {
    @Test fun vectors() {
        val source = requireNotNull(javaClass.classLoader.getResourceAsStream("parity-vectors/detail-actions.json"))
            .bufferedReader().use { it.readText() }
        val cases = Json.parseToJsonElement(source).jsonObject.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty())
        for (case in cases.map { it.jsonObject }) {
            val id = case.getValue("id").jsonPrimitive.content
            val input = case.getValue("input").jsonObject
            val actual: JsonElement = when (val operation = case.getValue("operation").jsonPrimitive.content) {
                "detail-action-slots" -> JsonArray(
                    detailActionSlots(
                        itemType = input.getValue("itemType").jsonPrimitive.content,
                        isMissing = input.getValue("isMissing").jsonPrimitive.boolean,
                        hasProgress = input.getValue("hasProgress").jsonPrimitive.boolean,
                        playsAgain = input.getValue("playsAgain").jsonPrimitive.boolean,
                        playbackReturn = input.getValue("playbackReturn").jsonPrimitive.boolean,
                        canDownload = input.getValue("canDownload").jsonPrimitive.boolean,
                        canFindSubtitles = input.getValue("canFindSubtitles").jsonPrimitive.boolean,
                        hasTrailer = input.getValue("hasTrailer").jsonPrimitive.boolean,
                        pending = input.getValue("pending").jsonPrimitive.boolean
                    ).map { slot ->
                        buildJsonObject {
                            put("target", slot.target)
                            put("presentation", slot.presentation.wireName)
                        }
                    }
                )
                else -> error("$id: unknown operation $operation")
            }
            assertEquals(case.getValue("expected"), actual, id)
        }
    }
}
