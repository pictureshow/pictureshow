/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.sync.syncSourceEligible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** SYN-11 eligibility expectations are frozen portable data, independent of the production predicate. */
class SyncSourceVectorsTest {
    @Test fun sourceEligibility() {
        val source = requireNotNull(javaClass.classLoader.getResourceAsStream("sync-vectors/sync-source.json"))
        val suite = source.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        assertEquals(setOf("schemaVersion", "suite", "cases"), suite.keys)
        assertFalse(suite.getValue("schemaVersion").jsonPrimitive.isString)
        assertEquals(1, suite.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("sync-source", suite.getValue("suite").jsonPrimitive.content)
        val rows = suite.getValue("cases").jsonArray
        assertEquals(21, rows.size, "SYN-11 boundary and complete positive-duration flag matrix must stay present")
        val ids = mutableSetOf<String>()
        rows.forEach { element ->
            val row = element.jsonObject
            assertEquals(setOf("id", "op", "input", "expected"), row.keys)
            val id = row.getValue("id").jsonPrimitive
            assertTrue(id.isString && id.content.isNotBlank(), "Each source vector needs an ID")
            assertTrue(ids.add(id.content), "Duplicate source vector ID: ${id.content}")
            assertEquals("sourceEligible", row.getValue("op").jsonPrimitive.content, id.content)
            val input = row.getValue("input").jsonObject
            assertEquals(setOf("durationTicks", "seekable", "live", "infinite", "offline"), input.keys, id.content)
            val ticks = input.getValue("durationTicks").jsonPrimitive
            assertTrue(ticks.isString, "${id.content}: int64 ticks use decimal strings for every platform")
            assertTrue(ticks.content.matches(Regex("-?(0|[1-9][0-9]*)")), "${id.content}: noncanonical int64 ticks")
            val expected = row.getValue("expected").jsonPrimitive.strictBoolean(id.content)
            assertEquals(
                expected,
                syncSourceEligible(
                    durationTicks = ticks.long,
                    seekable = input.strictBoolean("seekable", id.content),
                    live = input.strictBoolean("live", id.content),
                    infinite = input.strictBoolean("infinite", id.content),
                    offline = input.strictBoolean("offline", id.content)
                ),
                id.content
            )
        }
    }

    private fun JsonObject.strictBoolean(key: String, id: String): Boolean = getValue(key).jsonPrimitive.strictBoolean("$id.$key")

    private fun JsonPrimitive.strictBoolean(label: String): Boolean {
        assertFalse(isString, "$label must be a JSON boolean")
        assertTrue(content == "true" || content == "false", "$label must be a JSON boolean")
        return boolean
    }
}
