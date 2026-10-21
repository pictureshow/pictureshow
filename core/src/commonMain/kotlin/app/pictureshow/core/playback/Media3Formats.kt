package app.pictureshow.core.playback

/**
 * What Media3's default extractors can demux, by server container name. Sources: Media3's DefaultExtractorsFactory
 * (FLV, FLAC, WAV, MP4, AMR, PS, OGG, TS, Matroska, ADTS, AC-3, AC-4, MP3, AVI) and the codec tables of
 * MatroskaExtractor, BoxParser (MP4), DefaultTsPayloadReaderFactory (TS) and StreamFormatChunk (AVI). Whether a codec
 * actually plays still depends on a decoder, which the capabilities supply.
 */
internal object Media3Formats {
    data class Container(
        val serverNames: String,
        val video: Set<VideoCodec>,
        val audio: Set<AudioCodec>,
        val embeddedSubtitles: Set<String> = emptySet()
    )

    private val allPcm = setOf(AudioCodec.PCM)

    val containers: List<Container> = listOf(
        Container(
            "mkv",
            setOf(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.VP8, VideoCodec.VP9, VideoCodec.AV1, VideoCodec.MPEG2, VideoCodec.MPEG4),
            setOf(
                AudioCodec.AAC, AudioCodec.AC3, AudioCodec.EAC3, AudioCodec.TRUEHD, AudioCodec.DTS, AudioCodec.FLAC, AudioCodec.ALAC,
                AudioCodec.OPUS, AudioCodec.VORBIS, AudioCodec.MP3, AudioCodec.MP2
            ) + allPcm,
            // S_TEXT/UTF8, S_TEXT/ASS, S_TEXT/SSA, S_TEXT/WEBVTT, S_HDMV/PGS, S_VOBSUB, S_DVBSUB
            setOf("subrip", "srt", "ass", "ssa", "webvtt", "pgssub", "dvdsub", "dvbsub")
        ),
        Container(
            "webm",
            setOf(VideoCodec.VP8, VideoCodec.VP9, VideoCodec.AV1),
            setOf(AudioCodec.OPUS, AudioCodec.VORBIS),
            setOf("webvtt")
        ),
        Container(
            "mp4,m4v,mov",
            setOf(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.VP9, VideoCodec.AV1, VideoCodec.MPEG2, VideoCodec.MPEG4),
            setOf(
                AudioCodec.AAC, AudioCodec.AC3, AudioCodec.EAC3, AudioCodec.TRUEHD, AudioCodec.DTS, AudioCodec.FLAC, AudioCodec.ALAC,
                AudioCodec.OPUS, AudioCodec.MP3
            ) + allPcm,
            // tx3g
            setOf("mov_text")
        ),
        Container(
            "ts,mpegts",
            setOf(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.MPEG2),
            setOf(AudioCodec.AAC, AudioCodec.AC3, AudioCodec.EAC3, AudioCodec.DTS, AudioCodec.MP3, AudioCodec.MP2),
            setOf("dvbsub")
        ),
        Container(
            "avi",
            setOf(VideoCodec.H264, VideoCodec.MPEG4),
            setOf(AudioCodec.AAC, AudioCodec.AC3, AudioCodec.DTS, AudioCodec.MP3) + allPcm
        ),
        Container(
            "mpeg,mpg",
            setOf(VideoCodec.MPEG2, VideoCodec.H264),
            setOf(AudioCodec.AC3, AudioCodec.MP2, AudioCodec.MP3, AudioCodec.DTS)
        ),
        Container("flv", setOf(VideoCodec.H264), setOf(AudioCodec.AAC, AudioCodec.MP3)),
        Container("ogg,ogv", emptySet(), setOf(AudioCodec.OPUS, AudioCodec.VORBIS, AudioCodec.FLAC))
    )

    /** Text formats Media3 renders from a side-loaded file (WebVTT, TTML, SubRip, SSA/ASS). */
    val externalTextSubtitles: List<String> = listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "ttml")

    /** Video codecs the server will put in an HLS stream (StreamBuilder._supportedHlsVideoCodecs). */
    val hlsVideo: Set<VideoCodec> = setOf(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.VP9, VideoCodec.AV1)

    /** Audio codecs the server allows in HLS fMP4 (StreamBuilder._supportedHlsAudioCodecsMp4). */
    val hlsMp4Audio: Set<AudioCodec> = setOf(
        AudioCodec.AAC, AudioCodec.AC3, AudioCodec.EAC3, AudioCodec.MP3, AudioCodec.ALAC, AudioCodec.FLAC, AudioCodec.OPUS,
        AudioCodec.DTS, AudioCodec.TRUEHD
    )

    /** Audio codecs the server allows in HLS MPEG-TS (StreamBuilder._supportedHlsAudioCodecsTs). */
    val hlsTsAudio: Set<AudioCodec> = setOf(AudioCodec.AAC, AudioCodec.AC3, AudioCodec.EAC3, AudioCodec.MP3)

    /** Video codecs Media3's TS reader handles that the server will also put in HLS TS. */
    val hlsTsVideo: Set<VideoCodec> = setOf(VideoCodec.H264, VideoCodec.HEVC)
}
