/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/** Android reference runner. Ports owe every case; expected values are checked in and never regenerated here. */
class PlaybackSettingsVectorsTest {
    @Test
    fun portableSettingsCasesMatchProductionPolicies() {
        val text = requireNotNull(javaClass.classLoader.getResourceAsStream("parity-vectors/playback-settings.json"))
            .bufferedReader().use { it.readText() }
        val suite = PlaybackJson.parseToJsonElement(text).jsonObject
        assertEquals("playback-settings", suite.getValue("suite").jsonPrimitive.content)
        for (element in suite.getValue("cases").jsonArray) {
            val case = element.jsonObject
            val input = case.getValue("input").jsonObject
            val operation = case.getValue("operation").jsonPrimitive.content
            val actual = execute(operation, input)
            assertEquals(case.getValue("expected"), actual, case.getValue("id").jsonPrimitive.content)
        }
    }

    private fun execute(operation: String, input: JsonObject): JsonElement {
        val tuning = input["tuning"]?.let { PlaybackJson.decodeFromJsonElement<PlaybackTuning>(it) } ?: PlaybackTuning()
        return when (operation) {
            "information-reason" -> JsonPrimitive(
                PlaybackInformation.reason(
                    input.getValue("reason").jsonPrimitive.content,
                    tuning = tuning,
                    activeBitrate = input["activeBitrate"]?.jsonPrimitive?.intOrNull
                )
            )
            "tuning-json" -> PlaybackJson.encodeToJsonElement(
                PlaybackPreferenceJson.tuning(input.getValue("raw").jsonPrimitive.content)
            )
            "style-json" -> PlaybackJson.encodeToJsonElement(
                PlaybackPreferenceJson.style(input.getValue("raw").jsonPrimitive.content)
            )
            "quality-cap" -> JsonPrimitive(
                tuning.bitrate(
                    input["homeMbps"]?.jsonPrimitive?.intOrNull,
                    PlaybackNetwork.valueOf(input.getValue("network").jsonPrimitive.content),
                    input["estimatedBandwidth"]?.jsonPrimitive?.longOrNull,
                    input["overrideMbps"]?.jsonPrimitive?.intOrNull
                )
            )
            "resume-position" -> JsonPrimitive(tuning.resumedPositionMs(input.getValue("savedPositionMs").jsonPrimitive.long))
            "early-next" -> JsonPrimitive(tuning.allowEarlyNext(input.getValue("hasPostCreditsScene").jsonPrimitive.boolean))
            "unique-local-address" -> JsonPrimitive(
                PlaybackNetworkPolicy.isUniqueLocalAddress(
                    input.getValue("addressBytes").jsonArray.map {
                        it.jsonPrimitive.int.toByte()
                    }.toByteArray()
                )
            )
            "network-classification" -> JsonPrimitive(
                PlaybackNetworkPolicy.classify(
                    input.getValue("metered").jsonPrimitive.boolean,
                    input.getValue("localEndpoint").jsonPrimitive.boolean
                ).name
            )
            "quality-reduction" -> JsonPrimitive(
                PlaybackNetworkPolicy.shouldReduce(
                    input.getValue("currentCap").jsonPrimitive.int,
                    input.getValue("proposedCap").jsonPrimitive.int,
                    input.getValue("sampleCount").jsonPrimitive.int,
                    input.getValue("sampleBytes").jsonPrimitive.long,
                    input.getValue("elapsedSinceAdjustmentMs").jsonPrimitive.long
                )
            )
            "subtitle-style" -> PlaybackJson.encodeToJsonElement(
                PlaybackJson.decodeFromJsonElement<SubtitleStyle>(input.getValue("style")).normalized()
            )
            "subtitle-policy" -> {
                val profiles = DeviceProfileBuilder.build(
                    CapabilityFixtures.projector.copy(settings = PlaybackSettings(tuning = tuning))
                ).subtitleProfiles
                JsonObject(
                    mapOf(
                        "textMethods" to
                            PlaybackJson.encodeToJsonElement(
                                profiles.filter {
                                    it.format == "srt"
                                }.map { it.method.name }.distinct().sorted()
                            ),
                        "imageMethods" to
                            PlaybackJson.encodeToJsonElement(
                                profiles.filter {
                                    it.format == "pgssub"
                                }.map { it.method.name }.distinct().sorted()
                            )
                    )
                )
            }
            "audio-output" -> {
                val detected = CapabilityFixtures.dolbyVisionStreamer
                val effective = PlaybackCapabilityPolicy.effective(detected.copy(settings = detected.settings.copy(tuning = tuning)))
                JsonObject(
                    mapOf(
                        "passthrough" to PlaybackJson.encodeToJsonElement(effective.audioOutput.passthrough.map { it.name }.sorted()),
                        "pcmChannels" to JsonPrimitive(effective.audioOutput.pcmChannels),
                        "maximumInputChannels" to JsonPrimitive(effective.audioDecoders.maxOf { it.maxChannels })
                    )
                )
            }
            "hdr-profile" -> {
                val detected = if (input["fixture"]?.jsonPrimitive?.content == "projector") {
                    CapabilityFixtures.projector
                } else {
                    CapabilityFixtures.dolbyVisionStreamer
                }
                val profile = DeviceProfileBuilder.build(detected.copy(settings = detected.settings.copy(tuning = tuning)))
                val ranges = profile.codecProfiles.filter { it.codec == "hevc" }.flatMap { it.conditions }
                    .first { it.property == ProfileConditionValue.VideoRangeType }.value!!.split('|')
                JsonObject(
                    mapOf(
                        "hdr10" to JsonPrimitive("HDR10" in ranges),
                        "dolbyVision" to JsonPrimitive("DOVI" in ranges),
                        "hdr10Plus" to JsonPrimitive("HDR10Plus" in ranges),
                        "detectedDolbyVision" to JsonPrimitive(HdrFormat.DOLBY_VISION in detected.display.hdrFormats)
                    )
                )
            }
            else -> error("Unknown playback settings operation: $operation")
        }
    }
}
