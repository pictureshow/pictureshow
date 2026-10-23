/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import android.view.Display
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioCapabilities
import app.pictureshow.core.playback.AudioCodec
import app.pictureshow.core.playback.CodecLevel
import app.pictureshow.core.playback.DeviceProfileBuilder
import app.pictureshow.core.playback.HdrFormat
import app.pictureshow.core.playback.PassthroughEncoding
import app.pictureshow.core.playback.PlaybackSettings
import app.pictureshow.core.playback.VideoCodec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowMediaCodecList
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class DeviceCapabilityProbeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @AfterTest
    fun clearCodecs() = ShadowMediaCodecList.reset()

    private fun softwareAvc(name: String = "c2.android.avc.decoder", canonicalName: String? = null) = video(
        name,
        "video/avc",
        1920,
        1080,
        489600,
        CodecProfileLevel.AVCProfileHigh to CodecProfileLevel.AVCLevel42,
        software = true,
        canonicalName = canonicalName
    )

    @Test
    fun aliasesCollapseInReportToTheCanonicalDecoder() {
        // Alias first: enumeration order must not pick the compatibility name over the real codec.
        softwareAvc("OMX.google.h264.decoder", "c2.android.avc.decoder")
        softwareAvc()
        val capabilities = DeviceCapabilityProbe(context, ffmpegSupport = { false }).snapshot()
        assertEquals(1, capabilities.videoDecoders.size)
        val report = thisTvReport(capabilities, "test", "11", "PCM", emptyList())
        assertEquals(1, report.summary.lineSequence().count { it == "H264 · software" })
    }

    @Test
    fun aliasesDoNotCreateExtraDeviceProfileConstraints() {
        softwareAvc()
        val probe = DeviceCapabilityProbe(context, ffmpegSupport = { false })
        val canonical = probe.snapshot()
        ShadowMediaCodecList.reset()
        softwareAvc("OMX.google.h264.decoder", "c2.android.avc.decoder")
        softwareAvc()
        val aliased = probe.snapshot()
        assertEquals(DeviceProfileBuilder.build(canonical), DeviceProfileBuilder.build(aliased))
        assertEquals(
            thisTvReport(canonical, "test", "11", "PCM", emptyList()).profileJson,
            thisTvReport(aliased, "test", "11", "PCM", emptyList()).profileJson
        )
    }

    @Test
    @Config(sdk = [28, 30])
    fun distinctSoftwareCodecsRemainSeparateEvenWithMatchingLimits() {
        for (name in listOf("OMX.google.h264.decoder", "c2.android.avc.decoder")) {
            video(
                name,
                "video/avc",
                1920,
                1080,
                489600,
                CodecProfileLevel.AVCProfileHigh to CodecProfileLevel.AVCLevel42,
                software = true
            )
        }
        val capabilities = DeviceCapabilityProbe(context, ffmpegSupport = { false }).snapshot()
        assertEquals(2, capabilities.videoDecoders.size)
        assertEquals(
            2,
            thisTvReport(capabilities, "test", "test", "PCM", emptyList()).summary.lineSequence().count {
                it ==
                    "H264 · software"
            }
        )
    }

    @Test
    fun projectorCodecSnapshotMapsSizeThroughputProfilesHdrAndAudio() {
        ShadowDisplayManager.changeDisplay(Display.DEFAULT_DISPLAY, "w1920dp-h1080dp-land-mdpi")
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        shadowOf(display).setDisplayHdrCapabilities(
            Display.DEFAULT_DISPLAY,
            500f,
            300f,
            0.1f,
            Display.HdrCapabilities.HDR_TYPE_HDR10,
            Display.HdrCapabilities.HDR_TYPE_HLG
        )
        // Vendor snapshot: 16x16 blocks, AVC 972000 blocks/s and HEVC/VP9 1944000; 3840x2160 limits.
        video(
            "OMX.amlogic.avc.decoder.awesome",
            "video/avc",
            3840,
            2160,
            972000,
            CodecProfileLevel.AVCProfileHigh to CodecProfileLevel.AVCLevel51
        )
        video(
            "OMX.amlogic.hevc.decoder.awesome",
            "video/hevc",
            3840,
            2160,
            1944000,
            CodecProfileLevel.HEVCProfileMain to CodecProfileLevel.HEVCMainTierLevel51,
            CodecProfileLevel.HEVCProfileMain10 to CodecProfileLevel.HEVCMainTierLevel51
        )
        video(
            "OMX.amlogic.vp9.decoder.awesome",
            "video/x-vnd.on2.vp9",
            3840,
            2160,
            1944000,
            CodecProfileLevel.VP9Profile2 to CodecProfileLevel.VP9Level51
        )
        video(
            "OMX.amlogic.dolby-vision.decoder.awesome",
            "video/dolby-vision",
            3840,
            2160,
            1944000,
            CodecProfileLevel.DolbyVisionProfileDvheSt to CodecProfileLevel.DolbyVisionLevelUhd60
        )
        video(
            "OMX.google.h264.decoder",
            "video/avc",
            2048,
            2048,
            245760,
            CodecProfileLevel.AVCProfileHigh to CodecProfileLevel.AVCLevel51,
            software = true
        )
        audio("OMX.google.opus.decoder", "audio/opus", 8, software = true)
        audio("OMX.google.flac.decoder", "audio/flac", 8, software = true)
        audio("OMX.amlogic.audio.decoder.eac3", "audio/eac3", 8)
        val actual = DeviceCapabilityProbe(context, ffmpegSupport = { false }).snapshot()
        assertEquals(1920, actual.display.maxWidth)
        assertEquals(1080, actual.display.maxHeight)
        assertTrue(actual.display.maxRefreshRate in 59.0..61.0)
        assertEquals(setOf(HdrFormat.HDR10, HdrFormat.HLG), actual.display.hdrFormats)
        val hevc = actual.videoDecoders.single { it.codec == VideoCodec.HEVC }
        assertEquals(setOf("Main", "Main 10"), hevc.profiles)
        assertEquals(3840, hevc.maxWidth)
        assertEquals(2160, hevc.maxHeight)
        assertEquals(10, hevc.maxBitDepth)
        assertEquals(5, hevc.maxLevel?.major)
        assertEquals(1, hevc.maxLevel?.minor)
        assertTrue(hevc.maxFrameRateAtMaxSize >= 59)
        assertTrue(hevc.hdrFormats.containsAll(setOf(HdrFormat.HDR10, HdrFormat.HLG, HdrFormat.DOLBY_VISION)))
        assertEquals(setOf(8), hevc.dolbyVisionProfiles)
        val avc = actual.videoDecoders.single { it.codec == VideoCodec.H264 && it.hardware }
        assertTrue(avc.maxFrameRateAtMaxSize in 29.0..31.0)
        assertFalse(actual.videoDecoders.single { it.codec == VideoCodec.H264 && !it.hardware }.hardware)
        assertFalse(actual.videoDecoders.any { it.codec == VideoCodec.AV1 })
        assertEquals(8, actual.audioDecoders.single { it.codec == AudioCodec.OPUS }.maxChannels)
        assertFalse(actual.audioDecoders.single { it.codec == AudioCodec.FLAC }.hardware)
        assertEquals(8, actual.audioDecoders.single { it.codec == AudioCodec.EAC3 }.maxChannels)
        assertEquals(200_000_000, actual.settings.maxStreamingBitrate)
        assertFalse(actual.settings.passthroughEnabled)
    }

    @Test
    fun mixedProfileLevelsAndDepthAreRetainedAsTuples() {
        video(
            "OMX.vendor.hevc.decoder",
            "video/hevc",
            3840,
            2160,
            972000,
            CodecProfileLevel.HEVCProfileMain to CodecProfileLevel.HEVCMainTierLevel51,
            CodecProfileLevel.HEVCProfileMain10 to CodecProfileLevel.HEVCMainTierLevel41
        )
        val decoder = DeviceCapabilityProbe(context, ffmpegSupport = { false }).snapshot().videoDecoders.single()
        val tuples = decoder.profileCapabilities.associateBy { it.profile }
        assertEquals(CodecLevel(5, 1), tuples.getValue("Main").maxLevel)
        assertEquals(CodecLevel(4, 1), tuples.getValue("Main 10").maxLevel)
        assertEquals(8, tuples.getValue("Main").maxBitDepth)
        assertEquals(10, tuples.getValue("Main 10").maxBitDepth)
        assertTrue(tuples.getValue("Main").hdrFormats.isEmpty())
        assertTrue(HdrFormat.HDR10 in tuples.getValue("Main 10").hdrFormats)
    }

    @Test
    fun av1ProfilesWithOneServerNameRetainDifferentLevelsAndDepths() {
        video(
            "OMX.vendor.av1.decoder",
            "video/av01",
            3840,
            2160,
            1944000,
            CodecProfileLevel.AV1ProfileMain8 to CodecProfileLevel.AV1Level51,
            CodecProfileLevel.AV1ProfileMain10 to CodecProfileLevel.AV1Level41
        )
        val tuples = DeviceCapabilityProbe(context, ffmpegSupport = { false }).snapshot().videoDecoders.single().profileCapabilities
        assertEquals(2, tuples.size)
        assertEquals(setOf("Main"), tuples.map { it.profile }.toSet())
        assertEquals(CodecLevel(5, 1), tuples.single { it.maxBitDepth == 8 }.maxLevel)
        assertEquals(CodecLevel(4, 1), tuples.single { it.maxBitDepth == 10 }.maxLevel)
        assertTrue(tuples.single { it.maxBitDepth == 8 }.hdrFormats.isEmpty())
        assertTrue(HdrFormat.HDR10 in tuples.single { it.maxBitDepth == 10 }.hdrFormats)
    }

    @Test
    fun minimalStickDoesNotInventDecodersHdrOrSoftwareAv1Speed() {
        video(
            "OMX.vendor.avc.decoder",
            "video/avc",
            1920,
            1080,
            489600,
            CodecProfileLevel.AVCProfileHigh to CodecProfileLevel.AVCLevel42
        )
        video(
            "c2.android.av1.decoder",
            "video/av01",
            1920,
            1080,
            489600,
            CodecProfileLevel.AV1ProfileMain8 to CodecProfileLevel.AV1Level41,
            software = true
        )
        audio("OMX.vendor.aac.decoder", "audio/mp4a-latm", 2)
        val actual = DeviceCapabilityProbe(context, ffmpegSupport = { false }).snapshot()
        assertEquals(8, actual.videoDecoders.single { it.codec == VideoCodec.H264 }.maxBitDepth)
        assertEquals(setOf("High"), actual.videoDecoders.single { it.codec == VideoCodec.H264 }.profiles)
        assertFalse(actual.videoDecoders.any { it.codec == VideoCodec.HEVC })
        assertEquals(listOf(AudioCodec.AAC), actual.audioDecoders.map { it.codec })
        assertTrue(actual.display.hdrFormats.isEmpty())
        assertNull(actual.videoDecoders.single { it.codec == VideoCodec.AV1 }.sustainablePerformance)
        assertFalse(DeviceProfileBuilder.build(actual).directPlayProfiles.any { "av1" in it.videoCodec.orEmpty().split(',') })
    }

    @Test
    fun softwareAv1UsesMeasuredFrameRateRatherThanLegalInputRate() {
        video(
            "c2.android.av1.decoder",
            "video/av01",
            1920,
            1080,
            489600,
            CodecProfileLevel.AV1ProfileMain8 to CodecProfileLevel.AV1Level41,
            software = true,
            measurements = mapOf("measured-frame-rate-1280x720-range" to "26-32")
        )
        val decoder = DeviceCapabilityProbe(context, ffmpegSupport = { false }).snapshot().videoDecoders.single()
        val point = assertNotNull(decoder.sustainablePerformance)
        assertEquals(1280, point.width)
        assertEquals(720, point.height)
        // Android scales measured throughput to the queried size. 1080p is too slow; 720p's lower bound is safe.
        assertTrue(point.frameRate in 24.0..30.0)
    }

    @Test
    fun softwareAv1PerformancePointBoundsTheEnvelope() {
        video(
            "c2.android.av1.decoder",
            "video/av01",
            1920,
            1080,
            489600,
            CodecProfileLevel.AV1ProfileMain8 to CodecProfileLevel.AV1Level41,
            software = true,
            measurements = mapOf("performance-point-1280x720-range" to "30-30")
        )
        val point = assertNotNull(
            DeviceCapabilityProbe(context, ffmpegSupport = { false })
                .snapshot().videoDecoders.single().sustainablePerformance
        )
        assertEquals(1280, point.width)
        assertEquals(720, point.height)
        assertEquals(30.0, point.frameRate)
    }

    @Test
    fun optionalFfmpegCodecsOnlyAppearWhenAvailableAndSupported() {
        assertFalse(DeviceCapabilityProbe.ffmpegSupports("audio/not-a-real-codec"))
        val actual = DeviceCapabilityProbe(context, ffmpegSupport = { it == "audio/true-hd" || it == "audio/mpeg-L2" }).snapshot()
        assertEquals(setOf(AudioCodec.TRUEHD, AudioCodec.MP2), actual.audioDecoders.map { it.codec }.toSet())
        assertTrue(actual.audioDecoders.all { !it.hardware && it.maxChannels == 8 })
    }

    @Test
    fun ffmpegOnlyDtsAndTrueHdEnableDirectPlayWithoutInventingPlatformDecoders() {
        video(
            "OMX.vendor.avc.decoder",
            "video/avc",
            1920,
            1080,
            489600,
            CodecProfileLevel.AVCProfileHigh to CodecProfileLevel.AVCLevel42
        )
        audio("OMX.vendor.aac.decoder", "audio/mp4a-latm", 6)
        val without = DeviceCapabilityProbe(context, ffmpegSupport = { false }).snapshot()
        val with = DeviceCapabilityProbe(context, ffmpegSupport = { it in setOf("audio/vnd.dts", "audio/true-hd") }).snapshot()
        assertFalse(without.audioDecoders.any { it.codec in setOf(AudioCodec.DTS, AudioCodec.TRUEHD) })
        for (codec in listOf(AudioCodec.DTS, AudioCodec.TRUEHD)) {
            val decoder = with.audioDecoders.single { it.codec == codec }
            assertFalse(decoder.hardware)
            assertEquals(8, decoder.maxChannels)
            val before = DeviceProfileBuilder.build(without).directPlayProfiles.single { it.container == "mkv" }.audioCodec!!.split(',')
            val after = DeviceProfileBuilder.build(with).directPlayProfiles.single { it.container == "mkv" }.audioCodec!!.split(',')
            assertFalse(codec.serverNames.first() in before)
            assertTrue(codec.serverNames.first() in after)
        }
        // MediaCodecList caches the inventory on first query; reset it before describing another device.
        ShadowMediaCodecList.reset()
        // A supported platform codec and FFmpeg alternative both remain visible; renderer ordering chooses platform first.
        audio("OMX.vendor.dts.decoder", "audio/vnd.dts", 6)
        val both = DeviceCapabilityProbe(context, ffmpegSupport = { it == "audio/vnd.dts" }).snapshot()
        assertEquals(setOf(true, false), both.audioDecoders.filter { it.codec == AudioCodec.DTS }.map { it.hardware }.toSet())
    }

    @Test
    @Config(sdk = [28])
    fun passthroughStillOffersDtsAndTrueHdWhenNeitherPlatformNorFfmpegCanDecodeThem() {
        video(
            "OMX.vendor.avc.decoder",
            "video/avc",
            1920,
            1080,
            489600,
            CodecProfileLevel.AVCProfileHigh to CodecProfileLevel.AVCLevel42
        )
        audio("OMX.vendor.aac.decoder", "audio/mp4a-latm", 2)
        val probe = DeviceCapabilityProbe(context, settings = PlaybackSettings(passthroughEnabled = true), ffmpegSupport = { false })
        try {
            probe.start {}
            context.sendBroadcast(
                Intent(AudioManager.ACTION_HDMI_AUDIO_PLUG)
                    .putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 1)
                    .putExtra(AudioManager.EXTRA_ENCODINGS, intArrayOf(C.ENCODING_PCM_16BIT, C.ENCODING_DTS_HD, C.ENCODING_DOLBY_TRUEHD))
                    .putExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, 8)
            )
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val actual = probe.snapshot()
            assertFalse(actual.audioDecoders.any { it.codec in setOf(AudioCodec.DTS, AudioCodec.TRUEHD) })
            assertEquals(setOf(PassthroughEncoding.DTS_HD, PassthroughEncoding.TRUEHD), actual.audioOutput.passthrough)
            val on = DeviceProfileBuilder.build(actual).directPlayProfiles.single { it.container == "mkv" }.audioCodec!!.split(',')
            val off = DeviceProfileBuilder.build(actual.copy(settings = PlaybackSettings(passthroughEnabled = false)))
                .directPlayProfiles.single { it.container == "mkv" }.audioCodec!!.split(',')
            assertTrue("dts" in on && "truehd" in on)
            assertFalse("dts" in off || "truehd" in off)
        } finally {
            probe.close()
        }
    }

    @Test
    fun installedFfmpegReflectionMatchesLibraryAvailabilityAndCodecSupport() {
        val library = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary")
        val available = library.getMethod("isAvailable").invoke(null) as Boolean
        for (mime in listOf("audio/vnd.dts", "audio/true-hd", "audio/not-a-real-codec")) {
            val supported = available && library.getMethod("supportsFormat", String::class.java).invoke(null, mime) as Boolean
            assertEquals(supported, DeviceCapabilityProbe.ffmpegSupports(mime), mime)
        }
    }

    @Test
    fun pcmCountDoesNotInheritEncodedSurroundLimit() {
        @Suppress("DEPRECATION")
        val capabilities = AudioCapabilities(intArrayOf(C.ENCODING_PCM_16BIT, C.ENCODING_AC3, C.ENCODING_DTS_HD), 8)
        val output = DeviceCapabilityProbe.audioOutput(capabilities)
        assertEquals(2, output.pcmChannels)
        assertEquals(setOf(PassthroughEncoding.AC3, PassthroughEncoding.DTS_HD), output.passthrough)
    }

    @Test
    @Config(sdk = [28])
    fun routeReceiverRereadsHdmiChangesAndCanBeClosed() {
        val probe = DeviceCapabilityProbe(context, ffmpegSupport = { false })
        var changes = 0
        probe.start { changes++ }
        context.sendBroadcast(
            Intent(AudioManager.ACTION_HDMI_AUDIO_PLUG)
                .putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 1)
                .putExtra(AudioManager.EXTRA_ENCODINGS, intArrayOf(C.ENCODING_PCM_16BIT, C.ENCODING_AC3))
                .putExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, 6)
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(changes > 0)
        assertEquals(setOf(PassthroughEncoding.AC3), probe.snapshot().audioOutput.passthrough)
        probe.close()
        val before = changes
        context.sendBroadcast(Intent(AudioManager.ACTION_HDMI_AUDIO_PLUG).putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 0))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(before, changes)
    }

    private fun video(
        name: String,
        mime: String,
        width: Int,
        height: Int,
        blocksPerSecond: Int,
        vararg profiles: Pair<Int, Int>,
        software: Boolean = false,
        measurements: Map<String, String> = emptyMap(),
        canonicalName: String? = null
    ) {
        val format = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_WIDTH, width)
            setInteger(MediaFormat.KEY_HEIGHT, height)
            setString("size-range", "64x64-${width}x$height")
            setString("block-size", "16x16")
            setString("block-count-range", "1-${((width + 15) / 16) * ((height + 15) / 16)}")
            setString("blocks-per-second-range", "1-$blocksPerSecond")
            setString("frame-rate-range", "1-60")
            measurements.forEach { (key, value) -> setString(key, value) }
        }
        val caps = CodecCapabilitiesBuilder.newBuilder().setMediaFormat(format)
            .setProfileLevels(
                profiles.map { (p, l) ->
                    CodecProfileLevel().apply {
                        profile = p
                        level = l
                    }
                }.toTypedArray()
            )
            .setColorFormats(intArrayOf(android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)).build()
        val info = MediaCodecInfoBuilder.newBuilder().setName(name).setCapabilities(caps)
            .setIsSoftwareOnly(software).setIsHardwareAccelerated(!software).build()
        if (canonicalName != null) ReflectionHelpers.setField(info, "mCanonicalName", canonicalName)
        ShadowMediaCodecList.addCodec(info)
    }

    private fun audio(name: String, mime: String, channels: Int, software: Boolean = false) {
        val format = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setString("max-channel-count", channels.toString())
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, channels)
        }
        val caps = CodecCapabilitiesBuilder.newBuilder().setMediaFormat(format).build()
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder().setName(name).setCapabilities(caps)
                .setIsSoftwareOnly(software).setIsHardwareAccelerated(!software).build()
        )
    }
}
