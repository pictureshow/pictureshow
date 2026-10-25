/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34], application = Application::class)
class VideoSurfaceLifecycleTest {
    @Test
    fun actualPlaybackRendererReleasesLegacySoftwareCodecOnSurfaceLossAndPreservesOtherDecoderPolicies() {
        val context = RuntimeEnvironment.getApplication()
        val renderers = PictureshowRenderersFactory(context).createRenderers(
            Handler(Looper.getMainLooper()),
            object : VideoRendererEventListener {},
            object : AudioRendererEventListener {},
            {},
            {}
        )
        val renderer = renderers.filterIsInstance<EndVideoRenderer>().single()
        val upstream = MediaCodecVideoRenderer.Builder(context).build()
        try {
            val software = codec("OMX.google.h264.decoder", software = true)
            assertTrue(placeholder(upstream, software), "Control must exercise the live-surface-switch path")
            assertFalse(
                placeholder(renderer, software),
                "Surface loss must release this codec instead of orphaning its software frame tracker"
            )
            for (name in listOf("OMX.amlogic.hevc.decoder.awesome", "c2.android.avc.decoder", "c2.vendor.hevc.decoder")) {
                val info = codec(name, software = name.startsWith("c2.android."))
                assertEquals(placeholder(upstream, info), placeholder(renderer, info), "Keep Media3 surface policy for $name")
            }
        } finally {
            renderer.release()
            upstream.release()
        }
    }

    private fun codec(name: String, software: Boolean) = MediaCodecInfo.newInstance(
        name, MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H264, null, !software, software, !software, false, false
    )

    private fun placeholder(renderer: MediaCodecVideoRenderer, info: MediaCodecInfo): Boolean =
        MediaCodecVideoRenderer::class.java.getDeclaredMethod("shouldUsePlaceholderSurface", MediaCodecInfo::class.java).let {
            it.isAccessible = true
            it.invoke(renderer, info) as Boolean
        }
}
