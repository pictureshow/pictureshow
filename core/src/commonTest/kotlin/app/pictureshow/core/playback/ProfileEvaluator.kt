package app.pictureshow.core.playback

/** A source file's formats, as the server sees them after probing. Null means the server does not know the value. */
data class Combo(
    val container: String,
    val videoCodec: String?,
    val videoProfile: String?,
    val bitDepth: Int?,
    val rangeType: String?,
    val audioCodec: String?,
    val audioChannels: Int?,
    val subtitles: List<String>,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Double? = null,
    val level: Int? = null
)

/**
 * The server's direct-play test (StreamBuilder.GetVideoDirectPlayProfile with ConditionProcessor), reduced to the
 * properties a [Combo] carries. Returns the TranscodeReason names the server would give; empty means direct play.
 * It exists so tests can ask "would the server direct play this?" without a server.
 */
object ProfileEvaluator {
    fun directPlayFailures(profile: DeviceProfile, combo: Combo): Set<String> {
        val reasons = mutableSetOf<String>()
        val video = profile.directPlayProfiles.filter { it.type == DlnaProfileType.Video }
        val containerProfiles = video.filter { contains(it.container, combo.container) }
        if (containerProfiles.isEmpty()) reasons += "ContainerNotSupported"
        val matching = containerProfiles.filter { combo.videoCodec == null || contains(it.videoCodec, combo.videoCodec) }
        if (containerProfiles.isNotEmpty() && matching.isEmpty()) reasons += "VideoCodecNotSupported"
        if (matching.isNotEmpty() && combo.audioCodec != null && matching.none { contains(it.audioCodec, combo.audioCodec) }) {
            reasons += "AudioCodecNotSupported"
        }
        if (combo.videoCodec != null) {
            for (codecProfile in applicable(profile, CodecType.Video, combo.videoCodec, combo.container)) {
                if (codecProfile.applyConditions.all { satisfied(it, combo) }) {
                    codecProfile.conditions.filterNot { satisfied(it, combo) }.forEach { reasons += reason(it.property) }
                }
            }
        }
        if (combo.audioCodec != null) {
            for (codecProfile in applicable(profile, CodecType.VideoAudio, combo.audioCodec, combo.container)) {
                if (codecProfile.applyConditions.all { satisfied(it, combo) }) {
                    codecProfile.conditions.filterNot { satisfied(it, combo) }.forEach { reasons += reason(it.property) }
                }
            }
        }
        for (subtitle in combo.subtitles) {
            if (!subtitleDeliverable(profile, subtitle, combo.container)) reasons += "SubtitleCodecNotSupported"
        }
        return reasons
    }

    private fun applicable(profile: DeviceProfile, type: CodecType, codec: String, container: String) = profile.codecProfiles.filter {
        it.type == type && contains(it.codec, codec) && (it.container == null || contains(it.container, container))
    }

    private fun subtitleDeliverable(profile: DeviceProfile, format: String, container: String): Boolean = profile.subtitleProfiles.any {
        val embedded = it.method == SubtitleDeliveryMethod.Embed && contains(it.container, container)
        val external = it.method == SubtitleDeliveryMethod.External && format in TEXT_SUBTITLES
        it.format.equals(format, ignoreCase = true) && (embedded || external)
    }

    /** ContainerHelper.ContainsContainer: an empty profile list matches anything; otherwise any comma-separated match. */
    private fun contains(profileList: String?, value: String): Boolean {
        if (profileList.isNullOrBlank()) return true
        val values = value.split(',').map { it.trim().lowercase() }
        return profileList.split(',').map { it.trim().lowercase() }.any { it in values }
    }

    private fun satisfied(condition: ProfileCondition, combo: Combo): Boolean {
        val actual: String? = when (condition.property) {
            ProfileConditionValue.AudioChannels -> combo.audioChannels?.toString()
            ProfileConditionValue.VideoBitDepth -> combo.bitDepth?.toString()
            ProfileConditionValue.VideoProfile -> combo.videoProfile
            ProfileConditionValue.VideoRangeType -> combo.rangeType
            ProfileConditionValue.Width -> combo.width?.toString()
            ProfileConditionValue.Height -> combo.height?.toString()
            ProfileConditionValue.VideoFramerate -> (combo.frameRate ?: 0.0).toString()
            ProfileConditionValue.VideoLevel -> combo.level?.toString()
            else -> null
        }
        // ConditionProcessor: an unknown value satisfies a condition unless the condition is required.
        if (actual == null) return !condition.isRequired
        val expected = condition.value.orEmpty()
        return when (condition.condition) {
            ProfileConditionType.Equals -> actual.equals(expected, ignoreCase = true)
            ProfileConditionType.NotEquals -> !actual.equals(expected, ignoreCase = true)
            ProfileConditionType.LessThanEqual -> actual.toDouble() <= expected.toDouble()
            ProfileConditionType.GreaterThanEqual -> actual.toDouble() >= expected.toDouble()
            ProfileConditionType.EqualsAny -> {
                val options = expected.split('|').map { it.lowercase() }
                // ConditionProcessor.IsConditionSatisfied: HDR10+ plays wherever HDR10 is allowed.
                actual.lowercase() in options ||
                    (condition.property == ProfileConditionValue.VideoRangeType && actual == "HDR10Plus" && "hdr10" in options)
            }
        }
    }

    private fun reason(property: ProfileConditionValue): String = when (property) {
        ProfileConditionValue.AudioChannels -> "AudioChannelsNotSupported"
        ProfileConditionValue.VideoBitDepth -> "VideoBitDepthNotSupported"
        ProfileConditionValue.VideoProfile -> "VideoProfileNotSupported"
        ProfileConditionValue.VideoRangeType -> "VideoRangeTypeNotSupported"
        ProfileConditionValue.Width, ProfileConditionValue.Height -> "VideoResolutionNotSupported"
        ProfileConditionValue.VideoFramerate -> "VideoFramerateNotSupported"
        ProfileConditionValue.VideoLevel -> "VideoLevelNotSupported"
        else -> "UnknownReason"
    }

    private val TEXT_SUBTITLES = setOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "ttml", "mov_text")
}
