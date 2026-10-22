package app.pictureshow

import android.content.Context
import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection

/** Keeps Jellyfin's main variant at each bitrate, avoiding competing encodes in one session. */
@UnstableApi
class PictureshowTrackSelector(context: Context, private val isServerTranscode: Boolean) : DefaultTrackSelector(context) {
    init {
        // Some valid low-throughput HEVC streams advertise a spuriously high level. The profile
        // bounds their dimensions/rate; let the decoder attempt them rather than reject the level.
        parameters = buildUponParameters()
            .setExceedRendererCapabilitiesIfNecessary(true)
            .setAllowVideoMixedMimeTypeAdaptiveness(isServerTranscode)
            .build()
    }

    override fun selectVideoTrack(
        mappedTrackInfo: MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        mixedMimeTypeSupports: IntArray,
        params: Parameters,
        selectedAudioLanguage: String?
    ): Pair<ExoTrackSelection.Definition, Int>? {
        if (!isServerTranscode) {
            return super.selectVideoTrack(mappedTrackInfo, rendererFormatSupports, mixedMimeTypeSupports, params, selectedAudioLanguage)
        }
        // MappingTrackSelector also appends an unmapped slot with sparse/null group entries.
        // Copy only mapped video groups we modify, preserving all other slots unchanged.
        val supports = rendererFormatSupports.clone()
        for (renderer in 0 until mappedTrackInfo.rendererCount) {
            if (mappedTrackInfo.getRendererType(renderer) != C.TRACK_TYPE_VIDEO) continue
            val groups = mappedTrackInfo.getTrackGroups(renderer)
            supports[renderer] = Array(groups.length) { group -> rendererFormatSupports[renderer][group].clone() }
            for (groupIndex in 0 until groups.length) {
                val seenBitrates = mutableSetOf<Int>()
                val group = groups[groupIndex]
                for (track in 0 until group.length) {
                    val format = group.getFormat(track)
                    val bitrate = if (format.peakBitrate > 0) format.peakBitrate else format.averageBitrate
                    // Unknown bandwidth is not evidence of duplication.
                    if (bitrate > 0 && !seenBitrates.add(bitrate)) {
                        supports[renderer][groupIndex][track] = RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
                    }
                }
            }
        }
        return super.selectVideoTrack(mappedTrackInfo, supports, mixedMimeTypeSupports, params, selectedAudioLanguage)
    }
}
