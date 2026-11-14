/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Adding a contract without a JVM executor fails the desktop vector task instead of silently dropping it. */
class CorpusVectorsTest {
    @Test fun everyJsonContractHasAnExecutor() {
        val groups = mapOf(
            "parity-vectors" to
                setOf(
                    "application",
                    "catalog",
                    "compatibility",
                    "native-regressions",
                    "negotiation",
                    "network",
                    "playback-information",
                    "playback",
                    "profiles",
                    "frame-rate-match",
                    "playback-settings-layout",
                    "playback-settings",
                    "playback-start",
                    "player-quality",
                    "settings-presentation",
                    "track-labels",
                    "recovery"
                ),
            "control-vectors" to
                setOf(
                    "cast-capability",
                    "cast-handoff",
                    "cast-session",
                    "music-catalog",
                    "music-playlists",
                    "music-quality",
                    "music-queue",
                    "music-reports",
                    "music-session",
                    "playback-mode",
                    "queue-edit",
                    "queue-order",
                    "session-command",
                    "session-owner"
                ),
            "offline-vectors" to
                setOf(
                    "offline-budget",
                    "offline-fmp4",
                    "offline-job",
                    "offline-manifest",
                    "offline-next",
                    "offline-outbox",
                    "offline-plan",
                    "offline-reconnect",
                    "offline-resume"
                ),
            "remote-vectors" to setOf("remote-queue", "remote-receive", "remote-reconnect", "remote-targets", "remote-wire"),
            "grid-vectors" to setOf("grid-navigation", "home-browse")
        )
        fun directory(name: String) = File(requireNotNull(javaClass.classLoader.getResource(name)) { "Missing packaged $name" }.toURI())
        val inventory = directory("vector-inventory").listFiles()!!.filter {
            it.extension == "json"
        }.associateBy { it.nameWithoutExtension }
        assertEquals(groups.values.flatten().toSet(), inventory.keys, "A new contract needs an executable JVM suite")
        groups.forEach { (resource, names) ->
            assertEquals(
                names,
                directory(resource).listFiles()!!.filter {
                    it.extension == "json"
                }.map { it.nameWithoutExtension }.toSet(),
                resource
            )
            names.forEach { name ->
                val source = inventory.getValue(name).readText()
                assertEquals(source, File(directory(resource), "$name.json").readText(), "Stale resource $name")
                val envelope = Json.parseToJsonElement(source).jsonObject
                assertEquals("1", envelope.getValue("schemaVersion").jsonPrimitive.content, "Schema version $name")
                assertTrue(envelope.getValue("suite").jsonPrimitive.content.isNotBlank(), "Missing suite $name")
                val cases = envelope.getValue("cases").jsonArray
                assertTrue(cases.isNotEmpty(), "Empty contract $name")
                val ids = cases.map { it.jsonObject.getValue("id").jsonPrimitive.content }
                assertEquals(ids.size, ids.toSet().size, "Duplicate ids in $name")
            }
        }
    }
}
