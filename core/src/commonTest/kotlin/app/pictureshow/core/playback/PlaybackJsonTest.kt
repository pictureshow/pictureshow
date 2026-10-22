package app.pictureshow.core.playback

import app.pictureshow.core.Server
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PlaybackJsonTest {
    private val classes: Map<String, SerialDescriptor> = mapOf(
        "DeviceProfile" to DeviceProfile.serializer().descriptor,
        "DirectPlayProfile" to DirectPlayProfile.serializer().descriptor,
        "TranscodingProfile" to TranscodingProfile.serializer().descriptor,
        "ContainerProfile" to ContainerProfile.serializer().descriptor,
        "CodecProfile" to CodecProfile.serializer().descriptor,
        "ProfileCondition" to ProfileCondition.serializer().descriptor,
        "SubtitleProfile" to SubtitleProfile.serializer().descriptor,
        "PlaybackInfoDto" to PlaybackInfoRequest.serializer().descriptor,
        "PlaybackInfoResponse" to PlaybackInfoResponse.serializer().descriptor,
        "MediaSourceInfo" to MediaSourceInfo.serializer().descriptor,
        "MediaStream" to MediaStream.serializer().descriptor,
        "PlaybackProgressInfo" to PlaybackProgressInfo.serializer().descriptor,
        "PlaybackStartInfo" to PlaybackProgressInfo.serializer().descriptor,
        "PlaybackStopInfo" to PlaybackStopInfo.serializer().descriptor
    )

    private val enums: Map<String, KSerializer<*>> = mapOf(
        "DlnaProfileType" to DlnaProfileType.serializer(),
        "CodecType" to CodecType.serializer(),
        "EncodingContext" to EncodingContext.serializer(),
        "TranscodeSeekInfo" to TranscodeSeekInfo.serializer(),
        "MediaStreamProtocol" to MediaStreamProtocol.serializer(),
        "SubtitleDeliveryMethod" to SubtitleDeliveryMethod.serializer(),
        "ProfileConditionType" to ProfileConditionType.serializer(),
        "ProfileConditionValue" to ProfileConditionValue.serializer(),
        "VideoRangeType" to VideoRangeType.serializer(),
        "PlaybackErrorCode" to PlaybackErrorCode.serializer(),
        "MediaStreamType" to MediaStreamType.serializer(),
        "PlayMethod" to PlayMethod.serializer(),
        "RepeatMode" to RepeatMode.serializer(),
        "PlaybackOrder" to PlaybackOrder.serializer()
    )

    @Test
    fun everyFieldNameExistsInTheSpecSchema() {
        assertEquals(SpecSchemas.properties.keys, classes.keys)
        for ((schema, descriptor) in classes) {
            val unknown = descriptor.elementNames.toSet() - SpecSchemas.properties.getValue(schema)
            assertEquals(emptySet(), unknown, "$schema has field names the server does not know")
        }
    }

    @Test
    fun everyEnumIsSpelledLikeTheSpec() {
        assertEquals(SpecSchemas.enums.keys, enums.keys)
        for ((schema, serializer) in enums) {
            assertEquals(SpecSchemas.enums.getValue(schema), serializer.descriptor.elementNames.toSet(), schema)
        }
    }

    @Test
    fun builtProfilesUseOnlySpecNames() {
        for ((name, capabilities) in CapabilityFixtures.all) {
            val json = PlaybackJson.encodeToJsonElement(DeviceProfile.serializer(), DeviceProfileBuilder.build(capabilities))
            assertKeys(json, "DeviceProfile", name)
        }
    }

    @Test
    fun encodedProfileHasExactServerSpelling() {
        val json = PlaybackJson.encodeToJsonElement(DeviceProfile.serializer(), DeviceProfileBuilder.build(CapabilityFixtures.basicStick))
            .jsonObject
        assertEquals("Basic 1080p stick", json.getValue("Name").jsonPrimitive.content)
        val transcoding = json.getValue("TranscodingProfiles").jsonArray.first().jsonObject
        assertEquals("hls", transcoding.getValue("Protocol").jsonPrimitive.content)
        assertEquals("Streaming", transcoding.getValue("Context").jsonPrimitive.content)
        assertEquals("Video", transcoding.getValue("Type").jsonPrimitive.content)
        assertEquals("2", transcoding.getValue("MaxAudioChannels").jsonPrimitive.content)
        assertTrue(transcoding.getValue("MaxAudioChannels").jsonPrimitive.isString)
        val codec = json.getValue("CodecProfiles").jsonArray.first().jsonObject
        assertEquals("Video", codec.getValue("Type").jsonPrimitive.content)
        val condition = codec.getValue("Conditions").jsonArray.first().jsonObject
        assertEquals(setOf("Condition", "Property", "Value", "IsRequired"), condition.keys)
        assertEquals("EqualsAny", condition.getValue("Condition").jsonPrimitive.content)
        assertEquals("VideoProfile", condition.getValue("Property").jsonPrimitive.content)
        val subtitle = json.getValue("SubtitleProfiles").jsonArray.first().jsonObject
        assertEquals("Embed", subtitle.getValue("Method").jsonPrimitive.content)
        assertEquals(JsonPrimitive(200_000_000), json.getValue("MaxStreamingBitrate"))
        assertEquals(JsonPrimitive(200_000_000), json.getValue("MaxStaticBitrate"))
    }

    @Test
    fun specShapedPlaybackInfoResponseRoundTrips() {
        val decoded = PlaybackJson.decodeFromString(PlaybackInfoResponse.serializer(), SPEC_RESPONSE)
        val source = decoded.mediaSources.single()
        assertEquals("a1b2c3", source.id)
        assertEquals("mkv", source.container)
        assertEquals(false, source.supportsDirectPlay)
        assertEquals(MediaStreamProtocol.Hls, source.transcodingSubProtocol)
        assertEquals(mapOf("X-Custom" to "1"), source.requiredHttpHeaders)
        val (video, audio, subtitle) = source.mediaStreams
        assertEquals(VideoRangeType.DOVIInvalid, video.videoRangeType)
        assertEquals(8, video.dvProfile)
        assertEquals(6, video.dvBlSignalCompatibilityId)
        assertEquals(153.0, video.level)
        assertEquals(MediaStreamType.Audio, audio.type)
        assertEquals(8, audio.channels)
        assertEquals(SubtitleDeliveryMethod.External, subtitle.deliveryMethod)
        assertEquals("session-9", decoded.playSessionId)

        val reencoded = PlaybackJson.encodeToJsonElement(PlaybackInfoResponse.serializer(), decoded).jsonObject
        assertKeys(reencoded, "PlaybackInfoResponse", "response")
        val original = PlaybackJson.parseToJsonElement(SPEC_RESPONSE).jsonObject
        val reSource = reencoded.getValue("MediaSources").jsonArray.single().jsonObject
        val origSource = original.getValue("MediaSources").jsonArray.single().jsonObject
        for (key in reSource.keys - "MediaStreams") assertSameValue(origSource[key], reSource[key], "MediaSources[0].$key")
        val reStreams = reSource.getValue("MediaStreams").jsonArray
        val origStreams = origSource.getValue("MediaStreams").jsonArray
        for (i in origStreams.indices) {
            for (key in reStreams[i].jsonObject.keys) {
                assertSameValue(origStreams[i].jsonObject[key], reStreams[i].jsonObject[key], "MediaStreams[$i].$key")
            }
        }
        assertEquals(decoded, PlaybackJson.decodeFromJsonElement(PlaybackInfoResponse.serializer(), reencoded))
    }

    @Test
    fun externalSubtitleUrlFlagRoundTripsAndDoesNotAuthorizeAnotherOrigin() {
        val json = """{"Type":"Subtitle","Index":4,"IsExternalUrl":true,"IsExternal":false,
            "DeliveryUrl":"//captions.example.test/text.srt"}"""
        val stream = PlaybackJson.decodeFromString(MediaStream.serializer(), json)
        assertTrue(stream.isExternalUrl)
        assertFalse(stream.isExternal)
        val encoded = PlaybackJson.encodeToJsonElement(MediaStream.serializer(), stream).jsonObject
        assertEquals(JsonPrimitive(true), encoded["IsExternalUrl"])
        assertEquals(stream, PlaybackJson.decodeFromJsonElement(MediaStream.serializer(), encoded))
        assertFalse(PlaybackJson.decodeFromString(MediaStream.serializer(), """{"Type":"Subtitle"}""").isExternalUrl)
        val server = Server("s", "Demo server", "https://media.example.test/jellyfin", "12.0.0")
        assertFalse(PlayDecisions.isSameOrigin(server, PlayDecisions.resolve(server, stream.deliveryUrl!!)))
    }

    @Test
    fun unknownEnumValuesAndFieldsDoNotFailDecoding() {
        val future =
            """
            {"MediaSources":[{"Id":"x","SupportsDirectPlay":true,"NewField":1,
            "MediaStreams":[{"Type":"Video","Index":0,"VideoRangeType":"DOVIWithSomethingNew"}]}],"ErrorCode":null}
            """.trimIndent()
        val decoded = PlaybackJson.decodeFromString(PlaybackInfoResponse.serializer(), future)
        assertEquals(null, decoded.mediaSources.single().mediaStreams.single().videoRangeType)
    }

    @Test
    fun deviceProfileRoundTrips() {
        for (capabilities in CapabilityFixtures.all.values) {
            val profile = DeviceProfileBuilder.build(capabilities)
            val text = PlaybackJson.encodeToString(DeviceProfile.serializer(), profile)
            assertEquals(profile, PlaybackJson.decodeFromString(DeviceProfile.serializer(), text))
        }
    }

    /** JSON numbers compare by value: the spec's `Level` is a number, so 153 and 153.0 are the same. */
    private fun assertSameValue(expected: JsonElement?, actual: JsonElement?, message: String) {
        val expectedNumber = (expected as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        val actualNumber = (actual as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        if (expectedNumber != null && actualNumber != null) {
            assertEquals(expectedNumber, actualNumber, message)
        } else {
            assertEquals(expected, actual, message)
        }
    }

    private fun assertKeys(element: JsonElement, schema: String, path: String) {
        val obj = element as? JsonObject ?: return
        val allowed = SpecSchemas.properties.getValue(schema)
        val unknown = obj.keys - allowed
        assertEquals(emptySet(), unknown, "$path ($schema)")
        for ((key, value) in obj) {
            val child = CHILD_SCHEMAS["$schema.$key"] ?: continue
            when (value) {
                is JsonArray -> value.forEachIndexed { i, item -> assertKeys(item, child, "$path.$key[$i]") }
                is JsonObject -> assertKeys(value, child, "$path.$key")
                is JsonPrimitive -> Unit
            }
        }
    }

    private companion object {
        val CHILD_SCHEMAS = mapOf(
            "DeviceProfile.DirectPlayProfiles" to "DirectPlayProfile",
            "DeviceProfile.TranscodingProfiles" to "TranscodingProfile",
            "DeviceProfile.ContainerProfiles" to "ContainerProfile",
            "DeviceProfile.CodecProfiles" to "CodecProfile",
            "DeviceProfile.SubtitleProfiles" to "SubtitleProfile",
            "TranscodingProfile.Conditions" to "ProfileCondition",
            "ContainerProfile.Conditions" to "ProfileCondition",
            "CodecProfile.Conditions" to "ProfileCondition",
            "CodecProfile.ApplyConditions" to "ProfileCondition",
            "PlaybackInfoResponse.MediaSources" to "MediaSourceInfo",
            "MediaSourceInfo.MediaStreams" to "MediaStream"
        )

        /** Shaped like the spec's PlaybackInfoResponse, with extra fields the client ignores. */
        val SPEC_RESPONSE = """
            {
              "MediaSources": [
                {
                  "Protocol": "File",
                  "Id": "a1b2c3",
                  "Path": "/media/movie.mkv",
                  "Type": "Default",
                  "Container": "mkv",
                  "Size": 41234567890,
                  "Name": "movie",
                  "IsRemote": false,
                  "ETag": "etag-1",
                  "RunTimeTicks": 72000000000,
                  "ReadAtNativeFramerate": false,
                  "SupportsTranscoding": true,
                  "SupportsDirectStream": false,
                  "SupportsDirectPlay": false,
                  "IsInfiniteStream": false,
                  "RequiresOpening": false,
                  "RequiresClosing": false,
                  "VideoType": "VideoFile",
                  "MediaStreams": [
                    {
                      "Codec": "hevc",
                      "CodecTag": "dvh1",
                      "ColorTransfer": "smpte2084",
                      "DvProfile": 8,
                      "DvBlSignalCompatibilityId": 6,
                      "VideoRange": "HDR",
                      "VideoRangeType": "DOVIInvalid",
                      "Profile": "Main 10",
                      "Level": 153,
                      "BitDepth": 10,
                      "Height": 2160,
                      "Width": 3840,
                      "Type": "Video",
                      "Index": 0,
                      "IsDefault": true,
                      "IsForced": false,
                      "IsHearingImpaired": false,
                      "IsExternal": false,
                      "IsExternalUrl": false,
                      "IsTextSubtitleStream": false
                    },
                    {
                      "Codec": "eac3",
                      "Language": "eng",
                      "Channels": 8,
                      "Type": "Audio",
                      "Index": 1,
                      "IsDefault": true,
                      "IsForced": false,
                      "IsHearingImpaired": false,
                      "IsExternal": false,
                      "IsExternalUrl": false,
                      "IsTextSubtitleStream": false
                    },
                    {
                      "Codec": "subrip",
                      "Language": "eng",
                      "Type": "Subtitle",
                      "Index": 2,
                      "IsDefault": false,
                      "IsForced": false,
                      "IsHearingImpaired": false,
                      "IsExternal": false,
                      "IsExternalUrl": false,
                      "IsTextSubtitleStream": true,
                      "DeliveryMethod": "External",
                      "DeliveryUrl": "/Videos/item-1/a1b2c3/Subtitles/2/0/Stream.subrip?ApiKey=secret-token"
                    }
                  ],
                  "Bitrate": 45000000,
                  "RequiredHttpHeaders": {"X-Custom": "1"},
                  "TranscodingUrl": "/videos/item-1/master.m3u8?DeviceId=d&MediaSourceId=a1b2c3&ApiKey=secret-token",
                  "TranscodingSubProtocol": "hls",
                  "TranscodingContainer": "mp4",
                  "DefaultAudioStreamIndex": 1
                }
              ],
              "PlaySessionId": "session-9"
            }
        """.trimIndent()
    }
}
