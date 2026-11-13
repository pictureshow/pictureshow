/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.sync.syncChipName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SyncChipNameVectorsTest {
    @Test fun preserveOriginalUsefulNamesUsingNativeMeasurementFacts() {
        val resource = requireNotNull(javaClass.classLoader.getResource("sync-vectors/sync-chip-name.json"))
        val cases = Json.parseToJsonElement(resource.readText()).jsonObject.getValue("cases").jsonArray
        assertEquals(10, cases.size)
        cases.forEach { row ->
            val c = row.jsonObject
            val fits = c.getValue("fittingCandidates").jsonArray.map { it.jsonPrimitive.content }.toSet()
            val expected = c.getValue("expected").takeUnless { it is JsonNull }?.jsonPrimitive?.content
            assertEquals(expected, syncChipName(c.getValue("name").jsonPrimitive.content,
                c.getValue("graphemeEnds").jsonArray.map { it.jsonPrimitive.int }, fits::contains),
                c.getValue("id").jsonPrimitive.content)
        }
    }
}
