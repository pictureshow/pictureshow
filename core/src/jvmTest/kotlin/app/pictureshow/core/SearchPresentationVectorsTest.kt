/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

class SearchPresentationVectorsTest {
    @Test fun vectors() {
        val text = requireNotNull(javaClass.classLoader.getResourceAsStream("grid-vectors/search-presentation.json"))
            .bufferedReader().use { it.readText() }
        val cases = Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty())
        for (case in cases.map { it.jsonObject }) {
            val input = case.getValue("input").jsonObject
            fun items(key: String) = Json.decodeFromJsonElement<List<MediaItem>>(input.getValue(key))
            val actual = when (case.getValue("operation").jsonPrimitive.content) {
                "search-duplicate-ids" -> JsonArray(searchDuplicateIds(items("items")).sorted().map { JsonPrimitive(it) })
                "search-library-name" -> searchLibraryName(items("ancestors"), items("libraries"))?.let { JsonPrimitive(it) } ?: JsonNull
                else -> error("Unknown search operation")
            }
            assertEquals(case.getValue("expected"), actual, case.getValue("id").jsonPrimitive.content)
        }
    }
}
