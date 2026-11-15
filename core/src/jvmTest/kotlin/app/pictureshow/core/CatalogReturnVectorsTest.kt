/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class CatalogReturnVectorsTest {
    @Test fun returnRefreshAndLocalContinuity() {
        val source = requireNotNull(javaClass.classLoader.getResourceAsStream("parity-vectors/catalog-return.json"))
            .bufferedReader().use { it.readText() }
        for (case in Json.parseToJsonElement(source).jsonObject.getValue("cases").jsonArray) {
            val c = case.jsonObject
            val i = c.getValue("input").jsonObject
            val expected = c.getValue("expected")
            when (c.getValue("operation").jsonPrimitive.content) {
                "refresh" -> assertEquals(
                    expected.jsonPrimitive.content,
                    catalogReturnRefresh(i.getValue("page").jsonPrimitive.content).name
                )
                "progress" -> {
                    val previous = Json.decodeFromJsonElement<ItemUserData>(i.getValue("previous"))
                    val result =
                        playbackReturnData(
                            previous,
                            i.getValue("positionTicks").jsonPrimitive.long,
                            i.getValue("ended").jsonPrimitive.boolean
                        )
                    assertEquals(Json.decodeFromJsonElement<ItemUserData>(expected), result)
                }
                else -> error("Unknown catalog return operation")
            }
        }
    }
}
