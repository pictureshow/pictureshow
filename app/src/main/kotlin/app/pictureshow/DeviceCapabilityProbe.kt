/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioCapabilitiesReceiver
import app.pictureshow.core.playback.AudioCodec
import app.pictureshow.core.playback.AudioDecoder
import app.pictureshow.core.playback.AudioOutput
import app.pictureshow.core.playback.CodecLevel
import app.pictureshow.core.playback.DeviceCapabilities
import app.pictureshow.core.playback.DisplayCapabilities
import app.pictureshow.core.playback.HdrFormat
import app.pictureshow.core.playback.PassthroughEncoding
import app.pictureshow.core.playback.PlaybackSettings
import app.pictureshow.core.playback.VideoCodec
import app.pictureshow.core.playback.VideoDecoder
import app.pictureshow.core.playback.VideoPerformancePoint
import app.pictureshow.core.playback.VideoProfileCapability
import kotlin.math.floor

/** Runtime capabilities; no model-name allowlist. Own this for the player's lifetime and close its route receiver. */
class DeviceCapabilityProbe(
    private val context: Context,
    private val settings: PlaybackSettings = PlaybackSettings(),
    private val ffmpegSupport: (String) -> Boolean = ::ffmpegSupports
) : AutoCloseable {
    private var receiver: AudioCapabilitiesReceiver? = null
    private var routeCapabilities: AudioCapabilities? = null

    fun snapshot(): DeviceCapabilities {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }
        // API 29 exposes the underlying codec. Older inventories can contain distinct OMX and Codec2 decoders.
        val codecs = if (Build.VERSION.SDK_INT >= 29) {
            decoders.groupBy { it.canonicalName }.values.map { aliases -> aliases.firstOrNull { !it.isAlias } ?: aliases.first() }
        } else {
            decoders
        }
        val video = codecs.flatMap { info ->
            info.supportedTypes.mapNotNull { mime ->
                VIDEO_MIMES[mime]?.let { codec -> runCatching { videoDecoder(info, mime, codec) }.getOrNull() }
            }
        }
        val dv = codecs.flatMap { info ->
            if ("video/dolby-vision" in info.supportedTypes) {
                runCatching { info.getCapabilitiesForType("video/dolby-vision").profileLevels.mapNotNull { DV_PROFILES[it.profile] } }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
        }.toSet()
        val audio = codecs.flatMap { info ->
            info.supportedTypes.mapNotNull { mime ->
                AUDIO_MIMES[mime]?.let { codec ->
                    runCatching {
                        AudioDecoder(
                            codec,
                            requireNotNull(info.getCapabilitiesForType(mime).audioCapabilities).maxInputChannelCount,
                            hardware(info)
                        )
                    }
                        .getOrNull()
                }
            }
        } + AUDIO_MIMES.entries.distinctBy { it.value }.mapNotNull { (mime, codec) ->
            if (codec != AudioCodec.PCM && ffmpegSupport(mime)) AudioDecoder(codec, 8, hardware = false) else null
        }
        val output = audioOutput(routeCapabilities ?: currentAudioCapabilities())
        return DeviceCapabilities(
            name = "Android TV",
            videoDecoders = video.map { decoder ->
                val compatibleProfiles = dv.filter { profile ->
                    when (decoder.codec) {
                        VideoCodec.H264 -> profile in setOf(0, 1, 9)
                        VideoCodec.HEVC -> profile in 2..8
                        VideoCodec.AV1 -> profile == 10
                        else -> false
                    }
                }.toSet()
                if (compatibleProfiles.isNotEmpty()) {
                    decoder.copy(
                        hdrFormats = decoder.hdrFormats + HdrFormat.DOLBY_VISION,
                        dolbyVisionProfiles = compatibleProfiles,
                        profileCapabilities = decoder.profileCapabilities.map { tuple ->
                            if (tuple.maxBitDepth >= 10 || decoder.codec == VideoCodec.H264) {
                                tuple.copy(hdrFormats = tuple.hdrFormats + HdrFormat.DOLBY_VISION)
                            } else {
                                tuple
                            }
                        }
                    )
                } else {
                    decoder
                }
            },
            display = display(),
            audioDecoders = audio,
            audioOutput = output,
            settings = settings
        )
    }

    /** Media3 observes HDMI broadcasts, Bluetooth/device changes and surround settings; registration returns the initial route. */
    fun start(onChanged: (DeviceCapabilities) -> Unit) {
        close()
        receiver = AudioCapabilitiesReceiver(context, { capabilities ->
            routeCapabilities = capabilities
            onChanged(snapshot())
        }, AudioAttributes.DEFAULT, null).also { routeCapabilities = it.register() }
    }

    /** Forward the actual AudioTrack route when available; null follows Media3's default route discovery. */
    fun setRoutedDevice(device: AudioDeviceInfo?) {
        receiver?.setRoutedDevice(device)
    }

    override fun close() {
        receiver?.unregister()
        receiver = null
        routeCapabilities = null
    }

    private fun currentAudioCapabilities(): AudioCapabilities =
        AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null, emptyList())

    private fun display(): DisplayCapabilities {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?: return DisplayCapabilities(1920, 1080, 60.0)
        val modes = display.supportedModes
        val mode = modes.maxWithOrNull(compareBy<Display.Mode> { it.physicalWidth.toLong() * it.physicalHeight }.thenBy { it.refreshRate })
            ?: display.mode
        val hdr = if (Build.VERSION.SDK_INT >= 24) {
            (display.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()).toList().mapNotNull { type ->
                when (type) {
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> HdrFormat.HDR10
                    Display.HdrCapabilities.HDR_TYPE_HLG -> HdrFormat.HLG
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> HdrFormat.DOLBY_VISION
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> HdrFormat.HDR10_PLUS
                    else -> null
                }
            }.toSet()
        } else {
            emptySet()
        }
        return DisplayCapabilities(mode.physicalWidth, mode.physicalHeight, mode.refreshRate.toDouble(), hdr)
    }

    private fun videoDecoder(info: MediaCodecInfo, mime: String, codec: VideoCodec): VideoDecoder {
        val caps = info.getCapabilitiesForType(mime)
        val video = requireNotNull(caps.videoCapabilities)
        val width = video.supportedWidths.upper
        val height = runCatching { video.getSupportedHeightsFor(width).upper }.getOrDefault(video.supportedHeights.upper)
        // Server names can collapse distinct Android profiles (AV1 Main8/Main10 both become "Main").
        // Preserve every pair so level/depth/HDR never transfer between those variants.
        val tuples = caps.profileLevels.mapNotNull { entry ->
            val name = profileName(codec, entry.profile)
            if (name.isEmpty()) {
                null
            } else {
                VideoProfileCapability(
                    name,
                    level(codec, entry.level),
                    profileDepth(codec, entry.profile),
                    profileHdr(codec, entry.profile)
                )
            }
        }.distinct()
        val profiles = tuples.map { it.profile }.toSet()
        val depth = tuples.maxOfOrNull { it.maxBitDepth } ?: 8
        val rate = runCatching { video.getSupportedFrameRatesFor(minOf(1920, width), minOf(1080, height)).upper }
            .getOrDefault(video.supportedFrameRates.upper.toDouble())
        val maxRate = runCatching { video.getSupportedFrameRatesFor(width, height).upper }.getOrDefault(rate)
        val hdr = tuples.flatMap { it.hdrFormats }.toSet()
        return VideoDecoder(
            codec, profiles, caps.profileLevels.mapNotNull { level(codec, it.level) }.maxWithOrNull(compareBy({ it.major }, { it.minor })),
            width, height, rate, maxRate, depth, hdr, hardware = hardware(info),
            sustainablePerformance = if (!hardware(info) && codec in setOf(VideoCodec.AV1, VideoCodec.H264)) {
                sustainable(
                    video,
                    width,
                    if (codec == VideoCodec.H264) video.supportedHeights.upper else height
                )
            } else {
                null
            },
            profileCapabilities = tuples
        )
    }

    private fun sustainable(video: MediaCodecInfo.VideoCapabilities, width: Int, height: Int): VideoPerformancePoint? {
        for ((w, h) in listOf(1920 to 1080, 1280 to 720, 854 to 480, 640 to 360)) {
            if (w > width || h > height || !video.isSizeSupported(w, h)) continue
            if (Build.VERSION.SDK_INT >= 29 &&
                video.supportedPerformancePoints.orEmpty().any {
                    it.covers(MediaCodecInfo.VideoCapabilities.PerformancePoint(w, h, 30))
                }
            ) {
                return VideoPerformancePoint(w, h, 30.0)
            }
            // getSupportedFrameRatesFor describes legal input, not real-time performance. Only measured rates count.
            val measured = runCatching { video.getAchievableFrameRatesFor(w, h) }.getOrNull()
            if (measured != null && measured.lower >= 24.0) return VideoPerformancePoint(w, h, minOf(30.0, floor(measured.lower)))
        }
        return null
    }

    companion object {
        internal fun audioOutput(capabilities: AudioCapabilities): AudioOutput = AudioOutput(
            // getMaxChannelCount includes compressed passthrough and must not be used as the PCM count.
            pcmChannels = capabilities.speakerLayoutChannelMasks.maxOfOrNull { Integer.bitCount(it) } ?: 2,
            passthrough = PASSTHROUGH.filter { capabilities.supportsEncoding(it.second) }.map { it.first }.toSet()
        )

        private fun hardware(info: MediaCodecInfo): Boolean = if (Build.VERSION.SDK_INT >= 29) {
            info.isHardwareAccelerated && !info.isSoftwareOnly
        } else {
            !info.name.startsWith("OMX.google.") &&
                !info.name.startsWith("c2.android.") &&
                !info.name.startsWith("c2.google.") &&
                !info.name.lowercase().contains(".sw.")
        }

        internal fun ffmpegSupports(mime: String): Boolean = runCatching {
            val library = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary")
            library.getMethod("isAvailable").invoke(null) == true &&
                library.getMethod("supportsFormat", String::class.java).invoke(null, mime) == true
        }.getOrDefault(false)

        private fun profileDepth(codec: VideoCodec, profile: Int): Int {
            val name = profileName(codec, profile)
            return if (name in setOf("Main 10", "Profile 2", "Profile 3", "High 10", "High 4:2:2", "High 4:4:4") ||
                (codec == VideoCodec.AV1 && profile != CodecProfileLevel.AV1ProfileMain8)
            ) {
                10
            } else {
                8
            }
        }

        private fun profileHdr(codec: VideoCodec, profile: Int): Set<HdrFormat> =
            if (profileDepth(codec, profile) >= 10 && codec in setOf(VideoCodec.HEVC, VideoCodec.VP9, VideoCodec.AV1)) {
                buildSet {
                    add(HdrFormat.HDR10)
                    add(HdrFormat.HLG)
                    if (profileConstantName(codec, profile).contains("HDR10Plus")) add(HdrFormat.HDR10_PLUS)
                }
            } else {
                emptySet()
            }

        private fun profileConstantName(codec: VideoCodec, value: Int): String {
            val prefix = when (codec) {
                VideoCodec.H264 -> "AVCProfile"
                VideoCodec.HEVC -> "HEVCProfile"
                VideoCodec.VP9 -> "VP9Profile"
                VideoCodec.AV1 -> "AV1Profile"
                else -> return ""
            }
            return CodecProfileLevel::class.java.fields.firstOrNull {
                it.name.startsWith(prefix) && it.getInt(null) == value
            }?.name.orEmpty()
        }

        private fun profileName(codec: VideoCodec, value: Int): String {
            val name = profileConstantName(codec, value)
            return when (codec) {
                VideoCodec.H264 -> when (name.removePrefix("AVCProfile")) {
                    "Baseline" -> "Baseline"
                    "ConstrainedBaseline" -> "Constrained Baseline"
                    "Main" -> "Main"
                    "Extended" -> "Extended"
                    "High" -> "High"
                    "ConstrainedHigh" -> "High"
                    "High10" -> "High 10"
                    "High422" -> "High 4:2:2"
                    "High444" -> "High 4:4:4"
                    else -> ""
                }
                VideoCodec.HEVC -> if (name.contains("Main10")) {
                    "Main 10"
                } else if (name.endsWith("Main")) {
                    "Main"
                } else {
                    ""
                }
                VideoCodec.VP9 -> name.removePrefix("VP9Profile").firstOrNull()?.takeIf { it.isDigit() }?.let { "Profile $it" }.orEmpty()
                VideoCodec.AV1 -> if (name.isNotEmpty()) "Main" else ""
                else -> ""
            }
        }

        private fun level(codec: VideoCodec, value: Int): CodecLevel? {
            val prefixes = when (codec) {
                VideoCodec.H264 -> listOf("AVCLevel")
                VideoCodec.HEVC -> listOf("HEVCMainTierLevel", "HEVCHighTierLevel")
                VideoCodec.AV1 -> listOf("AV1Level")
                else -> return null
            }
            val name = CodecProfileLevel::class.java.fields.firstOrNull { field ->
                prefixes.any { field.name.startsWith(it) } && field.getInt(null) == value
            }?.name ?: return null
            val digits = name.removePrefix(prefixes.first { name.startsWith(it) })
            if (digits == "1b") return CodecLevel(1, 1)
            return CodecLevel(digits.first().digitToInt(), digits.drop(1).toIntOrNull() ?: 0)
        }

        private val VIDEO_MIMES = mapOf(
            "video/avc" to VideoCodec.H264,
            "video/hevc" to VideoCodec.HEVC,
            "video/x-vnd.on2.vp8" to VideoCodec.VP8,
            "video/x-vnd.on2.vp9" to VideoCodec.VP9,
            "video/av01" to VideoCodec.AV1,
            "video/mpeg2" to VideoCodec.MPEG2,
            "video/mp4v-es" to VideoCodec.MPEG4,
            "video/wvc1" to VideoCodec.VC1
        )
        private val AUDIO_MIMES = mapOf(
            "audio/mp4a-latm" to AudioCodec.AAC, "audio/ac3" to AudioCodec.AC3, "audio/eac3" to AudioCodec.EAC3,
            "audio/eac3-joc" to AudioCodec.EAC3, "audio/true-hd" to AudioCodec.TRUEHD, "audio/vnd.dts" to AudioCodec.DTS,
            "audio/vnd.dts.hd" to AudioCodec.DTS, "audio/dtshd" to AudioCodec.DTS, "audio/flac" to AudioCodec.FLAC,
            "audio/alac" to AudioCodec.ALAC, "audio/opus" to AudioCodec.OPUS, "audio/vorbis" to AudioCodec.VORBIS,
            "audio/mpeg" to AudioCodec.MP3, "audio/mpeg-L2" to AudioCodec.MP2, "audio/raw" to AudioCodec.PCM
        )
        private val PASSTHROUGH = listOf(
            PassthroughEncoding.AC3 to C.ENCODING_AC3,
            PassthroughEncoding.EAC3 to C.ENCODING_E_AC3,
            PassthroughEncoding.EAC3_JOC to C.ENCODING_E_AC3_JOC,
            PassthroughEncoding.DTS to C.ENCODING_DTS,
            PassthroughEncoding.DTS_HD to C.ENCODING_DTS_HD,
            PassthroughEncoding.TRUEHD to C.ENCODING_DOLBY_TRUEHD
        )

        // These integer values are interpreted only for profile IDs actually reported by the running platform.
        // They are compile-time constants, so referring to newer IDs creates no runtime API linkage on API 23.
        @android.annotation.SuppressLint("InlinedApi")
        private val DV_PROFILES = mapOf(
            CodecProfileLevel.DolbyVisionProfileDvavPer to 0, CodecProfileLevel.DolbyVisionProfileDvavPen to 1,
            CodecProfileLevel.DolbyVisionProfileDvheDer to 2, CodecProfileLevel.DolbyVisionProfileDvheDen to 3,
            CodecProfileLevel.DolbyVisionProfileDvheDtr to 4, CodecProfileLevel.DolbyVisionProfileDvheStn to 5,
            CodecProfileLevel.DolbyVisionProfileDvheDth to 6, CodecProfileLevel.DolbyVisionProfileDvheDtb to 7,
            CodecProfileLevel.DolbyVisionProfileDvheSt to 8, CodecProfileLevel.DolbyVisionProfileDvavSe to 9,
            CodecProfileLevel.DolbyVisionProfileDvav110 to 10
        )
    }
}
