package app.pictureshow.core.playback

/**
 * Turns [DeviceCapabilities] into the server's [DeviceProfile]: direct play for what Media3 demuxes and the device
 * decodes, codec conditions that keep the server honest, HLS transcoding into what the device decodes, and subtitle
 * delivery Media3 can render. Decisions and their evidence are in the leg notes; the rules that matter most:
 *
 * - E-AC-3 with more than six channels never goes into MPEG-TS: Media3's TS reader reports each dependent substream
 *   of 7.1 E-AC-3 as a new format and the decoder is rebuilt every frame. A codec profile scoped to `ts,mpegts`
 *   limits E-AC-3 there to six channels, which the server checks both for TS files (direct play) and before copying
 *   audio into a TS transcode (StreamBuilder.GetCompatibilityAudioCodec gets the transcoding container).
 * - HDR range types are what the decoder outputs and the display shows. Dolby Vision profile 8 with an HDR10, HLG or
 *   SDR base layer plays through the base layer when the display lacks Dolby Vision; profiles 5 and 7 need a Dolby
 *   Vision decoder and display. `DOVIInvalid` (which includes profile 8.6) is never allowed.
 */
object DeviceProfileBuilder {
    private const val FULL_HD_WIDTH = 1920

    fun build(capabilities: DeviceCapabilities): DeviceProfile {
        val video = selectVideoDecoders(capabilities.videoDecoders)
        val audio = audioChannelLimits(capabilities)
        val bitrate = capabilities.settings.maxStreamingBitrate
        return DeviceProfile(
            name = capabilities.name,
            maxStreamingBitrate = bitrate,
            maxStaticBitrate = bitrate,
            directPlayProfiles = directPlayProfiles(video.keys, audio.keys),
            transcodingProfiles = transcodingProfiles(capabilities, video.keys, audio.keys),
            codecProfiles = guardedVideoCodecProfiles(capabilities.videoDecoders, video, capabilities.display) + audioCodecProfiles(audio),
            subtitleProfiles = subtitleProfiles()
        )
    }

    /** Hardware decoders win; a software decoder is used for a codec only when no hardware decoder exists for it. */
    private fun selectVideoDecoders(decoders: List<VideoDecoder>): Map<VideoCodec, VideoDecoder> =
        decoders.filter { it.codec != VideoCodec.AV1 || it.hardware || it.sustainablePerformance != null }
            .map { decoder ->
                val point = decoder.sustainablePerformance
                if (decoder.codec == VideoCodec.AV1 && !decoder.hardware && point != null) {
                    decoder.copy(
                        maxWidth = minOf(decoder.maxWidth, point.width),
                        maxHeight = minOf(decoder.maxHeight, point.height),
                        maxFrameRate = minOf(decoder.maxFrameRate, point.frameRate),
                        maxFrameRateAtMaxSize = minOf(decoder.maxFrameRateAtMaxSize, point.frameRate)
                    )
                } else {
                    decoder
                }
            }.groupBy { it.codec }.mapValues { (_, forCodec) ->
                val preferred = forCodec.filter { it.hardware }.ifEmpty { forCodec }
                if (preferred.first().codec == VideoCodec.AV1 && preferred.none { it.hardware }) {
                    // Envelopes belong to individual decoders; combining width from one and speed from another overclaims.
                    preferred.maxBy { it.maxWidth.toLong() * it.maxHeight * it.maxFrameRate }
                } else {
                    preferred.reduce(::merge)
                }
            }.toSortedMap(compareBy { it.ordinal })

    private fun merge(a: VideoDecoder, b: VideoDecoder): VideoDecoder = a.copy(
        profiles = a.profiles + b.profiles,
        maxLevel = listOfNotNull(a.maxLevel, b.maxLevel).maxWithOrNull(compareBy({ it.major }, { it.minor })),
        maxWidth = maxOf(a.maxWidth, b.maxWidth),
        maxHeight = maxOf(a.maxHeight, b.maxHeight),
        maxFrameRate = maxOf(a.maxFrameRate, b.maxFrameRate),
        maxFrameRateAtMaxSize = maxOf(a.maxFrameRateAtMaxSize, b.maxFrameRateAtMaxSize),
        maxBitDepth = maxOf(a.maxBitDepth, b.maxBitDepth),
        hdrFormats = a.hdrFormats + b.hdrFormats,
        dolbyVisionProfiles = a.dolbyVisionProfiles + b.dolbyVisionProfiles
    )

    /**
     * The server ANDs every applicable codec profile. A broad outer bound retains the established homogeneous
     * profile shape; profile-scoped conditions below constrain heterogeneous reports to one real decoder tuple.
     * Incomparable alternatives for the same profile cannot be expressed as an OR here: choose an actual envelope.
     */
    private fun guardedVideoCodecProfiles(
        reported: List<VideoDecoder>,
        selected: Map<VideoCodec, VideoDecoder>,
        display: DisplayCapabilities
    ): List<CodecProfile> = selected.flatMap { (codec, summary) ->
        val available = reported.filter {
            it.codec == codec && (it.hardware || codec != VideoCodec.AV1 || it.sustainablePerformance != null)
        }
        val preferred = available.filter { it.hardware }.ifEmpty { available }
        val tuples = preferred.flatMap { decoder ->
            if (decoder.profileCapabilities.isEmpty()) {
                if (decoder.profiles.isEmpty()) listOf(decoder) else decoder.profiles.map { decoder.copy(profiles = setOf(it)) }
            } else {
                // Android HDR variants can share the server profile and bit depth. Within this physical decoder,
                // use their common reported ceiling so an HDR-specific level never leaks into an SDR claim.
                val commonLevels = decoder.profileCapabilities.groupBy { it.profile.lowercase() to it.maxBitDepth }
                    .mapValues { (_, variants) ->
                        variants.mapNotNull { it.maxLevel }.minWithOrNull(compareBy({ it.major }, { it.minor }))
                    }
                decoder.profileCapabilities.map { tuple ->
                    decoder.copy(
                        profiles = setOf(tuple.profile),
                        maxLevel = commonLevels[tuple.profile.lowercase() to tuple.maxBitDepth],
                        maxBitDepth = tuple.maxBitDepth,
                        hdrFormats = tuple.hdrFormats + if (HdrFormat.DOLBY_VISION in decoder.hdrFormats && tuple.maxBitDepth >= 10) {
                            setOf(HdrFormat.DOLBY_VISION)
                        } else {
                            emptySet()
                        },
                        profileCapabilities = emptyList()
                    )
                }
            }
        }.map { decoder ->
            val point = decoder.sustainablePerformance
            if (decoder.codec == VideoCodec.AV1 && !decoder.hardware && point != null) {
                decoder.copy(
                    maxWidth = minOf(decoder.maxWidth, point.width),
                    maxHeight = minOf(decoder.maxHeight, point.height),
                    maxFrameRate = minOf(decoder.maxFrameRate, point.frameRate),
                    maxFrameRateAtMaxSize = minOf(decoder.maxFrameRateAtMaxSize, point.frameRate)
                )
            } else {
                decoder
            }
        }
        val heterogeneous = preferred.size > 1 ||
            preferred.any { decoder ->
                decoder.profileCapabilities.any {
                    it.maxLevel != decoder.maxLevel ||
                        it.maxBitDepth != decoder.maxBitDepth ||
                        it.hdrFormats != decoder.hdrFormats
                }
            }
        if (!heterogeneous) {
            videoCodecProfiles(listOf(summary), display)
        } else {
            val scoped = tuples.groupBy { it.profiles.singleOrNull() }.flatMap { (profile, alternatives) ->
                val actual = alternatives.firstOrNull { candidate -> alternatives.all { dominates(candidate, it) } }
                    ?: alternatives.maxBy { it.maxWidth.toLong() * it.maxHeight * it.maxFrameRate }
                videoCodecProfiles(listOf(actual), display).map { constraints ->
                    constraints.copy(
                        applyConditions = (
                            profile?.let {
                                listOf(equalsAny(ProfileConditionValue.VideoProfile, listOf(it.lowercase())))
                            } ?: emptyList()
                            ) + constraints.applyConditions
                    )
                }
            }
            // Without named profiles there is no profile predicate: retain a single real envelope.
            if (tuples.all { it.profiles.isEmpty() }) scoped else videoCodecProfiles(listOf(summary), display) + scoped
        }
    }

    private fun dominates(a: VideoDecoder, b: VideoDecoder): Boolean {
        val aLevel = a.maxLevel
        val bLevel = b.maxLevel
        return a.maxWidth >= b.maxWidth &&
            a.maxHeight >= b.maxHeight &&
            a.maxFrameRate >= b.maxFrameRate &&
            a.maxFrameRateAtMaxSize >= b.maxFrameRateAtMaxSize &&
            a.maxBitDepth >= b.maxBitDepth &&
            a.hdrFormats.containsAll(b.hdrFormats) &&
            a.dolbyVisionProfiles.containsAll(b.dolbyVisionProfiles) &&
            (aLevel == null || (bLevel != null && compareValuesBy(aLevel, bLevel, { it.major }, { it.minor }) >= 0))
    }

    /** Input channels each playable audio codec accepts: a decoder's limit, or the passthrough limit when enabled. */
    private fun audioChannelLimits(capabilities: DeviceCapabilities): Map<AudioCodec, Int> {
        val limits = sortedMapOf<AudioCodec, Int>(compareBy { it.ordinal })
        for (decoder in capabilities.audioDecoders) {
            limits[decoder.codec] = maxOf(limits[decoder.codec] ?: 0, decoder.maxChannels)
        }
        if (AudioCodec.PCM !in limits) limits[AudioCodec.PCM] = PCM_CHANNELS
        if (capabilities.settings.passthroughEnabled) {
            for (encoding in capabilities.audioOutput.passthrough) {
                limits[encoding.codec] = maxOf(limits[encoding.codec] ?: 0, encoding.maxChannels)
            }
        }
        return limits
    }

    private fun directPlayProfiles(video: Set<VideoCodec>, audio: Set<AudioCodec>): List<DirectPlayProfile> =
        Media3Formats.containers.mapNotNull { container ->
            val videoCodecs = VideoCodec.entries.filter { it in container.video && it in video }
            val audioCodecs = AudioCodec.entries.filter { it in container.audio && it in audio }
            // An empty codec list means "any" to the server, so a container without playable codecs is left out.
            if (videoCodecs.isEmpty() || audioCodecs.isEmpty()) {
                null
            } else {
                DirectPlayProfile(
                    container = container.serverNames,
                    videoCodec = videoCodecs.joinToString(",") { it.serverName },
                    audioCodec = audioCodecs.flatMap { it.serverNames }.joinToString(","),
                    type = DlnaProfileType.Video
                )
            }
        }

    private fun transcodingProfiles(
        capabilities: DeviceCapabilities,
        video: Set<VideoCodec>,
        audio: Set<AudioCodec>
    ): List<TranscodingProfile> {
        // H.264 first: when the server must encode, it encodes to the first codec, and H.264 is the cheapest to encode.
        val videoOrder = listOf(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.AV1, VideoCodec.VP9).filter { it in video }
        val audioOrder = transcodeAudioOrder(capabilities).filter { it in audio }
        val output = capabilities.audioOutput
        val passthroughChannels = if (capabilities.settings.passthroughEnabled) {
            output.passthrough.maxOfOrNull { it.maxChannels } ?: 0
        } else {
            0
        }
        val routeChannels = maxOf(output.pcmChannels, passthroughChannels).toString()
        val size = listOf(
            ProfileCondition(ProfileConditionType.LessThanEqual, ProfileConditionValue.Width, capabilities.display.maxWidth.toString()),
            ProfileCondition(ProfileConditionType.LessThanEqual, ProfileConditionValue.Height, capabilities.display.maxHeight.toString())
        )

        fun hls(container: String, videoCodecs: List<VideoCodec>, audioCodecs: List<AudioCodec>) = TranscodingProfile(
            container = container,
            type = DlnaProfileType.Video,
            videoCodec = videoCodecs.joinToString(",") { it.serverName },
            audioCodec = audioCodecs.flatMap { it.serverNames }.joinToString(","),
            protocol = MediaStreamProtocol.Hls,
            context = EncodingContext.Streaming,
            maxAudioChannels = routeChannels,
            minSegments = 1,
            breakOnNonKeyFrames = false,
            conditions = size
        )

        // fMP4 segments first: they carry every codec the server allows in HLS, including E-AC-3 7.1. MPEG-TS stays as
        // the fallback the server switches to for sources marked UseMostCompatibleTranscodingProfile.
        return listOf(
            hls(
                "mp4",
                videoOrder.filter { it in Media3Formats.hlsVideo },
                audioOrder.filter { it in Media3Formats.hlsMp4Audio }
            ),
            hls(
                "ts",
                videoOrder.filter { it in Media3Formats.hlsTsVideo },
                audioOrder.filter { it in Media3Formats.hlsTsAudio }
            )
        )
    }

    /** The first codec is what the server encodes to; the rest are kept so matching sources are copied, not encoded. */
    private fun transcodeAudioOrder(capabilities: DeviceCapabilities): List<AudioCodec> {
        val passthrough = if (capabilities.settings.passthroughEnabled) capabilities.audioOutput.passthrough else emptySet()
        val encodeTargets = when {
            passthrough.any { it.codec == AudioCodec.EAC3 } -> listOf(AudioCodec.EAC3, AudioCodec.AC3, AudioCodec.AAC)
            passthrough.any { it.codec == AudioCodec.AC3 } -> listOf(AudioCodec.AC3, AudioCodec.AAC, AudioCodec.EAC3)
            else -> listOf(AudioCodec.AAC, AudioCodec.AC3, AudioCodec.EAC3)
        }
        val copyOnly = listOf(AudioCodec.MP3, AudioCodec.OPUS, AudioCodec.FLAC, AudioCodec.ALAC, AudioCodec.DTS, AudioCodec.TRUEHD)
        return encodeTargets + copyOnly
    }

    private fun videoCodecProfiles(decoders: Collection<VideoDecoder>, display: DisplayCapabilities): List<CodecProfile> =
        decoders.flatMap { decoder ->
            val conditions = buildList {
                if (decoder.codec in PROFILED_CODECS && decoder.profiles.isNotEmpty()) {
                    add(equalsAny(ProfileConditionValue.VideoProfile, decoder.profiles.map { it.lowercase() }))
                }
                val level = decoder.maxLevel?.let { serverLevel(decoder.codec, it) }
                if (level != null && !relaxHevcLevel(decoder)) add(lessThanEqual(ProfileConditionValue.VideoLevel, level.toString()))
                add(lessThanEqual(ProfileConditionValue.VideoBitDepth, decoder.maxBitDepth.toString()))
                val requirePerformance = decoder.codec == VideoCodec.AV1 && !decoder.hardware
                add(lessThanEqual(ProfileConditionValue.Width, decoder.maxWidth.toString()).copy(isRequired = requirePerformance))
                add(lessThanEqual(ProfileConditionValue.Height, decoder.maxHeight.toString()).copy(isRequired = requirePerformance))
                add(lessThanEqual(ProfileConditionValue.VideoFramerate, number(decoder.maxFrameRate)).copy(isRequired = requirePerformance))
                if (requirePerformance) {
                    add(
                        ProfileCondition(
                            ProfileConditionType.GreaterThanEqual,
                            ProfileConditionValue.VideoFramerate,
                            "1",
                            isRequired = true
                        )
                    )
                }
                add(equalsAny(ProfileConditionValue.VideoRangeType, rangeTypes(decoder, display).map { it.name }))
            }
            val base = CodecProfile(type = CodecType.Video, codec = decoder.codec.serverName, conditions = conditions)
            val extras = buildList {
                if (relaxHevcLevel(decoder)) {
                    val reportedLevel = serverLevel(decoder.codec, requireNotNull(decoder.maxLevel))!!
                    val scopes = listOf(
                        listOf(
                            lessThanEqual(ProfileConditionValue.Width, "1920"),
                            lessThanEqual(ProfileConditionValue.Height, "1080"),
                            lessThanEqual(ProfileConditionValue.VideoFramerate, "30")
                        ) to 186,
                        listOf(ProfileCondition(ProfileConditionType.GreaterThanEqual, ProfileConditionValue.Width, "1921")) to
                            reportedLevel,
                        listOf(ProfileCondition(ProfileConditionType.GreaterThanEqual, ProfileConditionValue.Height, "1081")) to
                            reportedLevel,
                        listOf(
                            ProfileCondition(
                                ProfileConditionType.GreaterThanEqual,
                                ProfileConditionValue.VideoFramerate,
                                "30.000000000000004"
                            )
                        ) to
                            reportedLevel,
                        listOf(lessThanEqual(ProfileConditionValue.VideoFramerate, "0")) to
                            reportedLevel
                    )
                    scopes.forEach { (scope, cap) ->
                        add(
                            CodecProfile(
                                type = CodecType.Video,
                                codec = decoder.codec.serverName,
                                applyConditions = scope,
                                conditions = listOf(lessThanEqual(ProfileConditionValue.VideoLevel, cap.toString()))
                            )
                        )
                    }
                }
                if (decoder.codec == VideoCodec.AV1 && !decoder.hardware) {
                    // Explicit scope accompanies the unconditional envelope: unknown/outside values never gain capability.
                    add(
                        CodecProfile(
                            type = CodecType.Video,
                            codec = decoder.codec.serverName,
                            applyConditions = listOf(
                                lessThanEqual(ProfileConditionValue.Width, decoder.maxWidth.toString()),
                                lessThanEqual(ProfileConditionValue.Height, decoder.maxHeight.toString()),
                                lessThanEqual(ProfileConditionValue.VideoFramerate, number(decoder.maxFrameRate))
                            ),
                            conditions = conditions
                        )
                    )
                }
            }
            if (decoder.maxWidth > FULL_HD_WIDTH && decoder.maxFrameRateAtMaxSize < decoder.maxFrameRate) {
                val aboveFullHd = CodecProfile(
                    type = CodecType.Video,
                    codec = decoder.codec.serverName,
                    applyConditions = listOf(
                        ProfileCondition(ProfileConditionType.GreaterThanEqual, ProfileConditionValue.Width, (FULL_HD_WIDTH + 1).toString())
                    ),
                    conditions = listOf(lessThanEqual(ProfileConditionValue.VideoFramerate, number(decoder.maxFrameRateAtMaxSize)))
                )
                listOf(base, aboveFullHd) + extras
            } else {
                listOf(base) + extras
            }
        }

    /** A 4K60 HEVC engine has ample throughput for mislabeled 1080p30 streams. Other limits remain enforced. */
    private fun relaxHevcLevel(decoder: VideoDecoder): Boolean = decoder.codec == VideoCodec.HEVC &&
        decoder.hardware &&
        decoder.maxLevel != null &&
        (serverLevel(decoder.codec, decoder.maxLevel) ?: 0) >= 153 &&
        decoder.maxWidth >= 3840 &&
        decoder.maxHeight >= 2160 &&
        decoder.maxFrameRateAtMaxSize >= 60.0

    /**
     * Range types follow MediaStream.GetVideoColorRange on the server. HDR10+ is listed only when it is shown as such;
     * the server already accepts HDR10+ streams wherever HDR10 is allowed (ConditionProcessor's special case).
     */
    internal fun rangeTypes(decoder: VideoDecoder, display: DisplayCapabilities): List<VideoRangeType> {
        val shown = decoder.hdrFormats intersect display.hdrFormats
        val hdr10 = HdrFormat.HDR10 in shown
        val hlg = HdrFormat.HLG in shown
        val dolbyVisionCodec = decoder.codec == VideoCodec.HEVC || decoder.codec == VideoCodec.AV1
        val dolbyVision = dolbyVisionCodec && HdrFormat.DOLBY_VISION in shown
        val result = mutableSetOf(VideoRangeType.SDR)
        if (hdr10) result += VideoRangeType.HDR10
        if (hlg) result += VideoRangeType.HLG
        if (HdrFormat.HDR10_PLUS in shown) result += VideoRangeType.HDR10Plus
        if (dolbyVisionCodec) {
            // Profile 8.x/10.x with a compatible base layer: the base layer plays wherever its own range does.
            result += VideoRangeType.DOVIWithSDR
            if (dolbyVision || hdr10) {
                result += VideoRangeType.DOVIWithHDR10
                result += VideoRangeType.DOVIWithHDR10Plus
            }
            if (dolbyVision || hlg) result += VideoRangeType.DOVIWithHLG
        }
        if (dolbyVision) {
            // No compatible base layer (profile 5, 10.0) or an enhancement layer (profile 7): Dolby Vision only.
            val withoutBase = if (decoder.codec == VideoCodec.HEVC) 5 else 10
            if (withoutBase in decoder.dolbyVisionProfiles) result += VideoRangeType.DOVI
            if (decoder.codec == VideoCodec.HEVC && 7 in decoder.dolbyVisionProfiles) {
                result += VideoRangeType.DOVIWithEL
                result += VideoRangeType.DOVIWithELHDR10Plus
            }
        }
        return VideoRangeType.entries.filter { it in result }
    }

    /** Levels in the server's (ffprobe's) units: H.264 level x10, HEVC level x30, AV1 seq_level_idx. */
    internal fun serverLevel(codec: VideoCodec, level: CodecLevel): Int? = when (codec) {
        VideoCodec.H264 -> level.major * 10 + level.minor
        VideoCodec.HEVC -> (level.major * 10 + level.minor) * 3
        VideoCodec.AV1 -> (level.major - 2) * 4 + level.minor
        else -> null
    }

    private fun audioCodecProfiles(limits: Map<AudioCodec, Int>): List<CodecProfile> {
        val perCodec = limits.map { (codec, channels) ->
            CodecProfile(
                type = CodecType.VideoAudio,
                codec = codec.serverNames.joinToString(","),
                conditions = listOf(lessThanEqual(ProfileConditionValue.AudioChannels, channels.toString()))
            )
        }
        val eac3 = limits[AudioCodec.EAC3] ?: return perCodec
        if (eac3 <= TS_EAC3_CHANNELS) return perCodec
        return perCodec + CodecProfile(
            type = CodecType.VideoAudio,
            codec = "eac3",
            container = "ts,mpegts",
            conditions = listOf(lessThanEqual(ProfileConditionValue.AudioChannels, TS_EAC3_CHANNELS.toString()))
        )
    }

    private fun subtitleProfiles(): List<SubtitleProfile> {
        val embedded = Media3Formats.containers.flatMap { container ->
            container.embeddedSubtitles.map {
                SubtitleProfile(format = it, method = SubtitleDeliveryMethod.Embed, container = container.serverNames)
            }
        }
        val external = Media3Formats.externalTextSubtitles.map { SubtitleProfile(format = it, method = SubtitleDeliveryMethod.External) }
        // Image subtitles in a transcode (HLS cannot carry them) are burned in.
        val burnIn = listOf("pgssub", "dvdsub", "dvbsub").map { SubtitleProfile(format = it, method = SubtitleDeliveryMethod.Encode) }
        return embedded + external + burnIn
    }

    private fun lessThanEqual(property: ProfileConditionValue, value: String) =
        ProfileCondition(ProfileConditionType.LessThanEqual, property, value)

    private fun equalsAny(property: ProfileConditionValue, values: List<String>) =
        ProfileCondition(ProfileConditionType.EqualsAny, property, values.joinToString("|"))

    private fun number(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    private val PROFILED_CODECS = setOf(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.VP9, VideoCodec.AV1)
    private const val TS_EAC3_CHANNELS = 6
    private const val PCM_CHANNELS = 8
}
