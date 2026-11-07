/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.control.QueueEvent
import app.pictureshow.core.control.QueueState
import app.pictureshow.core.control.reduceQueue
import app.pictureshow.core.music.ArtistRole
import app.pictureshow.core.music.MusicPageState
import app.pictureshow.core.music.MusicPlayRequest
import app.pictureshow.core.music.MusicReportEvent
import app.pictureshow.core.music.MusicReportState
import app.pictureshow.core.music.MusicSection
import app.pictureshow.core.music.MusicSessionEvent
import app.pictureshow.core.music.MusicSessionState
import app.pictureshow.core.music.PlaylistEditEvent
import app.pictureshow.core.music.PlaylistEditState
import app.pictureshow.core.music.albumDiscs
import app.pictureshow.core.music.mergeMusicPage
import app.pictureshow.core.music.musicArtistAlbumsRequest
import app.pictureshow.core.music.musicBitrateCeiling
import app.pictureshow.core.music.musicDeviceProfile
import app.pictureshow.core.music.musicEntries
import app.pictureshow.core.music.musicListState
import app.pictureshow.core.music.musicOutputFacts
import app.pictureshow.core.music.musicOutputRepeats
import app.pictureshow.core.music.musicPermutation
import app.pictureshow.core.music.musicPlayDecision
import app.pictureshow.core.music.musicSectionRequest
import app.pictureshow.core.music.musicWindow
import app.pictureshow.core.music.orderTracks
import app.pictureshow.core.music.planWindow
import app.pictureshow.core.music.playableTracks
import app.pictureshow.core.music.reduceMusicReport
import app.pictureshow.core.music.reduceMusicSession
import app.pictureshow.core.music.reducePlaylistEdit
import app.pictureshow.core.music.trackArtistLine
import app.pictureshow.core.playback.AudioCodec
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlaybackJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Frozen traces for MUS-01 to MUS-09: exact values at explicit projections, no record mode. */
class MusicVectorsTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test fun catalog() = run("music-catalog")

    @Test fun queue() = run("music-queue")

    @Test fun session() = run("music-session")

    @Test fun reports() = run("music-reports")

    @Test fun quality() = run("music-quality")

    @Test fun playlists() = run("music-playlists")

    private fun run(suite: String) {
        val document = json.parseToJsonElement(
            requireNotNull(javaClass.classLoader.getResourceAsStream("control-vectors/$suite.json")).bufferedReader().use { it.readText() }
        ).jsonObject
        assertEquals(suite, document.getValue("suite").jsonPrimitive.content)
        val cases = document.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty())
        cases.forEach { value ->
            val case = value.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val operation = case.getValue("operation").jsonPrimitive.content
            val steps = case["steps"]
            if (steps == null) {
                check(case.getValue("expected"), once(operation, case.getValue("input").jsonObject), id)
            } else {
                var state = case.getValue("state")
                steps.jsonArray.forEachIndexed { index, entry ->
                    val output = step(operation, state, entry.jsonObject.getValue("event"))
                    check(entry.jsonObject.getValue("expected"), output, "$id/$index")
                    state = if (operation == "queue") output.jsonObject.getValue("queue") else output
                }
            }
        }
    }

    private fun check(expected: JsonElement, actual: JsonElement, path: String) {
        if (expected is JsonObject) {
            assertTrue(actual is JsonObject, path)
            expected.forEach { (key, value) -> check(value, actual[key] ?: JsonNull, "$path.$key") }
        } else {
            assertEquals(expected, actual, path)
        }
    }

    private fun step(operation: String, state: JsonElement, event: JsonElement): JsonElement = when (operation) {
        "queue" -> json.encodeToJsonElement(reduceQueue(json.decodeFromJsonElement<QueueState>(state), json.decodeFromJsonElement<QueueEvent>(event)))
        "session" -> json.encodeToJsonElement(
            reduceMusicSession(json.decodeFromJsonElement<MusicSessionState>(state), json.decodeFromJsonElement<MusicSessionEvent>(event))
        )
        "report" -> json.encodeToJsonElement(
            reduceMusicReport(json.decodeFromJsonElement<MusicReportState>(state), json.decodeFromJsonElement<MusicReportEvent>(event))
        )
        "playlist" -> json.encodeToJsonElement(
            reducePlaylistEdit(json.decodeFromJsonElement<PlaylistEditState>(state), json.decodeFromJsonElement<PlaylistEditEvent>(event))
        )
        else -> error("Unknown music operation: $operation")
    }

    private fun tracks(input: JsonObject): List<MediaItem> = input.getValue("tracks").jsonArray.map { json.decodeFromJsonElement<MediaItem>(it) }

    private fun text(input: JsonObject, name: String): String? = input[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun once(operation: String, input: JsonObject): JsonElement = when (operation) {
        "section" -> json.encodeToJsonElement(
            musicSectionRequest(
                MusicSection.valueOf(text(input, "section")!!),
                text(input, "library")!!,
                start = input["start"]?.jsonPrimitive?.int ?: 0
            )
        )
        "artist-albums" -> json.encodeToJsonElement(
            musicArtistAlbumsRequest(text(input, "artist")!!, ArtistRole.valueOf(text(input, "role")!!), text(input, "library"))
        )
        "order" -> json.encodeToJsonElement(orderTracks(tracks(input)).map { it.id })
        "discs" -> json.encodeToJsonElement(albumDiscs(tracks(input)))
        "artist-line" -> JsonPrimitive(
            trackArtistLine(json.decodeFromJsonElement<MediaItem>(input.getValue("track")), text(input, "albumArtist"))
        )
        "page" -> json.encodeToJsonElement(
            input.getValue("pages").jsonArray.fold(MusicPageState()) { state, page ->
                mergeMusicPage(
                    state,
                    page.jsonObject.getValue("ids").jsonArray.map { it.jsonPrimitive.content },
                    page.jsonObject.getValue("total").jsonPrimitive.int
                )
            }
        )
        "list-state" -> JsonPrimitive(
            musicListState(
                input.getValue("loading").jsonPrimitive.content == "true",
                input.getValue("items").jsonPrimitive.int,
                text(input, "kind")?.let { ClientError(ErrorKind.valueOf(it), "", input["status"]?.jsonPrimitive?.int) }
            ).name
        )
        "playable" -> json.encodeToJsonElement(playableTracks(tracks(input)).map { it.id })
        "image" -> JsonPrimitive(imageUrl(Server("s", "s", "http://s", "12"), json.decodeFromJsonElement<MediaItem>(input.getValue("item")), 100))
        "entries" -> json.encodeToJsonElement(
            musicEntries(input.getValue("media").jsonArray.map { it.jsonPrimitive.content }, text(input, "first")!!.toLong())
        ).jsonArray.let { rows ->
            kotlinx.serialization.json.JsonArray(rows.map { row -> JsonObject(row.jsonObject.filterKeys { it != "downloaded" }) })
        }
        "permutation" -> json.encodeToJsonElement(
            musicPermutation(input.getValue("ids").jsonArray.map { it.jsonPrimitive.content }, text(input, "seed")!!.toLong())
        )
        "window" -> json.decodeFromJsonElement<QueueState>(input).let { q ->
            buildJsonObject {
                put("window", json.encodeToJsonElement(musicWindow(q)))
                put("repeats", musicOutputRepeats(q))
            }
        }
        "plan" -> json.encodeToJsonElement(
            planWindow(
                input.getValue("held").jsonArray.map { it.jsonPrimitive.content },
                text(input, "playing"),
                input.getValue("target").jsonArray.map { it.jsonPrimitive.content }
            )
        )
        "decision" -> JsonPrimitive(musicPlayDecision(json.decodeFromJsonElement<MusicPlayRequest>(input)))
        "ceiling" -> JsonPrimitive(musicBitrateCeiling(text(input, "quality")!!, input.getValue("metered").jsonPrimitive.content == "true"))
        "profile" -> musicDeviceProfile(
            input.getValue("decoders").jsonArray.map { AudioCodec.valueOf(it.jsonPrimitive.content) }.toSet(),
            input.getValue("ceiling").jsonPrimitive.int
        ).let { profile ->
            buildJsonObject {
                put("MaxStreamingBitrate", profile.maxStreamingBitrate)
                put("MaxStaticMusicBitrate", profile.maxStaticMusicBitrate)
                put("MusicStreamingTranscodingBitrate", profile.musicStreamingTranscodingBitrate)
                put("direct", json.encodeToJsonElement(profile.directPlayProfiles.map { "${it.container}:${it.audioCodec}" }))
                put(
                    "conversion",
                    json.encodeToJsonElement(profile.transcodingProfiles.map { "${it.container}:${it.audioCodec}:${it.protocol}:${it.videoCodec}" })
                )
            }
        }
        "output" -> json.encodeToJsonElement(
            musicOutputFacts(
                PlaybackJson.decodeFromJsonElement(MediaSourceInfo.serializer(), input.getValue("source")),
                PlayMethod.valueOf(text(input, "method")!!),
                text(input, "route")!!,
                input.getValue("ceiling").jsonPrimitive.int,
                input["reasons"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
            )
        )
        else -> error("Unknown music operation: $operation")
    }
}
