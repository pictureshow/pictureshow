/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import android.view.Display
import app.pictureshow.core.playback.VideoCodec
import app.pictureshow.core.playback.VideoPerformancePoint
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class SoftwareAvcProbeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @AfterTest
    fun clearFixture() {
        ShadowMediaCodecList.reset()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, false)
    }

    @Test
    fun measuredFullHdSoftwareAvcPerformanceSupportsThirtyDespiteLowerDeclaredLevelRate() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        installSoftwareAvc(measuredRange = "31-34")
        val decoder = DeviceCapabilityProbe(context, ffmpegSupport = { false }).snapshot().videoDecoders.single()
        assertEquals(VideoCodec.H264, decoder.codec)
        assertFalse(decoder.hardware)
        assertTrue(decoder.maxFrameRate in 14.0..15.0, "Declared-level input rate remains independent of measured throughput")
        assertEquals(VideoPerformancePoint(1920, 1080, 30.0), decoder.sustainablePerformance)
    }

    @Test
    fun fullHdMeasurementsRemainUsableWhenTheWidestSupportedSizeHasALowerHeight() {
        val video = installSoftwareAvc(measuredRange = "31-34", maxWidth = 4080)
        assertEquals(4080, video.supportedWidths.upper)
        assertEquals(1080, video.supportedHeights.upper)
        assertEquals(512, video.getSupportedHeightsFor(4080).upper)
        assertTrue(video.isSizeSupported(1920, 1080))
        val decoder = DeviceCapabilityProbe(context, ffmpegSupport = { false }).snapshot().videoDecoders.single()
        // The existing direct-play envelope still describes the height available at maximum width.
        assertEquals(4080, decoder.maxWidth)
        assertEquals(512, decoder.maxHeight)
        assertEquals(VideoPerformancePoint(1920, 1080, 30.0), decoder.sustainablePerformance)
    }

    @Test
    fun televisionWithoutMeasuredSoftwareAvcPerformanceDoesNotAssumeFullHdThirty() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        installSoftwareAvc()
        val decoder = DeviceCapabilityProbe(context, ffmpegSupport = { false }).snapshot().videoDecoders.single()
        assertEquals(VideoCodec.H264, decoder.codec)
        assertFalse(decoder.hardware)
        assertTrue(decoder.maxFrameRate in 14.0..15.0)
        assertNull(decoder.sustainablePerformance)
    }

    @Test
    fun nonTelevisionWithoutMeasuredSoftwareAvcPerformanceDoesNotAssumeFullHdThirty() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, false)
        installSoftwareAvc()
        val decoder = DeviceCapabilityProbe(context, ffmpegSupport = { false }).snapshot().videoDecoders.single()
        assertEquals(VideoCodec.H264, decoder.codec)
        assertFalse(decoder.hardware)
        assertTrue(decoder.maxFrameRate in 14.0..15.0)
        assertNull(decoder.sustainablePerformance)
    }

    private fun installSoftwareAvc(measuredRange: String? = null, maxWidth: Int = 1920): MediaCodecInfo.VideoCapabilities {
        ShadowDisplayManager.changeDisplay(Display.DEFAULT_DISPLAY, "w1920dp-h1080dp-land-mdpi")
        val format = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, "video/avc")
            setInteger(MediaFormat.KEY_WIDTH, maxWidth)
            setInteger(MediaFormat.KEY_HEIGHT, 1080)
            // AVC's coded height includes the final 16-pixel macroblock row. The builder
            // applies KEY_HEIGHT=1080 to the public range after Android parses this metadata.
            setString("size-range", "64x64-${maxWidth}x1088")
            setString("block-size", "16x16")
            setString("block-count-range", "1-8160")
            setString("blocks-per-second-range", "1-117200")
            setString("frame-rate-range", "1-60")
            measuredRange?.let { setString("measured-frame-rate-1920x1080-range", it) }
        }
        val profile = CodecProfileLevel().apply {
            this.profile = CodecProfileLevel.AVCProfileHigh
            level = CodecProfileLevel.AVCLevel4
        }
        val capabilities = CodecCapabilitiesBuilder.newBuilder().setMediaFormat(format)
            .setProfileLevels(arrayOf(profile))
            .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible))
            .build()
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder().setName("c2.android.avc.decoder").setCapabilities(capabilities)
                .setIsSoftwareOnly(true).setIsHardwareAccelerated(false).build()
        )
        return requireNotNull(capabilities.videoCapabilities)
    }
}
