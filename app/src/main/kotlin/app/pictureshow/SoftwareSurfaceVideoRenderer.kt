/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

/**
 * The legacy platform software renderer loses its old frame tracker on setOutputSurface.
 * Its queued GraphicBuffers then survive codec release. Let Media3 release/recreate that codec
 * on surface loss instead of parking it on a placeholder. Hardware and Codec2 keep Media3's policy.
 * See Android 11 MediaCodec.cpp, kWhatSetSurface (mSoftRenderer is overwritten without deletion).
 */
internal open class SoftwareSurfaceVideoRenderer(builder: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(builder) {
    override fun shouldUsePlaceholderSurface(codecInfo: MediaCodecInfo): Boolean =
        !codecInfo.name.startsWith("OMX.google.") && super.shouldUsePlaceholderSurface(codecInfo)
}
