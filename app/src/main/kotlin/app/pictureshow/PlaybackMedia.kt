/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import app.pictureshow.core.Server
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayDecisions
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.Redaction
import app.pictureshow.core.playback.SubtitleDeliveryMethod

/** One factory is shared by the manifest, media segments and external subtitle media sources. */
internal object PlaybackMedia {
    fun httpFactory(decision: PlayDecision): DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(decision.headers)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)

    /** Child playlists may carry legacy ApiKey parameters too; sanitize at the final request boundary. */
    fun dataSourceFactory(decision: PlayDecision, upstream: DataSource.Factory = httpFactory(decision)): DataSource.Factory =
        ResolvingDataSource.Factory(upstream) { spec -> sanitized(spec) }

    fun sanitized(spec: DataSpec): DataSpec = spec.withUri(Redaction.stripApiKey(spec.uri.toString()).toUri())

    fun item(server: Server, decision: PlayDecision): MediaItem = MediaItem.Builder()
        .setMediaId(decision.itemId)
        .setUri(Redaction.stripApiKey(decision.url))
        .apply {
            if (decision.method == PlayMethod.Transcode &&
                decision.mediaSource.transcodingSubProtocol == app.pictureshow.core.playback.MediaStreamProtocol.Hls
            ) {
                setMimeType(MimeTypes.APPLICATION_M3U8)
            }
        }
        .setSubtitleConfigurations(
            decision.mediaSource.mediaStreams.filter {
                it.type == MediaStreamType.Subtitle &&
                    deliveredExternally(it) &&
                    it.deliveryMethod != SubtitleDeliveryMethod.Encode &&
                    !it.deliveryUrl.isNullOrBlank() &&
                    subtitleMime(it.codec) != null
            }.map { stream ->
                MediaItem.SubtitleConfiguration.Builder(
                    PlayDecisions.resolve(server, Redaction.stripApiKey(stream.deliveryUrl!!)).toUri()
                )
                    .setId(subtitleId(stream.index))
                    .setMimeType(subtitleMime(stream.codec))
                    .setLabel(stream.displayTitle ?: stream.language ?: "Subtitle ${stream.index}")
                    .setLanguage(stream.language)
                    .setSelectionFlags(
                        (if (stream.isDefault) C.SELECTION_FLAG_DEFAULT else 0) or
                            (if (stream.isForced) C.SELECTION_FLAG_FORCED else 0)
                    )
                    .build()
            }
        )
        .build()

    fun deliveredExternally(stream: MediaStream): Boolean = stream.isExternal || stream.deliveryMethod == SubtitleDeliveryMethod.External

    fun subtitleId(index: Int): String = "server-subtitle:$index"

    fun subtitleMime(codec: String?): String? = when (codec?.lowercase()) {
        "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml" -> MimeTypes.APPLICATION_TTML
        else -> null
    }

    fun defaultStream(streams: List<MediaStream>, type: MediaStreamType, serverIndex: Int?, preferredLanguage: String): Int {
        val preferred = PlaybackTracks.language(preferredLanguage)
        return serverIndex ?: streams.firstOrNull { it.type == type && it.isDefault }?.index
            ?: streams.firstOrNull { it.type == type && preferred != null && PlaybackTracks.language(it.language) == preferred }?.index
            ?: if (type == MediaStreamType.Audio) streams.firstOrNull { it.type == type }?.index ?: -1 else -1
    }
}
